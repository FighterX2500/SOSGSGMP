package sosgsgmp.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.setup.FactionStep.PlayerPick;
import sosgsgmp.setup.FactionStep.PlayerProgress;
import sosgsgmp.setup.FactionStep.PlayerSlot;
import sosgsgmp.setup.FactionStep.Progress;

class FactionStepTest {

    /** The engine's rule as far as these tests need it: no closer than 6 tiles, and no location at x 0 (bad terrain). */
    private static final FactionStep.LocationRule RULE = (x, y, ox, oy) -> {
        if (x == 0)
            return "bad terrain";
        for (int i = 0; i < ox.length; i++)
            if (Math.hypot(x - ox[i], y - oy[i]) < 6)
                return "too close";
        return null;
    };

    private static FactionPick pick(String name, int x, int y) {
        return new FactionPick("HUMAN", name, "Ruler", new byte[] {1, 2}, x, y);
    }

    /** Ann (0) hosts; Bob (5) and Cid (9) joined in that order. */
    private static FactionStep host() {
        return FactionStep.host(0, List.of(5, 9), RULE);
    }

    /** Every player of the setup presses Ready. */
    private static void allReady(FactionStep h, int... ids) {
        for (int id : ids)
            assertTrue(h.onReady(id, true), "player " + id + " ready");
    }

    private static FactionStep initialized() {
        FactionStep h = host();
        h.onPick(0, pick("Ann", 10, 10));
        h.onPick(5, pick("Bob", 20, 10));
        h.onPick(9, pick("Cid", 10, 20));
        allReady(h, 0, 5, 9);
        h.initialize();
        return h;
    }

    @Test
    void aLocationTooCloseToAnotherIsClearedAndTheFirstWins() {
        FactionStep h = host();
        assertTrue(h.onPick(5, pick("Bob", 20, 10)));
        assertTrue(h.onPick(9, pick("Cid", 22, 12)));
        PlayerPick cid = h.table().get(1);
        assertEquals(9, cid.playerId());
        assertFalse(cid.pick().hasLocation());
        assertEquals("Cid", cid.pick().name());
        assertEquals("too close", cid.refused());
        assertTrue(h.table().get(0).pick().hasLocation(), "Bob picked first and keeps his location");

        assertTrue(h.onPick(9, pick("Cid", 0, 30)));
        assertEquals("bad terrain", h.table().get(1).refused());
        assertTrue(h.onPick(9, pick("Cid", 30, 10)));
        assertNull(h.table().get(1).refused());
    }

    @Test
    void thisPcsCheckCountsTheOthersButNotItsOwnLocation() {
        FactionStep h = host();
        h.onPick(0, pick("Ann", 10, 10));
        h.onPick(5, pick("Bob", 20, 10));
        assertNull(h.locationProblem(11, 11, RULE), "next to its own location: a move");
        assertEquals("too close", h.locationProblem(19, 10, RULE));

        FactionStep j = FactionStep.joiner(5);
        j.onPicks(h.table());
        assertNull(j.locationProblem(21, 10, RULE));
        assertEquals("too close", j.locationProblem(11, 10, RULE));
    }

    @Test
    void aRepeatedPickChangesNothingAndUnknownPlayersAreIgnored() {
        FactionStep h = host();
        assertTrue(h.onPick(5, pick("Bob", 20, 10)));
        assertFalse(h.onPick(5, pick("Bob", 20, 10)));
        assertFalse(h.onPick(7, pick("Eve", 40, 40)));
        assertEquals(1, h.table().size());
    }

    @Test
    void readyNeedsALocationAndHoldsThePicksUntilPressedAgain() {
        FactionStep h = host();
        assertFalse(h.onReady(5, true), "no picks yet");
        h.onPick(5, pick("Bob", FactionPick.NONE, FactionPick.NONE));
        assertFalse(h.onReady(5, true), "no location");
        h.onPick(5, pick("Bob", 20, 10));
        assertTrue(h.onReady(5, true));
        assertFalse(h.onReady(5, true), "nothing changes");
        assertFalse(h.onPick(5, pick("Bob", 30, 30)), "a ready player's city can't move");
        assertEquals(20, h.table().get(0).pick().x());
        assertTrue(h.table().get(0).ready());
        assertTrue(h.onReady(5, false));
        assertTrue(h.onPick(5, pick("Bob", 30, 30)), "pressed again, it moves");
        assertFalse(h.table().get(0).ready());
        assertFalse(h.onReady(7, true), "not in the setup");
    }

    @Test
    void theHostsOwnReadyCountsAndShowsOnItsPc() {
        FactionStep h = FactionStep.host(0, List.of(), RULE);
        assertFalse(h.canReady());
        h.onPick(0, pick("Ann", 10, 10));
        assertTrue(h.canReady());
        assertFalse(h.ready());
        assertFalse(h.canInitialize(), "the host is a player too");
        h.onReady(0, true);
        assertTrue(h.ready());
        assertTrue(h.canInitialize());
        h.onReady(0, false);
        assertFalse(h.ready());
        assertFalse(h.canInitialize(), "pressing Ready again takes it back");
    }

    @Test
    void aRefusedLocationEndsAJoinersOwnReady() {
        FactionStep j = FactionStep.joiner(5);
        j.onPicks(List.of(new PlayerPick(5, pick("Bob", 20, 10), null, false)));
        assertTrue(j.canReady());
        j.ownReady(true);
        assertTrue(j.onPicks(List.of(new PlayerPick(5, pick("Bob", 20, 10), null, true))));
        assertTrue(j.ready());
        j.ownReady(true);
        assertTrue(j.onPicks(List.of(new PlayerPick(5, pick("Bob", FactionPick.NONE, FactionPick.NONE), "too close", false))));
        assertFalse(j.ready());
        assertFalse(j.canReady());
    }

    @Test
    void initializeNeedsEveryoneReadyAndLocksThePicks() {
        FactionStep h = host();
        h.onPick(0, pick("Ann", 10, 10));
        h.onPick(5, pick("Bob", 20, 10));
        h.onPick(9, pick("Cid", 10, 20));
        assertFalse(h.canInitialize(), "every location, nobody ready");
        allReady(h, 0, 5);
        assertFalse(h.canInitialize(), "Cid isn't ready");
        assertThrows(IllegalStateException.class, h::initialize);
        allReady(h, 9);
        assertTrue(h.canInitialize());
        assertEquals(1, h.initialize());
        assertFalse(h.onReady(5, false), "the world is built");
        assertFalse(h.canInitialize());
        assertFalse(h.onPick(5, pick("Bob", 30, 30)), "picks are locked");
        assertThrows(IllegalStateException.class, () -> h.ownPick(pick("Ann", 40, 40)));
    }

    @Test
    void slotsFollowTheJoinOrderAfterTheHost() {
        FactionStep h = FactionStep.host(0, List.of(9, 5), RULE);
        h.onPick(5, pick("Bob", 20, 10));
        h.onPick(0, pick("Ann", 10, 10));
        h.onPick(9, pick("Cid", 10, 20));
        allReady(h, 0, 5, 9);
        h.initialize();
        assertEquals(List.of(new PlayerSlot(0, 0), new PlayerSlot(9, 1), new PlayerSlot(5, 2)), h.slotMap());
        assertEquals(List.of("Ann", "Cid", "Bob"), h.humans().stream().map(FactionPick::name).toList());
        assertEquals(2, h.slotOf(5));
    }

    @Test
    void theGameStartsWhenEveryPcLoadedTheSameWorldAndLanded() {
        FactionStep h = initialized();
        assertEquals(List.of(Progress.LOADING, Progress.LOADING, Progress.LOADING),
                h.progress().stream().map(PlayerProgress::progress).toList());
        assertTrue(h.onLoaded(5, 1, 77));
        assertEquals(Progress.LANDING, h.progress(5), "nothing to compare with before the host has loaded");
        h.loaded(77);
        assertTrue(h.onLoaded(9, 1, 77));
        assertFalse(h.onLoaded(9, 1, 77), "repeated");
        assertFalse(h.onLoaded(9, 2, 78), "another transfer");
        assertFalse(h.canGo());

        h.startSpot();
        assertTrue(h.onStartSpot(5, 1));
        assertFalse(h.onStartSpot(5, 1));
        assertFalse(h.canGo());
        assertTrue(h.onStartSpot(9, 1));
        assertTrue(h.canGo());
        h.go();
        assertTrue(h.started());
        assertFalse(h.canGo());
    }

    @Test
    void aWorldThatDiffersBlocksTheStart() {
        FactionStep h = initialized();
        h.loaded(77);
        h.onLoaded(5, 1, 77);
        h.onLoaded(9, 1, 78);
        assertEquals(Progress.MISMATCH, h.progress(9));
        h.startSpot();
        h.onStartSpot(5, 1);
        h.onStartSpot(9, 1);
        assertFalse(h.canGo());
    }

    @Test
    void aStartSpotBeforeTheLoadIsIgnored() {
        FactionStep h = initialized();
        assertFalse(h.onStartSpot(5, 1));
        assertThrows(IllegalStateException.class, h::startSpot);
    }

    @Test
    void aPlayerLeavingBeforeTheBuildIsDroppedAndCanUnblockInitialize() {
        FactionStep h = host();
        h.onPick(0, pick("Ann", 10, 10));
        h.onPick(5, pick("Bob", 20, 10));
        assertTrue(h.remove(9));
        assertFalse(h.remove(9));
        assertFalse(h.remove(0), "the host can't be removed");
        allReady(h, 0, 5);
        assertTrue(h.canInitialize());
        h.initialize();
        assertEquals(2, h.slotMap().size());
    }

    @Test
    void aJoinerFollowsTheHostsTablesOnce() {
        FactionStep j = FactionStep.joiner(5);
        List<PlayerPick> table = List.of(new PlayerPick(0, pick("Ann", 10, 10), null, false), new PlayerPick(5, pick("Bob", 20, 10), null, false));
        assertTrue(j.onPicks(table));
        assertFalse(j.onPicks(table));
        assertFalse(j.onSlotMap(1, List.of(new PlayerSlot(0, 0))), "no slot for this PC");
        assertTrue(j.onSlotMap(1, List.of(new PlayerSlot(0, 0), new PlayerSlot(5, 1))));
        assertFalse(j.onSlotMap(1, List.of(new PlayerSlot(0, 0), new PlayerSlot(5, 1))), "repeated");
        assertEquals(1, j.slotOf(5));
        assertFalse(j.onPicks(List.of()), "the picks are locked once the world is built");

        assertFalse(j.onProgress(2, List.of(new PlayerProgress(0, Progress.READY))), "another transfer");
        assertTrue(j.onProgress(1, List.of(new PlayerProgress(0, Progress.READY), new PlayerProgress(5, Progress.LANDING))));
        assertEquals(Progress.READY, j.progress(0));
        assertFalse(j.onGo(2));
        assertTrue(j.onGo(1));
        assertFalse(j.onGo(1));
        assertTrue(j.started());
    }

    @Test
    void eachSideRefusesTheOthersCalls() {
        FactionStep j = FactionStep.joiner(5);
        assertThrows(IllegalStateException.class, () -> j.onPick(5, pick("Bob", 1, 1)));
        assertThrows(IllegalStateException.class, () -> host().onPicks(List.of()));
    }
}
