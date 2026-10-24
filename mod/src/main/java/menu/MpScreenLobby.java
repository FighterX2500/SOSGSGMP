package menu;

import java.util.List;

import init.sprite.UI.UI;
import menu.GUI.COLORS;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.HostedLobby;
import sosgsgmp.session.LobbySession;
import util.gui.misc.GText;
import view.menu.MenuScreen;

/**
 * SOSGSGMP: the lobby: who is in it, the settings, Start Lobby for the host, Leave (spec §5, lobby step 4).
 * Its texts are rebuilt only when the session reports a change, never while rendering.
 */
final class MpScreenLobby extends GUI.Shadower implements SC {

	private final MpMenu mp;
	private LobbySession session;
	private int shownChanges = -1;
	private int shownPlayers;
	private final GText[] rows = new GText[LobbySettings.MAX_PLAYERS];
	private final GText settings = new GText(UI.FONT().H2, 64);
	private final GText status = new GText(UI.FONT().M, 256);
	private final GText join = new GText(UI.FONT().M, 256);
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
		// Steam placeholder: Steam friend invites come with the Steam card.
		CLICKABLE invite = new MenuScreen.ScreenButton("invite Steam friends");
		invite.activeSet(false);
		screen.addButt(invite);
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
				COLORS.label.bind();
				UI.FONT().M.render(r, join, body.x1(), y + 4 * UI.FONT().M.height(), body.width(), 1.0);
				COLOR.unbind();
			}
		});
		content.body().centerIn(MenuScreen.inner);
		add(content);
	}

	void open(LobbySession s) {
		session = s;
		shownChanges = -1;
		start.visableSet(s.isHost());
		join.clear();
		if (s instanceof HostedLobby h) {
			join.add("Others join at ");
			List<String> addresses = HostedLobby.localAddresses();
			for (int i = 0; i < addresses.size(); i++)
				join.add(i == 0 ? "" : " or ").add(addresses.get(i));
			join.add(", port ").add(h.port()).add('.');
		}
		refresh();
	}

	/** Called every frame by the menu's update; applies network events and refreshes the texts on change. */
	@Override
	public boolean hover(COORDINATE mCoo) {
		if (session != null) {
			session.update();
			refresh();
		}
		return super.hover(mCoo);
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
		settings.color(COLORS.label);

		status.clear();
		switch (session.state()) {
			case CONNECTING -> status.add("Connecting to the host...");
			case OPEN -> status.add(session.isHost()
					? (session.canStart() ? "Start the lobby when everyone is in." : "Waiting for players. Start needs 2 or more.")
					: "Waiting for the host to start.");
			case STARTED -> status.add("The lobby has started. Race, location and world setup come with the engine's player rework (SOSDET SOS-46); leave to go back.");
			case CLOSED -> status.add(session.closedReason());
		}
	}

	private void leave() {
		if (session != null)
			session.leave();
		session = null;
		shownPlayers = 0;
		mp.menu.switchScreen(mp.main);
	}

	@Override
	public boolean back(Menu menu) {
		leave();
		return true;
	}
}
