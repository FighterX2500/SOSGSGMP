package menu;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.Locale;

import game.GAME;
import game.VERSION;
import snake2d.LOG;
import sosdet.DesyncHold;
import sosdet.OwnBattle;
import sosdet.Stats;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.log.PlayLog;
import sosgsgmp.net.Protocol;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the playtest log's game side (SOS-63). From the world's load to the session's end it writes, to
 * {@code %APPDATA%/songsofsyx/sosgsgmp-logs}: the session, the network's notes, the players, every 600 steps the step,
 * the state hash and the engine's counters, the battles, the desyncs with the engine's report, the resyncs, and every
 * exception {@link MpPlay} and {@link MpWorldLoad} see. Each frame only compares a few values; it builds text only when it
 * writes. The log never stops the game: a failure of its own is logged, and the log stops.
 */
final class MpLog {

	/** A status line every this many steps, at the same steps on every PC: their hashes compare across the logs. */
	private static final long EVERY = 600;

	private final PlayLog log;
	private final SetupSession setup;
	private final EnginePlay engine;
	private int changes = -1;
	private String players = "";
	private boolean go, closed, ended, failureReported;
	private long statusAt;
	private long frames, statusFrames;
	private long statusNanos = System.nanoTime();
	private OwnBattle battle;
	private boolean battleAwaiting;
	private DesyncHold hold;
	private long desyncStep = -1;
	private long resyncedStep = -1;

	private MpLog(PlayLog log, SetupSession setup, EnginePlay engine) {
		this.log = log;
		this.setup = setup;
		this.engine = engine;
	}

	/** A new log for this PC's session, with the session's facts first. */
	static MpLog open(SetupSession setup, EnginePlay engine) {
		Path dir = Paths.get(System.getenv("APPDATA"), "songsofsyx", "sosgsgmp-logs");
		String name = (setup.isHost() ? "host-" : "join-") + localName(setup);
		MpLog l = new MpLog(PlayLog.open(dir, name, Clock.systemDefaultZone()), setup, engine);
		if (l.log.failure() == null)
			LOG.ln("SOSGSGMP: playtest log " + l.log.file());
		l.reportFailure();
		l.line("SOSGSGMP session, " + (setup.isHost() ? "host" : "joining PC") + ", player " + localName(setup) + " (id "
				+ setup.localPlayerId() + ")\nnetwork: " + setup.network() + "\nmod " + jar(MpLog.class) + ", engine "
				+ jar(OwnBattle.class) + ", protocol " + Protocol.VERSION + "\ngame " + VERSION.VERSION_STRING + ", Java "
				+ System.getProperty("java.version") + ", " + System.getProperty("os.name") + ", memory "
				+ Runtime.getRuntime().maxMemory() / (1 << 20) + " MB");
		return l;
	}

	private static String localName(SetupSession setup) {
		for (LobbyPlayer p : setup.players())
			if (p.id() == setup.localPlayerId())
				return p.name();
		return "player" + setup.localPlayerId();
	}

	/** The jar a class came from, with its time: which build ran. */
	private static String jar(Class<?> c) {
		try {
			Path p = Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI());
			return p.getFileName() + " of " + Files.getLastModifiedTime(p);
		} catch (URISyntaxException | IOException | RuntimeException e) {
			return "(jar unknown: " + e + ")";
		}
	}

	void line(String text) {
		log.line(text);
		reportFailure();
	}

	/** An exception at a boundary: logged with its stack, for the caller to rethrow. */
	void error(String context, Throwable t) {
		log.error(context, t);
		reportFailure();
	}

	private void reportFailure() {
		if (log.failure() != null && !failureReported) {
			failureReported = true;
			LOG.err("SOSGSGMP: the playtest log " + log.file() + " stopped: " + log.failure());
		}
	}

	/** Once per frame from {@link MpPlay}, after the session's update. */
	void frame(MpDesync desync) {
		if (ended)
			return;
		try {
			watch(desync);
		} catch (RuntimeException e) {
			// the log's own boundary: a bug in it must not end the game
			error("in the playtest log itself; it stops here", e);
			end();
		}
	}

	private void watch(MpDesync desync) {
		String note;
		while ((note = setup.takeNote()) != null)
			line("network: " + note);
		if (setup.changes() != changes) {
			changes = setup.changes();
			players();
		}
		if (desync.exitOutcome() != null) {
			line("Save and exit after the resync at step " + desync.resyncedStep() + ": "
					+ (desync.exitOutcome().isEmpty() ? "the campaign is saved" : desync.exitOutcome()) + "; the session ended");
			end();
			return; // the engine has ended
		}
		if (setup.closed() && !closed) {
			closed = true;
			line("session closed: " + setup.closedReason());
		}
		if (!setup.playing())
			return;
		frames++;
		if (!go) {
			go = true;
			statusNanos = System.nanoTime(); // the first status's fps counts from Go, not from the load
			statusFrames = frames;
			line("Go: the game runs from step " + engine.step() + String.format(Locale.ROOT, ", game speed %.2f", GAME.SPEED.speed()));
		}
		battle();
		desync(desync);
		long at = engine.step() / EVERY * EVERY;
		if (at > statusAt) {
			statusAt = at;
			status(at);
		}
	}

	private void players() {
		StringBuilder b = new StringBuilder();
		for (LobbyPlayer p : setup.players())
			b.append(b.length() == 0 ? "" : ", ").append(p.name()).append(" (id ").append(p.id()).append(setup.factions() == null ? ""
					: ", slot " + setup.factions().slotOf(p.id())).append(')');
		String now = b.toString();
		if (!now.equals(players)) {
			players = now;
			line("players: " + now);
		}
	}

	private void battle() {
		OwnBattle b = engine.battle();
		if (b != battle) {
			if (battle != null)
				line("battle over: back in this PC's own city at step " + engine.step());
			battle = b;
			battleAwaiting = b != null && b.awaiting();
			if (b != null)
				line("Engage: " + b + (b.pc == setup.factions().slotOf(setup.localPlayerId()) ? "; this PC sends it" : ""));
		}
		if (b != null && battleAwaiting && !b.awaiting()) {
			battleAwaiting = false;
			line("battle loaded at step " + engine.step() + ": its save was " + engine.battleBytes() + " bytes");
		}
	}

	private void desync(MpDesync desync) {
		DesyncHold h = engine.desyncHold();
		if (h != null && h != hold)
			line("desync hold: " + h);
		hold = h;
		long d = engine.desyncStep();
		if (d >= 0 && d != desyncStep) {
			String report = engine.desyncReport();
			line("this PC's engine found a desync at step " + d + (report == null ? "" : ":\n" + report));
		}
		desyncStep = d;
		if (desync.resyncedStep() > resyncedStep) {
			resyncedStep = desync.resyncedStep();
			line("world resync loaded at step " + resyncedStep);
		}
	}

	/** The status at step {@code at}: the hash compares across the PCs' logs; the rest is since the last status. */
	private void status(long at) {
		long now = System.nanoTime();
		double seconds = (now - statusNanos) / 1e9;
		Stats s = engine.stats();
		StringBuilder peers = new StringBuilder();
		for (LobbyPlayer p : setup.players()) {
			int pc = setup.factions().slotOf(p.id());
			if (p.id() != setup.localPlayerId())
				peers.append(peers.length() == 0 ? "" : ", ").append(p.name()).append(' ').append(engine.peerStep(pc));
		}
		line(String.format(Locale.ROOT,
				"step %d hash %s | speed agreed %.2f, game %.2f, paused %b | %.1f fps, step ms mean %.2f p99 %.2f max %.1f,"
						+ " over 100 ms %d | peers at %s | held %.1f s, longest %.2f s | hashes compared %d, missed %d | orders %d,"
						+ " commands %d, txns %d, reports %d, AI %d",
				at, hash(at), s.agreedSpeed, GAME.SPEED.speed(), GAME.SPEED.isPaused(), (frames - statusFrames) / seconds,
				s.meanMs, s.p99Ms, s.maxMs, s.stepsOver100Ms, peers, engine.heldSeconds(), engine.longestHoldSeconds(),
				s.hashesCompared, s.hashesMissed, s.ordersApplied, s.commandsApplied, s.txnsApplied, s.reportsReceived,
				s.aiApplied));
		statusNanos = now;
		statusFrames = frames;
	}

	/** This PC's hash at {@code at}, or why there is none (the hashes kept start again after a load). */
	private String hash(long at) {
		try {
			return String.format(Locale.ROOT, "%016x", engine.hash(at));
		} catch (IllegalArgumentException e) {
			return "none (" + e.getMessage() + ")";
		}
	}

	/** The session's end on this PC: nothing more to log. */
	private void end() {
		ended = true;
		log.close();
	}
}
