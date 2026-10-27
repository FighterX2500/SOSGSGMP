package menu;

import java.nio.file.Files;
import java.util.List;

import init.paths.PATHS;
import init.sprite.UI.UI;
import menu.GUI.COLORS;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.game.WorldGenAdapter;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.net.SteamNode;
import sosgsgmp.session.LobbySession;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GText;
import view.menu.MenuScreen;

/**
 * SOSGSGMP: the lobby: who is in it, the settings, Start Lobby for the host, Leave (spec §5, lobby step 4).
 * Its texts are rebuilt only when the session reports a change, never while rendering. Once the lobby has started,
 * its session hands over to the setup, and every PC leaves the menu to build the game for the map step when the
 * host's first build arrives (setup-map-step.md §3).
 */
final class MpScreenLobby extends GUI.Shadower implements SC {

	private final MpMenu mp;
	private LobbySession session;
	/** The host's Steam lobby, for invites; null over direct IP and on joining PCs. */
	private SteamNode steam;
	private String shownSteamFailure;
	private SetupSession setup;
	private WorldGenAdapter world;
	/** A resume's own save on this PC, once the lobby's settings name it (SOS-64); null for a new game. */
	private CampaignRecord resumed;
	/** Why this PC left a resume lobby by itself: it lacks the save. Null otherwise. */
	private String resumeProblem;
	private int shownChanges = -1;
	private int shownPlayers;
	private final GText[] rows = new GText[LobbySettings.MAX_PLAYERS];
	private final GText settings = new GText(UI.FONT().H2, 64);
	private final GText status = new GText(UI.FONT().M, 256);
	private final CLICKABLE start;

	MpScreenLobby(MpMenu mp) {
		this.mp = mp;
		MenuScreen screen = new MenuScreen("lobby", GUI.labelColor) {
			@Override
			protected void back() {
				leave();
			}
		};
		start = new MenuScreen.ScreenButton("start lobby") {
			@Override
			protected void clickA() {
				if (session.canStart())
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
				leave();
			}
		});
		add(screen);

		for (int i = 0; i < rows.length; i++)
			rows[i] = new GText(UI.FONT().H2, 64);

		int rowH = UI.FONT().H2.height() + 4;
		GuiSection content = new GuiSection();
		content.addDown(0, new RENDEROBJ.RenderImp(420, rowH * rows.length) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				for (int i = 0; i < shownPlayers; i++)
					rows[i].render(r, body.x1(), body.y1() + i * rowH);
			}
		});
		content.addRightC(40, new RENDEROBJ.RenderImp(520, rowH * rows.length) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				settings.render(r, body.x1(), body.y1());
				COLOR.WHITE100.bind();
				int y = body.y1() + settings.height() + 16;
				UI.FONT().M.render(r, status, body.x1(), y, body.width(), 1.0);
				COLOR.unbind();
			}
		});
		content.body().centerIn(MenuScreen.inner);
		add(content);
	}

	/** Extra room between the bottom buttons: their names are long, and the menu's own gap runs them together. */
	private static RENDEROBJ gap() {
		return new RENDEROBJ.RenderImp(24, 1) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
			}
		};
	}

	void open(LobbySession s, SteamNode steamLobby) {
		session = s;
		steam = steamLobby;
		shownSteamFailure = null;
		setup = null;
		resumed = null;
		resumeProblem = null;
		shownChanges = -1;
		start.visableSet(s.isHost());
		refresh();
	}

	/** Called every frame by the menu's update; applies network events and refreshes the texts on change. */
	@Override
	public boolean hover(COORDINATE mCoo) {
		if (setup != null) {
			updateSetup();
		} else if (session != null) {
			session.update();
			findResumed();
			refresh();
			steamFailure();
			if (mp.autopilot != null)
				mp.autopilot.lobby(session);
			if (session.state() == LobbyState.STARTED)
				startSetup();
		}
		return super.hover(mCoo);
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
		if (own != null && Files.exists(PATHS.local().save().get(own.file()))) {
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

	private void leaveMenu(snake2d.CORE_STATE.Constructor next) {
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
		if (ls != null)
			settings.add(ls.fullTransferLog() ? ",  every transfer logged" : ",  transfers between players logged");
		settings.color(COLORS.label);

		status.clear();
		switch (session.state()) {
			case CONNECTING -> status.add("Connecting to the host...");
			case OPEN -> status.add(!session.isHost() ? "Waiting for the host to start."
					: ls != null && ls.resume() != null && !missing(ls.resume(), players).isEmpty()
							? "Still missing: " + missing(ls.resume(), players) + (session.canStart()
									? ". Wait for them, or start now: they are removed from the campaign and can't play in it again."
									: ". The player in the campaign's first slot has to be in before it can start.")
							: session.canStart() ? "Start the lobby when everyone is in." : "Waiting for players. Start needs 2 or more.");
			case STARTED -> status.add(resumed != null ? "The lobby has started. Loading your save of the campaign..."
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
		if (setup != null)
			setup.leave();
		else if (session != null)
			session.leave();
		setup = null;
		session = null;
		steam = null;
		shownPlayers = 0;
		mp.menu.switchScreen(mp.main);
	}

	@Override
	public boolean back(Menu menu) {
		leave();
		return true;
	}
}
