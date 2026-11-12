package menu;

import java.lang.reflect.Field;
import java.util.function.Supplier;

import init.constant.C;
import init.sprite.SPRITES;
import init.sprite.UI.Icon;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.PlayerDeals;
import util.gui.misc.GBox;
import view.interrupter.Interrupter;
import view.main.VIEW;
import view.sett.SettViewStart;
import view.ui.top.UIPanelTop;
import view.world.generator.WorldViewGenerator;

/**
 * SOSGSGMP: the top bar's buttons that hide and show the multiplayer setup panel (SOS-91) and the chat (SOS-110),
 * directly left of the city/world toggle and the same size. Hook M8 puts them there, in the city's and the world's top
 * bar, in a multiplayer game only. The view's {@code Interrupters.multiplayerPanel} and {@code multiplayerChat}, fields
 * hook M8 adds, hold this load's windows; they set them after every load, so the buttons keep no reference to a session
 * past their view.
 */
public final class MpPanelButton {

	/** What a button works: this load's window. */
	public interface Panel {

		boolean shown();

		/** Hides the panel when it shows, shows it when hidden. */
		void toggle();
	}

	private static final CharSequence HOVER = "Hide or show the multiplayer setup panel.";
	private static final CharSequence CHAT_HOVER = "Hide or show the chat.";

	private MpPanelButton() {
	}

	/**
	 * Hook M8: the city/world toggle with the buttons left of it in a multiplayer game, built as the top bar is (the
	 * session starts before the game loads); the toggle alone in single player.
	 */
	public static RENDEROBJ beside(RENDEROBJ toggle, Supplier<Panel> panel, Supplier<Panel> chat) {
		if (!PlayerDeals.inSession())
			return toggle;
		GuiSection s = new GuiSection();
		s.addRightC(0, button(SPRITES.icons().l.flags, HOVER, toggle, panel));
		s.addRightC(0, button(SPRITES.icons().l.event, CHAT_HOVER, toggle, chat));
		s.addRightC(0, toggle);
		return s;
	}

	private static RENDEROBJ button(Icon icon, CharSequence hover, RENDEROBJ size, Supplier<Panel> panel) {
		UIPanelTop.Butt b = new UIPanelTop.Butt(icon) {
			@Override
			protected void clickA() {
				MpPanelButton.Panel p = panel.get();
				if (p != null)
					p.toggle();
			}

			@Override
			protected void renAction() {
				MpPanelButton.Panel p = panel.get();
				activeSet(p != null);
				selectedSet(p != null && p.shown());
			}
		};
		b.body().setDim(size.body().width(), size.body().height());
		b.hoverInfoSet(hover);
		return b;
	}

	/**
	 * The same two buttons, top centre, on the views that have no top bar (SOS-202): the setup's screens on the world
	 * generator and vanilla's landing. Made with the chat after every load, as the view is new.
	 */
	static void showWhereNoTopBar() {
		new NoTopBar().open();
	}

	/** This view's panel, through hook M8's field: called after every load, as the panel shows. */
	static void install(Panel panel) {
		install("multiplayerPanel", panel);
	}

	/** This view's chat box, the same way. */
	static void installChat(Panel chat) {
		install("multiplayerChat", chat);
	}

	private static void install(String field, Panel panel) {
		Field slot = field(field);
		try {
			slot.set(VIEW.inters(), panel);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("SOSGSGMP: hook M8 (Interrupters." + field + ") isn't public", e);
		}
	}

	private static Field field(String field) {
		try {
			return VIEW.inters().getClass().getField(field);
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException("SOSGSGMP: hook M8 (Interrupters." + field + ") is missing from the game's V71 "
					+ "view; is the mod's script jar ahead of SongsOfSyx.jar?", e);
		}
	}

	/** This view's window in hook M8's field, or null while it has none. */
	private static Panel installed(Field slot) {
		try {
			return (Panel) slot.get(VIEW.inters());
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("SOSGSGMP: hook M8 (Interrupters." + slot.getName() + ") isn't public", e);
		}
	}

	/** The buttons on a view with no top bar: pinned, so a view's screen changes keep them; they take no other input. */
	private static final class NoTopBar extends Interrupter {

		private static final int MARGIN = 8;

		private final GuiSection section = new GuiSection();

		NoTopBar() {
			super(true, true, false);
			Field panel = field("multiplayerPanel");
			Field chat = field("multiplayerChat");
			RENDEROBJ size = new RENDEROBJ.RenderDummy(36, UIPanelTop.HEIGHT);
			section.addRightC(0, button(SPRITES.icons().l.flags, HOVER, size, () -> installed(panel)));
			section.addRightC(0, button(SPRITES.icons().l.event, CHAT_HOVER, size, () -> installed(chat)));
			section.body().moveCX(C.WIDTH() / 2).moveY1(MARGIN); // the top right is the minimap's, with its zoom buttons
		}

		void open() {
			show(VIEW.inters().manager);
		}

		/** The generator's screens and the landing have no top bar; every other view has M8's buttons in it. */
		private static boolean noTopBar() {
			return VIEW.current() instanceof WorldViewGenerator || VIEW.current() instanceof SettViewStart;
		}

		@Override
		protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
			return noTopBar() && section.hover(mCoo);
		}

		@Override
		protected void mouseClick(MButt button) {
			if (button == MButt.LEFT)
				section.click();
		}

		@Override
		protected void hoverTimer(GBox text) {
			section.hoverInfoGet(text);
		}

		@Override
		protected boolean render(Renderer r, float ds) {
			if (noTopBar())
				section.render(r, ds);
			return true;
		}

		@Override
		protected boolean update(float ds) {
			return true;
		}
	}
}
