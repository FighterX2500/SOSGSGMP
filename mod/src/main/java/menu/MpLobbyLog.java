package menu;

import java.io.IOException;
import java.time.Clock;

import snake2d.LOG;
import sosgsgmp.game.DataFolders;
import sosgsgmp.lobby.Builds;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.log.PlayLog;
import sosgsgmp.log.Recorder;
import sosgsgmp.log.SessionLog;
import sosgsgmp.session.LobbySession;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the playtest log from Host, Join, a resume or a Steam join on (SOS-246), before any world loads: the system
 * line, the lobby's players, its refusals, its start and its end. A lobby that starts gives its file to the setup, and
 * the game's {@link MpLog} goes on in it; a lobby or a setup left without a game closes it. Each frame compares a
 * counter; it builds text only when it writes.
 */
final class MpLobbyLog {

	private final LobbySession session;
	private final PlayLog log;
	private final SessionLog both;
	private int changes = -1;
	private LobbyState state;
	private String players = "";

	private MpLobbyLog(LobbySession session, SessionLog both) {
		this.session = session;
		this.both = both;
		this.log = both.text();
	}

	/**
	 * A new file for the lobby {@code session} this PC's player {@code name} opens, with the system line first, and the
	 * recorder (SOS-248), which records the lobby's network from now on.
	 */
	static MpLobbyLog open(LobbySession session, String name, Builds builds) {
		String role = session.isHost() ? "host-" : "join-";
		PlayLog log = PlayLog.open(DataFolders.layout().logs(), role + name, Clock.systemDefaultZone());
		if (log.failure() == null)
			LOG.ln("SOSGSGMP: playtest log " + log.file());
		else
			LOG.err("SOSGSGMP: the playtest log " + log.file() + " can't be written: " + log.failure());
		log.line(MpLog.system(builds));
		log.line("lobby: " + (session.isHost() ? "hosting" : "joining") + " as " + name + ", network: " + session.network());
		Recorder recorder = null;
		try {
			recorder = Recorder.open(DataFolders.layout().recorder().resolve(role + PlayLog.fileName(name) + ".rec"), session.isHost(), name,
					System.currentTimeMillis());
			log.line("recorder: " + recorder.file());
		} catch (IOException e) {
			log.line("recorder: off, its file can't be made: " + e);
		}
		SessionLog both = new SessionLog(log, recorder);
		session.keepLog(both);
		return new MpLobbyLog(session, both);
	}

	/** Every frame the lobby screen updates the session: its notes, and its state and players when they changed. */
	void frame() {
		String note;
		while ((note = session.takeNote()) != null)
			log.line("lobby: " + note);
		if (session.changes() == changes)
			return;
		changes = session.changes();
		if (session.state() != state) {
			state = session.state();
			log.line("lobby " + state.name().toLowerCase(java.util.Locale.ROOT) + (state == LobbyState.CLOSED ? ": " + session.closedReason() : "")
					+ (state == LobbyState.STARTED && session.running() ? (session.returning() ? ", coming back into the running game" : ", joining the running game") : ""));
		}
		StringBuilder b = new StringBuilder();
		for (LobbyPlayer p : session.players())
			b.append(b.length() == 0 ? "" : ", ").append(p.name()).append(" (id ").append(p.id()).append(')');
		if (!b.toString().equals(players)) {
			players = b.toString();
			log.line("lobby players: " + players);
		}
	}

	/** The lobby closed with no game: the last notes and {@code why}, and the file closes. */
	void close(String why) {
		frame();
		log.line("lobby left without a game: " + why);
		both.close();
	}

	/**
	 * A setup left before its world loaded (the map step, the factions step, a hot-join's pick): its network notes, then
	 * {@code why}, and the file closes. In the game, {@link MpLog} owns the file instead.
	 */
	static void left(SetupSession setup, String why) {
		SessionLog both = setup.log();
		if (both == null || setup.playing())
			return;
		String note;
		while ((note = setup.takeNote()) != null)
			both.text().line("network: " + note);
		both.text().line("setup left without a game: " + why);
		both.close();
	}
}
