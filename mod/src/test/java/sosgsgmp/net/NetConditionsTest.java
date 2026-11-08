package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NetConditionsTest {

    @Test
    void aSpecReadsEveryKey() {
        NetConditions c = NetConditions.parse("delay=150, jitter=40,loss=2.5,seed=7,steamlag=100,steamloss=1,steamreorder=3");
        assertEquals(new NetConditions(150, 40, 2.5, 7, 100, 1, 3), c);
        assertTrue(c.steam());
    }

    @Test
    void anEmptySpecIsNoConditions() {
        NetConditions c = NetConditions.parse("");
        assertEquals(0, c.delayMs());
        assertFalse(c.steam());
    }

    @Test
    void aBadSpecFailsNamingWhatItCantRead() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NetConditions.parse("ping=100"));
        assertTrue(e.getMessage().contains("ping"));
        assertThrows(IllegalArgumentException.class, () -> NetConditions.parse("delay"));
        assertThrows(IllegalArgumentException.class, () -> NetConditions.parse("delay=fast"));
        assertThrows(IllegalArgumentException.class, () -> NetConditions.parse("loss=60"));
        assertThrows(IllegalArgumentException.class, () -> NetConditions.parse("delay=-5"));
    }
}
