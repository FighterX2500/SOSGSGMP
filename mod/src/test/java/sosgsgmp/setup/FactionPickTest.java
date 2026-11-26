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

    /** Spec §5 Titles: at most 5 titles, each once, each with its race count; kept by a new location. */
    @Test
    void titlesAreCheckedCopiedAndKept() {
        byte[] b = new byte[0];
        int[] titles = {3, 9};
        FactionPick p = new FactionPick("HUMAN", "Ann", "Ruler", b, 4, 5, titles, new int[] {2, 0});
        titles[0] = 1;
        assertArrayEquals(new int[] {3, 9}, p.titles());
        assertArrayEquals(new int[] {3, 9}, p.at(FactionPick.NONE, FactionPick.NONE).titles());
        assertArrayEquals(new int[] {2, 0}, p.at(1, 1).titleRaces());
        assertFalse(p.equals(new FactionPick("HUMAN", "Ann", "Ruler", b, 4, 5, new int[] {3, 9}, new int[] {2, 1})));
        assertEquals(0, new FactionPick("HUMAN", "Ann", "Ruler", b, 4, 5).titles().length);
        assertThrows(IllegalArgumentException.class, () -> p.withTitles(new int[] {1, 2, 3, 4, 5, 6}, new int[6]));
        assertThrows(IllegalArgumentException.class, () -> p.withTitles(new int[] {1, 1}, new int[2]));
        assertThrows(IllegalArgumentException.class, () -> p.withTitles(new int[] {1}, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> p.withTitles(new int[] {-1}, new int[1]));
        assertThrows(IllegalArgumentException.class, () -> p.withTitles(new int[] {1}, new int[] {256}));
    }
}
