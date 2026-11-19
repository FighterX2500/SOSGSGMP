package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.session.RestartTally.Outcome;

/**
 * The rules of a vote to restart a battle (spec §8, SOS-231): more than half of every player in the session says Yes; a
 * tie fails; no answer in 30 seconds counts as No; the caller votes Yes.
 */
class RestartTallyTest {

    private static final long T0 = 1_000_000;

    @Test
    void theCallerVotesYesAndAMajorityPassesAtOnce() {
        RestartTally t = new RestartTally(1, 0, 77, List.of(0, 1, 2), T0);
        assertEquals(1, t.yes());
        assertEquals(Outcome.OPEN, t.outcome(T0));
        assertTrue(t.answer(2, true));
        assertEquals(Outcome.PASSED, t.outcome(T0 + 1), "2 of 3, before the third answers");
        assertEquals(77, t.battle());
    }

    @Test
    void aTieFails() {
        RestartTally t = new RestartTally(1, 0, 77, List.of(0, 1), T0);
        assertEquals(Outcome.OPEN, t.outcome(T0));
        assertTrue(t.answer(1, false));
        assertEquals(Outcome.FAILED, t.outcome(T0 + 1), "1 to 1");

        RestartTally four = new RestartTally(2, 0, 77, List.of(0, 1, 2, 3), T0);
        four.answer(1, true);
        four.answer(2, false);
        assertEquals(Outcome.OPEN, four.outcome(T0), "the last voter decides");
        four.answer(3, false);
        assertEquals(Outcome.FAILED, four.outcome(T0), "2 to 2");
    }

    @Test
    void itFailsAsSoonAsAMajorityCantBeReached() {
        RestartTally t = new RestartTally(1, 0, 77, List.of(0, 1, 2, 3, 4), T0);
        t.answer(1, false);
        t.answer(2, false);
        assertEquals(Outcome.OPEN, t.outcome(T0));
        t.answer(3, false);
        assertEquals(Outcome.FAILED, t.outcome(T0), "at most 2 of 5 now");
    }

    @Test
    void noAnswerInThirtySecondsCountsAsNo() {
        RestartTally t = new RestartTally(1, 0, 77, List.of(0, 1, 2), T0);
        assertEquals(Outcome.OPEN, t.outcome(T0 + RestartTally.ANSWER_MS - 1));
        assertEquals(1, t.leftMs(T0 + RestartTally.ANSWER_MS - 1));
        assertEquals(Outcome.FAILED, t.outcome(T0 + RestartTally.ANSWER_MS), "1 of 3: the silent count as No");
        assertEquals(0, t.leftMs(T0 + 2 * RestartTally.ANSWER_MS));

        RestartTally alone = new RestartTally(2, 5, 77, List.of(5), T0);
        assertEquals(Outcome.PASSED, alone.outcome(T0), "a player alone in the session decides alone");
    }

    @Test
    void theFirstAnswerStandsAndOnlyVotersAnswer() {
        RestartTally t = new RestartTally(1, 0, 77, List.of(0, 1, 2), T0);
        assertFalse(t.answer(0, false), "the caller voted Yes by calling");
        assertTrue(t.answer(1, false));
        assertFalse(t.answer(1, true), "no change of mind");
        assertFalse(t.answer(9, true), "not in the session when it opened");
        assertEquals(1, t.yes());
        assertEquals(1, t.no());
        assertTrue(t.waitsFor(2));
        assertFalse(t.waitsFor(1));
    }

    @Test
    void aPlayerWhoLeftNoLongerCounts() {
        RestartTally t = new RestartTally(1, 0, 77, List.of(0, 1, 2, 3), T0);
        t.answer(1, true);
        assertEquals(Outcome.OPEN, t.outcome(T0), "2 of 4 is a tie so far");
        t.keepOnly(List.of(0, 1, 2));
        assertEquals(3, t.voters());
        assertEquals(Outcome.PASSED, t.outcome(T0), "2 of the 3 still here");
    }

    @Test
    void theCallerMustBeInTheSession() {
        assertThrows(IllegalArgumentException.class, () -> new RestartTally(1, 9, 77, List.of(0, 1), T0));
    }
}
