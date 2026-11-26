package view.world.generator;

import game.faction.FBanner;
import init.constant.C;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.sprite.SPRITE;
import snake2d.util.sprite.text.Font;
import snake2d.util.sprite.text.Str;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.setup.FactionPick;
import view.subview.GameWindow;
import world.map.regions.centre.WCentre;

/**
 * SOSGSGMP: every player's spot on the setup's map (owner, 2026-11-26): a border six screen pixels wide in the banner's
 * foreground colour, the banner inside it, and the faction's name over it in the small font at three times its size,
 * whatever the zoom. One drawing for the classic setup's Place Capital, a living world's settling and hot-join. It is
 * drawn from the side panel's render, once per frame, so it stays while the mouse is over the panel or the minimap:
 * the placer's own placeholder is drawn only while the map is hovered (vanilla's ToolManager).
 */
public final class MpSpotMarks {

	private static final int HALF = WCentre.TILE_DIM / 2;
	private static final int BORDER = 6;
	private static final int NAME_SCALE = 3;
	private static final int SHADOW = 2;
	private static final int GAP = 4;

	private final WorldSetupAdapter game = new WorldSetupAdapter();
	/** Banners loaded from players' picks, one per spot. */
	private final FBanner[] loaded = new FBanner[LobbySettings.MAX_PLAYERS];
	/** Each spot's banner: its loaded one, or a faction's own. */
	private final FBanner[] banners = new FBanner[LobbySettings.MAX_PLAYERS];
	private final Str[] names = new Str[LobbySettings.MAX_PLAYERS];
	private final int[] xs = new int[LobbySettings.MAX_PLAYERS];
	private final int[] ys = new int[LobbySettings.MAX_PLAYERS];
	private int count;
	/** This PC's own banner and name, its faction's. */
	private FBanner ownBanner;
	private CharSequence ownName;

	public MpSpotMarks() {
		for (int i = 0; i < loaded.length; i++) {
			loaded[i] = new FBanner(null);
			names[i] = new Str(FactionPick.MAX_NAME);
		}
	}

	/** Forgets the other players' spots; the caller adds them again when the picks change. */
	public void clear() {
		count = 0;
	}

	/** This PC's own banner and name, a faction's: drawn as they are, so a change shows at once. */
	public void own(CharSequence name, FBanner banner) {
		ownBanner = banner;
		ownName = name;
	}

	/** Another player's spot from their picks, its banner as they sent it. */
	public void add(FactionPick pick) {
		if (count == banners.length || !pick.hasLocation())
			return;
		game.loadBanner(pick.banner(), loaded[count]);
		set(pick.x(), pick.y(), pick.name(), loaded[count]);
	}

	/**
	 * Another player's spot with a copy of a faction's banner as it is now (a capital of a running game): on a joining PC
	 * Customize Faction later changes the snapshot's player faction, the host's.
	 */
	public void add(int cx, int cy, CharSequence name, FBanner banner) {
		if (count == banners.length)
			return;
		FBanner copy = loaded[count];
		copy.sprite.paint(banner.sprite);
		copy.bannerTypeSet(banner.bannerType());
		copy.colorBG().set(banner.colorBG());
		copy.colorFG().set(banner.colorFG());
		copy.colorBorder().set(banner.colorBorder());
		copy.colorPole().set(banner.colorPole());
		set(cx, cy, name, copy);
	}

	private void set(int cx, int cy, CharSequence name, FBanner banner) {
		banners[count] = banner;
		xs[count] = cx;
		ys[count] = cy;
		names[count].clear().add(name);
		count++;
	}

	/** Every other player's spot, and this PC's at {@code ownX, ownY} unless NONE, inside the world view of {@code window}. */
	public void render(SPRITE_RENDERER r, GameWindow window, int ownX, int ownY) {
		for (int i = 0; i < count; i++)
			mark(r, window, xs[i], ys[i], banners[i], names[i]);
		if (ownX != FactionPick.NONE && ownBanner != null)
			mark(r, window, ownX, ownY, ownBanner, ownName);
	}

	private static void mark(SPRITE_RENDERER r, GameWindow window, int cx, int cy, FBanner banner, CharSequence name) {
		int zoom = window.zoomout();
		RECTANGLE view = window.view();
		int size = (WCentre.TILE_DIM * C.TILE_SIZE) >> zoom;
		int x1 = view.x1() + (((cx - HALF) * C.TILE_SIZE - window.pixels().x1()) >> zoom);
		int y1 = view.y1() + (((cy - HALF) * C.TILE_SIZE - window.pixels().y1()) >> zoom);
		if (x1 < view.x1() || y1 < view.y1() || x1 + size > view.x2() || y1 + size > view.y2())
			return;
		border(r, banner.colorFG(), x1, y1, size);
		banner(r, banner, x1, y1, size);
		name(r, name, x1, y1, size);
	}

	private static void border(SPRITE_RENDERER r, COLOR color, int x1, int y1, int size) {
		color.render(r, x1, x1 + size, y1, y1 + BORDER);
		color.render(r, x1, x1 + size, y1 + size - BORDER, y1 + size);
		color.render(r, x1, x1 + BORDER, y1, y1 + size);
		color.render(r, x1 + size - BORDER, x1 + size, y1, y1 + size);
		COLOR.unbind();
	}

	/** The largest of the banner's sprites that fits inside the border; none when the zoom makes the square too small. */
	private static void banner(SPRITE_RENDERER r, FBanner b, int x1, int y1, int size) {
		int inner = size - 2 * BORDER;
		SPRITE s = b.HUGE.width() <= inner ? b.HUGE : b.BIG.width() <= inner ? b.BIG : b.MEDIUM.width() <= inner ? b.MEDIUM : null;
		if (s == null)
			return;
		int x = x1 + (size - s.width()) / 2;
		int y = y1 + (size - s.height()) / 2;
		s.render(r, x, x + s.width(), y, y + s.height());
		COLOR.unbind();
	}

	private static void name(SPRITE_RENDERER r, CharSequence name, int x1, int y1, int size) {
		Font font = UI.FONT().S;
		int x = x1 + (size - font.width(name, 0, name.length(), NAME_SCALE)) / 2;
		int y = y1 - font.height(NAME_SCALE) - GAP;
		COLOR.BLACK.bind();
		font.render(r, name, x + SHADOW, y + SHADOW, NAME_SCALE);
		COLOR.WHITE100.bind();
		font.render(r, name, x, y, NAME_SCALE);
		COLOR.unbind();
	}
}
