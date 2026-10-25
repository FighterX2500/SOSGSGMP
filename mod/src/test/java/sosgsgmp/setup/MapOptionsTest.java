package sosgsgmp.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MapOptionsTest {

    @Test
    void theLimitsAreChecked() {
        new MapOptions(128, "", 0, 0);
        new MapOptions(512, "x".repeat(64), 100, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> new MapOptions(127, "", 50, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapOptions(513, "", 50, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapOptions(256, null, 50, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapOptions(256, "x".repeat(65), 50, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapOptions(256, "", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapOptions(256, "", 101, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapOptions(256, "", 50, -1));
    }

    @Test
    void randomIsTheEmptyMapType() {
        assertTrue(new MapOptions(256, MapOptions.RANDOM, 50, 1).isRandom());
        assertFalse(new MapOptions(256, "Continent", 50, 1).isRandom());
    }

    @Test
    void presetsOnlyAtVanillasSize() {
        assertTrue(MapOptions.presetsAllowed(256));
        assertFalse(MapOptions.presetsAllowed(128));
        assertFalse(MapOptions.presetsAllowed(512));
    }

    @Test
    void sizesStepThroughTheOfferedOnes() {
        assertEquals(384, MapOptions.stepSize(256, 1));
        assertEquals(192, MapOptions.stepSize(256, -1));
        assertEquals(128, MapOptions.stepSize(128, -1));
        assertEquals(512, MapOptions.stepSize(512, 1));
        assertEquals(256, MapOptions.stepSize(200, 1));
        assertEquals(192, MapOptions.stepSize(200, -1));
        assertThrows(IllegalArgumentException.class, () -> MapOptions.stepSize(256, 2));
        assertTrue(MapOptions.isOfferedSize(MapOptions.DEFAULT_SIZE));
        assertTrue(MapOptions.isOfferedSize(192));
        assertFalse(MapOptions.isOfferedSize(320));
    }

    @Test
    void seedsAreChecked() {
        assertEquals(0, MapOptions.parseSeed("0"));
        assertEquals(Integer.MAX_VALUE, MapOptions.parseSeed(" 2147483647 "));
        assertEquals(-1, MapOptions.parseSeed("2147483648"));
        assertEquals(-1, MapOptions.parseSeed("99999999999"));
        assertEquals(-1, MapOptions.parseSeed(""));
        assertEquals(-1, MapOptions.parseSeed("-5"));
        assertEquals(-1, MapOptions.parseSeed("12a"));
    }
}
