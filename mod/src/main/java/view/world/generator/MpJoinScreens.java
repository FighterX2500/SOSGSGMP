package view.world.generator;

import menu.MpSetupLeave;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.LISTE;
import sosgsgmp.game.MinimapAdapter;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.panel.GPanel;
import view.interrupter.Interrupter;
import view.tool.PLACABLE;
import view.tool.ToolConfig;

/**
 * SOSGSGMP hot-join (SOS-198): the newcomer picks on the host's snapshot through the setup's own screens, on vanilla's
 * world generator view (no game bar, no city to enter, no fog): the race screen, Customize Faction, then the location
 * screen with the placer on the map and the side panel on the left, as {@link MpLocationStage} shows it. The pick's
 * session stays with the caller ({@code menu.MpJoinSettle}), behind {@link Pick}. In the generator's package because
 * reset(), dummy, tools, minimap and window are package-private (docs/hooks.md, note next to M1).
 */
public final class MpJoinScreens implements MpFactionScreens {

	private static final CharSequence PLACE_TITLE = "Place your city-state";

	/** What the hot-join's pick gives its screens. */
	public interface Pick {

		/** Once per frame, from the side panel's render: the session's pump. */
		void frame();

		/** The picks can't change: sent to the host, taken, restarting, or the session closed. */
		boolean locked();

		/** The players' rows and the status line, built afresh for each screen's side. */
		GuiSection info();

		/** Ready, built afresh for the location screen. */
		GButt.ButtPanel ready();

		/** Leave, built afresh for each screen's side. */
		GButt.ButtPanel leave();

		/** The city-state's placer, the same one on every visit of the location screen. */
		PLACABLE placer();
	}

	private final WorldViewGenerator g;
	private final Pick pick;
	private boolean onLocation;

	private MpJoinScreens(WorldViewGenerator g, Pick pick) {
		this.g = g;
		this.pick = pick;
	}

	/**
	 * Shows the generator view on the loaded snapshot, centred on {@code cx, cy}, with the race screen first, as the
	 * setup does. The running game's view stays behind it, never shown.
	 */
	public static MpJoinScreens open(Pick pick, int cx, int cy) {
		WorldViewGenerator g = new WorldViewGenerator();
		g.activate();
		new MinimapAdapter().zoomButtonsOnly(g.minimap); // vanilla's race, titles and terrain buttons belong to its own stages
		g.window.centererTile.set(cx, cy);
		new NoSave().on(g);
		MpJoinScreens s = new MpJoinScreens(g, pick);
		MpFactionStage.race(s);
		return s;
	}

	/** The location screen: the placer on the map, the side panel on the left, the minimap. */
	public void location() {
		g.reset();
		g.minimap.show();
		onLocation = true;
		GuiSection side = side(new GButt.ButtPanel("back") {
			@Override
			protected void clickA() {
				MpFactionStage.customize(MpJoinScreens.this);
			}
		}, null);
		GPanel panel = new GPanel(side.body().width(), side.body().height());
		panel.setTitle(PLACE_TITLE);
		panel.inner().set(side);
		side.add(panel);
		side.moveLastToBack();
		side.body().moveX1(16).moveY1(64);
		g.tools.place(pick.placer(), new ToolConfig() {
			@Override
			public boolean back() {
				return false;
			}

			@Override
			public void addUI(LISTE<RENDEROBJ> uis) {
				uis.add(side);
			}
		});
	}

	@Override
	public GuiSection side(GButt.ButtPanel back, GButt.ButtPanel next) {
		GuiSection s = new GuiSection() {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				pick.frame();
				super.render(r, ds);
			}
		};
		s.add(pick.info());
		GuiSection buttons = new GuiSection();
		if (back != null)
			buttons.addRightC(0, back);
		if (next != null)
			buttons.addRightC(16, next);
		GuiSection row = new GuiSection();
		row.add(locking(buttons));
		if (onLocation)
			row.addRightC(16, pick.ready());
		s.addDown(12, row);
		MpSetupLeave.addBottomRight(s, row, pick.leave());
		return s;
	}

	@Override
	public GuiSection locking(GuiSection content) {
		return MpFactions.locking(content, pick::locked);
	}

	@Override
	public void showCentred(GuiSection content, CharSequence title) {
		g.reset();
		onLocation = false;
		g.dummy.add(content, title);
	}

	@Override
	public GButt.ButtPanel afterCustomize() {
		return new GButt.ButtPanel("Pick Location") {
			@Override
			protected void clickA() {
				location();
			}
		};
	}

	/**
	 * Keeps the snapshot from being saved (a quicksave, or Save in the escape menu): it is the host's game. Pinned, so
	 * the generator's screen changes, which clear its manager, keep it; it has no body and takes no input.
	 */
	private static final class NoSave extends Interrupter {

		NoSave() {
			super(true, true, false);
		}

		void on(WorldViewGenerator g) {
			show(g.uiManager);
		}

		@Override
		public boolean canSave() {
			return false;
		}

		@Override
		protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
			return false;
		}

		@Override
		protected void mouseClick(MButt button) {
		}

		@Override
		protected void hoverTimer(GBox text) {
		}

		@Override
		protected boolean render(Renderer r, float ds) {
			return true;
		}

		@Override
		protected boolean update(float ds) {
			return true;
		}
	}
}
