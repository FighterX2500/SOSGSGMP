package menu;

import java.io.IOException;

import game.faction.player.PTitles.IconMaker;
import init.sprite.UI.UI;
import menu.GUI.COLORS;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.lobby.Lobby;
import sosgsgmp.lobby.LobbyPrefs;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.game.SteamworksPort;
import sosgsgmp.net.SteamNode;
import sosgsgmp.session.HostedLobby;
import util.data.INT.IntImp;
import util.gui.misc.GText;
import view.menu.MenuScreen;

/** SOSGSGMP: Create Lobby: the host's name, port, player limit and difficulty (spec §5, lobby step 2). */
final class MpScreenCreate extends GUI.Shadower implements SC {

	private final MpMenu mp;
	private final MpInput name;
	private final MpInput port;
	private final IntImp players = new IntImp(LobbySettings.MIN_PLAYERS, LobbySettings.MAX_PLAYERS);
	private int mode;
	private final GText error = new GText(UI.FONT().M, 160);

	MpScreenCreate(MpMenu mp) {
		this.mp = mp;
		MenuScreen screen = new MenuScreen("create lobby", GUI.labelColor) {
			@Override
			protected void back() {
				mp.menu.switchScreen(mp.main);
			}
		};
		screen.addButt(new MenuScreen.ScreenButton("create (IP)") {
			@Override
			protected void clickA() {
				create(false);
			}
		});
		screen.addButt(new MenuScreen.ScreenButton("create Steam lobby") {
			@Override
			protected void clickA() {
				create(true);
			}
		});
		add(screen);

		GuiSection form = new GuiSection();
		name = new MpInput(Lobby.MAX_NAME, false, mp.prefs.name);
		form.addDown(0, MpMenu.row("your name", name));
		port = new MpInput(5, true, Integer.toString(mp.prefs.port));
		form.addDown(8, MpMenu.row("port", port));

		players.set(mp.prefs.maxPlayers);
		form.addDown(8, MpMenu.row("players", playersField()));

		mode = mp.modes.index(mp.prefs.difficulty);
		GuiSection icons = new GuiSection();
		for (int i = 0; i < mp.modes.keys.length; i++)
			icons.addRightC(i == 0 ? 0 : 8, modeButton(i));
		form.addDown(24, MpMenu.row("difficulty", icons));
		form.addDown(8, new RENDEROBJ.RenderImp(400, UI.FONT().H2.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().H2.render(r, mp.modes.names[mode], body.x1() + 228, body.y1());
				COLOR.unbind();
			}
		});

		error.color(COLORS.error);
		form.addDown(24, new RENDEROBJ.RenderImp(600, UI.FONT().M.height() * 2) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				error.render(r, body.x1(), body.y1());
			}
		});

		form.body().centerIn(MenuScreen.inner);
		add(form);
	}

	/** "<< 4 >>": the player limit, as a field in the form's rows (vanilla's OptionLine places its own label). */
	private GuiSection playersField() {
		GuiSection field = new GuiSection();
		field.add(stepButton("<<", -1));
		GText value = new GText(UI.FONT().H2, 4);
		field.addRightC(8, new RENDEROBJ.RenderImp(40, UI.FONT().H2.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				value.clear().add(players.get());
				value.renderCY(r, body.cX() - value.width() / 2, body.cY());
			}
		});
		field.addRightC(8, stepButton(">>", 1));
		return field;
	}

	private CLICKABLE stepButton(CharSequence label, int step) {
		return new GUI.Button(GUI.getBigTexts(label)) {
			@Override
			protected void clickA() {
				players.inc(step);
			}

			@Override
			protected void renAction() {
				activeSet(step < 0 ? players.get() > players.min() : players.get() < players.max());
			}
		};
	}

	private CLICKABLE modeButton(int i) {
		return new CLICKABLE.ClickableAbs(IconMaker.WIDTH + 24, IconMaker.HEIGHT + 24) {
			@Override
			protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
				GUI.labelColor.renderFrame(r, body, -4, 2);
				if (isSelected) {
					COLOR.WHITE100.renderFrame(r, body, -8, 3);
					COLOR.WHITE150.bind();
				} else if (isHovered) {
					COLOR.WHITE50.renderFrame(r, body, -8, 2);
					COLOR.WHITE150.bind();
				}
				mp.modes.icons[i].renderC(r, body.cX(), body.cY());
				COLOR.unbind();
			}

			@Override
			protected void clickA() {
				mode = i;
			}

			@Override
			protected void renAction() {
				selectedSet(mode == i);
			}
		};
	}

	/** Opens the lobby over direct IP on the port, or as a Steam lobby for friends (the port isn't used). */
	private void create(boolean steam) {
		int p = LobbyPrefs.parsePort(port.text());
		if (p < 0 && !steam) {
			error.clear().add("The port must be a number from 1 to 65535.");
			return;
		}
		String n = Lobby.cleanName(name.text());
		mp.prefs.name = n;
		if (p > 0)
			mp.prefs.port = p;
		mp.prefs.maxPlayers = players.get();
		mp.prefs.difficulty = mp.modes.keys[mode];
		mp.savePrefs();
		LobbySettings settings = new LobbySettings(mp.modes.keys[mode], players.get());
		if (steam) {
			SteamNode node = SteamNode.host(SteamworksPort::new, players.get());
			error.clear();
			mp.openLobby(new HostedLobby(settings, n, node), node);
			return;
		}
		try {
			HostedLobby lobby = new HostedLobby(settings, n, p);
			error.clear();
			mp.openLobby(lobby, null);
		} catch (IOException e) {
			error.clear().add("Can't open port ").add(p).add(": ").add(String.valueOf(e.getMessage()));
		}
	}

	@Override
	public boolean back(Menu menu) {
		menu.switchScreen(mp.main);
		return true;
	}
}
