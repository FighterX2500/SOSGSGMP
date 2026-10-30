package menu;

import java.lang.reflect.Field;
import java.util.function.Supplier;

import init.sprite.SPRITES;
import init.sprite.UI.Icon;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.PlayerDeals;
import view.main.VIEW;
import view.ui.top.UIPanelTop;

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

	/** This view's panel, through hook M8's field: called after every load, as the panel shows. */
	static void install(Panel panel) {
		install("multiplayerPanel", panel);
	}

	/** This view's chat box, the same way. */
	static void installChat(Panel chat) {
		install("multiplayerChat", chat);
	}

	private static void install(String field, Panel panel) {
		Field slot;
		try {
			slot = VIEW.inters().getClass().getField(field);
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException("SOSGSGMP: hook M8 (Interrupters." + field + ") is missing from the game's V71 "
					+ "view; is the mod's script jar ahead of SongsOfSyx.jar?", e);
		}
		try {
			slot.set(VIEW.inters(), panel);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("SOSGSGMP: hook M8 (Interrupters." + field + ") isn't public", e);
		}
	}
}
