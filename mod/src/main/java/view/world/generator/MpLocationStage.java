package view.world.generator;

import game.faction.FACTIONS;
import init.constant.C;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.LIST;
import snake2d.util.sets.LISTE;
import snake2d.util.sprite.text.Font;
import snake2d.util.sprite.text.Str;
import sosdet.HumanStart;
import sosgsgmp.setup.FactionPick;
import util.colors.GCOLOR;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.panel.GPanel;
import view.tool.PlacableFixedImp;
import view.tool.ToolConfig;
import view.world.generator.tools.UIWorldToolCapitolPlaceInfo;
import world.map.regions.centre.WCentre;

/**
 * SOSGSGMP: the factions step's location screen (setup-factions-step.md §2): vanilla's capital placer on the world map,
 * as StageCapitol makes it, but placing only sends this PC's pick; the world is built later, once, by the host. A spot
 * the engine's rule refuses (the terrain, or 6 tiles from another player's location as this PC knows them) can't be
 * placed, and the tooltip says why. The other players' capitals are drawn while the placer is on the map.
 */
final class MpLocationStage {

	private static final CharSequence TITLE = "Place Capital";
	private static final int HALF = WCentre.TILE_DIM / 2;
	private static final CharSequence LOCKED = "The world is being initialized: locations can't change.";
	private static final CharSequence READY_LOCKED = "You are ready: press Ready again to move your city.";

	private MpLocationStage() {
	}

	static void place(MpFactions f) {
		WorldViewGenerator g = f.g;
		g.reset();
		g.minimap.show();
		f.onLocation = true;

		GuiSection side = f.side(new GButt.ButtPanel("back") {
			@Override
			protected void clickA() {
				MpFactionStage.customize(f);
			}
		}, null);
		GPanel panel = new GPanel(side.body().width(), side.body().height());
		panel.setTitle(TITLE);
		panel.inner().set(side);
		side.add(panel);
		side.moveLastToBack();
		side.body().moveX1(16).moveY1(64);

		Placer placer = new Placer(f);
		RouteNotice notice = new RouteNotice(f, placer);
		ToolConfig config = new ToolConfig() {
			@Override
			public boolean back() {
				return false;
			}

			@Override
			public void addUI(LISTE<RENDEROBJ> uis) {
				uis.add(notice);
				uis.add(side);
			}
		};
		g.tools.place(placer, config);
	}

	/**
	 * Under the top of the screen, as the speed notice sits in the game (SOS-42), twice its size with a black outline to
	 * read on any terrain: the spot in range of another player's city, 6 to 15 tiles, where the connection is guaranteed
	 * (spec §5, SOS-120). The spot is the cursor's where a city can go there, else this PC's location.
	 */
	private static final class RouteNotice extends RENDEROBJ.RenderImp {

		private static final int SCALE = 2;
		private static final int OUTLINE = 2;
		private static final int Y = 40;

		private final MpFactions f;
		private final Placer placer;
		private final Str text = new Str(96);
		private int shownX = FactionPick.NONE;
		private int shownY = FactionPick.NONE;
		private int shownChanges = -1;

		RouteNotice(MpFactions f, Placer placer) {
			super(0, 0);
			this.f = f;
			this.placer = placer;
		}

		@Override
		public void render(SPRITE_RENDERER r, float ds) {
			placer.frames++;
			boolean hover = placer.onSpot();
			int x = hover ? placer.checkedX : f.ownX();
			int y = hover ? placer.checkedY : f.ownY();
			if (x != shownX || y != shownY || f.setup.changes() != shownChanges) {
				shownX = x;
				shownY = y;
				shownChanges = f.setup.changes();
				set(x, y);
			}
			if (text.length() == 0)
				return;
			Font font = UI.FONT().S;
			int cx = C.WIDTH() / 2;
			int cy = Y + font.height(SCALE) / 2;
			COLOR.BLACK.bind();
			for (int dy = -OUTLINE; dy <= OUTLINE; dy += OUTLINE)
				for (int dx = -OUTLINE; dx <= OUTLINE; dx += OUTLINE)
					if (dx != 0 || dy != 0)
						font.renderC(r, cx + dx, cy + dy, text, SCALE);
			GCOLOR.T().WARNING.bind();
			font.renderC(r, cx, cy, text, SCALE);
			COLOR.unbind();
		}

		private void set(int x, int y) {
			text.clear();
			if (x == FactionPick.NONE)
				return;
			int n = 0;
			int first = 0;
			for (int i = 0; i < f.others; i++) {
				if (HumanStart.inRouteRange(x, y, f.otherX[i], f.otherY[i])) {
					if (n++ == 0)
						first = i;
				}
			}
			if (n == 1)
				text.add("Guaranteed connection to ").add(f.otherNames[first]);
			else if (n > 1)
				text.add("Guaranteed connection to ").add(n).add(" players");
		}
	}

	/** The capital placer: a 3x3 square whose centre is the location, as vanilla's (StageCapitol.afterPlaced). */
	private static final class Placer extends PlacableFixedImp {

		private final MpFactions f;
		private final UIWorldToolCapitolPlaceInfo info = new UIWorldToolCapitolPlaceInfo();
		// The rule's answer for the last centre asked, until the centre or the session changes.
		private int checkedX = -1;
		private int checkedY = -1;
		private int checkedChanges = -1;
		private CharSequence problem;
		// The notice's frames, and the last one with the cursor over a spot where a city can go: the notice follows it.
		private int frames;
		private int spotAt = -2;

		Placer(MpFactions f) {
			super(TITLE, 1, 1);
			this.f = f;
		}

		@Override
		public int width() {
			return WCentre.TILE_DIM;
		}

		@Override
		public int height() {
			return WCentre.TILE_DIM;
		}

		@Override
		public void place(int tx, int ty, int rx, int ry) {
			// Nothing on the map: the pick goes to the host in afterPlaced.
		}

		@Override
		public void afterPlaced(int tx1, int ty1) {
			f.placeAt(tx1 + HALF, ty1 + HALF);
		}

		@Override
		public CharSequence placableWhole(int tx1, int ty1) {
			if (f.locked())
				return f.initialized() ? LOCKED : READY_LOCKED;
			int cx = tx1 + HALF;
			int cy = ty1 + HALF;
			if (cx != checkedX || cy != checkedY || f.setup.changes() != checkedChanges) {
				checkedX = cx;
				checkedY = cy;
				checkedChanges = f.setup.changes();
				problem = f.setup.locationProblem(cx, cy);
			}
			if (problem == null)
				spotAt = frames; // asked every frame the cursor is on the map (PlacableFixedTool.render)
			return problem;
		}

		/** The cursor is over a spot a city can go, this frame or the last: {@link #checkedX}, {@link #checkedY}. */
		boolean onSpot() {
			return frames - spotAt <= 1;
		}

		@Override
		public CharSequence placable(int tx, int ty, int rx, int ry) {
			return null;
		}

		@Override
		public LIST<CLICKABLE> getAdditionalButt() {
			return null;
		}

		@Override
		public void placeInfo(GBox b, int x1, int y1) {
			info.placeInfo(b, x1, y1, FACTIONS.player().race());
		}

		/**
		 * Vanilla's placeholder, and once per frame (at its first tile) every placed capital: {@code x, y} is tile
		 * {@code tx, ty} on screen, so any tile is at the same offset in tiles (PlacableFixedTool.render).
		 */
		@Override
		public void renderPlaceHolder(SPRITE_RENDERER r, int mask, int x, int y, int tx, int ty, int rx, int ry, boolean isPlacable, boolean areaIsPlacable) {
			super.renderPlaceHolder(r, mask, x, y, tx, ty, rx, ry, isPlacable, areaIsPlacable);
			if (rx != 0 || ry != 0)
				return;
			for (int i = 0; i < f.others; i++) {
				int sx = square(x, tx, f.otherX[i]);
				int sy = square(y, ty, f.otherY[i]);
				mark(r, GCOLOR.MAP().BAD, sx, sy);
				f.otherNames[i].render(r, sx, sy - f.otherNames[i].height());
			}
			if (f.ownX() != FactionPick.NONE)
				mark(r, GCOLOR.MAP().OK, square(x, tx, f.ownX()), square(y, ty, f.ownY()));
		}

		/** The screen position of the capital square around centre {@code c}, from tile {@code t} at {@code at}. */
		private static int square(int at, int t, int c) {
			return at + (c - HALF - t) * C.TILE_SIZE;
		}

		private static void mark(SPRITE_RENDERER r, COLOR color, int x1, int y1) {
			int size = WCentre.TILE_DIM * C.TILE_SIZE;
			color.render(r, x1, x1 + size, y1, y1 + size);
			COLOR.unbind();
		}
	}
}
