package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sosgsgmp.session.PauseSync.Mode;

/**
 * SOS-229: the pause every PC shares (spec §4) during and after battles and loads. A PC as the test drives it: its
 * game's pause, the shared pause the host relays, and what {@link PauseSync} asks it to send and set.
 */
public class PauseSyncTest {

    private static final long FRAME = 16_000_000L;

    private long now = 1_000_000_000L;
    private final PauseSync sync = new PauseSync(now);
    private Mode mode = Mode.WORLD;
    private int loads;
    private boolean npcBattles;
    private boolean spectator;
    private boolean pausedHere;
    private boolean shared;
    private boolean heard;
    /** The last pause sent since the test set it to 0: 1 a pause, -1 an unpause, 0 nothing. */
    private int sent;

    private int frame() {
        now += FRAME;
        int act = sync.frame(mode, loads, npcBattles, spectator, pausedHere, shared, heard, now);
        heard = false;
        if ((act & PauseSync.SEND_PAUSE) != 0)
            sent = 1;
        if ((act & PauseSync.SEND_UNPAUSE) != 0)
            sent = -1;
        if ((act & PauseSync.PAUSE_HERE) != 0)
            pausedHere = true;
        if ((act & PauseSync.UNPAUSE_HERE) != 0)
            pausedHere = false;
        return act;
    }

    private void settle() {
        for (long end = now + PauseSync.SETTLE_NANOS + FRAME; now < end;)
            frame();
    }

    /** The host relays a pause or unpause to every PC. */
    private void hear(boolean paused) {
        shared = paused;
        heard = true;
    }

    @Test
    void onTheWorldAPressIsSentAndAHeardPauseIsSet() {
        settle();
        pausedHere = true; // the player's press
        frame();
        assertEquals(1, sent);
        hear(true);
        assertEquals(0, frame(), "its own pause, heard back: nothing more");
        assertTrue(pausedHere);
        sent = 0;
        hear(false); // another player unpaused
        frame();
        assertFalse(pausedHere);
        hear(true);
        frame();
        assertTrue(pausedHere);
        assertEquals(0, sent);
    }

    @Test
    void aSpectatorsPressIsUndoneAndNotSent() {
        spectator = true;
        settle();
        pausedHere = true;
        frame();
        assertEquals(0, sent);
        assertFalse(pausedHere);
        hear(true);
        frame();
        assertTrue(pausedHere, "a spectator follows the shared pause");
    }

    /**
     * The player's report: a pause pressed while another player fought a battle against NPCs wasn't sent, and stopped
     * only that PC. Now it is sent; the world runs on at 1x while the battle lasts (spec §4), and every PC pauses once it ends.
     */
    @Test
    void duringAnotherPlayersNpcBattleAPauseIsSentAndWaitsForItsEnd() {
        npcBattles = true;
        settle();
        pausedHere = true;
        frame();
        assertEquals(1, sent);
        assertFalse(pausedHere, "the world runs on meanwhile");
        hear(true);
        frame();
        assertFalse(pausedHere);
        npcBattles = false;
        frame();
        assertTrue(pausedHere, "the battle ended: the pause stops the game");
    }

    @Test
    void duringAnNpcBattleAPressTurnsAWaitingPauseOff() {
        npcBattles = true;
        settle();
        hear(true);
        frame();
        pausedHere = true;
        frame();
        assertEquals(-1, sent, "the shared pause waits, so the press unpauses it");
        assertFalse(pausedHere);
        hear(false);
        npcBattles = false;
        frame();
        assertFalse(pausedHere);
    }

    @Test
    void theFightingPlayersPauseIsTheirBattlesOwnAndAHeardPauseWaits() {
        npcBattles = true;
        mode = Mode.LOCAL_BATTLE;
        settle();
        pausedHere = true; // the battle's pause
        assertEquals(0, frame());
        assertEquals(0, sent);
        pausedHere = false;
        hear(true); // another player's
        assertEquals(0, frame());
        assertFalse(pausedHere);
        mode = Mode.WORLD; // the battle ends: back in the city, the engine sets the speed
        npcBattles = false;
        frame();
        assertFalse(pausedHere, "the speed settles first");
        settle();
        assertTrue(pausedHere, "then this PC comes back to the shared pause");
        assertEquals(0, sent);
    }

    @Test
    void inABattleEveryPcRunsThePauseIsSharedAndLoadingIsNoPress() {
        settle();
        mode = Mode.BATTLE_LOADING;
        pausedHere = true; // the battle's load
        assertEquals(0, frame());
        pausedHere = false;
        frame();
        mode = Mode.BATTLE;
        settle();
        assertEquals(0, sent, "the load's pause was no press");
        pausedHere = true;
        frame();
        assertEquals(1, sent, "a commander's pause is sent");
        hear(true);
        frame();
        hear(false);
        frame();
        assertFalse(pausedHere, "any player unpauses the battle");
        hear(true);
        frame();
        assertTrue(pausedHere, "another PC's pause pauses the battle here");
    }

    @Test
    void afterABattleOrALoadThePcComesBackToTheSharedPause() {
        settle();
        hear(true);
        frame();
        assertTrue(pausedHere);
        mode = Mode.BATTLE;
        settle();
        mode = Mode.WORLD;
        pausedHere = false; // the battle's end set the world's speed
        frame();
        assertEquals(0, sent, "not a press");
        settle();
        assertTrue(pausedHere);
        loads++;
        pausedHere = false; // a view's load set the agreed speed
        frame();
        settle();
        assertTrue(pausedHere);
        assertEquals(0, sent);
    }

    @Test
    void aPausePressedRightAfterABattleCountsOnceSettled() {
        mode = Mode.BATTLE;
        settle();
        mode = Mode.WORLD;
        frame();
        pausedHere = true; // pressed within the settle
        frame();
        assertEquals(0, sent);
        settle();
        assertEquals(1, sent, "a pause this PC holds alone, once settled, is the player's");
        assertTrue(pausedHere);
        hear(true);
        frame();
        assertTrue(pausedHere);
    }
}
