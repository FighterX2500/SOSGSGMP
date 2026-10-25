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
	private final MpLog log;
	/** Campaign saves: the host's orders, and every PC's save at their step (SOS-64). */
	private final MpSaves saves;

	MpPlay(SetupSession setup, EnginePlay engine, WorldSetupAdapter game, MpLog log, MpSaves saves) {
		this.setup = setup;
		this.engine = engine;
		this.desync = new MpDesync(setup, engine, game, saves);
		this.log = log;
		this.saves = saves;
	}

	@Override
	public void exe() {
		try {
			frame();
		} catch (RuntimeException | Error e) {
			// the mod's per-frame boundary: the playtest log keeps what broke, and the game reports it as before
			log.error("the game over the network failed at step " + (engine.active() ? engine.step() : -1), e);
			throw e;
		}
		log.frame(desync);
	}

	private void frame() {
		if (!setup.closed()) {
			setup.update();
			if (!setup.playing() && !setup.closed() && setup.factions().started()) {
				engine.start(setup.isHost(), otherPcs(setup));
				setup.play(engine);
			}
			if (setup.playing()) {
				setup.exchange();
				engine.battleFrame();
			}
		}
		if (setup.playing()) {
			desync.frame();
			saves.frame();
		}
		if (autopilot != null)
			autopilot.game(setup, engine, desync, saves);
	}

	/** The other players' PC numbers: their slots. */
	/** Every other player's PC: their slots (the host's is any slot when a campaign resumes). */
	static int[] otherPcs(SetupSession setup) {
		List<LobbyPlayer> players = setup.players();
		int[] pcs = new int[players.size() - 1];
		int n = 0;
		for (LobbyPlayer p : players)
			if (p.id() != setup.localPlayerId())
				pcs[n++] = setup.factions().slotOf(p.id());
		return pcs;
	}
}
