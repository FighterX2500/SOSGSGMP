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
}
