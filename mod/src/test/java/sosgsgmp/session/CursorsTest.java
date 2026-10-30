package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;

class CursorsTest {

    private static final long MS = 1_000_000L;

    @Test
    void aMovingCursorGoesOutAtMostTenTimesASecond() {
        Cursors c = new Cursors();
        assertTrue(c.own(Cursors.WORLD, 1, 1, 0), "the first goes out");
        assertFalse(c.own(Cursors.WORLD, 1, 1, 500 * MS), "unchanged");
        assertTrue(c.own(Cursors.WORLD, 2, 1, 600 * MS));
        assertFalse(c.own(Cursors.WORLD, 3, 1, 650 * MS), "50 ms after the last");
        assertTrue(c.own(Cursors.WORLD, 3, 1, 700 * MS));
    }

    @Test
    void leavingTheMapGoesOutAtOnceAndOnlyOnce() {
        Cursors c = new Cursors();
        assertTrue(c.own(Cursors.WORLD, 1, 1, 0));
        assertTrue(c.own(Cursors.OFF, 0, 0, 1 * MS));
        assertFalse(c.own(Cursors.OFF, 9, 9, 500 * MS), "off the map, the position doesn't matter");
        assertTrue(c.own(Cursors.WORLD, 1, 1, 501 * MS), "back on it");
    }

    @Test
    void othersAreKeptByIdAndLeaveWithTheirPlayer() {
        Cursors c = new Cursors();
        c.set(4, Cursors.WORLD, 10, 20);
        c.set(9, Cursors.WORLD, 30, 40);
        c.set(4, Cursors.WORLD, 11, 21);
        assertEquals(2, c.count());
        assertEquals(4, c.playerId(0));
        assertEquals(11, c.x(0));
        assertEquals(21, c.y(0));
        c.set(4, Cursors.OFF, 0, 0);
        assertEquals(1, c.count());
        assertEquals(9, c.playerId(0));
        c.retain(List.of(new LobbyPlayer(0, "Ann", true)));
        assertEquals(0, c.count());
        assertThrows(IndexOutOfBoundsException.class, () -> c.x(0));
    }

    @Test
    void moreCursorsThanPlayersIsABug() {
        Cursors c = new Cursors();
        for (int i = 0; i < LobbySettings.MAX_PLAYERS; i++)
            c.set(i, Cursors.WORLD, i, i);
        assertThrows(IllegalStateException.class, () -> c.set(99, Cursors.WORLD, 0, 0));
    }

    /** SOS-58: a cursor in a city is on that city's place; moving to another place goes out at once. */
    @Test
    void aCursorKeepsItsPlaceAndAMoveToAnotherGoesOutAtOnce() {
        Cursors c = new Cursors();
        assertTrue(c.own(Cursors.WORLD, 1, 1, 0));
        assertTrue(c.own(Cursors.city(2), 1, 1, 10 * MS), "into a city, at once though only 10 ms later");
        assertFalse(c.own(Cursors.city(2), 2, 1, 20 * MS), "moving in it: the rate limit");
        c.set(5, Cursors.city(0), 7, 8);
        c.set(6, Cursors.WORLD, 1, 2);
        assertEquals(Cursors.city(0), c.place(0));
        assertEquals(Cursors.WORLD, c.place(1));
        c.set(5, Cursors.city(3), 9, 9);
        assertEquals(Cursors.city(3), c.place(0), "a viewer moving to another city");
        assertThrows(IllegalArgumentException.class, () -> Cursors.city(-1));
        assertThrows(IllegalArgumentException.class, () -> c.own(-2, 0, 0, 30 * MS));
    }
}
