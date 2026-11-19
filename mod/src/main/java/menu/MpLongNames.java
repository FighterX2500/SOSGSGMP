package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import util.colors.GCOLOR;

/**
 * SOSGSGMP (owner, 2026-11-19): a player's faction name can be longer than any NPC's, so where vanilla draws a name on
 * one line, a name too wide for it goes on two. Hooks M4 (the faction list) and M16 (the region buttons) call here.
 * No allocation.
 */
public final class MpLongNames {

	private MpLongNames() {
	}

	/** The faction list's name block: two lines of the small font, as vanilla's label colour. */
	public static int listHeight() {
		return 2 * UI.FONT().S.height();
	}

	/** Hook M4: a faction's name in the list's row, wrapped at {@code width}, on two lines at most. */
	public static void listName(SPRITE_RENDERER r, CharSequence name, int x1, int y1, int width) {
		GCOLOR.T().H2.bind();
		UI.FONT().S.render(r, name, x1, y1, width, 1.0);
		COLOR.unbind();
	}

	/**
	 * Hook M16: a region's name on its button, centred on {@code cy}: vanilla's header font when it fits in
	 * {@code width}, else the medium font on two lines.
	 */
	public static void regionName(SPRITE_RENDERER r, CharSequence name, int x1, int cy, int width) {
		if (UI.FONT().H2.width(name) <= width) {
			UI.FONT().H2.render(r, name, x1, cy - UI.FONT().H2.height() / 2);
			return;
		}
		UI.FONT().M.render(r, name, x1, cy - UI.FONT().M.height(), width, 1.0);
	}
}
