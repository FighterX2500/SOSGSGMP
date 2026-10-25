package view.world.generator;

import menu.Menu;
import snake2d.CORE;
import snake2d.util.misc.ACTION;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * SOSGSGMP: the setup's Leave button, the same on every setup screen. Every PC can leave: the host ends the setup for
 * everyone, a joining PC drops out of it. It closes the session and returns to the main menu the way vanilla's "quit
 * to menu" does (view/menu/IMenu.exit2Menu); {@code leaving} runs first, so the screen stops pumping the session.
 */
final class MpSetupLeave {

	private MpSetupLeave() {
	}

	static GButt.ButtPanel button(SetupSession setup, Runnable leaving) {
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

	private static void toMainMenu(Runnable leaving) {
		leaving.run();
		CORE.setCurrentState(Menu::make);
	}
}
