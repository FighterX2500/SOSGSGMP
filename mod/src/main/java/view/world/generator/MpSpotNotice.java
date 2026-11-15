package view.world.generator;

import init.constant.C;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.text.Font;
import snake2d.util.sprite.text.Str;
import sosdet.HumanStart;
import sosgsgmp.setup.FactionPick;
import util.colors.GCOLOR;
import util.gui.misc.GText;

/**
 * SOSGSGMP: the notice under the top of the screen while a player places their city, as the speed notice sits in the
 * game (SOS-42), twice its size with a black outline to read on any terrain. Its first line names another player 6 to
 * 15 tiles away, where the connection is guaranteed (spec §5, SOS-120); on a built world a second line says where the
 * city-state's road links go, so a spot on the border of two or three states shows (SOS-213). The spot is the cursor's
 * where a city can go there, else this PC's own.
 */
final class MpSpotNotice extends RENDEROBJ.RenderImp {

	/** What the notice reads: the spot, the other players' spots, and the links line. */
	interface Spots {

		/** Once per frame, before the spot is read. */
		void frame();

		/** The spot to describe, or {@link FactionPick#NONE}. */
		int noticeX();

		int noticeY();

		/** Changes whenever the others' spots or the rule may have: the lines are rebuilt then. */
		int noticeKey();

		int others();

		int otherX(int i);

		int otherY(int i);

		GText otherName(int i);

		/** The links line for a city-state at {@code x, y} into {@code out}; nothing before the world is built. */
		default void links(int x, int y, Str out) {
		}
	}

	private static final int SCALE = 2;
	private static final int OUTLINE = 2;
	private static final int Y = 40;
	private static final int GAP = 4;

	private final Spots spots;
	private final Str route = new Str(96);
	private final Str links = new Str(192);
	private int shownX = FactionPick.NONE;
	private int shownY = FactionPick.NONE;
	private int shownKey = -1;

	MpSpotNotice(Spots spots) {
		super(0, 0);
		this.spots = spots;
	}

	@Override
	public void render(SPRITE_RENDERER r, float ds) {
		spots.frame();
		int x = spots.noticeX();
		int y = spots.noticeY();
		if (x != shownX || y != shownY || spots.noticeKey() != shownKey) {
			shownX = x;
			shownY = y;
			shownKey = spots.noticeKey();
			set(x, y);
		}
		Font font = UI.FONT().S;
		int line = font.height(SCALE) + GAP;
		int cy = Y + font.height(SCALE) / 2;
		if (route.length() > 0) {
			line(r, font, route, cy, GCOLOR.T().WARNING);
			cy += line;
		}
		if (links.length() > 0)
			line(r, font, links, cy, COLOR.WHITE100);
	}

	private static void line(SPRITE_RENDERER r, Font font, CharSequence text, int cy, COLOR color) {
		int cx = C.WIDTH() / 2;
		COLOR.BLACK.bind();
		for (int dy = -OUTLINE; dy <= OUTLINE; dy += OUTLINE)
			for (int dx = -OUTLINE; dx <= OUTLINE; dx += OUTLINE)
				if (dx != 0 || dy != 0)
					font.renderC(r, cx + dx, cy + dy, text, SCALE);
		color.bind();
		font.renderC(r, cx, cy, text, SCALE);
		COLOR.unbind();
	}

	private void set(int x, int y) {
		route.clear();
		links.clear();
		if (x == FactionPick.NONE)
			return;
		int n = 0;
		int first = 0;
		for (int i = 0; i < spots.others(); i++) {
			if (HumanStart.inRouteRange(x, y, spots.otherX(i), spots.otherY(i))) {
				if (n++ == 0)
					first = i;
			}
		}
		if (n == 1)
			route.add("Guaranteed connection to ").add(spots.otherName(first));
		else if (n > 1)
			route.add("Guaranteed connection to ").add(n).add(" players");
		spots.links(x, y, links);
	}
}
