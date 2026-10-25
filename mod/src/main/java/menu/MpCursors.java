package menu;

import java.util.List;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.Cursors;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GText;
import view.main.VIEW;
import view.subview.GameWindow;

/**
 * SOSGSGMP: the other players' cursors on a world map (spec §5 "Other players' cursors"): the setup's location screen
 * and the game's world view. Each frame it sends this PC's cursor (on the map when the mouse is over the map's window)
 * and draws every other player's with their name, in the player's colour: the n-th player of the lobby has the n-th
 * colour, the same on every PC. Per frame it allocates nothing; the names are rebuilt when the session changes.
 */
public final class MpCursors {

	private static final COLOR[] COLORS = {
			new ColorImp(230, 60, 60), new ColorImp(60, 140, 240), new ColorImp(60, 200, 90), new ColorImp(240, 200, 40),
			new ColorImp(200, 80, 220), new ColorImp(40, 210, 210), new ColorImp(250, 140, 40), new ColorImp(240, 240, 240),
			new ColorImp(150, 100, 60), new ColorImp(120, 120, 250), new ColorImp(160, 230, 60), new ColorImp(250, 120, 170),
			new ColorImp(90, 160, 120), new ColorImp(200, 200, 120), new ColorImp(130, 130, 130), new ColorImp(30, 90, 160) };
	private static final int ARM = 6;

	private final SetupSession setup;
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

	/** Sends this PC's cursor: on the map when {@code onMap} and the mouse is inside {@code window}'s view. */
	public void send(GameWindow window, boolean onMap) {
		if (setup.closed())
			return;
		boolean in = onMap && window.view().holdsPoint(VIEW.mouse().x(), VIEW.mouse().y());
		setup.cursor(in, in ? window.pixel().x() : 0, in ? window.pixel().y() : 0);
	}

	/** Draws every other player's cursor that is inside {@code window}'s view, on screen. */
	public void render(SPRITE_RENDERER r, GameWindow window) {
		if (setup.changes() != namedChanges)
			names();
		Cursors c = setup.cursors();
		int zoom = window.zoomout();
		for (int i = 0; i < c.count(); i++) {
			int x = window.view().x1() + ((c.x(i) - window.pixels().x1()) >> zoom);
			int y = window.view().y1() + ((c.y(i) - window.pixels().y1()) >> zoom);
			if (!window.view().holdsPoint(x, y))
				continue;
			int n = nameIndex(c.playerId(i));
			COLOR color = n < 0 ? COLOR.WHITE100 : COLORS[colorOf[n] % COLORS.length];
			color.render(r, x - ARM, x + ARM + 1, y - 1, y + 2);
			color.render(r, x - 1, x + 2, y - ARM, y + ARM + 1);
			COLOR.unbind();
			if (n >= 0) {
				color.bind();
				names[n].render(r, x + ARM + 2, y + 2);
				COLOR.unbind();
			}
		}
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
