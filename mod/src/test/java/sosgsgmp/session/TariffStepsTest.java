package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import sosdet.game.deal.TariffBook;

/** SOS-77: the Set Tariff slider covers 0% to 80% in steps of 5%, every step a tariff the engine accepts. */
class TariffStepsTest {

    @Test
    void everyStepIsATariffTheEngineAccepts() {
        assertEquals(16, TariffSteps.LAST);
        assertEquals(0, TariffSteps.percent(0));
        assertEquals(80, TariffSteps.percent(TariffSteps.LAST));
        for (int s = 0; s <= TariffSteps.LAST; s++) {
            assertNull(TariffBook.refusal(TariffSteps.percent(s)), "step " + s);
            assertEquals(s, TariffSteps.step(TariffSteps.percent(s)));
        }
        assertThrows(IllegalArgumentException.class, () -> TariffSteps.percent(17));
    }

    @Test
    void aTariffOffTheStepsGoesToTheNearestOneInRange() {
        assertEquals(4, TariffSteps.step(TariffBook.DEFAULT));
        assertEquals(2, TariffSteps.step(12));
        assertEquals(16, TariffSteps.step(95));
        assertEquals(0, TariffSteps.step(-3));
    }
}
