package menu;

import java.util.List;

import snake2d.util.misc.ACTION;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the game over the network, once per frame after the game's update, outside the step
 * (game-over-network.md §3): what arrived goes to the engine, and once every PC placed its landing party
 * ({@code Go}) this PC's engine starts and its streams go out every frame. The setup panel shows the state; this does
 * the work, so it runs whatever view or window is open.
 */
final class MpPlay implements ACTION {

	private final SetupSession setup;
	private final EnginePlay engine;
	/** The desync window: it runs the host's choice on every PC. */
	private final MpDesync desync;
	/** Test tooling: the autopilot, or null in play. */
	private final MpAutopilot autopilot = MpAutopilot.fromEnv();

	MpPlay(SetupSession setup, EnginePlay engine, WorldSetupAdapter game) {
		this.setup = setup;
		this.engine = engine;
		this.desync = new MpDesync(setup, engine, game);
	}

	@Override
	public void exe() {
		if (!setup.closed()) {
			setup.update();
			if (!setup.playing() && !setup.closed() && setup.factions().started()) {
				engine.start(setup.isHost(), otherPcs());
				setup.play(engine);
			}
			if (setup.playing()) {
				setup.exchange();
				engine.battleFrame();
			}
		}
		if (setup.playing())
			desync.frame();
		if (autopilot != null)
			autopilot.game(setup, engine, desync);
	}

	/** The other players' PC numbers: their slots. */
	private int[] otherPcs() {
		List<LobbyPlayer> players = setup.players();
		int[] pcs = new int[players.size() - 1];
		int n = 0;
		for (LobbyPlayer p : players)
			if (p.id() != setup.localPlayerId())
				pcs[n++] = setup.factions().slotOf(p.id());
		return pcs;
	}
}
