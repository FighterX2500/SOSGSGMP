package view.world.generator;

import menu.MpCursors;
import menu.MpSetupLeave;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.LISTE;
import sosgsgmp.game.MinimapAdapter;
import sosgsgmp.session.Cursors;
import sosgsgmp.setup.TitlesMode;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.panel.GPanel;
import view.interrupter.Interrupter;
import view.tool.ToolConfig;

/**
 * SOSGSGMP: picking a city-state's spot on a built world, on vanilla's world generator view (no game bar, no city to
 * enter, no fog), with the side panel on the left, as {@link MpLocationStage} shows it. Hot-join (SOS-198): the race
 * screen, Customize Faction, then the location screen, on the host's snapshot. A living world's settling (SOS-213):
 * the location screen alone, on every PC, the host's too; race and faction were picked before the world was built.
 * The location screen has the placer's hover window and the notice under the top of the screen ({@link MpSpotPlacer},
 * {@link MpSpotNotice}). The pick's session stays with the caller, behind {@link Pick}. In the generator's package
 * because reset(), dummy, tools, minimap and window are package-private (docs/hooks.md, note next to M1).
 */
public final class MpJoinScreens implements MpFactionScreens {

	private static final CharSequence PLACE_TITLE = "Place your city-state";

	/** What the pick gives its screens. */
	public interface Pick {

		/** Once per frame, from the side panel's render: the session's pump. */
		void frame();

		/** The picks can't change: sent to the host, taken, restarting, or the session closed. */
		boolean locked();

		/** The players' rows and the status line, built afresh for each screen's side. */
		GuiSection info();

		/** Ready, built afresh for the location screen. */
		GButt.ButtPanel ready();

		/** The host's Initialize World, built afresh for the location screen; null for none. */
		default GButt.ButtPanel start() {
			return null;
		}

		/** Leave, built afresh for each screen's side. */
		GButt.ButtPanel leave();

		/** The city-state's placer, the same one on every visit of the location screen. */
		MpSpotPlacer placer();

		/** Every player's spot on the map. */
		MpSpotMarks marks();

		/** The host's Regenerate, a vote for a new world (spec §5), built afresh for the location screen; null for none. */
		default GButt.ButtPanel regenerate() {
			return null;
		}

		/** The players' cursors on the map and the minimap, sent and drawn; null for none. */
		default MpCursors cursors() {
			return null;
		}

		/** The titles setting when this PC's player picks titles (spec §5 Titles); null when they pick none. */
		TitlesMode titles();
	}

	private final WorldViewGenerator g;
	private final Pick pick;
	/** The location screen's Back goes to Customize Faction. */
	private final boolean back;
	private final MpSpotNotice notice;
	private boolean onLocation;

	private MpJoinScreens(WorldViewGenerator g, Pick pick, boolean back) {
		this.g = g;
		this.pick = pick;
		this.back = back;
		this.notice = new MpSpotNotice(pick.placer());
	}

	/**
	 * Hot-join: shows the generator view on the loaded snapshot, centred on {@code cx, cy}, with the race screen first, as
	 * the setup does. The running game's view stays behind it, never shown.
	 */
	public static MpJoinScreens open(Pick pick, int cx, int cy) {
		MpJoinScreens s = new MpJoinScreens(view(pick, cx, cy), pick, true);
		MpFactionStage.race(s);
		return s;
	}

	/**
	 * A living world's settling: the generator view on the loaded world, centred on {@code cx, cy}, at the location. Back
	 * goes to Customize Faction and the race screen, as before the world was built (owner, 2026-11-26).
	 */
	public static MpJoinScreens settle(Pick pick, int cx, int cy) {
		MpJoinScreens s = new MpJoinScreens(view(pick, cx, cy), pick, true);
		s.location();
		return s;
	}

	private static WorldViewGenerator view(Pick pick, int cx, int cy) {
		WorldViewGenerator g = new WorldViewGenerator();
		g.activate();
		MinimapAdapter minimap = new MinimapAdapter();
		minimap.zoomButtonsOnly(g.minimap); // vanilla's race, titles and terrain buttons belong to its own stages
		g.window.centererTile.set(cx, cy);
		new NoSave().on(g);
		MpCursors cursors = pick.cursors();
		if (cursors != null)
			MpMinimapCursors.show(cursors, minimap.setupMap(g.minimap), g::isActive);
		return g;
	}

	/** The Customize Faction screen, as from the race screen's Next. */
	public void customize() {
		MpFactionStage.customize(this);
	}

	/** The location screen: the placer on the map, the side panel on the left, the notice, the minimap. */
	public void location() {
		g.reset();
		g.minimap.show();
		onLocation = true;
		GuiSection side = side(!back ? null : new GButt.ButtPanel("back") {
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
				uis.add(notice);
				uis.add(side);
			}
		});
	}

	@Override
	public GuiSection side(GButt.ButtPanel back, GButt.ButtPanel next) {
		MpCursors cursors = pick.cursors();
		GuiSection s = new GuiSection() {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				pick.frame();
				if (onLocation) {
					MpRegionLines.render(r, g.window);
					pick.marks().render(r, g.window, pick.placer().ownX(), pick.placer().ownY());
				}
				super.render(r, ds);
				if (cursors != null) {
					cursors.send(g.window, onLocation ? Cursors.WORLD : Cursors.OFF);
					if (onLocation)
						cursors.render(r, g.window, Cursors.WORLD);
				}
			}
		};
		s.add(pick.info());
		GButt.ButtPanel regenerate = onLocation ? pick.regenerate() : null;
		if (regenerate != null)
			s.addDown(12, regenerate);
		GuiSection buttons = new GuiSection();
		if (back != null)
			buttons.addRightC(0, back);
		if (next != null)
			buttons.addRightC(16, next);
		GuiSection row = new GuiSection();
		row.add(locking(buttons));
		if (onLocation) {
			row.addRightC(16, pick.ready());
			GButt.ButtPanel start = pick.start();
			if (start != null)
				row.addRightC(16, start);
		}
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
	public TitlesMode titles() {
		return pick.titles();
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
	 * Keeps the loaded world from being saved (a quicksave, or Save in the escape menu): it is the host's game, or a world
	 * to pick on. Pinned, so the generator's screen changes, which clear its manager, keep it; it has no body and takes no
	 * input.
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
