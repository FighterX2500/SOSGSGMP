package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.net.Message;
import sosgsgmp.net.Protocol;

/** A real host and real joining PCs over localhost, driven the way the lobby screen drives them. */
class LoopbackLobbyTest {

    private final List<LobbySession> sessions = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (LobbySession s : sessions)
            s.leave();
    }

    private HostedLobby host(int maxPlayers) throws IOException {
        HostedLobby h = new HostedLobby(new LobbySettings("normal", maxPlayers), "Ann", "Ann".hashCode(), 0);
        sessions.add(h);
        return h;
    }

    private JoinedLobby join(HostedLobby h, String name) throws IOException {
        JoinedLobby j = new JoinedLobby("127.0.0.1", h.port(), name, name.hashCode());
        sessions.add(j);
        return j;
    }

    /** Updates every session, as each PC's frame loop would, until the condition holds. */
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
    void playersJoinAndEveryoneSeesTheSameRoster() throws Exception {
        HostedLobby h = host(4);
        JoinedLobby bob = join(h, "Bob");
        until("Bob joined", () -> bob.state() == LobbyState.OPEN && names(bob).size() == 2);
        JoinedLobby cid = join(h, "Cid");
        until("everyone sees 3 players", () -> names(h).size() == 3 && names(bob).size() == 3 && names(cid).size() == 3);

        assertEquals(List.of("Ann", "Bob", "Cid"), names(h));
        assertEquals(h.players(), bob.players());
        assertEquals(h.players(), cid.players());
        assertTrue(bob.players().get(0).host());
        assertEquals(1, bob.localPlayerId());
        assertEquals(2, cid.localPlayerId());
        assertEquals(new LobbySettings("normal", 4), cid.settings());
    }

    @Test
    void startIsActiveWithTwoPlayersAndReachesEveryone() throws Exception {
        HostedLobby h = host(4);
        assertFalse(h.canStart());
        JoinedLobby bob = join(h, "Bob");
        until("Bob joined", () -> h.players().size() == 2);
        assertTrue(h.canStart());
        assertFalse(bob.canStart());

        h.start();
        until("Bob sees the start", () -> bob.state() == LobbyState.STARTED);
        assertEquals(LobbyState.STARTED, h.state());

        JoinedLobby late = join(h, "Late");
        until("Late is refused", () -> late.state() == LobbyState.CLOSED);
        assertEquals(Refusal.STARTED.text, late.closedReason());
        assertEquals(2, h.players().size());
    }

    @Test
    void aLobbyMessageReachesEveryPcOnceInTheHostsOrderAndGoesToTheSetup() throws Exception {
        HostedLobby h = host(3);
        JoinedLobby bob = join(h, "Bob");
        JoinedLobby cid = join(h, "Cid");
        until("everyone in", () -> {
            h.update();
            bob.update();
            cid.update();
            return h.players().size() == 3 && bob.players().size() == 3 && cid.players().size() == 3;
        });
        h.say("one");
        bob.say("two");
        until("both on every PC", () -> {
            h.update();
            bob.update();
            cid.update();
            return h.chat().size() == 2 && bob.chat().size() == 2 && cid.chat().size() == 2;
        });
        for (LobbySession s : List.of(h, bob, cid)) {
            assertEquals("one", s.chat().line(0).text());
            assertEquals("Ann", s.chat().line(0).name());
            assertEquals("two", s.chat().line(1).text());
            assertEquals("Bob", s.chat().line(1).name());
            assertEquals(1, s.chat().line(1).colour());
        }
    }

    @Test
    void aFullLobbyRefuses() throws Exception {
        HostedLobby h = host(2);
        JoinedLobby bob = join(h, "Bob");
        until("Bob joined", () -> bob.state() == LobbyState.OPEN);
        JoinedLobby cid = join(h, "Cid");
        until("Cid is refused", () -> cid.state() == LobbyState.CLOSED);
        assertEquals(Refusal.FULL.text, cid.closedReason());
        assertEquals(List.of("Ann", "Bob"), names(h));
    }

    @Test
    void leavingUpdatesEveryRosterOnce() throws Exception {
        HostedLobby h = host(4);
        JoinedLobby bob = join(h, "Bob");
        JoinedLobby cid = join(h, "Cid");
        until("3 players", () -> names(bob).size() == 3 && names(cid).size() == 3);

        bob.leave();
        until("Bob gone everywhere", () -> names(h).size() == 2 && names(cid).size() == 2);
        assertEquals(List.of("Ann", "Cid"), names(cid));
        assertEquals("You left the lobby.", bob.closedReason());
    }

    @Test
    void aDroppedConnectionCountsAsLeaving() throws Exception {
        HostedLobby h = host(4);
        try (Socket raw = new Socket("127.0.0.1", h.port())) {
            send(raw, new Message.Hello("Raw", 7));
            until("Raw joined", () -> h.players().size() == 2);
        }
        until("Raw gone", () -> h.players().size() == 1);
    }

    @Test
    void closingTheLobbyTellsEveryone() throws Exception {
        HostedLobby h = host(4);
        JoinedLobby bob = join(h, "Bob");
        until("Bob joined", () -> bob.state() == LobbyState.OPEN);
        h.leave();
        until("Bob sees the close", () -> bob.state() == LobbyState.CLOSED);
        assertEquals("The host closed the lobby.", bob.closedReason());
    }

    @Test
    void anotherProtocolVersionIsRefused() throws Exception {
        HostedLobby h = host(4);
        try (Socket raw = new Socket("127.0.0.1", h.port())) {
            DataOutputStream out = new DataOutputStream(raw.getOutputStream());
            out.writeInt(3);
            out.write(new byte[] {0, 99, 1});
            out.flush();
            Message reply = readReply(h, raw);
            assertEquals(new Message.Refused(Refusal.WRONG_VERSION), reply);
        }
        assertEquals(1, h.players().size());
    }

    @Test
    void noHostGivesAReason() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        JoinedLobby j = new JoinedLobby("127.0.0.1", freePort, "Bob", 1);
        sessions.add(j);
        until("connection fails", () -> j.state() == LobbyState.CLOSED);
        assertTrue(j.closedReason().startsWith("Could not connect"), j.closedReason());
    }

    private static void send(Socket s, Message m) throws IOException {
        byte[] b = Protocol.encode(m);
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }

    /** Reads one framed message while the host keeps updating on this thread. */
    private Message readReply(HostedLobby h, Socket s) throws Exception {
        DataInputStream in = new DataInputStream(s.getInputStream());
        long deadline = System.currentTimeMillis() + 5_000;
        while (in.available() < 4) {
            if (System.currentTimeMillis() > deadline)
                fail("no reply from the host");
            h.update();
            Thread.sleep(5);
        }
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        return Protocol.decode(b);
    }
}
