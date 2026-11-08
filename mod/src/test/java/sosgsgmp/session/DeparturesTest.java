package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DeparturesTest {

    private static final Departures.Gone BOB = new Departures.Gone(1, "Bob", 1);
    private static final Departures.Gone CID = new Departures.Gone(2, "Cid", 2);
    private static final Departures.Gone DAN = new Departures.Gone(3, "Dan", 5);

    @Test
    void aSecondDropJoinsTheOpenHold() {
        Departures d = new Departures();
        assertFalse(d.waiting());
        assertEquals(-1, d.holdStep());
        assertEquals(130, d.left(BOB, 100));
        assertEquals(130, d.left(CID, 120), "the hold stays where it is");
        assertEquals(130, d.left(BOB, 125), "a repeat changes nothing");
        assertEquals(List.of(BOB, CID), d.gone());
        assertTrue(d.waiting());
        assertEquals("Bob and Cid", d.names());
        d.left(DAN, 126);
        assertEquals("Bob, Cid and Dan", d.names());
    }

    @Test
    void theChoiceComesOnceAndOnlyForTheOpenHold() {
        Departures d = new Departures();
        assertFalse(d.chosen(130, Departures.Choice.CONTINUE), "no hold open");
        d.heard(BOB, 130);
        assertFalse(d.heard(BOB, 130), "a repeated PlayerGone");
        assertThrows(IllegalStateException.class, () -> d.heard(CID, 160), "a second hold while one is open");
        assertFalse(d.chosen(129, Departures.Choice.CONTINUE), "another hold's choice");
        assertTrue(d.chosen(130, Departures.Choice.CONTINUE));
        assertFalse(d.chosen(130, Departures.Choice.RETURN), "chosen already");
        assertFalse(d.waiting());
        assertEquals(Departures.Choice.CONTINUE, d.choice());
    }

    @Test
    void afterContinueALaterDropOpensANewHold() {
        Departures d = new Departures();
        d.left(BOB, 100);
        assertThrows(IllegalStateException.class, d::continued, "before the host's Continue");
        d.chosen(130, Departures.Choice.CONTINUE);
        d.continued();
        assertEquals(List.of(BOB), d.removed());
        assertEquals(List.of(), d.gone());
        assertNull(d.choice());
        assertEquals(-1, d.holdStep());
        assertEquals(930, d.left(CID, 900));
        assertTrue(d.waiting());
    }

    @Test
    void nobodyLeavesAfterReturn() {
        Departures d = new Departures();
        d.left(BOB, 100);
        d.chosen(130, Departures.Choice.RETURN);
        assertThrows(IllegalStateException.class, () -> d.left(CID, 131));
    }

    /** SOS-177: a lost player holds every PC; back before the choice, the hold ends by itself. */
    @Test
    void aLostPlayerBackBeforeTheChoiceEndsTheHold() {
        Departures d = new Departures();
        assertEquals(130, d.lost(BOB, 100, 7_000));
        assertTrue(d.waiting());
        assertTrue(d.lost(1));
        assertEquals(7_000, d.lostSince(1));
        assertEquals(130, d.lost(BOB, 110, 9_000), "still lost: nothing changes");
        assertEquals(7_000, d.lostSince(1));
        assertTrue(d.back(1));
        assertFalse(d.waiting());
        assertEquals(-1, d.holdStep());
        assertEquals(List.of(), d.gone());
        assertFalse(d.back(1), "back once");
        assertEquals(List.of(), d.removed(), "nobody removed");
    }

    @Test
    void aLostPlayerBackLeavesTheHoldToThoseWhoLeft() {
        Departures d = new Departures();
        d.left(CID, 100);
        assertEquals(130, d.lost(BOB, 110, 5));
        assertFalse(d.lost(2), "Cid left, not lost");
        assertTrue(d.back(1));
        assertTrue(d.waiting(), "Cid's hold stays for the host's choice");
        assertEquals(List.of(CID), d.gone());
        assertFalse(d.back(2), "a player who left can't come back");
    }

    @Test
    void aLostPlayerWhoseConnectionClosesHasLeft() {
        Departures host = new Departures();
        host.lost(BOB, 100, 5);
        assertEquals(130, host.left(BOB, 120));
        assertFalse(host.lost(1));
        assertFalse(host.back(1));
        assertEquals(List.of(BOB), host.gone());

        Departures joiner = new Departures();
        assertTrue(joiner.heardLost(BOB, 130, 5));
        assertFalse(joiner.heardLost(BOB, 130, 6), "a repeated PlayerLost");
        assertTrue(joiner.heard(BOB, 130), "the PlayerGone that follows is news");
        assertFalse(joiner.heard(BOB, 130), "once");
        assertFalse(joiner.lost(1));
    }

    @Test
    void backAfterTheChoiceChangesNothingAndContinueRemovesTheLost() {
        Departures d = new Departures();
        assertTrue(d.heardLost(BOB, 130, 5));
        assertFalse(d.heardBack(1, 129), "another hold's PlayerBack");
        assertTrue(d.chosen(130, Departures.Choice.CONTINUE));
        assertFalse(d.heardBack(1, 130), "the host chose first");
        d.continued();
        assertEquals(List.of(BOB), d.removed());
        assertFalse(d.lost(1));
        assertEquals(-1, d.lostSince(1));
    }
}
