package sosgsgmp.trade;

/**
 * SOSGSGMP: which of vanilla's depot messages applies once the players this player trades with count (spec §7;
 * SOS-210). Vanilla's {@code PBuyer} and {@code PSeller} say why goods don't trade from the NPCs' prices, neighbours and
 * trade agreements alone; another player can sell under the cap or buy at it while every NPC is out of reach. Each
 * method answers {@link #VANILLA} where players change nothing, so the caller keeps vanilla's own text. Plain Java,
 * no allocation: the depots read it each frame.
 */
public final class DepotMessages {

    /** Keep vanilla's message, whatever it is (null included). */
    public static final int VANILLA = -1;
    /** No message: the goods trade. */
    public static final int NONE = 0;
    /** "There are no traders of this resource available." */
    public static final int NO_TRADERS = 1;
    /** Import: "You don't have enough credits to purchase this resource." */
    public static final int CREDITS = 2;
    /** Import: the cheapest price is over the cap; export: the best price is under it. */
    public static final int PRICE_CAP = 3;
    /** Import: "A purchase of a batch of 32 of this resource is not possible due to your treasury limit." */
    public static final int TREASURY = 4;
    /** Export: "There is no one willing to buy this goods." */
    public static final int NO_BUYER = 5;

    /** Vanilla's price when no NPC sells: none at all. */
    private static final int NO_NPC_PRICE = 0;

    private DepotMessages() {
    }

    /**
     * The Import depot's problem (red). {@code npcProblem}: vanilla's is one the NPCs alone pose (no trade, no trade
     * partners); any other, a missing depot or a shut gate, stands. {@code partners}: a player trades with this one (an
     * agreement and trade reach); {@code playerPrice}: the cheapest such player's price for one item, toll included, 0
     * when none sells.
     */
    public static int importProblem(boolean npcProblem, boolean partners, int playerPrice) {
        if (!npcProblem || !partners) {
            return VANILLA;
        }
        return playerPrice > 0 ? NONE : NO_TRADERS;
    }

    /**
     * The cheapest price for one item: vanilla's best NPC price {@code npcPrice} (0 or {@code Integer.MAX_VALUE} when no
     * NPC sells) or {@code playerPrice} (0 when no player sells).
     */
    public static int cheapest(int npcPrice, int playerPrice) {
        if (playerPrice <= 0) {
            return npcPrice;
        }
        if (npcPrice <= NO_NPC_PRICE || npcPrice == Integer.MAX_VALUE) {
            return playerPrice;
        }
        return Math.min(npcPrice, playerPrice);
    }

    /**
     * The Import depot's warning (yellow), as vanilla checks it in its order: credits, the price cap, the treasury
     * limit; with the cheapest price a player's when it is. An NPC sells at a price up to the cap, a player under it,
     * as the market buys ({@link DepotPrice#buys}).
     */
    public static int importWarning(int npcPrice, int playerPrice, double credits, int cap, int minCredits) {
        if (playerPrice <= 0) {
            return VANILLA;
        }
        int best = cheapest(npcPrice, playerPrice);
        if (best > credits) {
            return CREDITS;
        }
        boolean npcSells = npcPrice > NO_NPC_PRICE && npcPrice != Integer.MAX_VALUE && npcPrice <= cap;
        if (!npcSells && !DepotPrice.buys(playerPrice, cap)) {
            return PRICE_CAP;
        }
        if (credits - best < minCredits) {
            return TREASURY;
        }
        return NONE;
    }

    /**
     * The Export depot's problem (red). {@code npcProblem}: vanilla's is one the NPCs alone pose (no trade, no trade
     * partners, no one willing to buy). {@code takes}: a player this one trades with has an Import depot for the goods,
     * so it would buy at some price.
     */
    public static int exportProblem(boolean npcProblem, boolean partners, boolean takes) {
        if (!npcProblem || !partners) {
            return VANILLA;
        }
        return takes ? NONE : NO_BUYER;
    }

    /**
     * The Export depot's warning (yellow) once vanilla's "no goods" doesn't apply: the price cap, unless someone buys
     * at it. {@code npcPrice}: vanilla's best NPC price (0 or less: none buys); {@code takes} as in
     * {@link #exportProblem}; {@code playerBuys}: such a player buys at this player's cap, the toll included.
     */
    public static int exportWarning(int npcPrice, boolean takes, boolean playerBuys, int cap) {
        if (!takes) {
            return VANILLA;
        }
        boolean npcBuys = npcPrice > NO_NPC_PRICE && npcPrice >= cap;
        return npcBuys || playerBuys ? NONE : PRICE_CAP;
    }
}
