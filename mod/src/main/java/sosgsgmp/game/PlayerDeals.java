package sosgsgmp.game;

import java.util.List;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.player.Player;
import sosdet.Engine;
import sosdet.game.deal.DealBook;
import sosdet.txn.TransferLog;

/**
 * SOSGSGMP: the engine's deals between players, and what this PC knows of another player (spec §6; SOS-72, SOS-76).
 * The engine keeps every proposal and answer as world state, so this holds none: the session panel, the faction
 * window and the autopilot all call here. Game thread, outside the step.
 */
public final class PlayerDeals {

    private PlayerDeals() {
    }

    /** True while a multiplayer game's engine session runs; false in single player. */
    public static boolean inSession() {
        return Engine.active();
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

    /** Why this PC's player can't declare war on the player in {@code slot} now; null when they can (SOS-74). */
    public static String warRefusal(int slot) {
        return Engine.warRefusal(slot);
    }

    /** This PC's player declares war on the player in {@code slot}, at once. Check {@link #warRefusal} first. */
    public static void declareWar(int slot) {
        Engine.declareWar(slot);
    }

    /** Why this PC's player can't set a tariff of {@code percent} on the player in {@code slot} now, or null. */
    public static String tariffRefusal(int slot, int percent) {
        return Engine.tariffRefusal(slot, percent);
    }

    /** This PC's player sets their tariff on the player in {@code slot}, at once. Check {@link #tariffRefusal} first. */
    public static void setTariff(int slot, int percent) {
        Engine.setTariff(slot, percent);
    }

    /** The tariff the player in {@code setter} puts on what the player in {@code other} sells them, in percent. */
    public static int tariff(int setter, int other) {
        return Engine.tariff(setter, other);
    }

    /**
     * Why the player in {@code giver} can't give {@code region} (a region index) to the player in {@code receiver} along
     * with {@code chosen}, or null (SOS-73).
     */
    public static String regionRefusal(int giver, int receiver, int region, int[] chosen) {
        return Engine.dealRegionRefusal(giver, receiver, region, chosen);
    }

    /** The yearly tribute, in percent of its worth, the player in {@code vassal} pays the player in {@code overlord}. */
    public static int tributePercent(int vassal, int overlord) {
        return Engine.tributePercent(vassal, overlord);
    }

    /**
     * What the tariff of the player in {@code buyer} on the player in {@code seller} brought in this year, or last
     * year; with {@code seller} -1, all of {@code buyer}'s tariffs this year.
     */
    public static int tariffRevenue(int buyer, int seller, boolean lastYear) {
        return Engine.tariffRevenue(buyer, seller, lastYear);
    }

    /**
     * The price per item the player in {@code slot} last reported for the goods with index {@code tradable}: their
     * lowest export price ({@code sell}), or their highest import price; 0 before their first report.
     */
    public static int price(int slot, int tradable, boolean sell) {
        return Engine.reportedPrice(slot, tradable, sell);
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

    /**
     * Adds to {@code out} every player's goods and money transfers made after the one numbered {@code afterSeq}, oldest
     * first, and returns how many of those the engine's record no longer holds (SOS-81).
     */
    public static long transfers(long afterSeq, List<TransferLog.Transfer> out) {
        return Engine.transfers(afterSeq, out);
    }

    /** The number of the latest transfer recorded; 0 before the first. */
    public static long lastTransfer() {
        return Engine.lastTransfer();
    }

    /** The goods with index {@code tradable} the player in {@code slot} last reported having; 0 before their first report. */
    public static int stock(int slot, int tradable) {
        return Engine.reportedStock(slot, tradable);
    }
}
