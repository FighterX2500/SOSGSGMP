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
        assertTrue(c.own(true, 1, 1, 0), "the first goes out");
        assertFalse(c.own(true, 1, 1, 500 * MS), "unchanged");
        assertTrue(c.own(true, 2, 1, 600 * MS));
        assertFalse(c.own(true, 3, 1, 650 * MS), "50 ms after the last");
        assertTrue(c.own(true, 3, 1, 700 * MS));
    }

    @Test
    void leavingTheMapGoesOutAtOnceAndOnlyOnce() {
        Cursors c = new Cursors();
        assertTrue(c.own(true, 1, 1, 0));
        assertTrue(c.own(false, 0, 0, 1 * MS));
        assertFalse(c.own(false, 9, 9, 500 * MS), "off the map, the position doesn't matter");
        assertTrue(c.own(true, 1, 1, 501 * MS), "back on it");
    }

    @Test
    void othersAreKeptByIdAndLeaveWithTheirPlayer() {
        Cursors c = new Cursors();
        c.set(4, true, 10, 20);
        c.set(9, true, 30, 40);
        c.set(4, true, 11, 21);
        assertEquals(2, c.count());
        assertEquals(4, c.playerId(0));
        assertEquals(11, c.x(0));
        assertEquals(21, c.y(0));
        c.set(4, false, 0, 0);
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
            c.set(i, true, i, i);
        assertThrows(IllegalStateException.class, () -> c.set(99, true, 0, 0));
    }
}
