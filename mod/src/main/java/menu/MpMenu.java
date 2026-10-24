package menu;

import java.io.IOException;
import java.nio.file.Path;

import init.paths.PATHS;
import init.paths.PATHS.ResFolder;
import init.sprite.UI.UI;
import menu.GUI.COLORS;
import snake2d.LOG;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.file.Json;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.misc.ACTION;
import snake2d.util.sprite.SPRITE;
import sosgsgmp.lobby.LobbyPrefs;
import sosgsgmp.session.LobbySession;
import util.info.INFO;

/**
 * SOSGSGMP: the multiplayer screens of the main menu (SOS-49). Hook M1 in ScMain adds the button
 * {@link #button(Menu)} returns; everything else lives here, in the game's package because the menu's screen
 * interface SC is package-private. One instance per Menu, made on the first click, so no state outlives the menu.
 */
final class MpMenu {

	final Menu menu;
	final LobbyPrefs prefs;
	final Modes modes;
	final MpScreenMain main;
	final MpScreenCreate create;
	final MpScreenJoin join;
	final MpScreenLobby lobby;
	private final Path prefsFile;

	/** The Multiplayer button of the main menu (hook M1). */
	static CLICKABLE button(Menu menu) {
		CLICKABLE b = GUI.getNavButt("multiplayer");
		b.clickActionSet(new ACTION() {
			private MpMenu mp;

			@Override
			public void exe() {
				if (mp == null)
					mp = new MpMenu(menu);
				menu.switchScreen(mp.main);
			}
		});
		return b;
	}

	private MpMenu(Menu menu) {
		this.menu = menu;
		prefsFile = PATHS.local().PROFILE.get().resolve("SOSGSGMP-lobby.properties");
		prefs = loadPrefs(prefsFile);
		modes = new Modes(menu);
		main = new MpScreenMain(this);
		create = new MpScreenCreate(this);
		join = new MpScreenJoin(this);
		lobby = new MpScreenLobby(this);
	}

	private static LobbyPrefs loadPrefs(Path file) {
		try {
			return LobbyPrefs.load(file);
		} catch (IOException e) {
			LOG.err("SOSGSGMP: can't read " + file + ", using defaults: " + e.getMessage());
			return LobbyPrefs.defaults();
		}
	}

	void savePrefs() {
		try {
			prefs.save(prefsFile);
		} catch (IOException e) {
			LOG.err("SOSGSGMP: can't save " + prefsFile + ": " + e.getMessage());
		}
	}

	void openLobby(LobbySession session) {
		lobby.open(session);
		menu.switchScreen(lobby);
	}

	/** A label of fixed width followed by a field, for the forms. */
	static GuiSection row(CharSequence label, RENDEROBJ field) {
		GuiSection row = new GuiSection();
		SPRITE text = UI.FONT().H2.getText(label);
		row.add(new RENDEROBJ.RenderImp(220, field.body().height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLORS.label.bind();
				text.renderCY(r, body.x1(), body.cY());
				COLOR.unbind();
			}
		});
		row.addRightC(8, field);
		return row;
	}

	/** A line of small label-coloured text. */
	static RENDEROBJ note(CharSequence text) {
		RENDEROBJ.Sprite n = new RENDEROBJ.Sprite(GUI.getSmallText(text));
		n.setColor(COLORS.label);
		return n;
	}

	/** The game's difficulty modes (the player/mode folder), as the random game screen lists them. */
	static final class Modes {

		final String[] keys;
		final CharSequence[] names;
		final SPRITE[] icons;

		private Modes(Menu menu) {
			ResFolder path = PATHS.PLAYER().folder("mode");
			keys = path.init.getFiles();
			names = new CharSequence[keys.length];
			icons = new SPRITE[keys.length];
			for (int i = 0; i < keys.length; i++) {
				names[i] = new INFO(new Json(path.text.gets(keys[i]))).name;
				icons[i] = menu.res.s().modeIcons[new Json(path.init.gets(keys[i])).i("ICON_I")];
			}
		}

		/** Index of a mode key, or 0 (the first mode) if this game has no such mode. */
		int index(String key) {
			for (int i = 0; i < keys.length; i++)
				if (keys[i].equals(key))
					return i;
			return 0;
		}

		/** A mode's name, or its key if this game has no such mode (a host with other mods). */
		CharSequence name(String key) {
			for (int i = 0; i < keys.length; i++)
				if (keys[i].equals(key))
					return names[i];
			return key;
		}
	}
}
