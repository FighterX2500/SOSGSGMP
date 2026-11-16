package menu;

import game.GAME;
import snake2d.CORE;
import snake2d.LOG;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: the window when the session closed under this PC's player in play and no other window says so (spec §11
 * Host disconnect): the host disconnected or closed the game, the host removed or dropped this PC, or a stream broke.
 * It shows the session's reason once, ends the engine and stops the game, as {@link MpGone}'s Return to the last save
 * does, and offers the main menu. Every other end of the session ends the engine with its own window (Return to the
 * last save, other game data, a save that ends the session, Leave and freeze), and every Leave this PC's player chose
 * ends it too: a session closed while the engine still runs is one nobody reported. Once per frame from {@link MpPlay},
 * after {@link MpExit}, so a save that ends the session has its own window in the frame it is done.
 */
final class MpClosed {

	private final SetupSession setup;
	private final EnginePlay engine;
	private final WorldSetupAdapter game;
	/** The window was shown: the session ended here. */
	private boolean ended;

	MpClosed(SetupSession setup, EnginePlay engine, WorldSetupAdapter game) {
		this.setup = setup;
		this.engine = engine;
		this.game = game;
	}

	void frame() {
		if (ended || !setup.closed() || !engine.active())
			return;
		ended = true;
		game.endSession();
		GAME.SPEED.speedSet(0);
		LOG.ln("SOSGSGMP: the session ended under this player: " + setup.closedReason());
		MpPrompt.show(setup.closedReason() + "\nThe session has ended.",
				new GButt.ButtPanel("Main menu") {
					@Override
					protected void clickA() {
						CORE.setCurrentState(Menu::make);
					}
				});
	}

	/** After the window ended the session here. */
	boolean ended() {
		return ended;
	}
}
