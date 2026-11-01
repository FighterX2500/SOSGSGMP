package menu;

import java.nio.file.Paths;

import game.GAME;
import init.settings.S;
import snake2d.CORE;
import sosdet.View;
import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.campaign.CampaignStore;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.SetupSession;
import sosgsgmp.session.ViewRequests;

/**
 * SOSGSGMP: campaign saves in the running game (spec §11 Saving, SOS-64; docs/plan/campaign-saves.md). The host orders a
 * save from the panel's button or on its autosave interval; every PC, the host too, holds at the order's step, saves
 * its own game and the campaign record there, and plays on once every PC has reported. A PC viewing a city has its own
 * city unloaded and can't save, so a save waits until nobody views one; only the save before a battle can't, since the
 * world holds there and a view needs steps to end. Once per frame from {@link MpPlay}, between steps.
 */
final class MpSaves {

	private final SetupSession setup;
	private final EnginePlay engine;
	private final WorldSetupAdapter game;
	private final MpLog log;
	private final CampaignStore store = store();
	/** This PC's open order: held at its step, saved, then waiting for every PC; null otherwise. */
	private SaveOrder order;
	private boolean savedHere;
	/** Host: the save that waits for the city views to end; null otherwise. */
	private CampaignRecord.Kind waiting;
	private boolean loggedWaiting;
	/** Host: a save that ends the session has the views in its way closed; each view is asked once. */
	private final View[] closing = new View[LobbySettings.MAX_PLAYERS];
	private boolean closingViews;
	/** The latest save's outcome for the panel; empty before the first. */
	private String status = "";
	private int changes;
	/** After a save that ends the session (SaveOrder.exit): its outcome once done here, empty when every PC saved; null before. */
	private String exited;
	/** After a save that ended the session: the player who quit with it, or SaveOrder.NO_QUITTER (SOS-79). */
	private int exitedQuitter = SaveOrder.NO_QUITTER;
	/** The Engage step of the last battle this PC saved before, held there (SOS-67); -1 before the first. */
	private long savedBeforeBattle = -1;
	/** The step of this PC's last save. */
	private long savedStep = -1;
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
			// the host ends the session once a save that ends it is done, so its done and its close can come in one
			// update: the done is still taken (SOS-79); a PC that saved but missed the done ends too
			if (order != null && order.exit() && savedHere) {
				String outcome = setup.takeSaveDone(order.number());
				finish(outcome != null ? outcome : "the session closed before the host confirmed every player's save");
			}
			return;
		}
		SaveOrder o = setup.takeSaveOrder();
		if (o != null)
			begin(o);
		ViewRequests views = setup.views();
		if (views != null)
			views.holdBegins(waiting != null || closingViews || order != null);
		// every PC holds before a battle's Engage until it has saved the campaign there (spec §11, SOS-67)
		long ahead = engine.battleAhead();
		if (order == null) {
			// a joining PC can save at the hold's step before it hears of the battle: that save is the one
			if (ahead >= 0 && (savedBeforeBattle == ahead || savedStep == ahead - 1)) {
				log.line("the battle at step " + ahead + " begins");
				engine.battleGo();
			} else if (ahead >= 0 && saveRefusal() == null) {
				log.line("a battle at step " + ahead + ": the campaign is saved before it");
				order(CampaignRecord.Kind.AUTO, false, engine.step());
			} else if (ahead < 0 && setup.isHost()) {
				startWaiting();
				autosave();
			}
			return;
		}
		// a battle's load restarts the steps, so a save that a battle overtook is reported at once, not at its step;
		// held before a battle, every PC at the same step, an open save is taken there, and counts as the battle's
		if (!savedHere && (engine.step() >= order.step() || engine.battle() != null || ahead >= 0))
			save();
		if (savedHere) {
			String outcome = setup.takeSaveDone(order.number());
			if (outcome != null)
				finish(outcome);
		}
	}

	/** Host: whether a save can start now: none open, no battle, no desync hold, nobody viewing a city. */
	boolean canSave() {
		return saveRefusal() == null && !engine.viewOpen();
	}

	/** Host: as {@link #canSave()} for a save that ends the session: it doesn't wait for a viewer, it closes the views in its way. */
	boolean canSaveToExit() {
		if (saveRefusal() != null)
			return false;
		if (!engine.viewOpen())
			return true;
		closingViews = true;
		ViewRequests views = setup.views();
		for (int slot = 0; slot < closing.length; slot++) {
			View v = engine.view(slot);
			if (EnginePlay.viewLive(v) && v != closing[slot] && v.thaw() < 0) {
				closing[slot] = v;
				log.line("a save that ends the session closes the view of slot " + slot);
				views.end(slot);
			}
		}
		return false;
	}

	/** Why this PC can't start a save now, for the escape menu (SOS-79); null when it can: the host, playing, nothing in the way. */
	String saveRefusal() {
		if (!setup.isHost())
			return "Only the host can save the campaign.";
		if (setup.closed())
			return "The session has ended.";
		if (!setup.playing() || !engine.active() || setup.campaign() == null)
			return "The game hasn't started yet.";
		if (order != null)
			return "A campaign save is under way.";
		if (engine.anyBattle())
			return "No saves during a battle.";
		if (engine.assault() != null)
			return "No saves during an assault.";
		if (engine.assaultTaken() != null)
			return "No saves while an attacker chooses what to take.";
		if (engine.desyncHold() != null)
			return "No saves while the worlds differ.";
		return null;
	}

	/** Host: orders save of {@code kind} on every PC, once nobody views a city. Check {@link #saveRefusal()} first. */
	void saveNow(CampaignRecord.Kind kind) {
		if (waiting != kind && waiting != CampaignRecord.Kind.SAVE) { // a player's save outranks an autosave
			waiting = kind;
			changes++;
		}
		startWaiting();
		if (waiting != null && !loggedWaiting) {
			loggedWaiting = true;
			log.line("a campaign save (" + waiting + ") waits for the city views to end");
		}
	}

	/** Host: the waiting save starts once nothing stands in its way. */
	private void startWaiting() {
		if (waiting == null || !canSave())
			return;
		CampaignRecord.Kind kind = waiting;
		waiting = null;
		loggedWaiting = false;
		order(kind, false, engine.stepPastPeers());
	}

	/** Host: a desync's Save and exit, after the resync: every PC saves the campaign, then the session ends. Check {@link #canSaveToExit()} first. */
	void saveAndExit() {
		saveAndExit(SaveOrder.NO_QUITTER);
	}

	/** Host: as {@link #saveAndExit()}, for player {@code quitter}'s Quit to menu or Exit (SOS-79). Check {@link #canSaveToExit()} first. */
	void saveAndExit(int quitter) {
		waiting = null;
		closingViews = false;
		order(CampaignRecord.Kind.SAVE, true, quitter, engine.stepPastPeers());
	}

	private void order(CampaignRecord.Kind kind, boolean exit, long step) {
		order(kind, exit, SaveOrder.NO_QUITTER, step);
	}

	private void order(CampaignRecord.Kind kind, boolean exit, int quitter, long step) {
		if (saveRefusal() != null)
			throw new IllegalStateException("SOSGSGMP: a campaign save can't start now");
		Campaign c = setup.campaign();
		int keep = kind == CampaignRecord.Kind.AUTO ? Math.max(1, S.get().autoSaveFiles.get()) : 0;
		setup.orderSave(new SaveOrder(store.nextNumber(c.id()), step, kind, keep, c, exit, quitter));
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
		if (autoMillis >= due && saveRefusal() == null)
			saveNow(CampaignRecord.Kind.AUTO);
	}

	private void begin(SaveOrder o) {
		order = o;
		savedHere = false;
		changes++;
		log.line("save " + o.number() + " (" + o.kind() + ") ordered for step " + o.step() + " at step " + engine.step());
		if (engine.step() <= o.step())
			engine.hold(EnginePlay.Hold.SAVE, o.step());
	}

	/** At the order's step: this PC's game and record, then the report to the host. A PC past the step reports why. */
	private void save() {
		savedHere = true;
		savedStep = engine.step();
		long hash = 0;
		String problem = null;
		if (engine.anyBattle()) {
			problem = "a battle started before the save step";
		} else if (engine.step() != order.step() && engine.battleAhead() < 0) {
			problem = "this PC was at step " + engine.step() + ", past the save step " + order.step();
		} else if (engine.viewingHere()) {
			// its own city isn't loaded: it runs the viewed one (spec §10, SOS-82)
			problem = "this PC was viewing another player's city";
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
				log.line(String.format("save %d written at step %d: %s, world hash %016x", order.number(), engine.step(), file, hash));
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
		engine.release(EnginePlay.Hold.SAVE);
		if (engine.battleAhead() >= 0)
			savedBeforeBattle = engine.battleAhead(); // a failed save too: the battle still begins, its outcome shown
		status = outcome.isEmpty() ? "Campaign saved (save " + order.number() + ")." : "Save " + order.number() + ": " + outcome;
		log.line("save " + order.number() + " done on every PC" + (outcome.isEmpty() ? "" : ": " + outcome));
		if (order.exit()) {
			exited = outcome;
			exitedQuitter = order.quitter();
		}
		order = null;
		savedHere = false;
		changes++;
	}

	/** The save's name in vanilla's list, before its time stamp: letters, digits and spaces of the campaign's name. */
	private static String label(SaveOrder o) {
		String name = o.campaign().name().replaceAll("[^A-Za-z0-9 ]", "");
		return "MP " + name + (o.kind() == CampaignRecord.Kind.AUTO ? " Auto" : "");
	}

	/** Whether a save is under way: ordered, not yet done. */
	boolean saving() {
		return order != null;
	}

	/** Whether a save that ends the session is under way: ordered, not yet done. */
	boolean exiting() {
		return order != null && order.exit();
	}

	/** After a save that ended the session: its outcome, empty when every PC saved the same world; null before. */
	String exited() {
		return exited;
	}

	/** After a save that ended the session: the player who quit with it, or SaveOrder.NO_QUITTER for a desync's Save and exit. */
	int exitedQuitter() {
		return exitedQuitter;
	}

	/** What the panel shows: a save under way, or the latest outcome. */
	String status() {
		if (order != null)
			return "Saving the campaign (save " + order.number() + ")...";
		return waiting != null ? "The campaign saves once no player is viewing a city." : status;
	}

	/** Counts changes to {@link #status()}, so the panel redraws only when it moves. */
	int changes() {
		return changes;
	}
}
