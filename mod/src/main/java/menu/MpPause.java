package menu;

import game.GAME;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.net.Message;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the pause every PC shares (spec §4, SOS-110). Any player's pause or unpause goes through the host and sets
 * every PC's game speed, so any player can unpause, and the chat says who it was. Once per frame from {@link MpPlay},
 * between steps. Battles keep their own pause: this never touches one, and the watch waits for the loads around them to
 * settle.
 */
final class MpPause {

	/** After a battle, the game speed settles for this long before a change of it counts as a player's. */
	private static final long SETTLE_NANOS = 2_000_000_000L;

	private final SetupSession setup;
	private final EnginePlay engine;
	/** This PC's pause as last seen: a change this class didn't make is the player's. */
	private boolean seen;
	private long settledAt = System.nanoTime();

	MpPause(SetupSession setup, EnginePlay engine) {
		this.setup = setup;
		this.engine = engine;
	}

	void frame() {
		Message.Pause p;
		while ((p = setup.pauses().take()) != null)
			heard(p);
		long now = System.nanoTime();
		if (engine.anyBattle())
			settledAt = now + SETTLE_NANOS;
		boolean paused = paused();
		if (now - settledAt >= 0 && paused != seen)
			setup.pause(paused);
		seen = paused;
	}

	private void heard(Message.Pause p) {
		setup.chat().notice(name(setup, p.playerId()) + (p.paused() ? " paused the game." : " unpaused the game."));
		if (!engine.anyBattle() && paused() != p.paused())
			GAME.SPEED.togglePause();
		seen = paused();
	}

	private static boolean paused() {
		return GAME.SPEED.speed() == 0;
	}

	/** The name of the player {@code id}. */
	static String name(SetupSession setup, int id) {
		for (LobbyPlayer p : setup.players())
			if (p.id() == id)
				return p.name();
		return "A player who left";
	}
}
