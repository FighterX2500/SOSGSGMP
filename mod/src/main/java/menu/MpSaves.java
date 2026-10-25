package menu;

import java.nio.file.Paths;

import game.GAME;
import init.settings.S;
import snake2d.CORE;
import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.campaign.CampaignStore;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: campaign saves in the running game (spec §11 Saving, SOS-64; docs/plan/campaign-saves.md). The host orders a
 * save from the panel's button or on its autosave interval; every PC, the host too, holds at the order's step, saves
 * its own game and the campaign record there, and plays on once every PC has reported. Once per frame from
 * {@link MpPlay}, between steps.
 */
final class MpSaves {

	/** The save step is this far past the host's step: past every peer, which runs at most 30 steps past the host. */
	private static final long LEAD = 60;

	private final SetupSession setup;
	private final EnginePlay engine;
	private final WorldSetupAdapter game;
	private final MpLog log;
	private final CampaignStore store = store();
	/** This PC's open order: held at its step, saved, then waiting for every PC; null otherwise. */
	private SaveOrder order;
	private boolean savedHere;
	/** The latest save's outcome for the panel; empty before the first. */
	private String status = "";
	private int changes;
	/** After a save that ends the session (SaveOrder.exit): its outcome once done here, empty when every PC saved; null before. */
	private String exited;
	/** Host: real milliseconds played since the last save, as vanilla's autosave counts them. */
	private long autoMillis;
	private long autoLast = -1;

	MpSaves(SetupSession setup, EnginePlay engine, WorldSetupAdapter game, MpLog log) {
		this.setup = setup;
		this.engine = engine;
		this.game = game;
		this.log = log;
	}

	/** This PC's campaign records, in its data folder. */
	static CampaignStore store() {
		return new CampaignStore(Paths.get(System.getenv("APPDATA"), "songsofsyx", "sosgsgmp-campaigns"));
	}

	void frame() {
		if (!setup.playing() || !engine.active())
			return;
		if (setup.closed()) {
			// the host ends the session once a save that ends it is done; a PC that saved but missed the done ends too
			if (order != null && order.exit() && savedHere)
				finish("the session closed before the host confirmed every player's save");
			return;
		}
		SaveOrder o = setup.takeSaveOrder();
		if (o != null)
			begin(o);
		if (order == null) {
			if (setup.isHost())
				autosave();
			return;
		}
		// a battle's load restarts the steps, so a save that a battle overtook is reported at once, not at its step
		if (!savedHere && (engine.step() >= order.step() || engine.battle() != null))
			save();
		if (savedHere) {
			String outcome = setup.takeSaveDone(order.number());
			if (outcome != null)
				finish(outcome);
		}
	}

	/** Host: whether a save can start now: none open, no battle, no desync hold. */
	boolean canSave() {
		return setup.isHost() && setup.playing() && !setup.closed() && engine.active() && order == null && setup.campaign() != null
				&& engine.battle() == null && engine.desyncHold() == null;
	}

	/** Host: orders save of {@code kind} on every PC. Check {@link #canSave()} first. */
	void saveNow(CampaignRecord.Kind kind) {
		order(kind, false);
	}

	/** Host: a desync's Save and exit, after the resync: every PC saves the campaign, then the session ends. Check {@link #canSave()} first. */
	void saveAndExit() {
		order(CampaignRecord.Kind.SAVE, true);
	}

	private void order(CampaignRecord.Kind kind, boolean exit) {
		if (!canSave())
			throw new IllegalStateException("SOSGSGMP: a campaign save can't start now");
		Campaign c = setup.campaign();
		int keep = kind == CampaignRecord.Kind.AUTO ? Math.max(1, S.get().autoSaveFiles.get()) : 0;
		setup.orderSave(new SaveOrder(store.nextNumber(c.id()), engine.step() + LEAD, kind, keep, c, exit));
		autoMillis = 0;
	}

	/** Host: vanilla's autosave interval (Settings, Auto-Saving), counted while the game runs, starts an autosave. */
	private void autosave() {
		long now = CORE.getUpdateInfo().getNowMillis();
		if (S.get().autoSaveInterval.get() == 0 || GAME.SPEED.speed() == 0) {
			autoLast = -1;
			return;
		}
		if (autoLast >= 0)
			autoMillis += now - autoLast;
		autoLast = now;
		long due = (1 + 2L * (S.get().autoSaveInterval.max() - S.get().autoSaveInterval.get())) * 60_000;
		if (autoMillis >= due && canSave())
			saveNow(CampaignRecord.Kind.AUTO);
	}

	private void begin(SaveOrder o) {
		order = o;
		savedHere = false;
		changes++;
		log.line("save " + o.number() + " (" + o.kind() + ") ordered for step " + o.step() + " at step " + engine.step());
		if (engine.step() <= o.step())
			engine.runUntil(o.step());
	}

	/** At the order's step: this PC's game and record, then the report to the host. A PC past the step reports why. */
	private void save() {
		savedHere = true;
		long hash = 0;
		String problem = null;
		if (engine.battle() != null) {
			problem = "a battle started before the save step";
		} else if (engine.step() != order.step()) {
			problem = "this PC was at step " + engine.step() + ", past the save step " + order.step();
		} else {
			try {
				String file = game.saveGame(label(order));
				hash = game.worldHash();
				int slot = setup.factions().slotOf(setup.localPlayerId());
				store.write(new CampaignRecord(order.campaign(), order.number(), order.kind(), System.currentTimeMillis(), slot,
						file, hash));
				if (order.keep() > 0)
					for (CampaignRecord gone : store.rotate(order.campaign().id(), order.keep()))
						game.deleteSave(gone.file());
				log.line(String.format("save %d written at step %d: %s, world hash %016x", order.number(), order.step(), file, hash));
			} catch (RuntimeException e) {
				// the save's own boundary: every PC reports, so the host releases the hold and names this PC's problem
				log.error("save " + order.number() + " failed", e);
				problem = e.toString();
			}
		}
		if (problem != null)
			log.line("save " + order.number() + " not made here: " + problem);
		setup.saved(order.number(), hash, problem);
	}

	private void finish(String outcome) {
		engine.runFreely();
		status = outcome.isEmpty() ? "Campaign saved (save " + order.number() + ")." : "Save " + order.number() + ": " + outcome;
		log.line("save " + order.number() + " done on every PC" + (outcome.isEmpty() ? "" : ": " + outcome));
		if (order.exit())
			exited = outcome;
		order = null;
		savedHere = false;
		changes++;
	}

	/** The save's name in vanilla's list, before its time stamp: letters, digits and spaces of the campaign's name. */
	private static String label(SaveOrder o) {
		String name = o.campaign().name().replaceAll("[^A-Za-z0-9 ]", "");
		return "MP " + name + (o.kind() == CampaignRecord.Kind.AUTO ? " Auto" : "");
	}

	/** Whether a save that ends the session is under way: ordered, not yet done. */
	boolean exiting() {
		return order != null && order.exit();
	}

	/** After a save that ended the session: its outcome, empty when every PC saved the same world; null before. */
	String exited() {
		return exited;
	}

	/** What the panel shows: a save under way, or the latest outcome. */
	String status() {
		return order != null ? "Saving the campaign (save " + order.number() + ")..." : status;
	}

	/** Counts changes to {@link #status()}, so the panel redraws only when it moves. */
	int changes() {
		return changes;
	}
}
