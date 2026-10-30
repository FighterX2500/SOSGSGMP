package sosgsgmp.setup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FactionPickTest {

    @Test
    void badValuesAreRefused() {
        byte[] b = new byte[0];
        assertThrows(IllegalArgumentException.class, () -> new FactionPick("", "Ann", "Ruler", b, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FactionPick("HUMAN", " ", "Ruler", b, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FactionPick("HUMAN", "x".repeat(FactionPick.MAX_NAME + 1), "Ruler", b, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FactionPick("HUMAN", "Ann", "Ruler", new byte[FactionPick.MAX_BANNER + 1], 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FactionPick("HUMAN", "Ann", "x".repeat(FactionPick.MAX_NAME + 1), b, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FactionPick("HUMAN", "Ann", null, b, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FactionPick("HUMAN", "Ann", "Ruler", b, FactionPick.NONE, 1));
        assertThrows(IllegalArgumentException.class, () -> new FactionPick("HUMAN", "Ann", "Ruler", b, -2, -2));
    }

    @Test
    void theBannerIsCopiedAndComparedByValue() {
        byte[] b = {1, 2, 3};
        FactionPick p = new FactionPick("HUMAN", "Ann", "Ruler", b, 4, 5);
        b[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, p.banner());
        assertEquals(p, new FactionPick("HUMAN", "Ann", "Ruler", new byte[] {1, 2, 3}, 4, 5));
        assertFalse(p.at(FactionPick.NONE, FactionPick.NONE).hasLocation());
    }
}
