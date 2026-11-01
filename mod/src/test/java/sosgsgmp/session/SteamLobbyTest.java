package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosgsgmp.lobby.Builds;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.net.FakeSteam;
import sosgsgmp.net.SteamNode;

/** The lobby over Steam P2P (an in-memory Steam): the same sessions as over direct IP, only the node differs. */
class SteamLobbyTest {

    private static final Builds BUILDS = new Builds("0.71.44", 1, 2);

    private final FakeSteam steam = new FakeSteam();
    private final List<LobbySession> sessions = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (LobbySession s : sessions)
            s.leave();
    }

    private void until(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (LobbySession s : sessions)
                s.update();
            if (condition.getAsBoolean())
                return;
            Thread.sleep(5);
        }
        fail("timed out waiting for: " + what);
    }

    private static List<String> names(LobbySession s) {
        return s.players().stream().map(LobbyPlayer::name).toList();
    }

    @Test
    void playersJoinThroughTheSteamLobbyAndStart() throws Exception {
        SteamNode hostNode = SteamNode.host(() -> steam.port(1), 4);
        HostedLobby h = new HostedLobby(new LobbySettings("normal", 4), "Ann", 1, BUILDS, hostNode);
        sessions.add(h);
        until("the Steam lobby", () -> hostNode.lobby() != 0);

        JoinedLobby bob = new JoinedLobby(SteamNode.join(() -> steam.port(2), hostNode.lobby()), "Bob", 2, BUILDS);
        sessions.add(bob);
        JoinedLobby cid = new JoinedLobby(SteamNode.join(() -> steam.port(3), hostNode.lobby()), "Cid", 3, BUILDS);
        sessions.add(cid);
        until("everyone sees 3 players", () -> names(h).size() == 3 && names(bob).size() == 3 && names(cid).size() == 3);
        assertEquals(h.players(), bob.players());
        assertEquals(h.players(), cid.players());
        assertEquals(-1, h.port());

        h.start();
        until("everyone sees the start", () -> bob.state() == LobbyState.STARTED && cid.state() == LobbyState.STARTED);
    }

    @Test
    void aPlayerLeavingTheSteamLobbyLeavesTheRoster() throws Exception {
        SteamNode hostNode = SteamNode.host(() -> steam.port(1), 4);
        HostedLobby h = new HostedLobby(new LobbySettings("normal", 4), "Ann", 1, BUILDS, hostNode);
        sessions.add(h);
        until("the Steam lobby", () -> hostNode.lobby() != 0);
        JoinedLobby bob = new JoinedLobby(SteamNode.join(() -> steam.port(2), hostNode.lobby()), "Bob", 2, BUILDS);
        sessions.add(bob);
        until("Bob joined", () -> names(h).size() == 2);

        bob.leave();
        sessions.remove(bob);
        until("Bob gone", () -> names(h).size() == 1);
    }
}
