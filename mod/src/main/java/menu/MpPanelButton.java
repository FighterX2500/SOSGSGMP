package menu;

import java.lang.reflect.Field;
import java.util.function.Supplier;

import init.sprite.SPRITES;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.PlayerDeals;
import view.main.VIEW;
import view.ui.top.UIPanelTop;

/**
 * SOSGSGMP: the top bar's button that hides and shows the multiplayer setup panel (SOS-91), directly left of the
 * city/world toggle and the same size. Hook M8 puts it there, in the city's and the world's top bar, in a multiplayer
 * game only. The view's {@code Interrupters.multiplayerPanel}, a field hook M8 adds, holds this load's panel; the panel
 * sets it after every load, so the button keeps no reference to a session past its view.
 */
public final class MpPanelButton {

	/** What the button works: this load's panel. */
	public interface Panel {

		boolean shown();

		/** Hides the panel when it shows, shows it when hidden. */
		void toggle();
	}

	private static final CharSequence HOVER = "Hide or show the multiplayer setup panel.";

	private MpPanelButton() {
	}

	/**
	 * Hook M8: the city/world toggle with the button left of it in a multiplayer game, built as the top bar is (the
	 * session starts before the game loads); the toggle alone in single player.
	 */
	public static RENDEROBJ beside(RENDEROBJ toggle, Supplier<Panel> panel) {
		if (!PlayerDeals.inSession())
			return toggle;
		UIPanelTop.Butt b = new UIPanelTop.Butt(SPRITES.icons().l.flags) {
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
		b.body().setDim(toggle.body().width(), toggle.body().height());
		b.hoverInfoSet(HOVER);
		GuiSection s = new GuiSection();
		s.addRightC(0, b);
		s.addRightC(0, toggle);
		return s;
	}

	/** This view's panel, through hook M8's field: called after every load, as the panel shows. */
	static void install(Panel panel) {
		Field slot;
		try {
			slot = VIEW.inters().getClass().getField("multiplayerPanel");
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException("SOSGSGMP: hook M8 (Interrupters.multiplayerPanel) is missing from the game's V71 "
					+ "view; is the mod's script jar ahead of SongsOfSyx.jar?", e);
		}
		try {
			slot.set(VIEW.inters(), panel);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("SOSGSGMP: hook M8 (Interrupters.multiplayerPanel) isn't public", e);
		}
	}
}
