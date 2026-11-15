package view.world.generator;

import game.faction.FACTIONS;
import game.faction.Faction;
import init.constant.C;
import init.race.Race;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.sets.LIST;
import snake2d.util.sprite.text.Font;
import snake2d.util.sprite.text.Str;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.setup.FactionPick;
import util.colors.GCOLOR;
import util.gui.misc.GBox;
import util.gui.misc.GText;
import view.subview.GameWindow;
import view.tool.PlacableFixedImp;
import view.world.generator.tools.UIWorldToolCapitolPlaceInfo;
import world.WORLD;
import world.map.regions.Region;
import world.map.regions.centre.WCentre;

/**
 * SOSGSGMP: the city-state placer on a built world (a living world's settling, SOS-187; hot-join, SOS-190): a 3x3 square
 * whose centre is the spot, checked by the caller's rule. Its hover is vanilla's Place Capital window (climate, resources,
 * moisture, fresh water, forest, open land) under the land's region and holder and the regions the city-state gets a
 * road link to (SOS-213). Once per frame it draws every other player's spot, named, and this PC's own, each as one
 * square as thick as the placer's edges (SOS-212). It feeds {@link MpSpotNotice}.
 */
public abstract class MpSpotPlacer extends PlacableFixedImp implements MpSpotNotice.Spots {

	private static final int HALF = WCentre.TILE_DIM / 2;
	/** The placer's edges are two of the game's sprite pixels thick (SOS-212). */
	private static final int EDGE = 2 * C.SCALE;
	/** The holders a border line names before "and n more". */
	private static final int NAMED = 3;

	private final UIWorldToolCapitolPlaceInfo info = new UIWorldToolCapitolPlaceInfo();
	private final WorldSetupAdapter game = new WorldSetupAdapter();
	/** A living world's settling: slot 0's capital is the host's stand-in, which becomes a neutral state (SOS-214). */
	private final boolean standIn;
	// The rule's answer for the last centre asked, until the centre or the key changes.
	private int checkedX = -1;
	private int checkedY = -1;
	private int checkedKey = -1;
	private CharSequence problem;
	// The notice's frames, and the last one with the cursor over a spot where a city can go: the notice follows it.
	private int frames;
	private int spotAt = -2;
	// The links of the last centre asked.
	private int linksX = -1;
	private int linksY = -1;
	private int[] links = new int[0];
	/** The view's window, for the zoom the names are drawn at; null before the screen shows. */
	private GameWindow window;

	protected MpSpotPlacer(CharSequence title, boolean standIn) {
		super(title, 1, 1);
		this.standIn = standIn;
	}

	/** The screen's window: names over the squares keep their size at any zoom. */
	void window(GameWindow window) {
		this.window = window;
	}

	/** Why the spot can't change now, or null. */
	protected abstract CharSequence locked();

	/** Why a city-state can't go with its centre at {@code cx, cy}, or null; asked again when {@link #noticeKey()} changes. */
	protected abstract CharSequence problem(int cx, int cy);

	/** The player picked the spot whose centre is {@code cx, cy}. */
	protected abstract void picked(int cx, int cy);

	/** The race whose climate the hover rates. */
	protected abstract Race race();

	/** This PC's picked spot, or {@link FactionPick#NONE}. */
	protected abstract int ownX();

	protected abstract int ownY();

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
		// Nothing on the map: the spot goes to the caller in afterPlaced.
	}

	@Override
	public void afterPlaced(int tx1, int ty1) {
		picked(tx1 + HALF, ty1 + HALF);
	}

	@Override
	public CharSequence placableWhole(int tx1, int ty1) {
		CharSequence l = locked();
		if (l != null)
			return l;
		int cx = tx1 + HALF;
		int cy = ty1 + HALF;
		if (cx != checkedX || cy != checkedY || noticeKey() != checkedKey) {
			checkedX = cx;
			checkedY = cy;
			checkedKey = noticeKey();
			problem = problem(cx, cy);
		}
		if (problem == null)
			spotAt = frames; // asked every frame the cursor is on the map (PlacableFixedTool.render)
		return problem;
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
		land(b, x1 + HALF, y1 + HALF);
		info.placeInfo(b, x1, y1, race());
	}

	/** The land under the centre and who holds it, and the road links a city-state there gets. */
	private void land(GBox b, int cx, int cy) {
		Region here = WORLD.REGIONS().map.get(cx, cy);
		if (here == null)
			return;
		b.textLL("Land");
		b.tab(6);
		GText t = b.text();
		t.add(here.info.name()).add(" (").add(holder(here)).add(')');
		b.add(t);
		b.NL();
		int[] l = linksAt(cx, cy);
		for (int i = 0; i < l.length; i++) {
			if (i == 0)
				b.textLL("Road links");
			b.tab(6);
			Region reg = WORLD.REGIONS().getByIndex(l[i]);
			GText r = b.text();
			r.add(reg.info.name()).add(" (").add(holder(reg)).add(')');
			b.add(r);
			b.NL();
		}
		b.sep();
	}

	/**
	 * Vanilla's placeholder, and once per frame (at its first tile) every other player's spot and this PC's: {@code x, y}
	 * is tile {@code tx, ty} on screen, so any tile is at the same offset in tiles (PlacableFixedTool.render).
	 */
	@Override
	public void renderPlaceHolder(SPRITE_RENDERER r, int mask, int x, int y, int tx, int ty, int rx, int ry, boolean isPlacable, boolean areaIsPlacable) {
		super.renderPlaceHolder(r, mask, x, y, tx, ty, rx, ry, isPlacable, areaIsPlacable);
		if (rx != 0 || ry != 0)
			return;
		for (int i = 0; i < others(); i++) {
			int sx = square(x, tx, otherX(i));
			int sy = square(y, ty, otherY(i));
			frame(r, GCOLOR.MAP().BAD, sx, sy);
			name(r, otherName(i), sx, sy);
		}
		if (ownX() != FactionPick.NONE)
			frame(r, GCOLOR.MAP().OK, square(x, tx, ownX()), square(y, ty, ownY()));
	}

	/**
	 * A player's name over their square, at the screen size of the game's own labels whatever the zoom: the placeholder
	 * draws in the world's pixels, which the zoom shrinks.
	 */
	private void name(SPRITE_RENDERER r, CharSequence name, int x1, int y1) {
		int scale = window == null ? 1 : 1 << window.zoomout();
		Font font = UI.FONT().S;
		int y = y1 - font.height() * scale - EDGE;
		COLOR.BLACK.bind();
		font.render(r, name, x1 + scale, y + scale, scale);
		COLOR.WHITE100.bind();
		font.render(r, name, x1, y, scale);
		COLOR.unbind();
	}

	private static int square(int at, int t, int c) {
		return at + (c - HALF - t) * C.TILE_SIZE;
	}

	/** One square, as thick as the placer's edges, not its nine cells (SOS-212): the land under the spot shows. */
	private static void frame(SPRITE_RENDERER r, COLOR color, int x1, int y1) {
		int size = WCentre.TILE_DIM * C.TILE_SIZE;
		color.render(r, x1, x1 + size, y1, y1 + EDGE);
		color.render(r, x1, x1 + size, y1 + size - EDGE, y1 + size);
		color.render(r, x1, x1 + EDGE, y1, y1 + size);
		color.render(r, x1 + size - EDGE, x1 + size, y1, y1 + size);
		COLOR.unbind();
	}

	// ------------------------------------------------------------ the notice (MpSpotNotice.Spots)

	@Override
	public void frame() {
		frames++;
	}

	/** The cursor is over a spot a city can go, this frame or the last: the notice describes it, else this PC's own. */
	private boolean onSpot() {
		return frames - spotAt <= 1;
	}

	@Override
	public int noticeX() {
		return onSpot() ? checkedX : ownX();
	}

	@Override
	public int noticeY() {
		return onSpot() ? checkedY : ownY();
	}

	/**
	 * Where the links go: one region by name and holder; several held by one state, how many; else the border of how many
	 * states, naming the first three. Unowned land counts as one, "free land".
	 */
	@Override
	public void links(int x, int y, Str out) {
		int[] l = linksAt(x, y);
		if (l.length == 0)
			return;
		if (l.length == 1) {
			Region reg = WORLD.REGIONS().getByIndex(l[0]);
			out.add("Road link to ").add(reg.info.name()).add(" (").add(holder(reg)).add(')');
			return;
		}
		int[] keys = new int[l.length];
		int states = 0;
		for (int i = 0; i < l.length; i++) {
			int k = holderKey(WORLD.REGIONS().getByIndex(l[i]));
			boolean seen = false;
			for (int j = 0; j < states; j++)
				seen |= keys[j] == k;
			if (!seen)
				keys[states++] = k;
		}
		if (states == 1) {
			out.add("Road links to ").add(l.length).add(" regions of ").add(holder(WORLD.REGIONS().getByIndex(l[0])));
			return;
		}
		out.add("On the border of ").add(states).add(" states: ");
		int named = 0;
		for (int j = 0; j < states && named < NAMED; j++) {
			for (int i = 0; i < l.length; i++) {
				Region reg = WORLD.REGIONS().getByIndex(l[i]);
				if (holderKey(reg) == keys[j]) {
					out.add(named++ == 0 ? "" : ", ").add(holder(reg));
					break;
				}
			}
		}
		if (states > NAMED)
			out.add(" and ").add(states - NAMED).add(" more");
	}

	private int[] linksAt(int x, int y) {
		if (x != linksX || y != linksY) {
			linksX = x;
			linksY = y;
			links = game.carveLinks(x, y);
		}
		return links;
	}

	/** One key per holder: the stand-in, nobody, or a faction's index. */
	private int holderKey(Region reg) {
		if (isStandIn(reg))
			return -2;
		Faction f = reg.faction();
		return f == null ? -1 : f.index();
	}

	private CharSequence holder(Region reg) {
		if (isStandIn(reg))
			return "the host's stand-in, neutral once the world starts";
		Faction f = reg.faction();
		return f == null ? "free land" : f.name;
	}

	private boolean isStandIn(Region reg) {
		return standIn && reg != null && reg == FACTIONS.getByIndex(0).capitolRegion();
	}
}
