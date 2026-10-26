package menu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntBinaryOperator;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.diplomacy.DIP;
import settlement.main.SETT;
import settlement.stats.POP;
import snake2d.CORE;
import snake2d.LOG;
import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.FactionIdentity;
import sosgsgmp.game.PlayerDeals;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.game.SteamworksPort;
import sosgsgmp.net.SteamNode;
import sosgsgmp.net.SteamPort;
import sosgsgmp.session.HostedLobby;
import sosgsgmp.session.JoinedLobby;
import sosgsgmp.session.LobbySession;
import sosgsgmp.session.Departures;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStep;
import sosdet.DesyncHold;
import sosdet.Stats;
import sosdet.game.deal.DealBook;
import view.main.VIEW;
import view.tool.PlacableFixed;
import world.WORLD;

/**
 * SOSGSGMP test tooling, never active in play: with {@code SOSGSGMP_AUTOPILOT} set, the mod plays its own setup and
 * game for the two-PC test (game-over-network.md §6). It calls the session methods the buttons call, from the same
 * per-frame points, and never from inside a render. Each call looks at the session's state and acts on it, so the
 * driver keeps no state of its own beyond throttles. Every milestone goes to
 * {@code %APPDATA%/songsofsyx/sosgsgmp-autopilot/events.jsonl} for the runner.
 *
 * <p>The variable is {@code key=value} pairs separated by commas: {@code role=host|join}, {@code name}, {@code port},
 * {@code address} (join), {@code players} (host: start with this many), {@code seed} (host: the map's),
 * {@code steps} (stop at this step, every PC), {@code difficulty} (host), {@code speed} (host: the game speed after
 * {@code Go}, as a player's speed buttons set it), {@code corrupt} (break this PC's world at this step, a test desync, as
 * SOSDET's rig {@code wcorrupt}), {@code ondesync} (host: {@code resync} or {@code exit} at a desync), {@code save}
 * (host: save the campaign at this step), {@code resume} (host, {@code 1}: host the newest campaign save of this data
 * folder instead of a new game; the joining PC learns it from the lobby), {@code crash} (this PC's game stops dead at
 * this step, as a crash or a pulled cable leaves the others: SOS-65), {@code ongone} (host: {@code continue} or
 * {@code return} when a player left), {@code startafter} (host: keep the lobby open this many seconds first),
 * {@code deal} (join: at this step propose trade and up to 100 denari to the host, who accepts every proposal: SOS-72),
 * {@code dealcancel} (join: from this step, cancel every treaty with the host at once, once there is one: SOS-76),
 * {@code capital} ({@code X:Y}: place this PC's capital at that world tile instead of searching for one, or search when
 * the location rules refuse it: SOS-75), {@code quit} (at this step this PC's player chooses Exit in the escape menu
 * and confirms: the campaign is saved on every PC, then the session ends: SOS-79). {@code save} goes through the escape
 * menu's Save Campaign. {@code rename} (at this step this PC's player renames the faction and the ruler and changes
 * the banner, as the profile window does: SOS-78); every PC reports each player's name, ruler and banner with each
 * hash.
 */
public final class MpAutopilot {

	private static final String VARIABLE = "SOSGSGMP_AUTOPILOT";
	private static final long RETRY_NANOS = 1_000_000_000L;
	private static final long HASH_EVERY = 600;

	private final boolean host;
	private final String name;
	private final int port;
	private final String address;
	private final int players;
	private final int seed;
	private final long steps;
	private final String difficulty;
	private final double speed;
	private final long corrupt;
	private final String onDesync;
	private final long save;
	private final long crash;
	/** Join: the step to propose a deal to the host at (SOS-72); -1 for none. */
	private final long deal;
	/** Join: the step from which to cancel every treaty with the host at once (SOS-76); -1 for none. */
	private final long dealCancel;
	/** The step this PC's player quits at, through the escape menu's Exit (SOS-79); -1 for never. */
	private final long quit;
	/** The step this PC's player renames the faction and changes the banner at (SOS-78); -1 for never. */
	private final long rename;
	/** This PC's capital's world tile from {@code capital=X:Y} (SOS-75); -1 to search for one. */
	private final int capitalX, capitalY;
	/** The location rules refused {@link #capitalX}, {@link #capitalY}: search for one instead. */
	private boolean capitalRefused;
	/** Host: seconds the lobby stays open before it starts, so that a refused PC has tried to join (SOS-65). */
	private final long startAfterNanos;
	private long lobbyAt;
	private final String onGone;
	/** The hold step of the last player-gone hold seen; -1 before the first. */
	private long seenGone = -1;
	private long goneSeenAt;
	private static final long CHOOSE_AFTER_NANOS = 3_000_000_000L;
	private final boolean resume;
	private final Path events;
	private int shownSaves;

	private long lastTry;
	private boolean lobbyOpened;
	private long hashedAt;
	private boolean done;
	private long statusAt;
	private DesyncHold seenHold;
	private long resyncedAt = -1;
	/** Frames, steps and timed step-milliseconds at the last status: the next one tells what happened in between. */
	private long frames, statusFrames, statusSteps, statusTimed;
	private double statusTimedMs;
	private static final long STATUS_NANOS = 10_000_000_000L;
	private final Map<String, Boolean> once = new HashMap<>();

	/** The autopilot the variable asks for, or null (play): read once by each per-frame point that may use it. */
	public static MpAutopilot fromEnv() {
		String v = System.getenv(VARIABLE);
		if (v == null || v.isBlank())
			return null;
		Map<String, String> m = new HashMap<>();
		for (String part : v.split(",")) {
			String[] kv = part.split("=", 2);
			if (kv.length != 2)
				throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " part '" + part + "' isn't key=value");
			m.put(kv[0].trim(), kv[1].trim());
		}
		return new MpAutopilot(m);
	}

	/** Over Steam instead of direct IP (net=steam). */
	private final boolean steam;
	/** A joining PC's Steam, looking for the host's lobby. */
	private SteamNode browsing;
	/** The host's Steam lobby. */
	private SteamNode hostSteam;

	private MpAutopilot(Map<String, String> m) {
		String role = m.getOrDefault("role", "");
		if (!role.equals("host") && !role.equals("join"))
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " role '" + role + "', not host or join");
		host = role.equals("host");
		name = m.getOrDefault("name", host ? "Host" : "Joiner");
		port = Integer.parseInt(m.getOrDefault("port", "47510"));
		address = m.getOrDefault("address", "");
		players = Integer.parseInt(m.getOrDefault("players", "2"));
		seed = Integer.parseInt(m.getOrDefault("seed", "12345"));
		steps = Long.parseLong(m.getOrDefault("steps", "3600"));
		difficulty = m.getOrDefault("difficulty", "");
		speed = Double.parseDouble(m.getOrDefault("speed", "0"));
		corrupt = Long.parseLong(m.getOrDefault("corrupt", "-1"));
		onDesync = m.getOrDefault("ondesync", "resync");
		save = Long.parseLong(m.getOrDefault("save", "-1"));
		resume = m.getOrDefault("resume", "0").equals("1");
		crash = Long.parseLong(m.getOrDefault("crash", "-1"));
		deal = Long.parseLong(m.getOrDefault("deal", "-1"));
		dealCancel = Long.parseLong(m.getOrDefault("dealcancel", "-1"));
		quit = Long.parseLong(m.getOrDefault("quit", "-1"));
		rename = Long.parseLong(m.getOrDefault("rename", "-1"));
		String[] capital = m.getOrDefault("capital", "-1:-1").split(":");
		if (capital.length != 2)
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " capital '" + m.get("capital") + "', not X:Y");
		capitalX = Integer.parseInt(capital[0]);
		capitalY = Integer.parseInt(capital[1]);
		startAfterNanos = Long.parseLong(m.getOrDefault("startafter", "0")) * 1_000_000_000L;
		onGone = m.getOrDefault("ongone", "continue");
		if (!onGone.equals("continue") && !onGone.equals("return"))
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " ongone '" + onGone + "', not continue or return");
		if (!onDesync.equals("resync") && !onDesync.equals("exit"))
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " ondesync '" + onDesync + "', not resync or exit");
		String net = m.getOrDefault("net", "ip");
		if (!net.equals("ip") && !net.equals("steam"))
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " net '" + net + "', not ip or steam");
		steam = net.equals("steam");
		events = Paths.get(System.getenv("APPDATA"), "songsofsyx", "sosgsgmp-autopilot", "events.jsonl");
		if (!host && !steam && address.isEmpty())
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " role=join needs address=");
	}

	/**
	 * Deals between players (SOS-72, SOS-76): the joiner proposes one, the host accepts, the joiner may then cancel it at
	 * once, and every PC reports what it sees.
	 */
	private void deals(SetupSession setup, long step) {
		int me = setup.factions().slotOf(setup.localPlayerId());
		if (!host && deal >= 0 && step >= deal && first("deal")) {
			int give = (int) Math.min(100, Math.max(0, FACTIONS.player().credits().credits())); // a new city may have less
			DealBook.Terms t = new DealBook.Terms(DealBook.TRADE, give, 0, new int[0], new int[0]);
			String why = PlayerDeals.refusal(0, t);
			if (why == null)
				PlayerDeals.propose(0, t);
			event("dealproposed", "\"step\":" + step + ",\"refused\":\"" + clean(why == null ? "" : why) + "\"");
		}
		if (!host && dealCancel >= 0 && step >= dealCancel && PlayerDeals.cancelRefusal(0) == null && first("dealcancel")) {
			PlayerDeals.cancel(0);
			event("dealcancel", "\"step\":" + step);
		}
		if (!host && once.containsKey("dealcancel") && DIP.get(FACTIONS.player(), FACTIONS.getByIndex(0)) == DIP.NEUTRAL()
				&& first("dealcancelled")) // the cancel applied, at its step
			event("dealcancelled", "\"stance\":\"" + DIP.get(FACTIONS.player(), FACTIONS.getByIndex(0)).key() + "\",\"step\":" + step);
		for (DealBook.Proposal p : PlayerDeals.open()) {
			if (first("dealopen" + p.id()))
				event("dealopen", "\"id\":" + p.id() + ",\"from\":" + p.from() + ",\"to\":" + p.to() + ",\"step\":" + step);
			if (host && p.to() == me && first("dealaccept" + p.id()))
				PlayerDeals.answer(p.id(), true);
		}
		for (DealBook.Answer a : PlayerDeals.answers()) {
			if (first("dealanswered" + a.id())) {
				String stance = DIP.get(FACTIONS.getByIndex(a.from()), FACTIONS.getByIndex(a.to())).key();
				event("dealanswered", "\"id\":" + a.id() + ",\"outcome\":\"" + a.outcome() + "\",\"stance\":\"" + stance
						+ "\",\"step\":" + step);
			}
		}
	}

	/** The main menu, every frame until the lobby opens: create or join it, as Create Lobby and Join do. */
	void menu(MpMenu mp) {
		if (lobbyOpened || !due())
			return;
		if (steam) {
			steamMenu(mp);
			return;
		}
		try {
			LobbySession s = host
					? new HostedLobby(hostSettings(mp), name, mp.prefs.playerKey, port)
					: new JoinedLobby(address, port, name, mp.prefs.playerKey);
			lobbyOpened = true;
			mp.openLobby(s, null);
			event("lobby", "\"role\":\"" + (host ? "host" : "join") + "\"");
		} catch (IOException e) {
			event("lobbyretry", "\"reason\":\"" + clean(e.getMessage()) + "\""); // the host may not be up yet
		}
	}

	/**
	 * Over Steam: the host creates a Steam lobby, as Create Steam Lobby does; a joining PC waits until a friend's lobby
	 * shows, then joins the first, as Join through Steam does (both accounts must be Steam friends).
	 */
	private void steamMenu(MpMenu mp) {
		if (host) {
			SteamNode node = SteamNode.host(SteamworksPort::new, Math.max(players, 2));
			hostSteam = node;
			lobbyOpened = true;
			mp.openLobby(new HostedLobby(hostSettings(mp), name, mp.prefs.playerKey, node), node);
			event("lobby", "\"role\":\"host\",\"net\":\"steam\"");
			return;
		}
		if (browsing == null) {
			browsing = SteamNode.browse(SteamworksPort::new);
			event("steambrowse", "");
			return;
		}
		if (browsing.failure() != null) {
			if (first("steamfail"))
				event("steamfail", "\"reason\":\"" + clean(browsing.failure()) + "\"");
			return;
		}
		if (browsing.friendLobbies().isEmpty())
			return;
		SteamPort.FriendLobby f = browsing.friendLobbies().get(0);
		browsing.join(f.lobby());
		lobbyOpened = true;
		mp.openLobby(new JoinedLobby(browsing, name, mp.prefs.playerKey), null);
		event("lobby", "\"role\":\"join\",\"net\":\"steam\",\"friend\":\"" + clean(f.friend()) + "\"");
	}

	/** A new game's settings, or with {@code resume=1} the newest campaign save's, as Load Saved Lobby hosts it. */
	private LobbySettings hostSettings(MpMenu mp) {
		if (!resume)
			// as Create Lobby: the remembered mode, or the game's first when none is (a fresh profile)
			return new LobbySettings(difficulty.isEmpty() ? mp.modes.keys[mp.modes.index(mp.prefs.difficulty)] : difficulty,
					Math.max(players, 2));
		List<CampaignRecord> all = MpSaves.store().all();
		if (all.isEmpty())
			throw new IllegalStateException("SOSGSGMP: " + VARIABLE + " resume=1, but this data folder has no campaign save");
		CampaignRecord r = all.get(0);
		event("resume", "\"campaign\":\"" + Long.toHexString(r.campaign().id()) + "\",\"number\":" + r.number() + ",\"file\":\""
				+ clean(r.file()) + "\"");
		return LobbySettings.resuming(new LobbySettings.Resume(r.campaign(), r.number()));
	}

	/** The lobby screen, every frame: the host starts once every player is in. */
	void lobby(LobbySession s) {
		if (hostSteam != null && hostSteam.lobby() != 0 && first("steamlobby"))
			event("steamlobby", "\"lobby\":" + hostSteam.lobby());
		if (hostSteam != null && hostSteam.failure() != null && first("steamfail"))
			event("steamfail", "\"reason\":\"" + clean(hostSteam.failure()) + "\"");
		if (lobbyAt == 0)
			lobbyAt = System.nanoTime();
		if (!host && s.state() == LobbyState.CLOSED) {
			// refused, or the host closed the lobby: nothing else comes
			if (first("closed")) {
				event("closed", "\"reason\":\"" + clean(s.closedReason()) + "\"");
				CORE.annihilate();
			}
			return;
		}
		if (host && s.canStart() && s.players().size() >= players && System.nanoTime() - lobbyAt >= startAfterNanos
				&& first("started")) {
			s.start();
			event("started", "\"players\":" + s.players().size());
		}
	}

	/** The map step, every frame: the host generates once and confirms; every PC goes on to the factions. */
	public void map(SetupSession setup, boolean canGoOn, Runnable next) {
		MapStep step = setup.step();
		if (setup.closed())
			return;
		if (host && step.genId() == 0 && step.canGenerate()) {
			setup.generate(new MapOptions(step.size(), "", MapOptions.DEFAULT_LATITUDE, seed));
			event("generated", "\"seed\":" + seed);
		} else if (host && step.canConfirm()) {
			setup.confirm();
		}
		if (canGoOn && first("map")) {
			event("mapconfirmed", "\"hash\":\"" + Long.toHexString(step.confirmedHash()) + "\"");
			next.run();
		}
	}

	/**
	 * The factions step, every frame: a name, then a capital, searched from a point of this player's own and placed
	 * through {@code placeAt} (returns the location's x, or NONE when none was placed); the host initializes.
	 */
	public void factions(SetupSession setup, boolean hasLocation, IntBinaryOperator placeAt) {
		if (setup.closed() || setup.factions().initialized())
			return;
		if (FACTIONS.player().name.toString().isBlank())
			FACTIONS.player().name.clear().add(name);
		if (!hasLocation && capitalX >= 0 && !capitalRefused && due()) { // due() starts its wait: one branch calls it
			String why = setup.locationProblem(capitalX, capitalY);
			if (why == null) {
				placeAt.applyAsInt(capitalX, capitalY);
				event("placed", "\"x\":" + capitalX + ",\"y\":" + capitalY);
			} else {
				capitalRefused = true;
				event("capitalrefused", "\"x\":" + capitalX + ",\"y\":" + capitalY + ",\"reason\":\"" + clean(why) + "\"");
			}
		} else if (!hasLocation && due()) {
			List<LobbyPlayer> ps = setup.players();
			int i = 0;
			while (i < ps.size() && ps.get(i).id() != setup.localPlayerId())
				i++;
			int sx = WORLD.TWIDTH() * (i + 1) / (ps.size() + 1);
			int sy = WORLD.THEIGHT() / 2;
			for (int r = 0; r < WORLD.TWIDTH(); r += 2) {
				int found = spiral(sx, sy, r, (x, y) -> setup.locationProblem(x, y) == null ? 1 : 0);
				if (found >= 0) {
					int x = found >> 16, y = found & 0xFFFF;
					placeAt.applyAsInt(x, y);
					event("placed", "\"x\":" + x + ",\"y\":" + y);
					break;
				}
			}
		}
		if (host && setup.factions().canInitialize() && first("initialize")) {
			event("initializing", "");
			setup.initialize();
		}
	}

	/**
	 * The loaded game, every frame after the update: the landing party; while playing, hashes, the test desync and the
	 * host's choice in the desync window; at the end, quit.
	 */
	void game(SetupSession setup, EnginePlay engine, MpDesync desync, MpSaves saves, MpGone gone, MpExit exit) {
		if (exit.outcome() != null && first("exit")) {
			// the engine has ended: a desync's Save and exit, or a player's quit (SOS-79)
			if (saves.exitedQuitter() == SaveOrder.NO_QUITTER)
				event("desyncexit", "\"outcome\":\"" + clean(exit.outcome()) + "\",\"resynced\":" + desync.resyncedStep());
			else
				event("quitexit", "\"outcome\":\"" + clean(exit.outcome()) + "\",\"quitter\":" + saves.exitedQuitter() + ",\"me\":"
						+ setup.localPlayerId());
			CORE.annihilate();
			return;
		}
		if (setup.closed()) {
			if (first("closed")) {
				event("closed", "\"reason\":\"" + clean(setup.closedReason()) + "\"");
				CORE.annihilate(); // a test game left on would hold its data folder's files for the next run
			}
			return;
		}
		if (VIEW.inters().load.isActivated())
			VIEW.inters().load.deactivate(); // vanilla's "click to continue" after a load holds the game until a click
		if (first("ingame"))
			event("ingame", "\"view\":\"" + VIEW.current().getClass().getSimpleName() + "\",\"pop\":" + POP.tot());
		if (POP.tot() == 0 && due()) {
			if (landParty())
				event("landed", "\"view\":\"" + VIEW.current().getClass().getSimpleName() + "\"");
			else if (first("landfail"))
				event("landfail", "\"view\":\"" + VIEW.current().getClass().getSimpleName() + "\",\"why\":\"" + clean(landWhy) + "\"");
		}
		if (!setup.playing())
			return;
		if (first("go")) {
			event("go", String.format(Locale.ROOT, "\"speed\":%.2f,\"paused\":%b", GAME.SPEED.speedTarget(), GAME.SPEED.isPaused()));
		}
		if (host && speed > 0 && first("speed"))
			GAME.SPEED.speedSet(speed);
		if (host && save >= 0 && engine.step() >= save && exit.saveRefusal() == null && first("save")) {
			exit.save(); // the escape menu's Save Campaign
			event("saveorder", "\"step\":" + engine.step());
		}
		if (quit >= 0 && engine.step() >= quit && first("quit")) {
			exit.quitConfirmed(true); // the escape menu's Exit, confirmed
			event("quit", "\"step\":" + engine.step());
		}
		if (saves.changes() != shownSaves) {
			shownSaves = saves.changes();
			event("save", "\"step\":" + engine.step() + ",\"status\":\"" + clean(saves.status()) + "\"");
		}
		frames++;
		long step = engine.step();
		long now = System.nanoTime();
		if (now - statusAt > STATUS_NANOS) {
			double seconds = (now - statusAt) / 1e9;
			statusAt = now;
			Stats s = engine.stats();
			long timed = s.timedSteps - statusTimed;
			double timedMs = s.meanMs * s.timedSteps - statusTimedMs;
			// per second since the last status: frames, steps, and the ms the steps took (near 1000: the PC is the limit)
			event("status", String.format(Locale.ROOT, "\"step\":%d,\"speed\":%.2f,\"target\":%.2f,\"paused\":%b,\"agreed\":%.2f,\"other\":%d,\"load\":%b,"
					+ "\"fps\":%.1f,\"stepsPerS\":%.1f,\"stepMsPerS\":%.1f,\"stepMs\":%.2f",
					step, GAME.SPEED.speed(), GAME.SPEED.speedTarget(), GAME.SPEED.isPaused(), s.agreedSpeed,
					MpPlay.otherPcs(setup).length == 0 ? -1 : engine.peerStep(MpPlay.otherPcs(setup)[0]),
					VIEW.inters().load.isActivated(), (frames - statusFrames) / seconds,
					(step - statusSteps) / seconds, timedMs / seconds, timed > 0 ? timedMs / timed : 0));
			statusFrames = frames;
			statusSteps = step;
			statusTimed = s.timedSteps;
			statusTimedMs = s.meanMs * s.timedSteps;
		}
		if (corrupt >= 0 && step >= corrupt && first("corrupt"))
			corruptWorld(step);
		if (crash >= 0 && step >= crash) {
			event("crash", "\"step\":" + step);
			Runtime.getRuntime().halt(1); // no goodbye to the host: the connection just drops
		}
		deals(setup, step);
		Departures d = setup.departures();
		if (d.waiting() && d.holdStep() != seenGone) {
			seenGone = d.holdStep();
			goneSeenAt = now;
			event("gone", "\"holdStep\":" + d.holdStep() + ",\"names\":\"" + clean(d.names()) + "\",\"step\":" + step);
		}
		// the host reads the window first, as a player would: every other PC shows the hold before the choice arrives
		if (host && gone.waiting() && now - goneSeenAt > CHOOSE_AFTER_NANOS)
			gone.choose(onGone.equals("continue"));
		// every removed player of the campaign, by Continue or missing at a resume
		if (setup.campaign() != null)
			for (Campaign.Player p : setup.campaign().players())
				if (p.slot() != 0 && engine.retired(p.slot()) && first("retired" + p.slot()))
					event("retired", "\"slot\":" + p.slot() + ",\"step\":" + step);
		for (Departures.Gone g : d.removed()) // a joining PC learns the campaign only from the first save order
			if (engine.retired(g.slot()) && first("retired" + g.slot()))
				event("retired", "\"slot\":" + g.slot() + ",\"step\":" + step);
		DesyncHold hold = desync.shown();
		if (hold != null && hold != seenHold) {
			seenHold = hold;
			event("desynchold", "\"desyncStep\":" + hold.desyncStep + ",\"pc\":" + hold.pc + ",\"holdStep\":" + hold.holdStep + ",\"step\":" + step);
			if (host)
				desync.choose(onDesync.equals("resync"));
		}
		if (desync.resyncedStep() > resyncedAt) {
			resyncedAt = desync.resyncedStep();
			event("resynced", "\"step\":" + resyncedAt + ",\"desync\":" + engine.desyncStep());
		}
		if (rename >= 0 && step >= rename && first("rename")) {
			FactionIdentity.change(name + " Renamed", name + " Ruler");
			event("rename", "\"step\":" + step);
		}
		long at = step / HASH_EVERY * HASH_EVERY;
		if (at > hashedAt) {
			hashedAt = at;
			event("hash", "\"step\":" + at + ",\"hash\":\"" + Long.toHexString(engine.hash(at)) + "\",\"desync\":" + engine.desyncStep()
					+ String.format(Locale.ROOT, ",\"speed\":%.2f", engine.agreedSpeed()));
			StringBuilder ids = new StringBuilder();
			for (LobbyPlayer p : setup.players()) {
				int slot = setup.factions().slotOf(p.id());
				ids.append(ids.length() == 0 ? "" : ",").append('"').append(slot).append("\":\"").append(clean(FactionIdentity.of(slot))).append('"');
			}
			event("identity", "\"step\":" + step + ",\"slots\":{" + ids + "}");
		}
		if (step >= steps && !done && (!host || peersReached(engine, MpPlay.otherPcs(setup), steps))) {
			done = true;
			event("done", "\"step\":" + step + ",\"desync\":" + engine.desyncStep());
			CORE.annihilate();
		}
	}

	/**
	 * Host: every other PC's hashes reached {@code step}, so it reached its own end; the host steps on meanwhile, so its
	 * AI log carries them there.
	 */
	private static boolean peersReached(EnginePlay engine, int[] pcs, long step) {
		for (int pc : pcs)
			if (engine.peerStep(pc) < step)
				return false;
		return true;
	}

	/**
	 * A test desync on this PC only, as SOSDET's rig {@code wcorrupt} makes it: the second human's rebellion timer,
	 * which the world hash covers, 1000 s on.
	 */
	private void corruptWorld(long step) {
		try {
			java.lang.reflect.Field timer = game.events.world.EventWorldRebellion.class.getDeclaredField("timer");
			timer.setAccessible(true);
			double[] t = (double[]) timer.get(GAME.events().rebellion);
			t[1] += 1000;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("SOSGSGMP autopilot: corrupt", e);
		}
		event("corrupt", "\"step\":" + step);
	}

	/** The load is done: this PC's world hash, and the view it shows. */
	public void loaded(long worldHash) {
		event("loaded", "\"hash\":\"" + Long.toHexString(worldHash) + "\",\"view\":\"" + VIEW.current().getClass().getSimpleName()
				+ "\",\"pop\":" + POP.tot());
	}

	/** SOS-75: this PC's own city's active entry points; none means it has no roads. */
	public void roads(int active) {
		event("roads", "\"active\":" + active);
	}

	/** The host's world arrived; the load starts. */
	public void loading() {
		event("loading", "");
	}

	/** The landing party at the first spot from the city's centre where it fits, as a click there places it. */
	private boolean landParty() {
		PlacableFixed p = SETT.PLACERS().landingParty;
		int c = SETT.TWIDTH / 2;
		whole = 0;
		tiles = 0;
		for (int r = 0; r < 80; r += 2) {
			int found = spiral(c, c, r, (cx, cy) -> fits(p, cx, cy) ? 1 : 0);
			if (found < 0)
				continue;
			int cx = found >> 16, cy = found & 0xFFFF;
			p.init(cx, cy);
			int x1 = cx - p.width() / 2, y1 = cy - p.height() / 2;
			for (int dy = 0; dy < p.height(); dy++)
				for (int dx = 0; dx < p.width(); dx++)
					p.place(x1 + dx, y1 + dy, dx, dy);
			p.afterPlaced(x1, y1);
			return true;
		}
		landWhy = "city " + SETT.TWIDTH + ", placer " + p.width() + "x" + p.height() + ": " + whole + " spots refused whole (last: "
				+ wholeWhy + "), " + tiles + " by a tile (last at " + tileAt + ")";
		return false;
	}

	private int whole;
	private int tiles;
	private String wholeWhy = "";
	private String tileAt = "";
	private String landWhy = "";

	private boolean fits(PlacableFixed p, int cx, int cy) {
		p.init(cx, cy);
		int x1 = cx - p.width() / 2, y1 = cy - p.height() / 2;
		if (x1 < 1 || y1 < 1 || x1 + p.width() >= SETT.TWIDTH - 1 || y1 + p.height() >= SETT.THEIGHT - 1)
			return false;
		CharSequence w = p.placableWhole(x1, y1);
		if (w != null) {
			whole++;
			wholeWhy = String.valueOf(w);
			return false;
		}
		for (int dy = 0; dy < p.height(); dy++)
			for (int dx = 0; dx < p.width(); dx++)
				if (p.placable(x1 + dx, y1 + dy, dx, dy) != null) {
					tiles++;
					tileAt = dx + "," + dy;
					return false;
				}
		return true;
	}

	/** The first point on the square ring of radius {@code r} around {@code cx, cy} that {@code ok} accepts, packed x << 16 | y; -1 if none. */
	private static int spiral(int cx, int cy, int r, IntBinaryOperator ok) {
		for (int d = -r; d <= r; d++) {
			int[][] ring = r == 0 ? new int[][] {{cx, cy}} : new int[][] {{cx + d, cy - r}, {cx + d, cy + r}, {cx - r, cy + d}, {cx + r, cy + d}};
			for (int[] pt : ring)
				if (pt[0] >= 0 && pt[1] >= 0 && pt[0] < 0xFFFF && pt[1] < 0xFFFF && ok.applyAsInt(pt[0], pt[1]) == 1)
					return pt[0] << 16 | pt[1];
			if (r == 0)
				break;
		}
		return -1;
	}

	/** Whether to try a retried action now: at most once a second. */
	private boolean due() {
		long now = System.nanoTime();
		if (now - lastTry < RETRY_NANOS)
			return false;
		lastTry = now;
		return true;
	}

	private boolean first(String what) {
		return once.putIfAbsent(what, Boolean.TRUE) == null;
	}

	/**
	 * One line to the events file. A line that can't be written now (the test runner reading the file holds it; laptop
	 * T7 crashed on that) waits for the next event: lines keep their order, and a test aid never stops the game.
	 */
	private void event(String event, String fields) {
		String line = "{\"t\":" + System.currentTimeMillis() + ",\"event\":\"" + event + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}\n";
		LOG.ln("SOSGSGMP autopilot: " + line.trim());
		unwritten.append(line);
		try {
			Files.createDirectories(events.getParent());
			Files.write(events, unwritten.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			unwritten.setLength(0);
		} catch (IOException e) {
			if (first("unwritten"))
				LOG.ln("SOSGSGMP autopilot: can't write " + events + " now, kept for the next event: " + e);
		}
	}

	/** Event lines not yet in the file. */
	private final StringBuilder unwritten = new StringBuilder();

	private static String clean(String s) {
		return s == null ? "" : s.replace('"', '\'').replace('\\', '/').replace('\n', ' ');
	}
}
