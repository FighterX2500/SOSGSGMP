package menu;

import game.GAME;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.net.Message;
import sosgsgmp.session.Pauses;
import sosgsgmp.session.PauseSync;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the pause every PC shares (spec §4, SOS-110). Any player's pause or unpause goes through the host and sets
 * every PC's game speed, so any player can unpause, and the chat says who it was. Once per frame from {@link MpPlay},
 * between steps. In a battle every PC runs, the shared pause pauses the battle; a battle against NPCs keeps its own
 * pause, and while one runs a pause of the world waits for its end; after a battle or a load, every PC comes back to
 * the shared pause ({@link PauseSync}, SOS-229). A joining player's press of another speed changes nothing but asks the
 * host in the chat (playtest 2026-11-14).
 */
final class MpPause {

	private final SetupSession setup;
	private final EnginePlay engine;
	private long settledAt = System.nanoTime();
	private final PauseSync sync = new PauseSync(System.nanoTime());

	MpPause(SetupSession setup, EnginePlay engine) {
		this.setup = setup;
		this.engine = engine;
	}

	void frame() {
		boolean heard = false;
		Message.Pause p;
		while ((p = setup.pauses().take()) != null) {
			heard(p);
			heard = true;
		}
		long now = System.nanoTime();
		if (engine.anyBattle())
			settledAt = now + PauseSync.SETTLE_NANOS; // a joining player's speed press after a battle is no request yet
		double asked = engine.takeSpeedAsked();
		if (asked >= 1 && now - settledAt >= 0 && !engine.spectating())
			setup.say(speedRequest(asked));
		int act = sync.frame(mode(), engine.loads(), engine.localBattle(), engine.spectating(), paused(),
				setup.pauses().pausedBy() != Pauses.NOBODY, heard, now);
		if ((act & PauseSync.SEND_PAUSE) != 0)
			setup.pause(true);
		if ((act & PauseSync.SEND_UNPAUSE) != 0)
			setup.pause(false);
		if ((act & (PauseSync.PAUSE_HERE | PauseSync.UNPAUSE_HERE)) != 0)
			GAME.SPEED.togglePause();
	}

	/** What this PC's game pause is: the world's, a battle's every PC runs, or a battle's against NPCs here. */
	private PauseSync.Mode mode() {
		if (engine.battle() != null)
			return engine.battle().awaiting() ? PauseSync.Mode.BATTLE_LOADING : PauseSync.Mode.BATTLE;
		return engine.localBattleHere(setup.factions().slotOf(setup.localPlayerId())) ? PauseSync.Mode.LOCAL_BATTLE
				: PauseSync.Mode.WORLD;
	}

	/** The chat line of a joining player's press of another speed: only the host sets it (spec §4). */
	static String speedRequest(double speed) {
		return "requests " + Math.round(speed) + "x speed";
	}

	private void heard(Message.Pause p) {
		setup.chat().notice(name(setup, p.playerId()) + (!p.paused() ? " unpaused the game."
				: engine.localBattle() ? " paused the game. The game pauses once the battles against NPCs end." : " paused the game."));
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
