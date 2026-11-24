package menu;

import java.util.ArrayList;
import java.util.List;

import game.faction.FACTIONS;
import sosdet.Applied;
import sosdet.ArmyOrder;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.log.Recorder;
import sosgsgmp.log.SameLine;

/**
 * SOSGSGMP: the engine's applied ring and army decisions ring (SOSDET 0022, E1 and E2; SOS-249), read between steps a
 * few times a second (reading allocates; the rings hold seconds). Every entry goes into the recorder at its own step;
 * the rare kinds are lines of the playtest log every PC writes alike: a world, military or diplomacy command (ids {@value #WORLD_COMMANDS} to {@value #LAST_WORLD_COMMAND})
 * and an army's creation, siege or disband. A command's line leaves out whether it was skipped: that is not the same
 * on every PC (a city's commands, a stationed player's orders, SOS-234); the recorder keeps it.
 */
final class MpEngineRings {

	private static final int WORLD_COMMANDS = 7000, LAST_WORLD_COMMAND = 8199;

	private final EnginePlay engine;
	private final Recorder recorder;
	private final List<Applied> applied = new ArrayList<>();
	private final List<ArmyOrder> orders = new ArrayList<>();
	/** The last entry of each ring read: from the engine session's start, so every PC writes every line. */
	private long appliedSeq, orderSeq;

	/** {@code recorder}: null when there is none. */
	MpEngineRings(EnginePlay engine, Recorder recorder) {
		this.engine = engine;
		this.recorder = recorder;
	}

	/** A few times a second, between steps, once the engine runs. */
	void look(MpLog out) {
		if (engine.lastApplied() < appliedSeq || engine.lastArmyOrder() < orderSeq) {
			appliedSeq = 0; // an engine session begun again: its rings count from the start
			orderSeq = 0;
		}
		if (engine.lastApplied() != appliedSeq)
			applied(out);
		if (engine.lastArmyOrder() != orderSeq)
			orders(out);
	}

	private void applied(MpLog out) {
		applied.clear();
		long lost = engine.applied(appliedSeq, applied);
		if (lost > 0)
			out.line("engine rings: " + lost + " applied AI log entries were overwritten before this PC read them");
		for (Applied a : applied) {
			appliedSeq = a.seq();
			boolean command = a.command() >= 0;
			if (recorder != null)
				recorder.recordAt(a.step(), command ? Recorder.COMMAND : Recorder.AI, a.pc(), a.div(), a.seq(),
						(command ? a.command() : a.kind()) | (a.skipped() ? 1 << 16 : 0));
			if (command && a.command() >= WORLD_COMMANDS && a.command() <= LAST_WORLD_COMMAND)
				out.line(SameLine.atStep(SameLine.CONFIRMED, a.step(), "command: " + engine.commandName(a.command()) + " (" + a.command()
						+ "), " + (a.pc() < 0 ? "the AI general" : "PC " + a.pc()) + (a.div() < 0 ? "" : ", division " + a.div())));
		}
	}

	private void orders(MpLog out) {
		orders.clear();
		long lost = engine.armyOrders(orderSeq, orders);
		if (lost > 0)
			out.line("engine rings: " + lost + " army decisions were overwritten before this PC read them");
		for (ArmyOrder o : orders) {
			orderSeq = o.seq();
			if (recorder != null)
				recorder.recordAt(o.step(), Recorder.ARMY, o.faction(), o.kind().ordinal(),
						(long) (o.x() & 0xFFFF) << 48 | (long) (o.y() & 0xFFFF) << 32 | o.army() & 0xFFFFFFFFL, o.target());
			if (o.kind() == ArmyOrder.Kind.CREATE || o.kind() == ArmyOrder.Kind.BESIEGE || o.kind() == ArmyOrder.Kind.DISBAND)
				out.line(SameLine.atStep(SameLine.CONFIRMED, o.step(), "army: " + o.kind().name().toLowerCase(java.util.Locale.ROOT) + ", army "
						+ o.army() + " of " + faction(o.faction()) + ", tile " + o.x() + "," + o.y()
						+ (o.target() < 0 ? "" : ", target " + o.target())));
		}
	}

	private static String faction(int i) {
		return i < 0 ? "the rebels" : FACTIONS.getByIndex(i).name + " (" + i + ")";
	}
}
