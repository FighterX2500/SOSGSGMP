package menu;

import java.nio.file.Path;
import java.util.List;

import game.save.GameLoader;
import snake2d.CORE_STATE;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;

/**
 * SOSGSGMP: loads the world the host built, on every PC, the host too (setup-factions-step.md §3, step 5). Vanilla's
 * GameLoader runs inside an engine session started as SOSDET's rig starts an own-city run, so every PC loads the same
 * world; the session holds at step 0. Then every PC's human is set (PC number = slot), a joining PC makes its own city
 * at its capital (the save holds the host's), and this PC reports its world hash. The session reaches the loaded game
 * through this object and the panel it shows, never through a static.
 */
public final class MpWorldLoad implements CORE_STATE.Constructor {

	private final SetupSession setup;
	private final byte[] save;
	private final WorldSetupAdapter game = new WorldSetupAdapter();

	public MpWorldLoad(SetupSession setup, byte[] save) {
		this.setup = setup;
		this.save = save.clone();
	}

	@Override
	public CORE_STATE getState() {
		FactionStep f = setup.factions();
		int seed = setup.choices().seed();
		Path file = game.saveFile(save);
		game.beginSession(setup.isHost(), f.slotOf(setup.localPlayerId()), WorldSetupAdapter.startId(save), seed);
		CORE_STATE view;
		try {
			view = new GameLoader(file).getState();
		} finally {
			game.deleteSaveFile(file);
		}
		game.humans(slots(), seed);
		if (!setup.isHost()) {
			FactionPick own = ownPick(f);
			game.ownCity(own.x(), own.y());
		}
		game.openLanding();
		setup.worldLoaded(game.worldHash());
		MpSessionPanel.show(setup, game);
		return view;
	}

	private int[] slots() {
		List<LobbyPlayer> players = setup.players();
		int[] slots = new int[players.size()];
		for (int i = 0; i < slots.length; i++) {
			slots[i] = setup.factions().slotOf(players.get(i).id());
			if (slots[i] < 0)
				throw new IllegalStateException("SOSGSGMP: " + players.get(i).name() + " has no slot in the world");
		}
		return slots;
	}

	/** This PC's picks as the host locked them: its table, sent before the slots. */
	private FactionPick ownPick(FactionStep f) {
		for (FactionStep.PlayerPick p : f.table())
			if (p.playerId() == setup.localPlayerId() && p.pick().hasLocation())
				return p.pick();
		throw new IllegalStateException("SOSGSGMP: the host's table has no location for this PC");
	}
}
