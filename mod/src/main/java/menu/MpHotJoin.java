package menu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import game.GAME;
import snake2d.CORE;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.FactionPick;

/**
 * SOSGSGMP: hot-join in the game (SOS-190, docs/plan/hot-join-restart.md), once per frame.
 *
 * <p>On the host, a PC that asks to join is answered in a popup; on Accept, a campaign save is made and sent to it as
 * the snapshot it picks its spot on; its pick is checked on the live world; then the founding save is made and every
 * PC restarts from its copy of it with the newcomer in the world. On a joined PC, the host's restart ends this session,
 * and once the new setup has its slot map, this PC loads its copy of the founding save.
 *
 * <p>A frozen player coming back (spec §11 Freezing a player, SOS-195) goes the same way without a snapshot or a pick:
 * on Accept the founding save is made at once, the host writes the rejoin's files at its step, and every PC restarts,
 * the returning one from its own last save; every load then reads the host's world and thaws that player's slot.
 */
final class MpHotJoin {

	private enum Phase {
		IDLE, ASKED, SNAPSHOT, PICKING, FOUNDING
	}

	private final SetupSession setup;
	private final WorldSetupAdapter game;
	private final MpSaves saves;
	private final MpLog log;
	private final MpAutopilot autopilot;
	private Phase phase = Phase.IDLE;
	private MpJoinAsk popup;
	/** The last save done before the one this waits for. */
	private MpSaves.Done before;
	/** The phase's save isn't ordered yet: it waits for a battle, an assault, a desync hold, a view or another save to end. */
	private boolean saveDue;
	/** The newcomer's pick, taken on the live world, for the restart. */
	private FactionPick pick;
	/** A joined PC after the host's restart: the new setup, until its slot map is here. */
	private SetupSession restarted;
	private boolean ended;

	MpHotJoin(SetupSession setup, WorldSetupAdapter game, MpSaves saves, MpLog log, MpAutopilot autopilot) {
		this.setup = setup;
		this.game = game;
		this.saves = saves;
		this.log = log;
		this.autopilot = autopilot;
	}

	/** This session ended for a hot-join restart: the game does nothing more in it. */
	boolean ended() {
		return ended;
	}

	void frame() {
		if (setup.isHost())
			host();
		else
			joined();
	}

	private void host() {
		String asking = setup.joinAsk();
		if (phase == Phase.IDLE && asking != null) {
			if (!setup.joinReturning() && game.joinSlot() < 0) {
				log.line("hot-join: " + asking + " asked to join; the map has no room");
				setup.answerJoin(Refusal.NO_ROOM);
				return;
			}
			phase = Phase.ASKED;
			log.line("hot-join: " + asking + (setup.joinReturning() ? " wants to come back from a freeze" : " asks to join"));
			popup = new MpJoinAsk(this, asking, setup.joinReturning());
			popup.open();
			if (autopilot != null)
				autopilot.event("joinask", "\"name\":\"" + asking + "\"");
		}
		if (phase == Phase.ASKED && asking == null) // the asking PC left before the answer
			reset("the asking PC left");
		if (phase.ordinal() > Phase.ASKED.ordinal() && setup.joining() == null)
			reset("the joining PC left");
		if (phase == Phase.ASKED && autopilot != null && autopilot.acceptJoin(setup::say))
			answer(autopilot.joinAccepted());
		switch (phase) {
		case SNAPSHOT -> snapshot();
		case PICKING -> picking();
		case FOUNDING -> founding();
		default -> {
		}
		}
	}

	/** The host's answer in the popup. */
	void answer(boolean accept) {
		if (phase != Phase.ASKED)
			return;
		closePopup();
		String name = setup.joinAsk();
		setup.answerJoin(accept ? null : Refusal.DECLINED);
		log.line("hot-join: " + name + (accept ? " may join" : " turned away"));
		if (!accept) {
			phase = Phase.IDLE;
			return;
		}
		saveDue = true;
		if (setup.joinReturning()) { // no snapshot or pick: their city stands, frozen (SOS-195)
			setup.say(name + " comes back. Every game saves and reloads for a moment.");
			phase = Phase.FOUNDING;
			return;
		}
		setup.say(name + " joins the game. Once they have picked a spot, every game saves and reloads for a moment.");
		phase = Phase.SNAPSHOT;
	}

	/**
	 * The phase's save, ordered only when it can start at once (spec §11: a join waits for a battle, an assault, a save,
	 * a view or a desync window to end), so the next save done is this one. Whether it is still due.
	 */
	private boolean orderSave() {
		if (!saveDue)
			return false;
		if (!saves.canSave())
			return true;
		before = saves.lastDone();
		if (phase == Phase.FOUNDING && setup.joinReturning())
			saves.rejoinOutAtNextSave();
		saves.saveNow(CampaignRecord.Kind.AUTO);
		saveDue = false;
		return true;
	}

	/** The save the newcomer picks on: the host's file goes to it once every PC has saved. */
	private void snapshot() {
		if (orderSave())
			return;
		MpSaves.Done d = saves.lastDone();
		if (d == before)
			return;
		if (d.file() == null) { // the host's own save failed: another one
			log.line("hot-join: the snapshot save " + d.number() + " wasn't made here (" + d.outcome() + "); saving again");
			saveDue = true;
			return;
		}
		setup.sendSnapshot(read(d.file()));
		log.line("hot-join: snapshot " + d.file() + " sent to " + setup.joining());
		phase = Phase.PICKING;
	}

	/** The newcomer's pick, checked on the live world; a good one orders the founding save. */
	private void picking() {
		FactionPick p = setup.takeJoinPick();
		if (p == null)
			return;
		String problem = game.joinProblem(p.x(), p.y());
		if (problem == null && game.joinSlot() < 0)
			problem = "The map has no room for another city-state any more.";
		setup.answerJoinPick(problem);
		log.line("hot-join: " + setup.joining() + " picked " + p.x() + "," + p.y() + (problem == null ? "" : ": " + problem));
		if (problem != null)
			return;
		pick = p;
		saveDue = true;
		phase = Phase.FOUNDING;
	}

	/** Once every PC has the founding save, every PC restarts from it. */
	private void founding() {
		if (orderSave())
			return;
		MpSaves.Done d = saves.lastDone();
		if (d == before)
			return;
		boolean returning = setup.joinReturning();
		byte[] rejoinFiles = returning ? saves.takeRejoinFiles() : null;
		if (!d.outcome().isEmpty() || d.file() == null || returning && rejoinFiles == null) {
			log.line("hot-join: the founding save " + d.number() + " failed: " + d.outcome());
			if (returning) { // nothing for the returning PC to press: the save is made again
				saveDue = true;
				return;
			}
			setup.answerJoinPick("Not every game could save (" + d.outcome() + "). Press Ready again.");
			phase = Phase.PICKING;
			return;
		}
		CampaignRecord record = MpSaves.store().find(setup.campaign().id(), d.number());
		if (record == null)
			throw new IllegalStateException("SOSGSGMP: the host has no record of its founding save " + d.number());
		if (returning) {
			String name = setup.joining();
			SetupSession next = setup.restartRejoin(d.number(), rejoinFiles);
			log.line("hot-join: " + name + " comes back; every PC restarts from save " + d.number());
			load(next, record);
			return;
		}
		int slot = game.joinSlot();
		double credits = game.joinCredits();
		String name = setup.joining();
		SetupSession next = setup.restart(d.number(), slot, pick, credits, read(d.file()));
		log.line(String.format(java.util.Locale.ROOT, "hot-join: %s joins in slot %d with %.0f credits; every PC restarts from save %d",
				name, slot, credits, d.number()));
		load(next, record);
	}

	private void joined() {
		if (restarted == null) {
			SetupSession next = setup.takeRestart();
			if (next == null)
				return;
			restarted = next;
			ended = true;
			GAME.SPEED.speedSet(0); // the session ends with the load: the game's panels still ask it until then (HJ2)
			log.line("hot-join: the host restarts every PC from save " + next.restartInfo().number() + " for "
					+ (next.restartInfo().rejoins() ? "a frozen player coming back, slot " + next.restartInfo().rejoinSlot()
							: next.restartInfo().pick().name()));
		}
		restarted.update();
		if (restarted.closed()) {
			log.line("hot-join: the restart ended: " + restarted.closedReason());
			throw new IllegalStateException("SOSGSGMP: the connection ended during the restart: " + restarted.closedReason());
		}
		if (restarted.factions() == null || !restarted.factions().initialized())
			return;
		int number = restarted.restartInfo().number();
		CampaignRecord record = MpSaves.store().find(restarted.campaign().id(), number);
		if (record == null)
			throw new IllegalStateException("SOSGSGMP: this PC has no copy of the founding save " + number);
		SetupSession next = restarted;
		restarted = null;
		game.endSession();
		CORE.setCurrentState(new MpWorldLoad(next, record));
	}

	/** The host: this session ends, and the founding save loads with the new setup. */
	private void load(SetupSession next, CampaignRecord record) {
		ended = true;
		phase = Phase.IDLE;
		GAME.SPEED.speedSet(0);
		game.endSession();
		CORE.setCurrentState(new MpWorldLoad(next, record));
	}

	private void reset(String why) {
		log.line("hot-join: " + why);
		closePopup();
		phase = Phase.IDLE;
		pick = null;
		saveDue = false;
	}

	private void closePopup() {
		if (popup != null) {
			popup.close();
			popup = null;
		}
	}

	private byte[] read(String file) {
		try {
			return Files.readAllBytes(game.savePath(file));
		} catch (IOException e) {
			throw new UncheckedIOException("SOSGSGMP: can't read the campaign save " + file + " for a joining player", e);
		}
	}
}
