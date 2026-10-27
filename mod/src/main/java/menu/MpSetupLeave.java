package menu;

import snake2d.CORE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.misc.ACTION;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * SOSGSGMP: the setup's Leave button, the same on every setup screen and on the in-game setup panel. Every PC can leave: the host ends the setup for
 * everyone, a joining PC drops out of it. It closes the session and returns to the main menu the way vanilla's "quit
 * to menu" does (view/menu/IMenu.exit2Menu); {@code leaving} runs first, so the screen stops pumping the session.
 */
public final class MpSetupLeave {

	private static final int GAP = 16;

	private MpSetupLeave() {
	}

	public static GButt.ButtPanel button(SetupSession setup, Runnable leaving) {
		return new GButt.ButtPanel("leave") {
			@Override
			protected void clickA() {
				if (setup.closed()) {
					toMainMenu(leaving);
					return;
				}
				VIEW.inters().yesNo.activate(setup.isHost() ? "Leave and end the setup for every player?"
						: "Leave the setup? The others go on without you.", () -> {
							setup.leave();
							toMainMenu(leaving);
						}, ACTION.NOP, true);
			}
		};
	}

	/**
	 * Adds {@code leave} to the bottom-right corner of {@code s}: on the row of {@code buttons}, the last row added to
	 * {@code s}, when it fits there, else on a row of its own below it.
	 */
	public static void addBottomRight(GuiSection s, RENDEROBJ buttons, GButt.ButtPanel leave) {
		int x2 = s.body().x2();
		if (buttons.body().x2() + GAP + leave.body().width() <= x2)
			leave.body().moveCY(buttons.body().cY());
		else
			leave.body().moveY1(s.body().y2() + GAP);
		leave.body().moveX2(x2);
		s.add(leave);
	}

	private static void toMainMenu(Runnable leaving) {
		leaving.run();
		CORE.setCurrentState(Menu::make);
	}
}
