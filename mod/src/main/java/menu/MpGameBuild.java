package menu;

import java.util.function.IntConsumer;

import game.GAME;
import game.GameSpec;
import snake2d.CORE_STATE;
import sosgsgmp.game.WorldGenAdapter;
import sosgsgmp.session.SetupSession;
import view.main.VIEW;
import view.world.generator.MpMapStage;

/**
 * SOSGSGMP: builds the game for the map step's pending build (setup-map-step.md §3, steps 2 and 5): the world size
 * from the host, GAME.create as vanilla's random game screen calls it, then the map stage takes over the world
 * generator. The lobby screen leaves the menu with it; the map stage rebuilds with the same instance on a size change.
 * The session reaches the new game through this object, never through a static.
 */
final class MpGameBuild implements CORE_STATE.Constructor {

	/** No ticked scripts, the same on every PC; the script engine adds every forceInit script itself. */
	private static final String[] SCRIPTS = {};

	private final SetupSession setup;
	private final WorldGenAdapter world;
	private final IntConsumer rememberSize;

	MpGameBuild(SetupSession setup, WorldGenAdapter world, IntConsumer rememberSize) {
		this.setup = setup;
		this.world = world;
		this.rememberSize = rememberSize;
	}

	@Override
	public CORE_STATE getState() {
		int size = setup.step().size();
		GameSpec spec = GameSpec.get(SCRIPTS);
		world.setWorldSize(spec, size);
		VIEW view = GAME.create(spec);
		MpMapStage.takeOver(setup, world.mapTypes(), this);
		setup.built();
		if (setup.isHost())
			rememberSize.accept(size);
		return view;
	}
}
