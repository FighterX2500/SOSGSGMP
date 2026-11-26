package menu;

import java.io.IOException;

import game.faction.player.PTitles.IconMaker;
import init.constant.C;
import init.sprite.UI.UI;
import menu.GUI.COLORS;
import snake2d.CORE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.text.Font;
import sosgsgmp.lobby.Lobby;
import sosgsgmp.lobby.LobbyPrefs;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.game.SteamworksPort;
import sosgsgmp.net.SteamNode;
import sosgsgmp.session.HostedLobby;
import sosgsgmp.setup.TitlesMode;
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
	/** The world: vanilla, or the players settle in a living world (SOS-187). */
	private boolean livingWorld;
	/** Which titles the factions hold (spec §5 Titles). */
	private TitlesMode titles = TitlesMode.NONE;
	private final GText error = new GText(UI.FONT().M, 160);
	/**
	 * The tooltip of what the mouse is over, set while hovering, or null. The main menu draws no hover boxes, so this
	 * screen draws its own, as vanilla's random game screen writes its modes' descriptions itself.
	 */
	private CharSequence tipTitle;
	private CharSequence tipText;
	private static final int TIP_WIDTH = 420;
	private static final int TIP_PAD = 8;

	MpScreenCreate(MpMenu mp) {
		this.mp = mp;
		MenuScreen screen = new MenuScreen("create lobby", GUI.labelColor) {
			@Override
			protected void back() {
				mp.menu.switchScreen(mp.main);
			}
		};
		screen.addButt(new MenuScreen.ScreenButton("create (Direct IP)") {
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

		GuiSection world = new GuiSection();
		world.add(worldButton("Vanilla", false));
		world.addRightC(24, worldButton("Living world", true));
		form.addDown(16, MpMenu.row("world", world));

		GuiSection titleRow = new GuiSection();
		for (TitlesMode m : TitlesMode.values())
			titleRow.addRightC(m.ordinal() == 0 ? 0 : 24, titlesButton(m));
		form.addDown(8, MpMenu.row("titles", titleRow));

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
		GText value = new GText(UI.FONT().M, 4);
		field.addRightC(8, new RENDEROBJ.RenderImp(48, UI.FONT().H1.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				value.clear().add(players.get());
				value.adjustWidth();
				value.renderC(r, body.cX(), body.cY());
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

	/**
	 * One choice of the world (spec §5 World, SOS-187): vanilla, built around every player's pick; or a living world,
	 * built around the host, where every other player settles a city-state carved into the real political map.
	 */
	private CLICKABLE worldButton(CharSequence label, boolean living) {
		CharSequence tip = living ? "The world is built around the host. Every other player then picks a spot on the real map, "
				+ "with its kingdoms, borders and roads, and their city-state is carved there."
				: "The world is built as in vanilla, around every player's capital, picked on the bare terrain.";
		return new GUI.Button(UI.FONT().H2.getText(label)) {
			@Override
			protected void clickA() {
				livingWorld = living;
			}

			@Override
			protected void renAction() {
				selectedSet(livingWorld == living);
			}

			@Override
			public boolean hover(COORDINATE mCoo) {
				if (!super.hover(mCoo))
					return false;
				tip(label, tip);
				return true;
			}
		};
	}

	/** One choice of the titles (spec §5 Titles): none, the host's for everyone, or each player's own. */
	private CLICKABLE titlesButton(TitlesMode m) {
		return new GUI.Button(UI.FONT().H2.getText(m.label)) {
			@Override
			protected void clickA() {
				titles = m;
			}

			@Override
			protected void renAction() {
				selectedSet(titles == m);
			}

			@Override
			public boolean hover(COORDINATE mCoo) {
				if (!super.hover(mCoo))
					return false;
				tip(m.label, m.desc);
				return true;
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

			@Override
			public boolean hover(COORDINATE mCoo) {
				if (!super.hover(mCoo))
					return false;
				tip(mp.modes.names[i], mp.modes.descs[i]);
				return true;
			}
		};
	}

	private void tip(CharSequence title, CharSequence text) {
		tipTitle = title;
		tipText = text;
	}

	@Override
	public boolean hover(COORDINATE mCoo) {
		tip(null, null); // what is hovered sets it again
		return super.hover(mCoo);
	}

	@Override
	public void render(SPRITE_RENDERER r, float ds) {
		super.render(r, ds);
		if (tipText != null)
			renderTip(r);
	}

	/** The tooltip box beside the mouse, kept on the screen. */
	private void renderTip(SPRITE_RENDERER r) {
		Font head = UI.FONT().H2;
		Font text = UI.FONT().M;
		int w = Math.max(head.width(tipTitle), text.getDim(tipText, TIP_WIDTH).x());
		int h = head.height() + 4 + text.getHeight(tipText, TIP_WIDTH);
		COORDINATE m = CORE.getInput().getMouse().getCoo();
		int x = m.x() + 24;
		int y = m.y() + 24;
		if (x + w + TIP_PAD > C.DIM().x2())
			x = m.x() - 24 - w;
		if (y + h + TIP_PAD > C.DIM().y2())
			y = m.y() - 24 - h;
		COLOR.BLACK.render(r, x - TIP_PAD, x + w + TIP_PAD, y - TIP_PAD, y + h + TIP_PAD);
		GUI.labelColor.renderFrame(r, x - TIP_PAD, x + w + TIP_PAD, y - TIP_PAD, y + h + TIP_PAD, 0, 2);
		GUI.labelColor.bind();
		head.render(r, tipTitle, x, y);
		COLOR.unbind();
		text.render(r, tipText, x, y + head.height() + 4, TIP_WIDTH, 1.0);
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
		LobbySettings settings = new LobbySettings(mp.modes.keys[mode], players.get()).withLivingWorld(livingWorld).withTitles(titles);
		if (steam) {
			mp.invites.stop(); // Steam runs once in the process (SOS-103)
			SteamNode node = SteamNode.host(SteamworksPort::lease, LobbySettings.MAX_PLAYERS); // room for a player joining later (SOS-190); the lobby refuses past its limit
			error.clear();
			mp.openLobby(new HostedLobby(settings, n, mp.prefs.playerKey, mp.builds, node), node);
			return;
		}
		try {
			HostedLobby lobby = new HostedLobby(settings, n, mp.prefs.playerKey, mp.builds, p);
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
