package sosgsgmp.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** SOS-210: the depots' messages once player traders count (PTreport/TradeMessageBug.png). */
class DepotMessagesTest {

    @Test
    void withoutPlayerPartnersVanillasMessagesStand() {
        assertEquals(DepotMessages.VANILLA, DepotMessages.importProblem(true, false, false, 0));
        assertEquals(DepotMessages.VANILLA, DepotMessages.importWarning(500, 0, 10_000, 411, 1));
        assertEquals(DepotMessages.VANILLA, DepotMessages.exportProblem(true, false, false));
        assertEquals(DepotMessages.VANILLA, DepotMessages.exportWarning(100, false, false, 200));
    }

    @Test
    void aPlayerSellingClearsTheImportProblemThatNpcsAlonePose() {
        assertEquals(DepotMessages.NONE, DepotMessages.importProblem(true, false, true, 16), "no NPC partner, a player sells");
        assertEquals(DepotMessages.NO_TRADERS, DepotMessages.importProblem(true, false, true, 0),
                "a player partner who doesn't sell it: no traders of the goods, not 'no trade agreements'");
        assertEquals(DepotMessages.VANILLA, DepotMessages.importProblem(true, true, true, 16), "a shut gate stops a player's goods too");
        assertEquals(DepotMessages.VANILLA, DepotMessages.importProblem(false, false, true, 16), "no problem stays none");
    }

    /** The screenshot: NPCs cheapest at 453, the cap 411, another player at 16. */
    @Test
    void aPlayerUnderTheCapClearsThePriceCapWarning() {
        assertEquals(DepotMessages.NONE, DepotMessages.importWarning(453, 16, 10_000, 411, 1));
        assertEquals(DepotMessages.PRICE_CAP, DepotMessages.importWarning(453, 420, 10_000, 411, 1), "both over the cap");
        assertEquals(DepotMessages.PRICE_CAP, DepotMessages.importWarning(453, 411, 10_000, 411, 1),
                "a player's price at the cap doesn't sell, as the market buys under it");
        assertEquals(DepotMessages.NONE, DepotMessages.importWarning(411, 420, 10_000, 411, 1), "an NPC at the cap sells, as vanilla");
        assertEquals(DepotMessages.NONE, DepotMessages.importWarning(0, 16, 10_000, 411, 1), "no NPC sells at all");
    }

    @Test
    void creditsAndTheTreasuryLimitUseTheCheapestPriceAPlayerIncluded() {
        assertEquals(DepotMessages.NONE, DepotMessages.importWarning(500, 16, 100, 1000, 1), "a player's 16 is affordable");
        assertEquals(DepotMessages.CREDITS, DepotMessages.importWarning(500, 300, 100, 1000, 1));
        assertEquals(DepotMessages.TREASURY, DepotMessages.importWarning(500, 16, 100, 1000, 90), "100 - 16 under the limit of 90");
        assertEquals(16, DepotMessages.cheapest(Integer.MAX_VALUE, 16));
        assertEquals(12, DepotMessages.cheapest(12, 16));
        assertEquals(12, DepotMessages.cheapest(12, 0));
    }

    @Test
    void aPlayerBuyingClearsTheExportMessages() {
        assertEquals(DepotMessages.NONE, DepotMessages.exportProblem(true, true, true), "no NPC buys, a player's Import depot takes it");
        assertEquals(DepotMessages.NO_BUYER, DepotMessages.exportProblem(true, true, false));
        assertEquals(DepotMessages.NONE, DepotMessages.exportWarning(100, true, true, 200), "NPCs under the cap, a player buys at it");
        assertEquals(DepotMessages.PRICE_CAP, DepotMessages.exportWarning(100, true, false, 200), "nobody pays the cap");
        assertEquals(DepotMessages.PRICE_CAP, DepotMessages.exportWarning(0, true, false, 200), "only a player, over their cap");
        assertEquals(DepotMessages.NONE, DepotMessages.exportWarning(200, true, false, 200), "an NPC pays the cap, as vanilla");
    }
}
