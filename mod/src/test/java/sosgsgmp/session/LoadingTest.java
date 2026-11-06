package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sosgsgmp.session.Loading.Kind;

/** The loading window on one PC (SOS-158): opened once, closed once, never longer than the ceiling. */
public class LoadingTest {

    private final Loading loading = new Loading();

    @Test
    void aWindowOpensOnceAndClosesWithItsTimes() {
        loading.open(Kind.VIEW_START, 3000, "Bob is opening Ann's city.", 1000);
        loading.open(Kind.VIEW_START, 3000, "again", 1100);
        assertEquals("Bob is opening Ann's city.", loading.shown().text(), "the open window stays as it opened");
        loading.frame(1016);
        loading.frame(8416); // this PC's own freeze: 7.4 s
        loading.frame(8432);
        loading.close(Kind.VIEW_START, 3000, 9000, "by the host");
        assertNull(loading.shown());
        Loading.Window opened = loading.takeOpened();
        assertEquals(Kind.VIEW_START, opened.kind());
        assertNull(loading.takeOpened(), "each opening is told once");
        Loading.Closed c = loading.takeClosed();
        assertEquals(8000, c.openMs());
        assertEquals(7400, c.longestFrameMs());
        assertEquals("by the host", c.how());
        assertNull(loading.takeClosed());
    }

    @Test
    void aClosedWindowNeverOpensAgain() {
        loading.open(Kind.BATTLE_START, 8190, "Ann's battle is loading.", 0);
        loading.close(Kind.BATTLE_START, 8190, 12000, "by the host");
        loading.open(Kind.BATTLE_START, 8190, "Ann's battle is loading.", 12016); // this PC still sees the step ahead
        assertNull(loading.shown());
        assertTrue(loading.wasClosed(Kind.BATTLE_START, 8190));
        assertFalse(loading.wasClosed(Kind.VIEW_START, 8190), "another kind at the same step is another window");
    }

    @Test
    void theHostsCloseBeforeThisPcSawTheFreezeKeepsItClosed() {
        loading.close(Kind.VIEW_END, 4200, 0, "by the host");
        assertNull(loading.takeClosed(), "nothing was open, so nothing closed");
        loading.open(Kind.VIEW_END, 4200, "Bob is returning.", 10);
        assertNull(loading.shown());
    }

    @Test
    void aNewerFreezeReplacesTheOpenWindow() {
        loading.open(Kind.VIEW_START, 3000, "first", 0);
        loading.open(Kind.BATTLE_START, 3030, "second", 500);
        assertEquals("second", loading.shown().text());
        assertEquals("replaced", loading.takeClosed().how());
        loading.open(Kind.VIEW_START, 3000, "first", 600);
        assertEquals("second", loading.shown().text(), "the replaced window counts as closed");
    }

    @Test
    void aWindowClosesItselfAtTheCeiling() {
        loading.open(Kind.WORLD_INITIALIZATION, 1, "Initializing the world…", 0);
        loading.frame(Loading.CEILING_MS - 1);
        assertEquals(Kind.WORLD_INITIALIZATION, loading.shown().kind());
        loading.frame(Loading.CEILING_MS);
        assertNull(loading.shown());
        assertTrue(loading.takeClosed().how().startsWith("after 120 s"));
        loading.close(Kind.WORLD_INITIALIZATION, 1, Loading.CEILING_MS + 50, "by the host"); // the host's late close
        assertNull(loading.takeClosed());
    }

    @Test
    void manyClosedWindowsForgetTheOldestOnly() {
        for (int i = 0; i < 100; i++) {
            loading.open(Kind.VIEW_START, i, "v", i);
            loading.close(Kind.VIEW_START, i, i, "by the host");
        }
        assertTrue(loading.wasClosed(Kind.VIEW_START, 99));
        assertTrue(loading.wasClosed(Kind.VIEW_START, 36));
        assertFalse(loading.wasClosed(Kind.VIEW_START, 0), "64 remembered: long past steps are never ahead again");
    }

    @Test
    void aNegativeKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> loading.open(Kind.VIEW_START, -1, "x", 0));
    }
}
