package menu;

import java.nio.file.Files;
import java.util.List;

import init.constant.C;
import init.sprite.UI.UI;
import menu.GUI.COLORS;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.color.OPACITY;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.game.DataFolders;
import sosgsgmp.game.WorldGenAdapter;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.net.Message;
import sosgsgmp.net.SteamNode;
import sosgsgmp.session.LobbySession;
import sosgsgmp.session.SetupSession;
import util.colors.GCOLOR;
import util.gui.misc.GText;
import view.menu.MenuScreen;

/**
 * SOSGSGMP: the lobby: who is in it, the settings, Start Lobby for the host, Leave (spec §5, lobby step 4).
 * Its texts are rebuilt only when the session reports a change, never while rendering. Once the lobby has started,
 * its session hands over to the setup, and every PC leaves the menu to build the game for the map step when the
 * host's first build arrives (setup-map-step.md §3).
 */
final class MpScreenLobby extends GUI.Shadower implements SC {

	private static final int CHAT_WIDTH = 520;
	private static final int CHAT_ROWS = 6;

	private final MpMenu mp;
	private LobbySession session;
	/** The playtest log while the lobby is open (SOS-246); the setup carries its file on from the start. */
	private MpLobbyLog log;
	/** The host's Steam lobby, for invites; null over direct IP and on joining PCs. */
	private SteamNode steam;
	private String shownSteamFailure;
	private SetupSession setup;
	private WorldGenAdapter world;
	/** A resume's own save on this PC, once the lobby's settings name it (SOS-64); null for a new game. */
	private CampaignRecord resumed;
	/** Why this PC left a resume lobby by itself: it lacks the save. Null otherwise. */
	private String resumeProblem;
	/** A frozen player's PC coming back into the running game (SOS-195): no snapshot; the host's restart, then its own save. */
	private boolean returning;
	/** With {@link #returning}: the setup the host's restart made, until its slot map is here. */
	private SetupSession restarted;
	private int shownChanges = -1;
	private int shownPlayers;
	private final GText[] rows = new GText[LobbySettings.MAX_PLAYERS];
	/** The settings and status block's width. */
	private static final int SETTINGS_WIDTH = 520;
	private final GText settings = new GText(UI.FONT().H2, 96).setMaxWidth(SETTINGS_WIDTH);
	private final GText status = new GText(UI.FONT().M, 2048);
	/** This PC's game files code, every PC of the lobby's (SOS-159); and the unsupported mods' warning, if any. */
	private final GText files = new GText(UI.FONT().M, 64);
	private final GText unsupported = new GText(UI.FONT().M, 1024);
	private final CLICKABLE start;
	private final MpChatBox chat = new MpChatBox(CHAT_WIDTH, CHAT_ROWS, this::say);
	/** Asks before leaving, over the lobby, which takes no clicks meanwhile: a stray click shouldn't drop a player out. */
	private final GuiSection leaveAsk = new GuiSection();
	private final GText leaveQuestion = new GText(UI.FONT().H2, 64);
	private boolean askingLeave;
	/**
	 * The host's Start of a resume with players missing asks, as Leave does, how they stay out (spec §11 Resuming):
	 * frozen or removed; with a save of an older version, removed only. The section shown, or null.
	 */
	private GuiSection startAsk;
	private final GuiSection startAskFreeze = new GuiSection();
	private final GuiSection startAskRemove = new GuiSection();
	private final GText startQuestion = new GText(UI.FONT().H2, 128);
	private final GText startInfo = new GText(UI.FONT().M, 512);
	private static final int START_INFO_WIDTH = 900;
	private static final String FREEZE_INFO = "Freeze them: their cities wait as this save has them, and they can come back"
			+ " into the running game. Remove them: their regions become free and their armies disband.";
	private static final String OLD_SAVE_INFO = "This save is from an older version of the mod, so they can't be frozen."
			+ " Remove them: their regions become free and their armies disband.";

	MpScreenLobby(MpMenu mp) {
		this.mp = mp;
		settings.setMultipleLines(true);
		MenuScreen screen = new MenuScreen("lobby", GUI.labelColor) {
			@Override
			protected void back() {
				askLeave();
			}
		};
		start = new MenuScreen.ScreenButton("start lobby") {
			@Override
			protected void clickA() {
				if (!session.canStart())
					return;
				LobbySettings ls = session.settings();
				if (ls != null && ls.resume() != null && !missing(ls.resume(), session.players()).isEmpty())
					askStart(ls.resume());
				else
					session.start();
			}

			@Override
			protected void renAction() {
				activeSet(session != null && session.canStart());
			}
		};
		screen.addButt(start);
		screen.addButt(gap());
		screen.addButt(new MenuScreen.ScreenButton("invite Steam friends") {
			@Override
			protected void clickA() {
				if (steam != null)
					steam.invite();
			}

			@Override
			protected void renAction() {
				activeSet(steam != null && steam.lobby() != 0 && setup == null);
			}
		});
		screen.addButt(gap());
		screen.addButt(new MenuScreen.ScreenButton("leave") {
			@Override
			protected void clickA() {
				askLeave();
			}
		});
		add(screen);
		leaveAsk.add(new MenuScreen.ScreenButton("confirm") {
			@Override
			protected void clickA() {
				askingLeave = false;
				leave();
			}
		});
		leaveAsk.addRightC(80, new MenuScreen.ScreenButton("cancel") {
			@Override
			protected void clickA() {
				askingLeave = false;
			}
		});
		leaveAsk.body().centerIn(C.DIM());
		startAskFreeze.add(askButton("freeze them", () -> startWithout(true)));
		startAskFreeze.addRightC(80, askButton("remove them", () -> startWithout(false)));
		startAskFreeze.addRightC(80, askButton("back", () -> startAsk = null));
		startAskFreeze.body().centerIn(C.DIM());
		startAskRemove.add(askButton("remove them", () -> startWithout(false)));
		startAskRemove.addRightC(80, askButton("back", () -> startAsk = null));
		startAskRemove.body().centerIn(C.DIM());

		for (int i = 0; i < rows.length; i++)
			rows[i] = new GText(UI.FONT().H2, 64);
		files.add("Game files: ").add(mp.builds.files().code());
		files.color(COLORS.label);
		List<String> others = mp.builds.files().unsupported();
		if (!others.isEmpty())
			unsupported.add("Unsupported mods: ").add(String.join(", ", others))
					.add(". They may cause crashes, desyncs and other problems in multiplayer.");

		int rowH = UI.FONT().H2.height() + 4;
		GuiSection content = new GuiSection();
		content.addDown(0, new RENDEROBJ.RenderImp(420, rowH * rows.length) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				for (int i = 0; i < shownPlayers; i++)
					rows[i].render(r, body.x1(), body.y1() + i * rowH);
			}
		});
		content.addRightC(40, new RENDEROBJ.RenderImp(SETTINGS_WIDTH, rowH * rows.length) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				settings.render(r, body.x1(), body.y1());
				int y = body.y1() + settings.height() + 4;
				files.render(r, body.x1(), y);
				y += files.height() + 12;
				if (unsupported.length() > 0) {
					GCOLOR.T().WARNING.bind();
					y += UI.FONT().M.render(r, unsupported, body.x1(), y, body.width(), 1.0) + 12;
				}
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, status, body.x1(), y, body.width(), 1.0);
				COLOR.unbind();
			}
		});
		chat.body().moveX1(content.getLastX1()).moveY2(content.body().y2());
		content.add(chat);
		content.body().centerIn(MenuScreen.inner);
		add(content);
	}

	private void say(String text) {
		if (setup != null)
			setup.say(text);
		else if (session != null)
			session.say(text);
	}

	/** Extra room between the bottom buttons: their names are long, and the menu's own gap runs them together. */
	private static RENDEROBJ gap() {
		return new RENDEROBJ.RenderImp(24, 1) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
			}
		};
	}

	void open(LobbySession s, SteamNode steamLobby, MpLobbyLog lobbyLog) {
		session = s;
		log = lobbyLog;
		steam = steamLobby;
		shownSteamFailure = null;
		setup = null;
		resumed = null;
		resumeProblem = null;
		shownChanges = -1;
		start.visableSet(s.isHost());
		askingLeave = false;
		startAsk = null;
		chat.show(s.chat());
		refresh();
	}

	private static CLICKABLE askButton(String name, Runnable action) {
		return new MenuScreen.ScreenButton(name) {
			@Override
			protected void clickA() {
				action.run();
			}
		};
	}

	/** The host's Start with players of the resume missing: the question, Freeze them only for a save that can freeze. */
	private void askStart(LobbySettings.Resume r) {
		boolean canFreeze = resumed != null && resumed.lastReports();
		startQuestion.clear().add("Start without ").add(missing(r, session.players())).add("?");
		startQuestion.adjustWidth();
		startInfo.clear().add(canFreeze ? FREEZE_INFO : OLD_SAVE_INFO);
		startAsk = canFreeze ? startAskFreeze : startAskRemove;
	}

	/** The question's answer: the lobby starts, its missing players frozen or removed on every PC. */
	private void startWithout(boolean freeze) {
		startAsk = null;
		if (session != null && session.canStart())
			session.start(freeze);
	}

	/** Leave and Escape come here. A lobby already closed has nothing to leave: it goes back at once. */
	private void askLeave() {
		startAsk = null;
		if (session == null || session.state() == LobbyState.CLOSED) {
			leave();
			return;
		}
		leaveQuestion.clear().add(session.isHost() ? "Leave and close the lobby for every player?" : "Leave the lobby?");
		leaveQuestion.adjustWidth();
		askingLeave = true;
	}

	@Override
	public void render(SPRITE_RENDERER r, float ds) {
		super.render(r, ds);
		if (!askingLeave && startAsk == null)
			return;
		OPACITY.O75.bind();
		COLOR.BLACK.render(r, 0, C.WIDTH(), 0, C.HEIGHT());
		OPACITY.unbind();
		if (askingLeave) {
			leaveQuestion.renderC(r, C.WIDTH() / 2, leaveAsk.body().y1() - 60);
			leaveAsk.render(r, ds);
			return;
		}
		startQuestion.renderC(r, C.WIDTH() / 2, startAsk.body().y1() - 130);
		COLOR.WHITE100.bind();
		UI.FONT().M.render(r, startInfo, C.WIDTH() / 2 - START_INFO_WIDTH / 2, startAsk.body().y1() - 90, START_INFO_WIDTH, 1.0);
		COLOR.unbind();
		startAsk.render(r, ds);
	}

	@Override
	public boolean click() {
		return askingLeave ? leaveAsk.click() : startAsk != null ? startAsk.click() : super.click();
	}

	/** Called every frame by the menu's update; applies network events and refreshes the texts on change. */
	@Override
	public boolean hover(COORDINATE mCoo) {
		chat.wheel();
		if (setup != null) {
			updateSetup();
		} else if (session != null) {
			session.update();
			log.frame();
			findResumed();
			refresh();
			steamFailure();
			if (mp.autopilot != null)
				mp.autopilot.lobby(session, this::previewLeave);
			if (session.state() == LobbyState.STARTED)
				startSetup();
		}
		return askingLeave ? leaveAsk.hover(mCoo) : startAsk != null ? startAsk.hover(mCoo) : super.hover(mCoo);
	}

	/** The test autopilot's GUI preview: shows Leave's prompt or takes it away. */
	private void previewLeave(boolean show) {
		if (show)
			askLeave();
		else
			askingLeave = false;
	}

	/**
	 * A resume lobby: this PC's own record and game save of the campaign save it resumes, looked up once the settings are
	 * here. A PC without them can't play the campaign: it leaves, saying why.
	 */
	private void findResumed() {
		LobbySettings ls = session.settings();
		if (resumed != null || resumeProblem != null || ls == null || ls.resume() == null)
			return;
		LobbySettings.Resume r = ls.resume();
		CampaignRecord own = MpSaves.store().find(r.campaign().id(), r.number());
		if (own != null && Files.exists(DataFolders.layout().save(own.file()))) {
			resumed = own;
			return;
		}
		resumeProblem = "You don't have save " + r.number() + " of " + r.campaign().name()
				+ (own == null ? "." : ": its game save is gone from the saves folder.");
		session.leave();
	}

	/** Steam couldn't start or create the lobby: the reason replaces the status, once. */
	private void steamFailure() {
		String failure = steam == null ? null : steam.failure();
		if (failure == null || failure.equals(shownSteamFailure))
			return;
		shownSteamFailure = failure;
		status.clear().add(failure);
	}

	/** The lobby has started: its connection and players go to the setup; the host starts the first build. */
	private void startSetup() {
		world = new WorldGenAdapter();
		returning = session.running() && session.returning();
		setup = session.handOver(world, new WorldSetupAdapter());
		if (setup.isHost() && resumed == null)
			setup.build(mp.prefs.mapSize);
	}

	/** Until the first build arrives; then this PC leaves the menu the way vanilla starts a game. */
	private void updateSetup() {
		setup.update();
		if (setup.closed()) {
			status.clear().add(setup.closedReason());
			return;
		}
		if (setup.candidate() && returning) {
			comeBack();
			return;
		}
		if (setup.candidate()) { // hot-join (SOS-190): this PC picks on the host's snapshot
			byte[] snapshot = setup.takeSettleWorld();
			if (snapshot != null)
				leaveMenu(new MpJoinSettle(setup, snapshot));
			return;
		}
		if (resumed != null) {
			// a resume: every PC loads its own save once the host's slot map is here
			if (setup.factions() != null && setup.factions().initialized())
				leaveMenu(new MpWorldLoad(setup, resumed));
			return;
		}
		if (setup.step().pendingBuild() == 0)
			return;
		leaveMenu(new MpGameBuild(setup, world, mp.mapSizeMemory()));
	}

	/**
	 * A frozen player's PC coming back (SOS-195): once the host's restart is here and its slot map with it, this PC loads
	 * its own latest save of the campaign, the one its city froze in; every load then reads the host's world.
	 */
	private void comeBack() {
		if (restarted == null)
			restarted = setup.takeRestart();
		if (restarted == null)
			return;
		restarted.update();
		if (restarted.closed()) {
			status.clear().add(restarted.closedReason());
			return;
		}
		if (restarted.factions() == null || !restarted.factions().initialized())
			return;
		Message.Restart r = restarted.restartInfo();
		CampaignRecord own = MpSaves.store().latest(r.campaign().id());
		if (own == null || own.slot() != r.rejoinSlot() || !Files.exists(DataFolders.layout().save(own.file())))
			throw new IllegalStateException("SOSGSGMP: this PC has no save of " + r.campaign().name() + " in slot "
					+ r.rejoinSlot() + " to come back from" + (own == null ? "" : " (its latest: save " + own.number() + ")"));
		SetupSession next = restarted;
		restarted = null;
		leaveMenu(new MpWorldLoad(next, own));
	}

	private void leaveMenu(snake2d.CORE_STATE.Constructor next) {
		log = null; // the setup carries the file on
		askingLeave = false;
		startAsk = null;
		session = null;
		steam = null;
		setup = null;
		world = null;
		resumed = null;
		shownPlayers = 0;
		mp.menu.start(next);
	}

	private void refresh() {
		if (session.changes() == shownChanges)
			return;
		shownChanges = session.changes();
		session.chat().roster(session.players());

		List<LobbyPlayer> players = session.players();
		shownPlayers = players.size();
		for (int i = 0; i < shownPlayers; i++) {
			LobbyPlayer p = players.get(i);
			rows[i].clear().add(i + 1).add(". ").add(p.name());
			if (p.host())
				rows[i].add("  (host)");
			if (p.id() == session.localPlayerId())
				rows[i].add("  (you)");
			rows[i].color(p.id() == session.localPlayerId() ? COLORS.selected : COLOR.WHITE100);
		}

		settings.clear();
		LobbySettings ls = session.settings();
		if (ls != null)
			settings.add(mp.modes.name(ls.difficulty())).add(",  ").add(shownPlayers).add(" / ").add(ls.maxPlayers()).add(" players");
		if (ls != null && ls.resume() != null)
			settings.add(",  ").add(ls.resume().campaign().name()).add(", save ").add(ls.resume().number());
		if (ls != null && ls.livingWorld())
			settings.add(",  living world");
		settings.color(COLORS.label);
		settings.adjustWidth(); // wraps at the block's width: a living world's line is longer than the block (SOS-187)

		status.clear();
		switch (session.state()) {
			case CONNECTING -> status.add("Connecting to the host...");
			case OPEN -> status.add(session.running() && session.returning()
					? "The game is under way. The host is asked whether you can come back to your city."
					: session.running()
					? "The game is under way. The host is asked whether you can join it with a city-state of your own."
					: !session.isHost() ? "Waiting for the host to start."
					: ls != null && ls.resume() != null && !missing(ls.resume(), players).isEmpty()
							? "Still missing: " + missing(ls.resume(), players) + (session.canStart()
									? resumed != null && resumed.lastReports()
											? ". Wait for them, or start without them: you choose then whether they are frozen or removed."
											: ". Wait for them, or start now: they are removed from the campaign, and can join its game again only as a new player."
									: ". The player in the campaign's first slot has to be in before it can start.")
							: session.canStart() ? "Start the lobby when everyone is in." : "Waiting for players. Start needs 2 or more.");
			case STARTED -> status.add(session.running() && session.returning()
					? "The host lets you back in. Every game saves and reloads with your city in it..."
					: session.running() ? "The host lets you in. Getting a copy of the world to pick your spot on..."
					: resumed != null ? "The lobby has started. Loading your save of the campaign..."
					: "The lobby has started. Building the game for the map...");
			case CLOSED -> status.add(resumeProblem != null ? resumeProblem : session.closedReason());
		}
	}

	/** The campaign's players not in the lobby yet, by name. */
	private static String missing(LobbySettings.Resume r, List<LobbyPlayer> in) {
		StringBuilder b = new StringBuilder();
		for (var p : r.campaign().playing()) {
			boolean here = false;
			for (LobbyPlayer l : in)
				here |= l.name().equals(p.name());
			if (!here)
				b.append(b.length() == 0 ? "" : ", ").append(p.name());
		}
		return b.toString();
	}

	private void leave() {
		if (setup != null) {
			setup.leave();
			MpLobbyLog.left(setup, "Leave in the lobby screen" + (setup.closedReason() == null ? "" : ": " + setup.closedReason()));
		} else if (session != null) {
			session.leave();
			log.close(resumeProblem != null ? resumeProblem : "Leave: " + session.closedReason());
		}
		log = null;
		setup = null;
		session = null;
		steam = null;
		shownPlayers = 0;
		mp.menu.switchScreen(mp.main);
	}

	@Override
	public boolean back(Menu menu) {
		if (askingLeave)
			askingLeave = false;
		else
			askLeave();
		return true;
	}
}
