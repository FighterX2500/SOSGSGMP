package menu;

import java.util.List;

import init.constant.C;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;
import snake2d.util.datatypes.RECTANGLE;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.Cursors;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GText;
import util.gui.panel.GFrame;
import view.main.VIEW;
import view.subview.GameWindow;
import world.WORLD;
import world.WorldMinimap;

/**
 * SOSGSGMP: the other players' cursors (spec §5 "Other players' cursors", §12): on the setup's location screen, the
 * game's world view and a city, the owner's and its viewers' alike (SOS-58). Each frame it sends this PC's cursor (on
 * its place when the mouse is over that map's window) and draws every other player's on the same place with their
 * name, in the player's colour: the n-th player of the lobby has the n-th
 * colour, the same on every PC. Per frame it allocates nothing; the names are rebuilt when the session changes.
 */
public final class MpCursors {

	private static final COLOR[] COLORS = {
			new ColorImp(230, 60, 60), new ColorImp(60, 140, 240), new ColorImp(60, 200, 90), new ColorImp(240, 200, 40),
			new ColorImp(200, 80, 220), new ColorImp(40, 210, 210), new ColorImp(250, 140, 40), new ColorImp(240, 240, 240),
			new ColorImp(150, 100, 60), new ColorImp(120, 120, 250), new ColorImp(160, 230, 60), new ColorImp(250, 120, 170),
			new ColorImp(90, 160, 120), new ColorImp(200, 200, 120), new ColorImp(130, 130, 130), new ColorImp(30, 90, 160) };
	private static final int ARM = 6;
	private static final int MINIMAP_ARM = 4;

	/** The n-th player's colour, on cursors and in the chat. */
	static COLOR colour(int n) {
		return COLORS[n % COLORS.length];
	}

	private final SetupSession setup;
	/** A GUI preview has no mouse to move: the cursor stays at the window's centre, for the screenshots. */
	private final boolean pinned = MpAutopilot.previewing();
	private final GText[] names = new GText[LobbySettings.MAX_PLAYERS];
	private final int[] nameIds = new int[LobbySettings.MAX_PLAYERS];
	private final int[] colorOf = new int[LobbySettings.MAX_PLAYERS];
	private int named;
	private int namedChanges = -1;

	public MpCursors(SetupSession setup) {
		this.setup = setup;
		for (int i = 0; i < names.length; i++)
			names[i] = new GText(UI.FONT().S, 32);
	}

	/**
	 * Sends this PC's cursor: on {@code place} ({@link Cursors}) when the mouse is inside {@code window}'s view, else
	 * off. {@code window} may be null with {@link Cursors#OFF}.
	 */
	public void send(GameWindow window, int place) {
		if (setup.closed())
			return;
		if (pinned && place != Cursors.OFF) {
			setup.cursor(place, window.pixels().cX(), window.pixels().cY());
			return;
		}
		boolean in = place != Cursors.OFF && window.view().holdsPoint(VIEW.mouse().x(), VIEW.mouse().y());
		setup.cursor(in ? place : Cursors.OFF, in ? window.pixel().x() : 0, in ? window.pixel().y() : 0);
	}

	/** Draws every other player's cursor on {@code place} that is inside {@code window}'s view, on screen. */
	public void render(SPRITE_RENDERER r, GameWindow window, int place) {
		if (setup.changes() != namedChanges)
			names();
		Cursors c = setup.cursors();
		int zoom = window.zoomout();
		for (int i = 0; i < c.count(); i++) {
			if (c.place(i) != place)
				continue;
			int x = window.view().x1() + ((c.x(i) - window.pixels().x1()) >> zoom);
			int y = window.view().y1() + ((c.y(i) - window.pixels().y1()) >> zoom);
			if (!window.view().holdsPoint(x, y))
				continue;
			int n = nameIndex(c.playerId(i));
			COLOR color = n < 0 ? COLOR.WHITE100 : colour(colorOf[n]);
			cross(r, color, x, y, ARM);
			if (n >= 0) {
				color.bind();
				names[n].render(r, x + ARM + 2, y + 2);
				COLOR.unbind();
			}
		}
	}

	/** Every other player's cursor on the world, on a world minimap (SOS-124); {@code map} is its body. */
	public void renderWorldMinimap(SPRITE_RENDERER r, RECTANGLE map) {
		if (setup.changes() != namedChanges)
			names();
		Cursors c = setup.cursors();
		int border = (map.width() - WorldMinimap.WIDTH) / 2;
		for (int i = 0; i < c.count(); i++) {
			if (c.place(i) != Cursors.WORLD)
				continue;
			int x = map.x1() + border + WorldMinimap.WIDTH * c.x(i) / WORLD.PWIDTH();
			int y = map.y1() + border + WorldMinimap.HEIGHT * c.y(i) / WORLD.PHEIGHT();
			cross(r, playerColour(c.playerId(i)), x, y, MINIMAP_ARM);
		}
	}

	/**
	 * Every other player's cursor on {@code place}, on the city minimap (SOS-124): one pixel per tile around
	 * {@code window}'s centre, as the game's {@code UIMinimapPanel} draws it; {@code map} is its body.
	 */
	public void renderCityMinimap(SPRITE_RENDERER r, RECTANGLE map, GameWindow window, int place) {
		if (setup.changes() != namedChanges)
			names();
		Cursors c = setup.cursors();
		int left = map.x1() + GFrame.MARGIN - (window.tiles().cX() - map.width() / 2);
		int top = map.y1() + GFrame.MARGIN - (window.tiles().cY() - map.height() / 2);
		for (int i = 0; i < c.count(); i++) {
			if (c.place(i) != place)
				continue;
			int x = left + c.x(i) / C.TILE_SIZE;
			int y = top + c.y(i) / C.TILE_SIZE;
			if (map.holdsPoint(x, y))
				cross(r, playerColour(c.playerId(i)), x, y, MINIMAP_ARM);
		}
	}

	private COLOR playerColour(int playerId) {
		int n = nameIndex(playerId);
		return n < 0 ? COLOR.WHITE100 : colour(colorOf[n]);
	}

	private static void cross(SPRITE_RENDERER r, COLOR color, int x, int y, int arm) {
		color.render(r, x - arm, x + arm + 1, y - 1, y + 2);
		color.render(r, x - 1, x + 2, y - arm, y + arm + 1);
		COLOR.unbind();
	}

	private void names() {
		namedChanges = setup.changes();
		List<LobbyPlayer> players = setup.players();
		named = Math.min(players.size(), names.length);
		for (int i = 0; i < named; i++) {
			LobbyPlayer p = players.get(i);
			nameIds[i] = p.id();
			colorOf[i] = i;
			names[i].clear().add(p.name());
		}
	}

	private int nameIndex(int playerId) {
		for (int i = 0; i < named; i++)
			if (nameIds[i] == playerId)
				return i;
		return -1;
	}
}
