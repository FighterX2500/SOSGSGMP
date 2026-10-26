package menu;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import game.GAME;
import game.save.GameLoader;
import snake2d.CORE_STATE;
import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.game.EnginePlay;
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
 * through this object, the panel it shows and the per-frame {@link MpPlay}, never through a static.
 */
public final class MpWorldLoad implements CORE_STATE.Constructor {

	private final SetupSession setup;
	/** A new game's world, from the host; null when a campaign resumes. */
	private final byte[] save;
	/** The campaign save this PC resumes from (SOS-64); null for a new game. */
	private final CampaignRecord resumed;
	private final WorldSetupAdapter game = new WorldSetupAdapter();

	public MpWorldLoad(SetupSession setup, byte[] save) {
		this.setup = setup;
		this.save = save.clone();
		this.resumed = null;
	}

	/** A resumed campaign: this PC loads its own save of {@code record}, its city in it; the host compares the worlds. */
	MpWorldLoad(SetupSession setup, CampaignRecord record) {
		this.setup = setup;
		this.save = null;
		this.resumed = record;
	}

	@Override
	public CORE_STATE getState() {
		EnginePlay engine = new EnginePlay();
		MpLog log = MpLog.open(setup, engine);
		try {
			return load(engine, log);
		} catch (RuntimeException | Error e) {
			// the world load's boundary: the playtest log keeps what broke, and the game reports it as before
			log.error("loading the host's world failed", e);
			throw e;
		}
	}

	private CORE_STATE load(EnginePlay engine, MpLog log) {
		long t = System.nanoTime();
		MpAutopilot autopilot = MpAutopilot.fromEnv();
		if (autopilot != null)
			autopilot.loading();
		FactionStep f = setup.factions();
		int seed = resumed == null ? setup.choices().seed() : resumed.campaign().seed();
		// every PC names the same start: the host's world, or the campaign's save
		long startId = resumed == null ? WorldSetupAdapter.startId(save) : resumed.campaign().id() ^ resumed.number();
		Path file = resumed == null ? game.saveFile(save) : game.savePath(resumed.file());
		game.beginSession(setup.isHost(), f.slotOf(setup.localPlayerId()), startId, seed);
		CORE_STATE view;
		try {
			view = new GameLoader(file).getState();
		} finally {
			if (resumed == null)
				game.deleteSaveFile(file);
		}
		game.humans(slots(), seed);
		if (resumed != null)
			for (int slot : Campaign.missingSlots(resumed.campaign(), slotList())) {
				game.removeAtStart(slot); // a player missing at a resume (spec §11 Resuming)
				log.line("the missing player in slot " + slot + " is removed at the start");
			}
		if (resumed == null && !setup.isHost()) {
			FactionPick own = ownPick(f);
			game.ownCity(own.x(), own.y());
		}
		if (resumed == null)
			game.openLanding();
		log.line("own city: " + game.activeEntries() + " entry points active"); // none: no roads (SOS-75)
		setup.worldLoaded(game.worldHash());
		log.line(String.format(Locale.ROOT, "world loaded in %.1f s%s: map seed %d, this PC's slot %d, world hash %016x",
				(System.nanoTime() - t) / 1e9, resumed == null ? "" : " from campaign save " + resumed.number() + " (" + resumed.file() + ")",
				seed, f.slotOf(setup.localPlayerId()), game.worldHash()));
		if (autopilot != null) {
			autopilot.loaded(game.worldHash());
			autopilot.roads(game.activeEntries());
		}
		MpSaves saves = new MpSaves(setup, engine, game, log);
		MpPlay play = new MpPlay(setup, engine, game, log, saves);
		GAME.addAfterUpdate(play);
		MpSessionPanel.show(setup, game, engine, saves);
		engine.afterEachLoad(() -> { // a battle's loads build a new game and view (SOSDET plan 0006)
			GAME.addAfterUpdate(play);
			MpSessionPanel.show(setup, game, engine, saves);
			log.line("whole game loaded at step " + engine.step() + "; the mod is back in it");
		});
		return view;
	}

	private List<Integer> slotList() {
		List<Integer> out = new ArrayList<>();
		for (int slot : slots())
			out.add(slot);
		return out;
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
