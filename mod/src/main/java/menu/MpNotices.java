package menu;

import sosdet.BattleOutcome;
import sosdet.Notice;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the engine's events as chat lines (spec §12, SOS-110). Every PC gets them at the same step in the same order;
 * a tariff tells only the player it is set on. English only. Once per frame from {@link MpPlay}, between steps.
 */
final class MpNotices {

	private final SetupSession setup;
	private final EnginePlay engine;

	MpNotices(SetupSession setup, EnginePlay engine) {
		this.setup = setup;
		this.engine = engine;
	}

	void frame() {
		Notice n;
		while ((n = engine.noticeTake()) != null) {
			String text = text(n);
			if (text != null)
				setup.chat().notice(text);
		}
	}

	void battleOver(BattleOutcome o) {
		String winner = engine.factionName(o.winner);
		String loser = engine.factionName(o.loser);
		setup.chat().notice(o.retreat ? loser + " retreated from the battle with " + winner + "."
				: winner + " won the battle with " + loser + ".");
	}

	/** What {@code n} says on this PC; null when it isn't for this player. */
	private String text(Notice n) {
		String a = engine.factionName(n.first());
		String b = engine.factionName(n.second());
		return switch (n.kind()) {
			case Notice.WAR -> a + " declared war on " + b + ".";
			case Notice.PEACE -> a + " and " + b + " made peace.";
			case Notice.VASSAL -> a + " became the vassal of " + b + ".";
			case Notice.SIEGE -> "The capital of " + a + " is under siege.";
			case Notice.ASSAULT -> a + " is assaulting the capital of " + b + ".";
			case Notice.BATTLE -> a + " and " + b + " are fighting a battle.";
			case Notice.TARIFF -> n.second() == setup.factions().slotOf(setup.localPlayerId())
					? a + " set a tariff of " + n.value() + "% on you." : null;
			default -> throw new IllegalArgumentException("SOSGSGMP: a notice of kind " + n.kind());
		};
	}
}
