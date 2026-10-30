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
import sosgsgmp.game.SteamworksPort;
import sosgsgmp.lobby.Lobby;
import sosgsgmp.net.SteamNode;
import sosgsgmp.net.SteamPort;
import sosgsgmp.session.JoinedLobby;
import util.gui.misc.GText;
import view.menu.MenuScreen;

/**
 * SOSGSGMP: Join through Steam (SOS-60, spec §5 lobby step 3): friends playing Songs of Syx in a lobby, each a
 * button; accepting a friend's invite in Steam's overlay (Shift+Tab) joins that lobby too. The screen takes the main
 * menu's browsing node ({@link MpInviteWatch}, SOS-103), or starts Steam, and stops it when the player goes back;
 * joining keeps it for the lobby.
 */
final class MpScreenSteamJoin extends GUI.Shadower implements SC {

	private static final int ROWS = 8;

	private final MpMenu mp;
	private final MpInput name;
	private final GText status = new GText(UI.FONT().M, 160);
	private final GText[] rows = new GText[ROWS];
	private SteamNode node;
	private List<SteamPort.FriendLobby> shown = List.of();
	private String shownFailure;

	MpScreenSteamJoin(MpMenu mp) {
		this.mp = mp;
		MenuScreen screen = new MenuScreen("join through Steam", GUI.labelColor) {
			@Override
			protected void back() {
				close();
				mp.menu.switchScreen(mp.main);
			}
		};
		add(screen);

		GuiSection form = new GuiSection();
		name = new MpInput(Lobby.MAX_NAME, false, mp.prefs.name);
		form.addDown(0, MpMenu.row("your name", name));
		form.addDown(16, MpMenu.note("Friends in a multiplayer lobby. Or accept a friend's invite in Steam's overlay (Shift+Tab)."));
		for (int i = 0; i < ROWS; i++) {
			rows[i] = new GText(UI.FONT().H2, 64);
			form.addDown(i == 0 ? 16 : 4, row(i));
		}
		status.color(COLORS.label);
		form.addDown(16, new RENDEROBJ.RenderImp(600, UI.FONT().M.height() * 2) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				status.render(r, body.x1(), body.y1());
			}
		});
		form.body().centerIn(MenuScreen.inner);
		add(form);
	}

	private CLICKABLE row(int i) {
		return new CLICKABLE.ClickableAbs(600, UI.FONT().H2.height() + 8) {
			@Override
			protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
				if (i >= shown.size())
					return;
				GUI.labelColor.renderFrame(r, body, -2, 1);
				rows[i].color(isHovered ? COLORS.selected : COLOR.WHITE100);
				rows[i].renderCY(r, body.x1() + 12, body.cY());
			}

			@Override
			protected void clickA() {
				if (i < shown.size())
					join(shown.get(i).lobby());
			}

			@Override
			protected void renAction() {
				activeSet(i < shown.size());
			}
		};
	}

	/** Opens the screen with the main menu's browsing node when there is one (SOS-103), or starts Steam. */
	void open(SteamNode browsing) {
		close();
		node = browsing != null ? browsing : SteamNode.browse(SteamworksPort::new);
		shown = List.of();
		shownFailure = null;
		status.clear().add("Starting Steam...");
	}

	/** Every frame: the friends' lobbies and an accepted invite. */
	@Override
	public boolean hover(COORDINATE mCoo) {
		if (node != null)
			update();
		return super.hover(mCoo);
	}

	private void update() {
		String failure = node.failure();
		if (failure != null) {
			if (!failure.equals(shownFailure)) {
				shownFailure = failure;
				status.clear().add(failure);
			}
			return;
		}
		if (node.invited() != 0) {
			join(node.invited());
			return;
		}
		List<SteamPort.FriendLobby> now = node.friendLobbies();
		if (now.equals(shown))
			return;
		shown = now;
		for (int i = 0; i < ROWS && i < shown.size(); i++)
			rows[i].clear().add(shown.get(i).friend()).add("'s lobby");
		status.clear().add(shown.isEmpty() ? "No friend is in a multiplayer lobby yet." : "Click a lobby to join it.");
	}

	/** An invite accepted on the main menu (SOS-103): joins it through this screen's join, with the menu's node. */
	void joinInvite(SteamNode browsing, long lobby) {
		open(browsing);
		join(lobby);
	}

	private void join(long lobby) {
		String n = Lobby.cleanName(name.text());
		mp.prefs.name = n;
		mp.savePrefs();
		SteamNode joining = node;
		node = null; // the lobby owns it now
		joining.join(lobby);
		mp.openLobby(new JoinedLobby(joining, n, mp.prefs.playerKey), null);
	}

	private void close() {
		if (node != null)
			node.close();
		node = null;
	}

	@Override
	public boolean back(Menu menu) {
		close();
		menu.switchScreen(mp.main);
		return true;
	}
}
