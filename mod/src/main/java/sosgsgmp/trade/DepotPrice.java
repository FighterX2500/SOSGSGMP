package sosgsgmp.trade;

/**
 * SOSGSGMP: one item's price in a market sale between two players, in its parts, as the market reckons it (spec §7;
 * SOS-138). The buyer pays the seller's export price plus the toll, and the toll pays the caravan (owner, 2026-11-04);
 * the buyer's tariff is cut from what reaches the seller and goes to the buyer's treasury. Plain Java: the depots'
 * partner lists read it each frame, so nothing here allocates beyond the value itself, made once per hover.
 */
public final class DepotPrice {

    /** What the seller asks: their lowest export price. */
    public final int asked;
    /** The toll per item. */
    public final int toll;
    /** The buyer's tariff on the seller, in percent. */
    public final int tariffPercent;

    public DepotPrice(int asked, int toll, int tariffPercent) {
        if (asked < 0 || toll < 0 || tariffPercent < 0 || tariffPercent > 100)
            throw new IllegalArgumentException("SOSGSGMP: no price of " + asked + " with toll " + toll + " and tariff "
                    + tariffPercent + "%");
        this.asked = asked;
        this.toll = toll;
        this.tariffPercent = tariffPercent;
    }

    /** What the buyer pays the market, which their import price cap is held against. */
    public int paid() {
        return paid(asked, toll);
    }

    /** The buyer's tariff, cut from what reaches the seller and paid back to the buyer's treasury. */
    public int tariff() {
        return tariff(asked, tariffPercent);
    }

    /** What reaches the seller: their price less the buyer's tariff. The toll went to the caravan. */
    public int received() {
        return asked - tariff();
    }

    /** What the buyer is out once their tariff comes back to their treasury. */
    public int cost() {
        return paid() - tariff();
    }

    /** {@link #paid()} without a value: for the per-frame list. */
    public static int paid(int asked, int toll) {
        return asked + toll;
    }

    /** The tariff on a sale at {@code asked}, as the engine's tariff book cuts it: of the asked price, rounded down. */
    public static int tariff(int asked, int percent) {
        return (int) ((long) asked * percent / 100);
    }

    /**
     * The toll per item, as vanilla's {@code TradeManager.totalFee} charges it on a sale of single items: the distance
     * from capital to capital, plus 20, times {@code tollPerTile}, over the buyer's roads' {@code divisor}, rounded down.
     */
    public static int toll(double distance, double tollPerTile, double divisor) {
        if (divisor <= 0)
            throw new IllegalArgumentException("SOSGSGMP: no toll divisor of " + divisor);
        return (int) Math.floor(Math.max(0, (20 + distance) * tollPerTile) / divisor);
    }

    /** True when a buyer with import price cap {@code cap} buys at {@code paid}: vanilla's buyer stops at its cap. */
    public static boolean buys(int paid, int cap) {
        return paid < cap;
    }

    /**
     * The highest export price at which a buyer with import price cap {@code cap} still buys, with {@code toll} per item,
     * as {@link #buys} decides (SOS-148); -1 when no export price of 1 or more does, the toll alone reaching the cap.
     */
    public static int highestAsked(int cap, int toll) {
        int most = cap - 1 - toll;
        return most >= 1 ? most : -1;
    }

    /** The lowest import price cap at which a buyer buys at {@code paid}, as {@link #buys} decides (SOS-148). */
    public static int lowestCap(int paid) {
        return paid + 1;
    }
}
