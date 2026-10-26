package sosgsgmp.session;

import java.util.Arrays;

import sosdet.game.deal.DealBook;

/**
 * SOSGSGMP: the deal this PC's player sets up for another player in the faction window's Diplomacy tab (spec §6,
 * SOS-76): vanilla's terms in vanilla's order, how each works between players, the one term chosen, and the money and
 * goods each side gives. The engine judges whether the deal can be made; this class knows only what isn't built yet.
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

    /** {@code tradables}: how many kinds of goods there are; each side's goods are kept by their index. */
    public PlayerTerms(int tradables) {
        if (tradables <= 0)
            throw new IllegalArgumentException("SOSGSGMP: " + tradables + " kinds of goods");
        give = new int[tradables];
        ask = new int[tradables];
    }

    public static Kind kind(Term t) {
        return switch (t) {
            case PEACE, TRADE, PACT, ALLY -> Kind.PROPOSE;
            case CANCEL_ALL -> Kind.AT_ONCE;
            case WAR, PROTECTOR, VASSAL, UNITE -> Kind.NOT_YET;
        };
    }

    /** Why the term is greyed out between players, as a sentence; null for a term that works. */
    public static String notYet(Term t) {
        return switch (t) {
            case WAR -> "No battles between players yet.";
            case PROTECTOR, VASSAL -> "Not between players yet.";
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

    /** Back to an empty deal, after it was proposed. */
    public void clear() {
        chosen = null;
        giveCredits = 0;
        askCredits = 0;
        Arrays.fill(give, 0);
        Arrays.fill(ask, 0);
    }

    /** True when either side gives money or goods. */
    public boolean hasGoodsOrMoney() {
        return giveCredits > 0 || askCredits > 0 || any(give) || any(ask);
    }

    /** Why the deal can't go ahead for a reason of its own, before the engine's checks; null when it can. */
    public String refusal() {
        if (atOnce() && hasGoodsOrMoney())
            return "Cancel All Agreements applies at once: it takes no money or goods.";
        return null;
    }

    /** The deal as the engine's terms: this player is the proposer, and each side's goods as pairs. */
    public DealBook.Terms terms() {
        int treaty = chosen == null ? DealBook.NO_TREATY : treaty(chosen);
        return new DealBook.Terms(treaty, giveCredits, askCredits, pairs(give), pairs(ask));
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
