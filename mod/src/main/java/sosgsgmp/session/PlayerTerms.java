package sosgsgmp.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sosdet.game.deal.DealBook;

/**
 * SOSGSGMP: the deal this PC's player sets up for another player in the faction window's Diplomacy tab (spec §6,
 * SOS-76): vanilla's terms in vanilla's order, how each works between players, the one term chosen, the money, goods
 * and regions each side gives, and a vassal's tribute rate (SOS-73). The engine judges whether the deal can be made;
 * this class knows only what isn't built yet.
 */
public final class PlayerTerms {

    /** Vanilla's deal terms, in the order its Diplomacy tab lists them. */
    public enum Term {
        WAR, PEACE, CANCEL_ALL, TRADE, PACT, ALLY, PROTECTOR, VASSAL, UNITE
    }

    /** How a term works between players. */
    public enum Kind {
        /** A proposal the other player accepts or declines. */
        PROPOSE,
        /** Applies at once, with no answer (owner, 2026-10-27). */
        AT_ONCE,
        /** Greyed out, with the reason: not between players yet. */
        NOT_YET
    }

    private final int[] give;
    private final int[] ask;
    private int giveCredits;
    private int askCredits;
    private Term chosen;
    private final List<Integer> giveRegions = new ArrayList<>();
    private final List<Integer> askRegions = new ArrayList<>();
    private int tribute = DealBook.DEFAULT_TRIBUTE;

    /** {@code tradables}: how many kinds of goods there are; each side's goods are kept by their index. */
    public PlayerTerms(int tradables) {
        if (tradables <= 0)
            throw new IllegalArgumentException("SOSGSGMP: " + tradables + " kinds of goods");
        give = new int[tradables];
        ask = new int[tradables];
    }

    public static Kind kind(Term t) {
        return switch (t) {
            case PEACE, TRADE, PACT, ALLY, PROTECTOR, VASSAL -> Kind.PROPOSE;
            case WAR, CANCEL_ALL -> Kind.AT_ONCE; // War since battles between players are fought (SOS-83)
            case UNITE -> Kind.NOT_YET;
        };
    }

    /** Why the term is greyed out between players, as a sentence; null for a term that works. */
    public static String notYet(Term t) {
        return switch (t) {
            case UNITE -> "Not between players.";
            default -> null;
        };
    }

    /** The engine's treaty for a term that works; {@link DealBook#NO_TREATY} for one that doesn't. */
    public static int treaty(Term t) {
        return switch (t) {
            case PEACE -> DealBook.PEACE;
            case TRADE -> DealBook.TRADE;
            case PACT -> DealBook.PACT;
            case ALLY -> DealBook.ALLY;
            case CANCEL_ALL -> DealBook.CANCEL_ALL;
            case PROTECTOR -> DealBook.PROTECTOR;
            case VASSAL -> DealBook.VASSAL;
            default -> DealBook.NO_TREATY;
        };
    }

    /** Chooses {@code t}, or no term when it was chosen already: a deal holds one term, as the engine's does. */
    public void toggle(Term t) {
        if (kind(t) == Kind.NOT_YET)
            throw new IllegalArgumentException("SOSGSGMP: " + t + " isn't between players yet");
        chosen = chosen == t ? null : t;
    }

    /** The chosen term, or null. */
    public Term chosen() {
        return chosen;
    }

    /** True when the chosen term applies at once instead of being proposed. */
    public boolean atOnce() {
        return chosen != null && kind(chosen) == Kind.AT_ONCE;
    }

    /** How much of the goods with index {@code tradable} this player gives ({@code mine}) or asks for. */
    public int goods(boolean mine, int tradable) {
        return (mine ? give : ask)[tradable];
    }

    public void goods(boolean mine, int tradable, int amount) {
        if (amount < 0)
            throw new IllegalArgumentException("SOSGSGMP: " + amount + " goods");
        (mine ? give : ask)[tradable] = amount;
    }

    /** The denari this player gives ({@code mine}) or asks for. */
    public int credits(boolean mine) {
        return mine ? giveCredits : askCredits;
    }

    public void credits(boolean mine, int amount) {
        if (amount < 0)
            throw new IllegalArgumentException("SOSGSGMP: " + amount + " denari");
        if (mine)
            giveCredits = amount;
        else
            askCredits = amount;
    }

    /** The regions this player gives ({@code mine}) or asks for, as region indices, in the order chosen. */
    public int[] regions(boolean mine) {
        List<Integer> l = mine ? giveRegions : askRegions;
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++)
            out[i] = l.get(i);
        return out;
    }

    /** True when the region with index {@code region} is in this player's offer ({@code mine}) or demand. */
    public boolean hasRegion(boolean mine, int region) {
        return (mine ? giveRegions : askRegions).contains(region);
    }

    /** Adds the region to the offer ({@code mine}) or demand, or takes it out when it is there already. */
    public void toggleRegion(boolean mine, int region) {
        if (region < 0)
            throw new IllegalArgumentException("SOSGSGMP: a region of index " + region);
        List<Integer> l = mine ? giveRegions : askRegions;
        if (!l.remove(Integer.valueOf(region)))
            l.add(region);
    }

    /** The vassal's yearly tribute in percent of its worth, for Protector and Vassal; 10% until set. */
    public int tribute() {
        return tribute;
    }

    public void tribute(int percent) {
        if (percent < 0 || percent > 100)
            throw new IllegalArgumentException("SOSGSGMP: a tribute of " + percent + "%");
        tribute = percent;
    }

    /** True when the chosen term makes one player the other's vassal, so the tribute rate counts. */
    public boolean vassalage() {
        return chosen == Term.PROTECTOR || chosen == Term.VASSAL;
    }

    /** Back to an empty deal, after it was proposed. */
    public void clear() {
        chosen = null;
        giveCredits = 0;
        askCredits = 0;
        Arrays.fill(give, 0);
        Arrays.fill(ask, 0);
        giveRegions.clear();
        askRegions.clear();
        tribute = DealBook.DEFAULT_TRIBUTE;
    }

    /** True when either side gives money, goods or regions. */
    public boolean hasGoodsOrMoney() {
        return giveCredits > 0 || askCredits > 0 || any(give) || any(ask) || !giveRegions.isEmpty() || !askRegions.isEmpty();
    }

    /** Why the deal can't go ahead for a reason of its own, before the engine's checks; null when it can. */
    public String refusal() {
        if (atOnce() && hasGoodsOrMoney())
            return (chosen == Term.WAR ? "Declare War" : "Cancel All Agreements") + " applies at once: it takes no money, goods or regions.";
        return null;
    }

    /** The deal as the engine's terms: this player is the proposer, each side's goods as pairs, its regions as indices. */
    public DealBook.Terms terms() {
        int treaty = chosen == null ? DealBook.NO_TREATY : treaty(chosen);
        return new DealBook.Terms(treaty, giveCredits, askCredits, pairs(give), pairs(ask), regions(true), regions(false),
                tribute);
    }

    private static boolean any(int[] goods) {
        for (int g : goods)
            if (g > 0)
                return true;
        return false;
    }

    private static int[] pairs(int[] goods) {
        int n = 0;
        for (int g : goods)
            if (g > 0)
                n++;
        int[] out = new int[n * 2];
        int k = 0;
        for (int i = 0; i < goods.length; i++) {
            if (goods[i] > 0) {
                out[k++] = i;
                out[k++] = goods[i];
            }
        }
        return out;
    }
}
