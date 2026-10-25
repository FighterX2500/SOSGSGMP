package menu;

import java.util.List;

import snake2d.util.misc.ACTION;
import sosgsgmp.game.EnginePlay;
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

	MpPlay(SetupSession setup, EnginePlay engine) {
		this.setup = setup;
		this.engine = engine;
	}

	@Override
	public void exe() {
		if (setup.closed())
			return;
		setup.update();
		if (!setup.playing() && !setup.closed() && setup.factions().started()) {
			engine.start(setup.isHost(), otherPcs());
			setup.play(engine);
		}
		if (setup.playing())
			setup.exchange();
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
