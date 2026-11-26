package view.world.generator;

import game.faction.Faction;
import init.constant.C;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.RECTANGLE;
import view.subview.GameWindow;
import world.WORLD;
import world.map.regions.Region;

/**
 * SOSGSGMP: a thin line between every two regions while a player places a city-state on a built world (a living world's
 * settling, hot-join; owner, 2026-11-26). Vanilla draws a border only where the holder changes, so the regions of one
 * state show none and every piece of unowned land shares one; here each region is outlined, in its holder's colour
 * (vanilla's border colour, the banner's background) or grey when nobody holds it. Drawn from the side panel's render
 * over the tiles in view; nothing in the game changes.
 */
final class MpRegionLines {

	private static final int WIDTH = 2;
	private static final COLOR UNOWNED = COLOR.WHITE50;

	private MpRegionLines() {
	}

	static void render(SPRITE_RENDERER r, GameWindow window) {
		int zoom = window.zoomout();
		RECTANGLE view = window.view();
		RECTANGLE pixels = window.pixels();
		int tile = C.TILE_SIZE >> zoom;
		int tx1 = Math.max(0, pixels.x1() / C.TILE_SIZE);
		int ty1 = Math.max(0, pixels.y1() / C.TILE_SIZE);
		int tx2 = Math.min(WORLD.TWIDTH() - 1, pixels.x2() / C.TILE_SIZE + 1);
		int ty2 = Math.min(WORLD.THEIGHT() - 1, pixels.y2() / C.TILE_SIZE + 1);
		for (int ty = ty1; ty <= ty2; ty++) {
			int y = view.y1() + ((ty * C.TILE_SIZE - pixels.y1()) >> zoom);
			for (int tx = tx1; tx <= tx2; tx++) {
				Region here = WORLD.REGIONS().map.get(tx, ty);
				int x = view.x1() + ((tx * C.TILE_SIZE - pixels.x1()) >> zoom);
				if (tx < tx2) {
					Region right = WORLD.REGIONS().map.get(tx + 1, ty);
					if (right != here)
						line(r, colour(here, right), x + tile - WIDTH / 2, x + tile + WIDTH / 2, y, y + tile, view);
				}
				if (ty < ty2) {
					Region below = WORLD.REGIONS().map.get(tx, ty + 1);
					if (below != here)
						line(r, colour(here, below), x, x + tile, y + tile - WIDTH / 2, y + tile + WIDTH / 2, view);
				}
			}
		}
		COLOR.unbind();
	}

	/** A held region's colour wins over unowned land's; between two held ones, the first's. None at a coast. */
	private static COLOR colour(Region a, Region b) {
		if (a == null || b == null)
			return null;
		Faction f = a.faction() != null ? a.faction() : b.faction();
		return f == null ? UNOWNED : f.banner().colorBG();
	}

	private static void line(SPRITE_RENDERER r, COLOR c, int x1, int x2, int y1, int y2, RECTANGLE view) {
		if (c == null || x1 < view.x1() || y1 < view.y1() || x2 > view.x2() || y2 > view.y2())
			return;
		c.render(r, x1, x2, y1, y2);
	}
}
