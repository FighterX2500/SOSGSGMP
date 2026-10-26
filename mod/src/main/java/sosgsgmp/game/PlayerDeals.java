package sosgsgmp.game;

import java.util.List;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.player.Player;
import sosdet.Engine;
import sosdet.game.deal.DealBook;

/**
 * SOSGSGMP: the engine's deals between players, and what this PC knows of another player (spec §6; SOS-72, SOS-76).
 * The engine keeps every proposal and answer as world state, so this holds none: the session panel, the faction
 * window and the autopilot all call here. Game thread, outside the step.
 */
public final class PlayerDeals {

    private PlayerDeals() {
    }

    /**
     * True for another player in a running multiplayer game who wasn't removed: their faction window and hover box are
     * the mod's. False in single player, where no engine session runs.
     */
    public static boolean otherPlayer(Faction f) {
        return f instanceof Player && f != FACTIONS.player() && Engine.active() && !Engine.retired(f.index());
    }

    /**
     * Why this PC's player can't propose {@code terms} to the player in {@code slot} now, as a sentence, or null. Only
     * this PC's own money and goods are checked here; the other side's are checked when the deal applies.
     */
    public static String refusal(int slot, DealBook.Terms terms) {
        return Engine.dealRefusal(slot, terms);
    }

    /** This PC's player proposes {@code terms} to the player in {@code slot}. Check {@link #refusal} first. */
    public static void propose(int slot, DealBook.Terms terms) {
        Engine.dealPropose(slot, terms);
    }

    /** Why this PC's player can't accept the proposal {@code id} now, or null. Declining always works. */
    public static String acceptRefusal(int id) {
        return Engine.dealAcceptRefusal(id);
    }

    /** This PC's player answers the proposal {@code id} made to them. */
    public static void answer(int id, boolean accept) {
        Engine.dealAnswer(id, accept);
    }

    /** Why this PC's player can't cancel every treaty with the player in {@code slot} now, or null. */
    public static String cancelRefusal(int slot) {
        return Engine.dealCancelRefusal(slot);
    }

    /** This PC's player cancels every treaty with the player in {@code slot}, at once. Check {@link #cancelRefusal} first. */
    public static void cancel(int slot) {
        Engine.dealCancel(slot);
    }

    /** Every open proposal between players, oldest first: a live view. */
    public static List<DealBook.Proposal> open() {
        return Engine.deals();
    }

    /** The latest answers to proposals between players, oldest first: a live view. */
    public static List<DealBook.Answer> answers() {
        return Engine.dealAnswers();
    }

    /** The open proposal {@code id}, or null when it closed. */
    public static DealBook.Proposal proposal(int id) {
        for (DealBook.Proposal p : Engine.deals())
            if (p.id() == id)
                return p;
        return null;
    }

    /** The goods with index {@code tradable} the player in {@code slot} last reported having; 0 before their first report. */
    public static int stock(int slot, int tradable) {
        return Engine.reportedStock(slot, tradable);
    }
}
