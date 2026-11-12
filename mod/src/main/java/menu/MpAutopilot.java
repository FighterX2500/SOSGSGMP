package menu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntBinaryOperator;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.diplomacy.DIP;
import game.faction.npc.FactionNPC;
import init.trade.TR;
import init.trade.TRADABLE;
import settlement.main.SETT;
import settlement.stats.POP;
import snake2d.CORE;
import snake2d.LOG;
import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.game.DataFolders;
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
import sosgsgmp.session.Loading;
import sosgsgmp.session.Departures;
import sosgsgmp.session.SetupSession;
import sosgsgmp.session.ViewRequests;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStep;
import sosdet.Assault;
import sosdet.DesyncHold;
import sosdet.Stats;
import sosdet.game.deal.DealBook;
import sosdet.game.siege.AssaultTerms;
import view.main.VIEW;
import view.ui.goods.UIGoodsExport;
import view.ui.goods.UIGoodsImport;
import view.tool.PlacableFixed;
import world.WORLD;

/**
 * SOSGSGMP test tooling, never active in play: with {@code SOSGSGMP_AUTOPILOT} set, the mod plays its own setup and
 * game for the two-PC test (game-over-network.md §6). It calls the session methods the buttons call, from the same
 * per-frame points, and never from inside a render. Each call looks at the session's state and acts on it, so the
 * driver keeps no state of its own beyond throttles. Every milestone goes to
 * {@code %APPDATA%/songsofsyx/sosgsgmp/autopilot/events.jsonl} for the runner.
 *
 * <p>The variable is {@code key=value} pairs separated by commas: {@code role=host|join}, {@code name}, {@code port},
 * {@code address} (join), {@code players} (host: start with this many), {@code seed} (host: the map's),
 * {@code steps} (stop at this step, every PC), {@code difficulty} (host), {@code speed} (host: the game speed after
 * {@code Go}, as a player's speed buttons set it), {@code corrupt} (break this PC's world at this step, a test desync, as
 * SOSDET's rig {@code wcorrupt}), {@code ondesync} (host: {@code resync} or {@code exit} at a desync), {@code save}
 * (host: save the campaign at this step), {@code resume} (host, {@code 1}: host the newest campaign save of this data
 * folder instead of a new game; the joining PC learns it from the lobby), {@code crash} (this PC's game stops dead at
 * this step, as a crash or a pulled cable leaves the others: SOS-65), {@code ongone} (host: {@code continue},
 * {@code freeze} (SOS-194) or {@code return} when a player left), {@code freeze} (join: at this step choose the escape
 * menu's Leave and freeze, confirmed: SOS-194), {@code startafter} (host: keep the lobby open this many seconds first),
 * {@code deal} (join: at this step propose trade, up to 100 denari and 5 of a good it has to the host, who accepts every
 * proposal: SOS-72), {@code fulllog} (host, {@code 0}: the lobby's transfer log lists only transfers between players: SOS-81),
 * {@code living} (host, {@code 1}: the lobby's world is a living world, every joiner settling on the host's map: SOS-187),
 * {@code dealcancel} (join: from this step, cancel every treaty with the host at once, once there is one: SOS-76),
 * {@code war} (join: at this step declare war on the host, at once: SOS-74; the Diplomacy tab doesn't offer it yet),
 * {@code capital} ({@code X:Y}: place this PC's capital at that world tile instead of searching for one, or search when
 * the location rules refuse it: SOS-75), {@code quit} (at this step this PC's player chooses Exit in the escape menu
 * and confirms: the campaign is saved on every PC, then the session ends: SOS-79). {@code save} goes through the escape
 * menu's Save Campaign. {@code rename} (at this step this PC's player renames the faction and the ruler and changes
 * the banner, as the profile window does: SOS-78); every PC reports each player's name, ruler and banner with each
 * hash. {@code view} ({@code STEP:CLOSE}: at STEP this PC's player asks to view the other player's city, the host's or,
 * on the host, the first joined player's, and closes the view at CLOSE, -1 for never: SOS-82), {@code viewanswer}
 * ({@code allow}, {@code refuse} or {@code none}: how this PC's player answers an ask; {@code none} lets it time out).
 * {@code battles} ({@code auto}: fight every battle prompt, Start, fight on and accept the result: SOS-83). An
 * outside player's actions come through {@link MpAutopilotCommands}.
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
	/** The player answers deals with {@code accept}; otherwise the host's autopilot accepts each. */
	private final boolean manualDeals;
	/** Join: the step to declare war on the host at (SOS-74); -1 for never. */
	private final long war;
	/** Host: the lobby's switch for the playtest log (SOS-81). */
	private final boolean fullLog;
	/** Host: the lobby's world is a living world (SOS-187). */
	private final boolean living;
	/** Join: the step to set a 50% tariff on what the host sells this player at (SOS-77); -1 for none. */
	private final long tariff;
	/** The step this PC's player quits at, through the escape menu's Exit (SOS-79); -1 for never. */
	private final long quit;
	/** The step this PC's player leaves frozen at, through the escape menu (SOS-194); -1 for never. */
	private final long freeze;
	/** The step this PC's player renames the faction and changes the banner at (SOS-78); -1 for never. */
	private final long rename;
	/** The host opens each siege window in turn from this step, for screenshots (SOS-92); -1 for none. */
	private final long guiPreview;
	/** The preview's window showing, its index, and when it opened. */
	private MpWindow previewWindow;
	private int previewIndex = -1;
	private long previewNanos;
	/** The game's own screenshot of the preview window was taken (it reads the frame buffer, so a locked screen still works). */
	private boolean previewShot;
	/** Leave's prompt was shown once, for the preview's screenshot. */
	private boolean leavePreviewed;
	/** When {@link #stageChat} said its line, and whether it took its screenshot. */
	private long stageAt;
	private boolean stageShot;
	/** The step this PC's player asks to view the other player's city at, and closes the view at (SOS-82); -1 for never. */
	private final long viewAt, viewClose;
	/** How this PC's player answers an ask to view its city: allow, refuse or none. */
	private final String viewAnswer;
	/** Host: Refuse a PC that asks to join the running game (SOS-190), instead of Accept. */
	private final boolean refuseJoin;
	/** Each view's state as last reported, by viewer slot. */
	private final Map<Integer, String> viewSeen = new HashMap<>();
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
	/** With a GUI preview (SOS-196): Leave and freeze's stage (0 none yet, 1 the menu, 2 the prompt, 3 done), since when. */
	private int freezeStage;
	private long freezeStageAt;
	private boolean freezeShot;
	/** When this PC left frozen; when the next chat shot is due (0: none). */
	private long frozeAt;
	private long chatShotAt;
	private static final long CHOOSE_AFTER_NANOS = 3_000_000_000L;
	/** The players this PC saw lost and not back yet (SOS-177). */
	private final java.util.Set<Integer> lostSeen = new java.util.HashSet<>();
	/** This joining PC saw its host silent and not heard again yet. */
	private boolean hostSilentSeen;
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
	/** The outside player's actions and, with {@code battles=auto}, the battles fought on their own (SOS-83). */
	private final MpAutopilotCommands commands;

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

	/** Whether the variable asks for a GUI preview: read once, by the cursors. */
	static boolean previewing() {
		String v = System.getenv(VARIABLE);
		return v != null && v.contains("guipreview=");
	}

	/**
	 * Whether the variable asks every PC to send the same empty game files (samefiles=1), so a PC with other game data
	 * gets past the lobby and the engine's report check is reached (SOS-159). Read once, by the builds.
	 */
	static boolean sameFiles() {
		String v = System.getenv(VARIABLE);
		return v != null && v.contains("samefiles=1");
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
		manualDeals = m.containsKey("manualdeals");
		war = Long.parseLong(m.getOrDefault("war", "-1"));
		fullLog = !"0".equals(m.getOrDefault("fulllog", "1"));
		living = "1".equals(m.getOrDefault("living", "0"));
		tariff = Long.parseLong(m.getOrDefault("tariff", "-1"));
		quit = Long.parseLong(m.getOrDefault("quit", "-1"));
		freeze = Long.parseLong(m.getOrDefault("freeze", "-1"));
		rename = Long.parseLong(m.getOrDefault("rename", "-1"));
		guiPreview = Long.parseLong(m.getOrDefault("guipreview", "-1"));
		String[] view = m.getOrDefault("view", "-1:-1").split(":");
		if (view.length != 2)
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " view '" + m.get("view") + "', not STEP:CLOSE");
		viewAt = Long.parseLong(view[0]);
		viewClose = Long.parseLong(view[1]);
		viewAnswer = m.getOrDefault("viewanswer", "allow");
		refuseJoin = m.getOrDefault("joinanswer", "accept").equals("refuse");
		if (!viewAnswer.equals("allow") && !viewAnswer.equals("refuse") && !viewAnswer.equals("none"))
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " viewanswer '" + viewAnswer + "', not allow, refuse or none");
		String[] capital = m.getOrDefault("capital", "-1:-1").split(":");
		if (capital.length != 2)
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " capital '" + m.get("capital") + "', not X:Y");
		capitalX = Integer.parseInt(capital[0]);
		capitalY = Integer.parseInt(capital[1]);
		startAfterNanos = Long.parseLong(m.getOrDefault("startafter", "0")) * 1_000_000_000L;
		onGone = m.getOrDefault("ongone", "continue");
		if (!onGone.equals("continue") && !onGone.equals("freeze") && !onGone.equals("return"))
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " ongone '" + onGone + "', not continue, freeze or return");
		if (!onDesync.equals("resync") && !onDesync.equals("exit"))
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " ondesync '" + onDesync + "', not resync or exit");
		String net = m.getOrDefault("net", "ip");
		if (!net.equals("ip") && !net.equals("steam"))
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " net '" + net + "', not ip or steam");
		steam = net.equals("steam");
		events = DataFolders.layout().autopilot().resolve("events.jsonl");
		if (!host && !steam && address.isEmpty())
			throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " role=join needs address=");
		commands = new MpAutopilotCommands(events, host, m.getOrDefault("battles", "").equals("auto"), this::event);
	}

	/**
	 * Deals between players (SOS-72, SOS-76): the joiner proposes one, the host accepts, the joiner may then cancel it at
	 * once or set a tariff on the host (SOS-77), and every PC reports what it sees.
	 */
	/** 5 of the first good the player in {@code slot} last reported having 5 of, as a deal's goods; none when it has none. */
	private static int[] someGoods(int slot) {
		for (int i = 0; i < TR.ALL().size(); i++)
			if (PlayerDeals.stock(slot, i) >= 5)
				return new int[] {i, 5};
		return new int[0];
	}

	private void deals(SetupSession setup, long step) {
		int me = setup.factions().slotOf(setup.localPlayerId());
		if (!host && deal >= 0 && step >= deal && first("deal")) {
			int give = (int) Math.min(100, Math.max(0, FACTIONS.player().credits().credits())); // a new city may have less
			DealBook.Terms t = new DealBook.Terms(DealBook.TRADE, give, 0, someGoods(me), new int[0]);
			String why = PlayerDeals.refusal(0, t);
			if (why != null) { // the goods may not be in its warehouses yet
				t = new DealBook.Terms(DealBook.TRADE, give, 0, new int[0], new int[0]);
				why = PlayerDeals.refusal(0, t);
			}
			if (why == null)
				PlayerDeals.propose(0, t);
			event("dealproposed", "\"step\":" + step + ",\"refused\":\"" + clean(why == null ? "" : why) + "\"");
		}
		if (!host && war >= 0 && step >= war && first("war")) {
			String why = PlayerDeals.warRefusal(0);
			if (why == null)
				PlayerDeals.declareWar(0);
			event("war", "\"step\":" + step + ",\"refused\":\"" + clean(why == null ? "" : why) + "\"");
		}
		if (DIP.WAR().is(FACTIONS.player(), FACTIONS.getByIndex(host ? 1 : 0)) && first("atwar")) // on both PCs, at the step it applied
			event("atwar", "\"step\":" + step);
		if (!host && dealCancel >= 0 && step >= dealCancel && PlayerDeals.cancelRefusal(0) == null && first("dealcancel")) {
			PlayerDeals.cancel(0);
			event("dealcancel", "\"step\":" + step);
		}
		if (!host && once.containsKey("dealcancel") && DIP.get(FACTIONS.player(), FACTIONS.getByIndex(0)) == DIP.NEUTRAL()
				&& first("dealcancelled")) // the cancel applied, at its step
			event("dealcancelled", "\"stance\":\"" + DIP.get(FACTIONS.player(), FACTIONS.getByIndex(0)).key() + "\",\"step\":" + step);
		if (!host && tariff >= 0 && step >= tariff && first("tariff")) {
			String why = PlayerDeals.tariffRefusal(0, 50);
			if (why == null)
				PlayerDeals.setTariff(0, 50);
			event("tariffset", "\"step\":" + step + ",\"refused\":\"" + clean(why == null ? "" : why) + "\"");
		}
		for (int s = 1; s < players; s++) // every PC sees the joiner's tariff on the host, at the same step
			if (PlayerDeals.tariff(s, 0) == 50 && first("tariffseen" + s)) // the host has no tariff= of its own
				event("tariffseen", "\"setter\":" + s + ",\"step\":" + step + ",\"revenue\":" + PlayerDeals.tariffRevenue(s, 0, false));
		for (DealBook.Proposal p : PlayerDeals.open()) {
			if (first("dealopen" + p.id()))
				event("dealopen", "\"id\":" + p.id() + ",\"from\":" + p.from() + ",\"to\":" + p.to() + ",\"step\":" + step);
			if (host && !manualDeals && p.to() == me && first("dealaccept" + p.id()))
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

	/**
	 * City viewing (SOS-82): this PC's player asks to view the other player's city and closes the view, as the faction
	 * window's View city and the bar's Close view do; the owner answers as {@code viewanswer} says; every PC reports each
	 * view's state as it changes, and the viewer its catching up and a lost step.
	 */
	private void views(SetupSession setup, EnginePlay engine, MpViews views, long step) {
		ViewRequests r = setup.views();
		if (r == null)
			return;
		int me = r.local();
		if (viewAt >= 0 && step >= viewAt && first("viewask")) {
			int owner = -1;
			for (LobbyPlayer p : setup.players()) {
				int slot = setup.factions().slotOf(p.id());
				if (slot != me && (host || p.host())) {
					owner = slot;
					break;
				}
			}
			String why = owner < 0 ? "no other player" : views.askRefusal(owner);
			if (why == null)
				views.ask(owner);
			event("viewask", "\"step\":" + step + ",\"owner\":" + owner + ",\"refused\":\"" + clean(why == null ? "" : why) + "\"");
		}
		ViewRequests.Ask a = r.ask();
		if (a != null && !viewAnswer.equals("none") && first("viewanswered" + a.viewer() + ":" + a.at())) {
			views.answer(a.viewer(), viewAnswer.equals("allow"));
			event("viewanswered", "\"viewer\":" + a.viewer() + ",\"allowed\":" + viewAnswer.equals("allow") + ",\"step\":" + step);
		}
		if (viewClose >= 0 && step >= viewClose && engine.viewingHere() && first("viewclose")) {
			views.close(sosdet.View.CLOSED);
			event("viewclose", "\"step\":" + step);
		}
		for (LobbyPlayer p : setup.players()) {
			int slot = setup.factions().slotOf(p.id());
			sosdet.View v = engine.view(slot);
			if (v == null)
				continue;
			String state = v.state() + ":" + v.thaw();
			if (state.equals(viewSeen.get(slot)))
				continue;
			viewSeen.put(slot, state);
			event("viewstate", String.format(Locale.ROOT, "\"viewer\":%d,\"owner\":%d,\"state\":%d,\"freeze\":%d,\"thaw\":%d,"
					+ "\"why\":%d,\"reason\":\"%s\",\"loaded\":%b,\"step\":%d,\"loadMs\":%.0f", v.viewer, v.owner, v.state(),
					v.freeze, v.thaw(), v.why(), clean(v.reason() == null ? "" : v.reason()), v.loaded(), step, engine.lastViewLoadMs()));
		}
		if (engine.viewingHere() && engine.viewCaughtUpSeconds() >= 0 && first("viewcaughtup" + viewSeen.get(me)))
			event("viewcaughtup", String.format(Locale.ROOT, "\"step\":%d,\"s\":%.1f", step, engine.viewCaughtUpSeconds()));
		if (engine.viewingHere() && engine.viewDesync() && first("viewdesync"))
			event("viewdesync", "\"step\":" + step);
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
					? new HostedLobby(hostSettings(mp), name, mp.prefs.playerKey, mp.builds, port)
					: new JoinedLobby(address, port, name, mp.prefs.playerKey, mp.builds);
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
			SteamNode node = SteamNode.host(SteamworksPort::new, sosgsgmp.lobby.LobbySettings.MAX_PLAYERS); // as the screens (SOS-190)
			hostSteam = node;
			lobbyOpened = true;
			mp.openLobby(new HostedLobby(hostSettings(mp), name, mp.prefs.playerKey, mp.builds, node), node);
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
		if (browsing.personaName() != null && first("steamname"))
			event("steamname", "\"name\":\"" + clean(browsing.personaName()) + "\"");
		if (browsing.friendLobbies().isEmpty())
			return;
		SteamPort.FriendLobby f = browsing.friendLobbies().get(0);
		browsing.join(f.lobby());
		lobbyOpened = true;
		mp.openLobby(new JoinedLobby(browsing, name, mp.prefs.playerKey, mp.builds), null);
		event("lobby", "\"role\":\"join\",\"net\":\"steam\",\"friend\":\"" + clean(f.friend()) + "\"");
	}

	/** A new game's settings, or with {@code resume=1} the newest campaign save's, as Load Saved Lobby hosts it. */
	private LobbySettings hostSettings(MpMenu mp) {
		if (!resume)
			// as Create Lobby: the remembered mode, or the game's first when none is (a fresh profile)
			return new LobbySettings(difficulty.isEmpty() ? mp.modes.keys[mp.modes.index(mp.prefs.difficulty)] : difficulty,
					Math.max(players, 2)).withFullTransferLog(fullLog).withLivingWorld(living);
		List<CampaignRecord> all = MpSaves.store().all();
		if (all.isEmpty())
			throw new IllegalStateException("SOSGSGMP: " + VARIABLE + " resume=1, but this data folder has no campaign save");
		CampaignRecord r = all.get(0);
		event("resume", "\"campaign\":\"" + Long.toHexString(r.campaign().id()) + "\",\"number\":" + r.number() + ",\"file\":\""
				+ clean(r.file()) + "\"");
		return LobbySettings.resuming(new LobbySettings.Resume(r.campaign(), r.number()));
	}

	/** The lobby screen, every frame: the host starts once every player is in. */
	void lobby(LobbySession s, java.util.function.Consumer<Boolean> leavePrompt) {
		if (leavePreview(leavePrompt))
			return;
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
		if (s.players().size() > 1 && stageChat("lobby", s::say))
			return;
		if (host && s.canStart() && s.players().size() >= players && System.nanoTime() - lobbyAt >= startAfterNanos
				&& first("started")) {
			s.start();
			event("started", "\"players\":" + s.players().size());
		}
	}

	/** A screenshot into the data folder's screenshots, named by time, and an event saying what it shows. */
	private void guiShot(String what) {
		CORE.getGraphics().makeScreenShot();
		event("guishot", "\"what\":\"" + what + "\"");
	}

	/**
	 * With a GUI preview, Leave and freeze as a player meets it (SOS-196): the escape menu with its button, then the
	 * confirmation, each shown three seconds with a shot. True meanwhile; the autopilot then confirms.
	 */
	private boolean freezePreview(MpExit exit) {
		if (guiPreview < 0 || freezeStage == 3)
			return false;
		long now = System.nanoTime();
		if (freezeStage == 0) {
			VIEW.inters().menu.show();
			freezeStage = 1;
			freezeStageAt = now;
			return true;
		}
		double s = (now - freezeStageAt) / 1e9;
		if (s >= 2 && !freezeShot) {
			freezeShot = true;
			guiShot(freezeStage == 1 ? "freezemenu" : "freezeprompt");
		}
		if (s < 3)
			return true;
		freezeShot = false;
		freezeStageAt = now;
		if (freezeStage == 1) {
			VIEW.inters().manager.remove(VIEW.inters().menu);
			exit.leaveFrozen();
			freezeStage = 2;
			return true;
		}
		VIEW.inters().yesNo.deactivate();
		freezeStage = 3;
		return false;
	}

	/**
	 * With a GUI preview, the chat a few seconds after a player froze, and after a session began with a player back
	 * (SOS-196), each once.
	 */
	private void chatShots(SetupSession setup, EnginePlay engine, long now) {
		if (guiPreview < 0)
			return;
		for (int slot = 1; slot < LobbySettings.MAX_SLOTS; slot++)
			if (engine.frozen(slot) && first("frozenchat" + slot))
				chatShotAt = now + 4_000_000_000L;
		if (setup.restartInfo() != null && setup.restartInfo().rejoins() && first("backchat"))
			chatShotAt = now + 6_000_000_000L;
		if (chatShotAt > 0 && now >= chatShotAt) {
			chatShotAt = 0;
			guiShot("chat");
		}
	}

	/**
	 * With a GUI preview, on reaching a screen before the game: a chat line, a screenshot of the box, and this stage
	 * waits a moment for them (SOS-110). True while it waits.
	 */
	private boolean stageChat(String stage, java.util.function.Consumer<String> say) {
		if (guiPreview < 0)
			return false;
		if (first("stagechat" + stage)) {
			say.accept("Hello from the " + stage + " screen, " + name);
			stageAt = System.nanoTime();
			stageShot = false;
		}
		double seconds = (System.nanoTime() - stageAt) / 1e9;
		if (seconds >= 2 && !stageShot) {
			stageShot = true;
			CORE.getGraphics().makeScreenShot();
		}
		return seconds < 3;
	}

	/** With a GUI preview, once in the lobby: Leave's prompt for three seconds, with a screenshot; true meanwhile. */
	private boolean leavePreview(java.util.function.Consumer<Boolean> prompt) {
		if (guiPreview < 0 || leavePreviewed)
			return false;
		if (first("leaveprompt")) {
			prompt.accept(true);
			stageAt = System.nanoTime();
			stageShot = false;
		}
		double seconds = (System.nanoTime() - stageAt) / 1e9;
		if (seconds >= 2 && !stageShot) {
			stageShot = true;
			CORE.getGraphics().makeScreenShot();
		}
		if (seconds < 3)
			return true;
		prompt.accept(false);
		leavePreviewed = true;
		return false;
	}

	/** The map step, every frame: the host generates once and confirms; every PC goes on to the factions. */
	public void map(SetupSession setup, boolean canGoOn, Runnable next) {
		MapStep step = setup.step();
		loadingShot(setup, null, -1, null);
		if (setup.closed() || stageChat("map", setup::say))
			return;
		if (host && step.genId() == 0 && step.canGenerate() && setup.loading().shown() == null) { // once: the generation runs a frame later (SOS-158)
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
	public void factions(SetupSession setup, boolean hasLocation, IntBinaryOperator placeAt, Runnable toLocation) {
		if (setup.closed() || setup.factions().initialized() || stageChat("factions", setup::say))
			return;
		if (guiPreview >= 0 && first("tolocation"))
			toLocation.run(); // the preview's shots show the location screen, where Ready is
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
			if (guiPreview >= 0 && !host) { // near the host's city, so that the preview shows the route notice
				FactionPick other = setup.factions().table().isEmpty() ? null : setup.factions().table().get(0).pick();
				if (other == null || !other.hasLocation())
					return;
				sx = other.x() + 9;
				sy = other.y();
			}
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
		FactionStep f = setup.factions();
		if (previewUnready(setup, f))
			return;
		if (f.canReady() && !f.ready() && !stageChat("placed", setup::say)) {
			event("ready", "");
			setup.ready(true);
		}
		if (f.ready() && stageChat("ready", setup::say))
			return;
		if (host && f.canBuildForSettling() && !stageChat("allready", setup::say) && first("buildforsettling")) {
			event("buildingforsettling", "");
			setup.initialize(); // a living world's first Initialize (SOS-187); the rest is settle()'s
			return;
		}
		if (host && f.canInitialize() && (guiPreview < 0 || unreadySeen(setup)) && !stageChat("allready", setup::say)
				&& first("initialize")) {
			event("initializing", "");
			setup.initialize();
		}
	}

	/**
	 * A living world's settling (SOS-187), once per frame on the world to settle on: a joining PC places its city-state
	 * on the nearest spot the carving rule takes, 7 tiles or more from the host's capital (the others' too), and presses
	 * Ready; the host presses Initialize World once every player is ready.
	 */
	public void settle(SetupSession setup, boolean placed, IntBinaryOperator placeAt) {
		if (VIEW.inters().load.isActivated())
			VIEW.inters().load.deactivate(); // vanilla's "click to continue" after the load, as after the world's
		if (setup.closed() || setup.factions().initialized() || stageChat("settling", setup::say))
			return;
		FactionStep f = setup.factions();
		if (!host && !placed && due()) {
			FactionPick at = f.table().isEmpty() ? null : f.table().get(0).pick();
			if (at == null || !at.hasLocation())
				return;
			for (int r = 7; r < WORLD.TWIDTH(); r++) {
				int found = spiral(at.x(), at.y(), r, (x, y) -> setup.locationProblem(x, y) == null ? 1 : 0);
				if (found >= 0) {
					int x = found >> 16, y = found & 0xFFFF;
					placeAt.applyAsInt(x, y);
					event("settled", "\"x\":" + x + ",\"y\":" + y);
					break;
				}
			}
		}
		if (f.canReady() && !f.ready() && placed && !stageChat("settled", setup::say)) {
			event("ready", "\"settling\":true");
			setup.ready(true);
		}
		if (host && f.canInitialize() && !stageChat("allsettled", setup::say) && first("initialize")) {
			event("initializing", "\"settled\":true");
			setup.initialize();
		}
	}

	/** With a GUI preview, on the joining PC: Ready undone once and held for a screenshot; true during the hold. */
	private boolean previewUnready(SetupSession setup, FactionStep f) {
		if (guiPreview < 0 || host)
			return false;
		if (f.ready() && !stageChat("ready", setup::say) && first("unready")) {
			event("unready", "");
			setup.ready(false);
		}
		return !f.ready() && once.containsKey("unready") && stageChat("unready", setup::say);
	}

	/** With a GUI preview, the host waits for the chat's line about {@link #previewUnready} before it initializes. */
	private static boolean unreadySeen(SetupSession setup) {
		for (int i = 0; i < setup.chat().size(); i++)
			if (setup.chat().line(i).text().endsWith(" is no longer ready."))
				return true;
		return false;
	}

	/** Another PC's game data ended the session here (SOS-159): the window's text, then quit. */
	void mismatch(String text) {
		if (!first("mismatch"))
			return;
		first("closed"); // the session closed for this, not for a reason of its own
		event("mismatch", "\"text\":\"" + clean(text) + "\"");
		CORE.annihilate();
	}

	/**
	 * The loaded game, every frame after the update: the landing party; while playing, hashes, the test desync and the
	 * host's choice in the desync window; at the end, quit.
	 */
	/** Events for the players lost and back, and for a joining PC's host gone silent and heard again (SOS-177). */
	private void lost(SetupSession setup, Departures d, long step) {
		for (Departures.Gone g : d.gone())
			if (d.lost(g.playerId()) && lostSeen.add(g.playerId()))
				event("lost", "\"player\":" + g.playerId() + ",\"name\":\"" + clean(g.name()) + "\",\"holdStep\":" + d.holdStep()
						+ ",\"step\":" + step);
		for (java.util.Iterator<Integer> i = lostSeen.iterator(); i.hasNext();) {
			int id = i.next();
			if (!d.lost(id)) {
				i.remove();
				event("back", "\"player\":" + id + ",\"left\":" + inHold(d, id) + ",\"step\":" + step);
			}
		}
		long silent = setup.hostSilentMs();
		if (!hostSilentSeen && silent >= Departures.LOST_MS) {
			hostSilentSeen = true;
			event("hostsilent", "\"ms\":" + silent + ",\"step\":" + step);
		} else if (hostSilentSeen && silent < Departures.BACK_MS) {
			hostSilentSeen = false;
			event("hostback", "\"step\":" + step);
		}
	}

	private static boolean inHold(Departures d, int id) {
		for (Departures.Gone g : d.gone())
			if (g.playerId() == id)
				return true;
		return false;
	}

	/** Whether a player of the open hold left, not only lost. */
	private static boolean anyLeft(Departures d) {
		for (Departures.Gone g : d.gone())
			if (!d.lost(g.playerId()))
				return true;
		return false;
	}

	void game(SetupSession setup, EnginePlay engine, MpDesync desync, MpSaves saves, MpGone gone, MpExit exit, MpViews views,
			MpLoading loading) {
		loadingShot(setup, engine, engine.active() ? engine.step() : -1, loading);
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
		if (saves.frozeHere() != null) { // this PC left frozen (SOS-194)
			if (first("frozeexit")) {
				event("frozeexit", "\"outcome\":\"" + clean(saves.frozeHere()) + "\",\"save\":" + saves.lastDone().number());
				frozeAt = System.nanoTime();
			}
			// with a GUI preview, the window it leaves with stays up for a shot (SOS-196)
			long shown = System.nanoTime() - frozeAt;
			if (guiPreview >= 0 && shown >= 2_000_000_000L && first("frozeshot"))
				guiShot("frozeexit");
			if (guiPreview < 0 || shown >= 3_000_000_000L)
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
		if (host && guiPreview >= 0 && engine.step() >= guiPreview)
			preview(engine);
		if (host && save >= 0 && engine.step() >= save && exit.saveRefusal() == null && first("save")) {
			exit.save(); // the escape menu's Save Campaign
			event("saveorder", "\"step\":" + engine.step());
		}
		if (host && commands.takeSave()) { // a driver's save (SOS-162): pressed whatever the escape menu would refuse
			exit.save();
			event("saveorder", "\"step\":" + engine.step() + ",\"refusal\":\"" + clean(String.valueOf(exit.saveRefusal())) + "\"");
		}
		if (freeze >= 0 && engine.step() >= freeze && !freezePreview(exit) && first("freeze")) {
			exit.freezeConfirmed(); // the escape menu's Leave and freeze, confirmed
			event("freeze", "\"step\":" + engine.step());
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
		views(setup, engine, views, step);
		commands.frame(engine, setup);
		Departures d = setup.departures();
		if (d.waiting() && d.holdStep() != seenGone) {
			seenGone = d.holdStep();
			goneSeenAt = now;
			event("gone", "\"holdStep\":" + d.holdStep() + ",\"names\":\"" + clean(d.names()) + "\",\"step\":" + step);
		}
		lost(setup, d, step);
		if (guiPreview >= 0 && d.waiting() && now - goneSeenAt > 2_000_000_000L && first("goneshot" + seenGone))
			guiShot("gone"); // the disconnect window, the host's with its three choices (SOS-196)
		chatShots(setup, engine, now);
		if (commands.leaveLost() && gone.canLeaveWithoutHost()) { // SOS-179: the window's button, then the test game quits
			event("lefthostlost", "\"step\":" + step);
			gone.leaveWithoutHost();
			if (first("closed"))
				event("closed", "\"reason\":\"left while the host was lost\"");
			CORE.annihilate();
			return;
		}
		// the host reads the window first, as a player would: every other PC shows the hold before the choice arrives;
		// a hold of players only lost is waited out, as they may come back (SOS-177)
		if (host && gone.waiting() && now - goneSeenAt > CHOOSE_AFTER_NANOS && (anyLeft(d) || commands.chooseLost()))
			gone.choose(onGone.equals("continue") ? Departures.Choice.CONTINUE
					: onGone.equals("freeze") ? Departures.Choice.FREEZE : Departures.Choice.RETURN);
		// every removed player of the campaign, by Continue or missing at a resume
		if (setup.campaign() != null)
			for (Campaign.Player p : setup.campaign().players())
				if (p.slot() != 0 && engine.retired(p.slot()) && first("retired" + p.slot()))
					event("retired", "\"slot\":" + p.slot() + ",\"step\":" + step);
		for (int slot = 1; slot < LobbySettings.MAX_SLOTS; slot++) // every frozen player, on every PC (SOS-194)
			if (engine.frozen(slot) && first("frozen" + slot))
				event("frozen", "\"slot\":" + slot + ",\"step\":" + step);
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
		long last = commands.endAt() >= 0 ? commands.endAt() : steps;
		if (step >= last && !done && (!host || peersReached(engine, MpPlay.otherPcs(setup), last))) {
			done = true;
			event("done", "\"step\":" + step + ",\"desync\":" + engine.desyncStep());
			// a joining PC leaves as a player does: what it queued reaches the host first, then its Leave; a process that
			// just ends resets its connection, which now reads as a broken line the host waits for (SOS-178)
			if (!host)
				setup.leave();
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

	/** The loading window seen, and the one last shot (SOS-158). */
	private Loading.Window seenLoading;
	private Loading.Window shotLoading;

	/**
	 * A screenshot of each loading window in the frame after it first drew (SOS-158), with an event: the window on screen,
	 * which a freeze's own frames can't show. In the game, only before the window's step: the freeze there changes the
	 * game's state, and then the main thread waits for this frame to end while the game's screenshot waits for the main
	 * thread, so both would wait forever.
	 *
	 * @param step the game's step, or -1 in the setup's screens
	 * @param loading the game's loading window, which knows each freeze's step; null in the setup's screens
	 */
	private void loadingShot(SetupSession setup, EnginePlay engine, long step, MpLoading loading) {
		Loading.Window w = setup.loading().shown();
		if (w != seenLoading) {
			seenLoading = w;
			return;
		}
		boolean inGame = w != null && w.kind() != Loading.Kind.WORLD_GENERATION && w.kind() != Loading.Kind.WORLD_INITIALIZATION;
		if (w == null || w == shotLoading || inGame && (step < 0 || loading == null || step >= loading.freezeStep(w)))
			return;
		// this PC's own battle against NPCs is over: its city loads back before the window's own freeze (A6: a VIEW_END
		// window replaced the fighter's BATTLE_END, and the shot waited forever for a frame the load never drew)
		sosdet.LocalBattle own = inGame && engine != null && engine.active()
				? engine.localBattleOf(setup.factions().slotOf(setup.localPlayerId())) : null;
		if (own != null && own.resultStep() >= 0)
			return;
		if (CoreState.changing())
			return; // a whole-game load was asked this frame (B7: the assault's end overtook the window's step)
		shotLoading = w;
		CORE.getGraphics().makeScreenShot(); // into the data folder's screenshots, named by time
		event("loadingshot", "\"kind\":\"" + w.kind() + "\",\"key\":" + w.key());
	}

	/**
	 * snake2d's pending state change, read by reflection: from {@code CORE.setCurrentState} until the new state runs, the
	 * main thread waits for this updater thread to end and runs no GL job, so a screenshot asked meanwhile waits forever.
	 * A holder, resolved on the autopilot's first loading shot only, never in a player's game.
	 */
	private static final class CoreState {

		private static final java.lang.reflect.Field NEW_STATE = field();

		private static java.lang.reflect.Field field() {
			try {
				java.lang.reflect.Field f = CORE.class.getDeclaredField("newState");
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException e) {
				throw new IllegalStateException("SOSGSGMP autopilot: no snake2d.CORE.newState (V71, build 71.44)", e);
			}
		}

		static boolean changing() {
			try {
				return NEW_STATE.get(null) != null;
			} catch (IllegalAccessException e) {
				throw new IllegalStateException("SOSGSGMP autopilot: snake2d.CORE.newState unreadable (V71, build 71.44)", e);
			}
		}
	}

	/** Seconds each preview window stays open. */
	private static final long PREVIEW_NANOS = 6_000_000_000L;
	/** Seconds before the game's screenshot of each preview window: it has drawn by then. */
	private static final long PREVIEW_SHOT_NANOS = 2_000_000_000L;

	/**
	 * The siege windows one after another, as this PC's player (slot 0, the defender) and as slot 1 (the attacker) would
	 * see them, with preview numbers: a {@code guipreview} event as each opens, for the screenshot tool (SOS-92).
	 */
	private void preview(EnginePlay engine) {
		if (hoverGoods != null) // the hover shows while the mouse is on a row: each frame, until the next window (SOS-153)
			MpPlayers.depotHover(VIEW.hoverBox(), FACTIONS.getByIndex(1), hoverGoods, hoverImporting);
		if (previewIndex >= 0 && !previewShot && System.nanoTime() - previewNanos >= PREVIEW_SHOT_NANOS) {
			previewShot = true;
			CORE.getGraphics().makeScreenShot(); // into the data folder's screenshots, named by time
			event("guishot", "\"index\":" + previewIndex);
		}
		if (previewIndex >= 0 && System.nanoTime() - previewNanos < PREVIEW_NANOS)
			return;
		if (previewWindow != null)
			previewWindow.close();
		previewWindow = null;
		hoverGoods = null;
		previewIndex++;
		String me = engine.factionName(0);
		String them = engine.factionName(1);
		String name;
		switch (previewIndex) {
		case 0: name = "siege-notice"; previewWindow = new MpSiegeNotice(16); break;
		case 1: name = "outcome-defender-held"; previewWindow = new MpAssaultOutcome(1, 0, Assault.HELD, 71, false); break;
		case 2: name = "outcome-defender-taken"; previewWindow = new MpAssaultOutcome(1, 0, Assault.TAKEN, 71, false); break;
		case 3: name = "outcome-defender-retreat"; previewWindow = new MpAssaultOutcome(1, 0, Assault.RETREAT, 71, false); break;
		case 4: name = "outcome-attacker-held"; previewWindow = new MpAssaultOutcome(0, 1, Assault.HELD, 71, true); break;
		case 5: name = "outcome-attacker-retreat"; previewWindow = new MpAssaultOutcome(0, 1, Assault.RETREAT, 71, true); break;
		case 6: name = "terms-attacker"; previewWindow = new MpAssaultTerms(1); break;
		case 7: name = "notice-sacked"; previewWindow = new MpViewNotice("Assault", MpAssaultOutcome.termsText(them, me, AssaultTerms.SACK, 60, 1350763, 1200, 300, 150), true); break;
		case 8: name = "notice-vassal"; previewWindow = new MpViewNotice("Assault", MpAssaultOutcome.termsText(them, me, AssaultTerms.VASSALAGE, 25, 0, 0, 0, 0), true); break;
		case 9: name = "vanilla-npc-diplomacy"; previewNpc(false); break; // SOS-100: vanilla's deal with an NPC, to compare
		case 10: name = "vanilla-npc-trade"; previewNpc(true); break;
		case 11: name = "player-diplomacy"; VIEW.world().panels.clear(); MpPlayers.open(FACTIONS.getByIndex(1), true); break;
		case 12: name = "faction-list"; VIEW.world().panels.clear(); VIEW.world().UI.factions.open(null); break; // SOS-134
		case 13: name = "player-goods"; VIEW.world().panels.clear(); MpPlayers.openGoodsForPreview(FACTIONS.getByIndex(1)); break; // SOS-135
		case 14: name = "export-depot"; VIEW.world().panels.clear(); previewDepot(); break; // SOS-136
		case 15: name = "export-depot-over"; VIEW.world().panels.clear(); previewOverCap(false); break; // SOS-148
		case 16: name = "import-depot-over"; VIEW.world().panels.clear(); previewOverCap(true); break; // SOS-148
		case 17: name = "export-depot-hover"; previewHover(false); break; // SOS-153
		case 18: name = "import-depot-hover"; previewHover(true); break; // SOS-153
		default:
			VIEW.inters().popup.close();
			if (first("guipreviewdone"))
				event("guipreview", "\"name\":\"done\",\"step\":" + engine.step());
			return;
		}
		if (previewWindow != null)
			previewWindow.open();
		previewNanos = System.nanoTime();
		previewShot = false;
		event("guipreview", "\"name\":\"" + name + "\",\"step\":" + engine.step());
	}

	/**
	 * Vanilla's Export depot window, as Economy & Trade opens it, for the first goods the other player (slot 1) would
	 * buy, so its partner list shows them (SOS-136).
	 */
	private static void previewDepot() {
		UIGoodsExport ex = new UIGoodsExport(true);
		for (int i = 0; i < TR.ALL().size(); i++) {
			if (PlayerDeals.price(1, TR.ALL().get(i).index(), false) > 0) {
				ex.res.set(TR.ALL().get(i));
				break;
			}
		}
		VIEW.inters().popup.show(ex, null);
	}

	/**
	 * A depot window with slot 1's row darkened (SOS-148): for the first goods slot 1's Import depot takes, this player's
	 * export price is set one over slot 1's highest import price; with {@code importing}, for the first goods slot 1's
	 * Export depot holds, this player's highest import price is set to 1. The prices are this PC's own city's settings,
	 * which the depot's own sliders set the same way.
	 */
	private void previewOverCap(boolean importing) {
		for (int i = 0; i < TR.ALL().size(); i++) {
			TRADABLE t = TR.ALL().get(i);
			if (PlayerDeals.depot(1, t.index(), importing, false) <= 0 || PlayerDeals.price(1, t.index(), importing) <= 0)
				continue;
			if (importing) {
				t.pb().priceCapsI.set(1);
				UIGoodsImport im = new UIGoodsImport();
				im.res.set(t);
				VIEW.inters().popup.show(im, null);
			} else {
				t.ps().priceCapsI.set(PlayerDeals.price(1, t.index(), false) + 1);
				UIGoodsExport ex = new UIGoodsExport(true);
				ex.res.set(t);
				VIEW.inters().popup.show(ex, null);
			}
			event("depotover", "\"importing\":" + importing + ",\"goods\":\"" + t.key() + "\",\"dark\":"
					+ MpPlayers.depotOverCap(FACTIONS.getByIndex(1), t, importing));
			return;
		}
		event("depotover", "\"importing\":" + importing + ",\"goods\":\"none\"");
	}

	/** The depot hover of the other player's (slot 1) row, for the first goods that row is in (SOS-153). */
	private void previewHover(boolean importing) {
		VIEW.inters().popup.close();
		for (int i = 0; i < TR.ALL().size(); i++) {
			TRADABLE t = TR.ALL().get(i);
			if (PlayerDeals.depot(1, t.index(), importing, false) > 0 && PlayerDeals.price(1, t.index(), importing) > 0) {
				hoverGoods = t;
				hoverImporting = importing;
				return;
			}
		}
	}

	/** The goods whose depot hover the preview shows each frame; null for none. */
	private TRADABLE hoverGoods;
	private boolean hoverImporting;

	/** Vanilla's deal panel with the first NPC that has a capital: its Diplomacy, or with Trade drafted (SOS-100). */
	private static void previewNpc(boolean trade) {
		for (int i = 0; i < FACTIONS.NPCs().size(); i++) {
			FactionNPC f = FACTIONS.NPCs().get(i);
			if (f.capitolRegion() == null)
				continue;
			if (trade)
				VIEW.world().UI.factions.openTrade(f);
			else
				VIEW.world().UI.factions.openDip(f);
			return;
		}
	}

	/**
	 * Hot-join (SOS-190), on the host: a PC asks to join; the autopilot accepts once the popup has been shown (and shot,
	 * with a GUI preview).
	 */
	boolean acceptJoin(java.util.function.Consumer<String> say) {
		return !stageChat("joinask", say) && first("acceptjoin");
	}

	/** Host: the answer {@link #acceptJoin} gives: true for Accept (the default), false for Refuse. */
	boolean joinAccepted() {
		event("joinanswer", "\"accept\":" + !refuseJoin);
		return !refuseJoin;
	}

	/** Hot-join's screens as the autopilot walks them (SOS-198): 0 race, 1 race shot, 2 Customize, 3 its shot, 4 location. */
	private int joinScreen;
	private long joinScreenAt;

	/**
	 * Hot-join, once per frame after the game's update (SOS-198): with a GUI preview, the race screen and Customize
	 * Faction two seconds each with a shot, then the location screen; without, the location screen at once.
	 */
	void joinScreens(Runnable customize, Runnable location) {
		if (joinScreen == 4)
			return;
		if (guiPreview < 0) {
			joinScreen = 4;
			location.run();
			return;
		}
		long now = System.nanoTime();
		if (joinScreenAt == 0)
			joinScreenAt = now;
		double s = (now - joinScreenAt) / 1e9;
		if ((joinScreen == 0 || joinScreen == 2) && s >= 2) {
			guiShot(joinScreen == 0 ? "joinrace" : "joincustomize");
			joinScreen++;
		} else if (joinScreen == 1 && s >= 3) {
			joinScreen = 2;
			joinScreenAt = now;
			customize.run();
		} else if (joinScreen == 3 && s >= 3) {
			joinScreen = 4;
			location.run();
		}
	}

	/** Hot-join with a GUI preview: when this PC picked its spot, for the shot (SOS-198). */
	private long joinPlacedAt;

	/**
	 * Hot-join (SOS-190), on the joining PC's snapshot: the nearest spot 7 tiles or more from the host's capital that the
	 * carving rule takes, then Ready, once; with a GUI preview, a shot of the screen three seconds after the spot is picked.
	 */
	void joinSettle(int hostX, int hostY, boolean placed, java.util.function.BiFunction<Integer, Integer, String> problem,
			IntBinaryOperator placeAt, Runnable ready) {
		if (VIEW.inters().load.isActivated()) {
			VIEW.inters().load.deactivate(); // vanilla's "click to continue" after the snapshot's load, as settle's
			return;
		}
		if (stageChat("joinsettling", s -> {
		}))
			return;
		if (!placed && due() && first("joinplace")) {
			for (int r = 7; r < WORLD.TWIDTH(); r++) {
				int found = spiral(hostX, hostY, r, (x, y) -> problem.apply(x, y) == null ? 1 : 0);
				if (found >= 0) {
					int x = found >> 16, y = found & 0xFFFF;
					placeAt.applyAsInt(x, y);
					event("joinsettled", "\"x\":" + x + ",\"y\":" + y);
					return;
				}
			}
			event("joinsettled", "\"x\":-1");
		}
		if (placed && guiPreview >= 0 && !once.containsKey("joinshot")) { // the pick screen as a newcomer sees it (SOS-198)
			if (joinPlacedAt == 0)
				joinPlacedAt = System.nanoTime();
			if (System.nanoTime() - joinPlacedAt >= 3_000_000_000L && first("joinshot"))
				guiShot("joinsettle");
			return;
		}
		if (placed && first("joinready")) {
			event("joinready", "");
			ready.run();
		}
	}

	private boolean first(String what) {
		return once.putIfAbsent(what, Boolean.TRUE) == null;
	}

	/**
	 * One line to the events file. A line that can't be written now (the test runner reading the file holds it; laptop
	 * T7 crashed on that) waits for the next event: lines keep their order, and a test aid never stops the game.
	 */
	void event(String event, String fields) {
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
