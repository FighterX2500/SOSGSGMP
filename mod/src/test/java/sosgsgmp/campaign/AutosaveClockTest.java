package sosgsgmp.campaign;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SOS-229: the host's autosave interval counts only real time in which the world ran, unpaused. */
public class AutosaveClockTest {

    private static final long MINUTE = 60_000;
    private static final long FRAME = 16;

    private final AutosaveClock clock = new AutosaveClock();
    private long now = 1_000_000;
    private long step = 500;

    /** {@code ms} of frames, the step moving each frame when {@code moving}. */
    private void run(long ms, boolean moving, boolean paused) {
        for (long end = now + ms; now < end; now += FRAME) {
            if (moving)
                step++;
            clock.frame(step, paused, now);
        }
    }

    @Test
    void aRunningWorldCountsRealTime() {
        run(MINUTE - 1_000, true, false);
        assertFalse(clock.elapsed(MINUTE));
        run(2_000, true, false);
        assertTrue(clock.elapsed(MINUTE));
    }

    /**
     * The player's report: the world stood still for hours while the host's game speed stayed above 0 (a PC paused on
     * its own, or the session's pause missed on the host). The old count, real time while the host's speed was above 0,
     * reached the interval here; this one doesn't.
     */
    @Test
    void aWorldThatStandsStillCountsNothingWhateverTheHostsSpeed() {
        run(10_000, true, false);
        run(15 * MINUTE, false, false);
        assertFalse(clock.elapsed(MINUTE));
    }

    @Test
    void theSessionsPauseCountsNothingEvenIfStepsStillRun() {
        run(15 * MINUTE, true, true);
        assertFalse(clock.elapsed(MINUTE), "a host catching up to its pause step doesn't count");
    }

    @Test
    void theFirstStepAfterAStandStillCountsAtMostASecond() {
        run(1_000, true, false);
        run(15 * MINUTE, false, false);
        step++;
        clock.frame(step, false, now);
        assertFalse(clock.elapsed(2_100), "1 s of running, the step after 15 minutes at most 1 s more");
        assertTrue(clock.elapsed(1_900));
    }

    @Test
    void afterAPauseTheFirstStepCountsNothing() {
        run(1_000, true, false);
        run(MINUTE, false, true);
        step++;
        clock.frame(step, false, now + 500);
        assertFalse(clock.elapsed(1_100));
    }

    @Test
    void restartCountsFromNothing() {
        run(2 * MINUTE, true, false);
        clock.restart();
        assertFalse(clock.elapsed(MINUTE));
        run(MINUTE + 100, true, false);
        assertTrue(clock.elapsed(MINUTE));
    }

    /** The owner's safeguard (2026-11-18): an autosave of a world still at the last campaign save's step is skipped. */
    @Test
    void aWorldStillAtTheLastSavesStepHasNotMoved() {
        run(1_000, true, false);
        long saved = step;
        assertFalse(clock.movedSince(saved));
        run(15 * MINUTE, false, false);
        assertFalse(clock.movedSince(saved));
        run(100, true, false);
        assertTrue(clock.movedSince(saved));
        assertTrue(clock.movedSince(-1), "before the first save");
    }
}
