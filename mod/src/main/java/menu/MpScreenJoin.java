package menu;

import java.io.IOException;

import init.sprite.UI.UI;
import menu.GUI.COLORS;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.lobby.Lobby;
import sosgsgmp.lobby.LobbyPrefs;
import sosgsgmp.session.JoinedLobby;
import util.gui.misc.GText;
import view.menu.MenuScreen;

/** SOSGSGMP: Join by IP: the player's name and the host's address and port (spec §5, lobby step 3). */
final class MpScreenJoin extends GUI.Shadower implements SC {

	private final MpMenu mp;
	private final MpInput name;
	private final MpInput address;
	private final MpInput port;
	private final GText error = new GText(UI.FONT().M, 160);

	MpScreenJoin(MpMenu mp) {
		this.mp = mp;
		MenuScreen screen = new MenuScreen("join by IP", GUI.labelColor) {
			@Override
			protected void back() {
				mp.menu.switchScreen(mp.main);
			}
		};
		screen.addButt(new MenuScreen.ScreenButton("join") {
			@Override
			protected void clickA() {
				join();
			}
		});
		add(screen);

		GuiSection form = new GuiSection();
		name = new MpInput(Lobby.MAX_NAME, false, mp.prefs.name);
		form.addDown(0, MpMenu.row("your name", name));
		address = new MpInput(40, false, mp.prefs.address);
		form.addDown(8, MpMenu.row("host address", address));
		port = new MpInput(5, true, Integer.toString(mp.prefs.port));
		form.addDown(8, MpMenu.row("port", port));
		form.addDown(24, MpMenu.note("The host's lobby screen shows its addresses. Over Radmin VPN, use the host's Radmin IP (26.x.x.x)."));

		error.color(COLORS.error);
		form.addDown(16, new RENDEROBJ.RenderImp(600, UI.FONT().M.height() * 2) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				error.render(r, body.x1(), body.y1());
			}
		});

		form.body().centerIn(MenuScreen.inner);
		add(form);
	}

	private void join() {
		String host = address.text();
		int p = LobbyPrefs.parsePort(port.text());
		if (host.isEmpty()) {
			error.clear().add("Type the host's address.");
			return;
		}
		if (p < 0) {
			error.clear().add("The port must be a number from 1 to 65535.");
			return;
		}
		String n = Lobby.cleanName(name.text());
		mp.prefs.name = n;
		mp.prefs.address = host;
		mp.prefs.port = p;
		mp.savePrefs();
		try {
			JoinedLobby lobby = new JoinedLobby(host, p, n, mp.prefs.playerKey);
			error.clear();
			mp.openLobby(lobby);
		} catch (IOException e) {
			error.clear().add("Can't connect: ").add(String.valueOf(e.getMessage()));
		}
	}

	@Override
	public boolean back(Menu menu) {
		menu.switchScreen(mp.main);
		return true;
	}
}
