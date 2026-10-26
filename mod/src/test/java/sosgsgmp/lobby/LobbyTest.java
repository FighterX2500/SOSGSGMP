package sosgsgmp.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.campaign.Campaign;

class LobbyTest {

    private static final LobbySettings THREE = new LobbySettings("normal", 3);

    /** Ann in slot 0, Bob in slot 1, Cid in slot 2 removed earlier (SOS-65). */
    private static final Campaign THREE_PLAYED = new Campaign(7, "Ann's campaign", 1, "normal",
            List.of(new Campaign.Player(1, "Ann", 0), new Campaign.Player(2, "Bob", 1), new Campaign.Player(3, "Cid", 2, true)));

    @Test
    void aResumeStartsWithoutMissingPlayersButNeverWithoutSlot0() {
        Lobby lobby = new Lobby(LobbySettings.resuming(new LobbySettings.Resume(THREE_PLAYED, 4)), "Ann", 1);
        assertEquals(List.of(THREE_PLAYED.players().get(1)), lobby.missing(), "Cid is removed: nobody waits for them");
        assertTrue(lobby.canStart(), "Bob is removed at the start");
        assertEquals(Refusal.REMOVED, lobby.refusal(3));
        assertNull(lobby.refusal(2));
        lobby.join("Bob", 2);
        assertEquals(List.of(), lobby.missing());

        Lobby bobs = new Lobby(LobbySettings.resuming(new LobbySettings.Resume(THREE_PLAYED, 4)), "Bob", 2);
        assertFalse(bobs.canStart(), "Ann's slot 0 can't be removed");
        bobs.join("Ann", 1);
        assertTrue(bobs.canStart());
        assertThrows(IllegalArgumentException.class,
                () -> new Lobby(LobbySettings.resuming(new LobbySettings.Resume(THREE_PLAYED, 4)), "Cid", 3), "a removed host");
    }

    @Test
    void theHostIsTheFirstPlayer() {
        Lobby lobby = new Lobby(THREE, "Ann", 1);
        assertEquals(List.of(new LobbyPlayer(0, "Ann", true)), lobby.players());
    }

    @Test
    void startNeedsTwoPlayers() {
        Lobby lobby = new Lobby(THREE, "Ann", 1);
        assertFalse(lobby.canStart());
        assertThrows(IllegalStateException.class, lobby::start);
        lobby.join("Bob", 2);
        assertTrue(lobby.canStart());
        lobby.start();
        assertFalse(lobby.canStart());
        assertTrue(lobby.started());
    }

    @Test
    void joinsUpToTheLimitThenRefuses() {
        Lobby lobby = new Lobby(THREE, "Ann", 1);
        assertEquals(1, lobby.join("Bob", 2));
        assertEquals(2, lobby.join("Cid", 3));
        assertEquals(Refusal.FULL, lobby.refusal(9));
        assertThrows(IllegalStateException.class, () -> lobby.join("Dee", 4));
    }

    @Test
    void refusesAfterStart() {
        Lobby lobby = new Lobby(THREE, "Ann", 1);
        lobby.join("Bob", 2);
        lobby.start();
        assertEquals(Refusal.STARTED, lobby.refusal(9));
    }

    @Test
    void leavingFreesASlotAndIdsAreNotReused() {
        Lobby lobby = new Lobby(THREE, "Ann", 1);
        int bob = lobby.join("Bob", 2);
        lobby.join("Cid", 3);
        lobby.leave(bob);
        assertNull(lobby.refusal(9));
        assertEquals(3, lobby.join("Dee", 4));
        assertEquals(List.of("Ann", "Cid", "Dee"), lobby.players().stream().map(LobbyPlayer::name).toList());
    }

    @Test
    void theHostCantLeave() {
        Lobby lobby = new Lobby(THREE, "Ann", 1);
        assertThrows(IllegalArgumentException.class, () -> lobby.leave(0));
    }

    @Test
    void namesAreTrimmedCutAndNeverEmpty() {
        assertEquals("Ann", Lobby.cleanName("  Ann "));
        assertEquals("Player", Lobby.cleanName("   "));
        assertEquals("Player", Lobby.cleanName(null));
        assertEquals(Lobby.MAX_NAME, Lobby.cleanName("x".repeat(40)).length());
    }

    @Test
    void settingsCheckThePlayerLimit() {
        assertThrows(IllegalArgumentException.class, () -> new LobbySettings("normal", 1));
        assertThrows(IllegalArgumentException.class, () -> new LobbySettings("normal", 17));
        assertThrows(IllegalArgumentException.class, () -> new LobbySettings("", 4));
    }
}
