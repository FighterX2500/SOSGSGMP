package sosgsgmp.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LobbyTest {

    private static final LobbySettings THREE = new LobbySettings("normal", 3);

    @Test
    void theHostIsTheFirstPlayer() {
        Lobby lobby = new Lobby(THREE, "Ann");
        assertEquals(List.of(new LobbyPlayer(0, "Ann", true)), lobby.players());
    }

    @Test
    void startNeedsTwoPlayers() {
        Lobby lobby = new Lobby(THREE, "Ann");
        assertFalse(lobby.canStart());
        assertThrows(IllegalStateException.class, lobby::start);
        lobby.join("Bob");
        assertTrue(lobby.canStart());
        lobby.start();
        assertFalse(lobby.canStart());
        assertTrue(lobby.started());
    }

    @Test
    void joinsUpToTheLimitThenRefuses() {
        Lobby lobby = new Lobby(THREE, "Ann");
        assertEquals(1, lobby.join("Bob"));
        assertEquals(2, lobby.join("Cid"));
        assertEquals(Refusal.FULL, lobby.refusal());
        assertThrows(IllegalStateException.class, () -> lobby.join("Dee"));
    }

    @Test
    void refusesAfterStart() {
        Lobby lobby = new Lobby(THREE, "Ann");
        lobby.join("Bob");
        lobby.start();
        assertEquals(Refusal.STARTED, lobby.refusal());
    }

    @Test
    void leavingFreesASlotAndIdsAreNotReused() {
        Lobby lobby = new Lobby(THREE, "Ann");
        int bob = lobby.join("Bob");
        lobby.join("Cid");
        lobby.leave(bob);
        assertNull(lobby.refusal());
        assertEquals(3, lobby.join("Dee"));
        assertEquals(List.of("Ann", "Cid", "Dee"), lobby.players().stream().map(LobbyPlayer::name).toList());
    }

    @Test
    void theHostCantLeave() {
        Lobby lobby = new Lobby(THREE, "Ann");
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
