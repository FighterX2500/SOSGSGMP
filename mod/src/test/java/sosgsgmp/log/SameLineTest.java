package sosgsgmp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SameLineTest {

    @Test
    void aMarkAKeyAndTheText() {
        assertEquals("= 123450 world: stance: Ann (1) and Bo (2), neutral to war",
                SameLine.atStep(SameLine.CONFIRMED, 123450, "world: stance: Ann (1) and Bo (2), neutral to war"));
        assertEquals("~ d812 world: news: The Ann declares war on Bo", SameLine.onDay(SameLine.CANDIDATE, 812, "world: news: The Ann declares war on Bo"));
    }

    @Test
    void oneLineOnlyAndAKnownMark() {
        assertThrows(IllegalArgumentException.class, () -> SameLine.atStep(SameLine.CONFIRMED, 1, "a\nb"));
        assertThrows(IllegalArgumentException.class, () -> SameLine.atStep('#', 1, "a"));
    }
}
