package sosgsgmp.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DepotPriceTest {

    @Test
    void theBuyerPaysThePricePlusTheTollAndGetsTheTariffBack() {
        DepotPrice p = new DepotPrice(50, 21, 20);
        assertEquals(71, p.paid());
        assertEquals(10, p.tariff(), "20% of the asked price, not of the toll");
        assertEquals(61, p.cost());
    }

    @Test
    void theSellerGetsTheirPriceLessTheTariffNotTheToll() {
        assertEquals(40, new DepotPrice(50, 21, 20).received());
        assertEquals(1, new DepotPrice(1, 70, 20).received(), "a price of 1 earns 1, however far the buyer");
    }

    @Test
    void theTariffRoundsDownAsTheEnginesBookCutsIt() {
        assertEquals(2, DepotPrice.tariff(13, 20));
        assertEquals(0, DepotPrice.tariff(4, 20));
        assertEquals(0, new DepotPrice(1, 70, 0).tariff());
    }

    @Test
    void theTollIsTheDistancePlusTwentyOverTheRoadsRoundedDown() {
        assertEquals(30, DepotPrice.toll(40, 1.0, 2.0));
        assertEquals(21, DepotPrice.toll(1, 1.0, 1.0));
        assertEquals(0, DepotPrice.toll(-50, 1.0, 1.0), "never below 0");
        assertEquals(6, DepotPrice.toll(0, 1.0, 3.0), "20 / 3 = 6.67, rounded down");
    }

    @Test
    void aBuyerStopsAtTheirImportPriceCap() {
        assertTrue(DepotPrice.buys(71, 72));
        assertFalse(DepotPrice.buys(72, 72));
        assertTrue(DepotPrice.buys(71, 1_000_000), "the default cap: no cap");
    }

    @Test
    void badPartsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new DepotPrice(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DepotPrice(1, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> DepotPrice.toll(1, 1, 0));
    }
}
