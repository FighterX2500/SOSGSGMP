package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class DelayLineTest {

    /** Every item out by {@code untilMs}, with the time each left, polling each millisecond from {@code fromMs}. */
    private static List<long[]> drain(DelayLine<Integer> line, long fromMs, long untilMs) {
        List<long[]> out = new ArrayList<>();
        for (long t = fromMs; t <= untilMs; t++) {
            Integer i;
            while ((i = line.poll(t)) != null)
                out.add(new long[] {i, t});
        }
        return out;
    }

    @Test
    void anItemLeavesAfterItsDelayAndNotBefore() {
        DelayLine<Integer> line = new DelayLine<>(75, 0, 0, new Random(1));
        line.add(7, 1000);
        assertNull(line.poll(1074));
        assertEquals(7, line.poll(1075));
        assertNull(line.poll(1075));
    }

    @Test
    void jitterKeepsTheOrderAndStaysWithinItsBounds() {
        DelayLine<Integer> line = new DelayLine<>(100, 80, 0, new Random(42));
        for (int i = 0; i < 500; i++)
            line.add(i, 1000 + i);
        List<long[]> out = drain(line, 1000, 3000);
        assertEquals(500, out.size());
        for (int i = 0; i < out.size(); i++) {
            assertEquals(i, out.get(i)[0], "in order");
            long wait = out.get(i)[1] - (1000 + i);
            assertTrue(wait >= 20 && wait <= 180 + 80, "item " + i + " waited " + wait + " ms"); // 100 +/- 80, or behind one ahead
        }
        assertTrue(line.maxAddedMs() <= 260);
    }

    @Test
    void aLostItemWaitsForItsResendAndHoldsTheOnesBehindIt() {
        DelayLine<Integer> line = new DelayLine<>(50, 0, 100, new Random(1)); // every item lost
        line.add(1, 0);
        line.add(2, 10);
        long resend = Math.max(DelayLine.MIN_RESEND_MS, 4 * 50);
        assertNull(line.poll(50 + resend - 1));
        assertEquals(1, line.poll(50 + resend));
        assertEquals(2, line.lost());
    }

    @Test
    void anOutageHoldsEverythingThenReleasesItAtOnce() {
        DelayLine<Integer> line = new DelayLine<>(10, 0, 0, new Random(1));
        line.holdUntil(5000);
        for (int i = 0; i < 3; i++)
            line.add(i, 100 + i);
        assertTrue(line.held(4999));
        assertNull(line.poll(4999));
        List<long[]> out = drain(line, 5000, 5000);
        assertEquals(3, out.size(), "all due at the outage's end");
        line.holdUntil(1000); // a shorter one never shortens it
        assertTrue(!line.held(5000));
    }

    @Test
    void noConditionsIsNoWait() {
        DelayLine<Integer> line = new DelayLine<>(0, 0, 0, new Random(1));
        line.add(1, 500);
        assertEquals(1, line.poll(500));
        assertEquals(0, line.meanAddedMs());
    }

    @Test
    void badSettingsFail() {
        assertThrows(IllegalArgumentException.class, () -> new DelayLine<Integer>(-1, 0, 0, new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> new DelayLine<Integer>(0, 0, 101, new Random(1)));
    }
}
