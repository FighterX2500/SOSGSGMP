package menu;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import sosdet.BattleKey;
import sosdet.BattleOutcome;
import sosdet.Notice;
import sosdet.OwnBattle;
import sosdet.game.station.StationBook;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.lobby.LobbyPlayer;
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
		String winner = name(o.winner);
		String loser = name(o.loser);
		setup.chat().notice(o.retreat ? loser + " retreated from the battle with " + winner + "."
				: winner + " won the battle with " + loser + ".");
	}

	/** The player in {@code slot}, by the name the chat shows them with: two realms can share a name. */
	String name(int slot) {
		for (LobbyPlayer p : setup.players())
			if (setup.factions().slotOf(p.id()) == slot)
				return p.name();
		return engine.factionName(slot);
	}

	/** The battle of {@code BattleKey} kind {@code kind} whose human is {@code human}, in words: whose, against whom (SOS-231). */
	String battle(int kind, int human) {
		switch (kind) {
			case BattleKey.BATTLE:
				OwnBattle b = engine.battle();
				return b != null && b.human == human && b.other >= 0 ? "the battle between " + name(human) + " and " + name(b.other)
						: name(human) + "'s battle";
			case BattleKey.LOCAL:
				return name(human) + "'s battle against NPCs";
			default:
				return "the assault on " + name(human) + "'s capital";
		}
	}

	/** This player and another player's NPC vassal {@code vassal} have a stance through its overlord (SOS-140). */
	private String passedText(String vassal, Notice n) {
		Faction overlord = DIP.overlord(FACTIONS.getByIndex(n.second()));
		String through = overlord == null ? "" : ", through your agreement with " + name(overlord.index());
		return switch (n.value()) {
			case 1 -> vassal + " is now your trade partner" + through + ".";
			case 2 -> vassal + " is now your colleague" + through + ".";
			default -> vassal + " is no longer your trade partner or colleague: the agreement with its overlord ended.";
		};
	}

	/** Why a stationing ended ({@code StationBook}'s reasons), as the end of the notice's sentence (SOS-233). */
	private static String stationEnd(int why) {
		return switch (why) {
			case StationBook.SENT_AWAY -> ": sent away.";
			case StationBook.LEFT -> ": called back.";
			case StationBook.MOVED -> ": it moved.";
			case StationBook.ARMY_GONE -> ": the army is gone.";
			case StationBook.AGREEMENT_ENDED -> ": their agreement ended.";
			case StationBook.AWAY -> ": a player left.";
			case StationBook.CAPITAL_LOST -> ": the capital changed hands.";
			default -> ".";
		};
	}

	/** What {@code n} says on this PC; null when it isn't for this player. */
	private String text(Notice n) {
		String a = name(n.first());
		String b = name(n.second());
		return switch (n.kind()) {
			case Notice.WAR -> a + " declared war on " + b + ".";
			case Notice.PEACE -> a + " and " + b + " made peace.";
			case Notice.VASSAL -> a + " became the vassal of " + b + ".";
			case Notice.SIEGE -> "The capital of " + a + " is under siege.";
			case Notice.ASSAULT -> a + " is assaulting the capital of " + b + ".";
			case Notice.BATTLE -> a + " and " + b + " are fighting a battle.";
			case Notice.TARIFF -> n.second() == setup.factions().slotOf(setup.localPlayerId())
					? a + " set a tariff of " + n.value() + "% on you." : null;
			case Notice.SPEED_VOTE -> a + " voted for speed " + n.value() + "x.";
			case Notice.ALLY_JOINED -> a + " joined the battle of " + b + ".";
			case Notice.ALLY_OUT -> a + " stayed out of the battle of " + b + ".";
			case Notice.ARMY_LEFT -> a + " retreated from the battle.";
			case Notice.STANCE_PASSED -> n.first() == setup.factions().slotOf(setup.localPlayerId()) ? passedText(b, n) : null;
			case Notice.RESTART -> n.value() == 1 ? "The vote restarts " + battle(n.second(), n.first()) + " from its start."
					: battle(n.second(), n.first()) + " couldn't restart: it ended, or a player joined or left it, or a prompt was open.";
			case Notice.SALLY_EMPTY -> n.first() == setup.factions().slotOf(setup.localPlayerId())
					? "Nobody sallies out: no soldiers stand in your capital or within 4 tiles of it." : null;
			case Notice.STATIONED -> a + " stationed an army at the capital of " + b + ".";
			case Notice.STATION_ENDED -> "An army of " + a + " is no longer stationed at the capital of " + b + stationEnd(n.value());
			case Notice.STATION_SUPPLIED -> a + " supplied the army of " + b + " stationed at its capital.";
			default -> throw new IllegalArgumentException("SOSGSGMP: a notice of kind " + n.kind());
		};
	}
}
