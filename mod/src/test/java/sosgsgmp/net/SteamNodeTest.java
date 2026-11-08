package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosgsgmp.lobby.Builds;
import sosgsgmp.lobby.GameFiles;

class SteamNodeTest {

    private final FakeSteam steam = new FakeSteam();
    private final List<SteamNode> nodes = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (SteamNode n : nodes)
            n.close();
    }

    private SteamNode host() throws InterruptedException {
        SteamNode h = SteamNode.host(() -> steam.port(1), 4);
        nodes.add(h);
        waitFor("the lobby", () -> h.lobby() != 0);
        return h;
    }

    private SteamNode join(long user, long lobby) {
        SteamNode j = SteamNode.join(() -> steam.port(user), lobby);
        nodes.add(j);
        return j;
    }

    @Test
    void aJoinSteamNeverAnswersEndsWithAReason() throws InterruptedException {
        steam.silentJoins();
        SteamNode j = SteamNode.join(() -> steam.port(2), 1000, 50);
        nodes.add(j);
        Node.Event.Disconnected d = (Node.Event.Disconnected) next(j, e -> e instanceof Node.Event.Disconnected);
        assertTrue(d.reason().contains("into the lobby"), d.reason());
    }

    @Test
    void aHostWhoNeverAnswersEndsTheJoinWithAReason() throws InterruptedException {
        SteamNode h = host();
        steam.lostPackets();
        SteamNode j = SteamNode.join(() -> steam.port(2), h.lobby(), 50);
        nodes.add(j);
        Node.Event.Disconnected d = (Node.Event.Disconnected) next(j, e -> e instanceof Node.Event.Disconnected);
        assertTrue(d.reason().contains("didn't answer"), d.reason());
    }

    /** The next event from {@code node} that matches, skipping others; fails after 5 s. */
    private static Node.Event next(SteamNode node, Predicate<Node.Event> match) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Node.Event e = node.poll();
            if (e != null && match.test(e))
                return e;
            if (e == null)
                Thread.sleep(2);
        }
        fail("no matching event");
        return null;
    }

    private static void waitFor(String what, java.util.function.BooleanSupplier c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline)
                fail("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    @Test
    void aSessionThatStartsAfterTheMemberJoinedIsStillAccepted() throws Exception {
        steam.silentSessions();
        SteamNode h = host();
        SteamNode bob = join(2, h.lobby());
        int bobPeer = ((Node.Event.Connected) next(h, e -> e instanceof Node.Event.Connected)).peer();
        next(bob, e -> e instanceof Node.Event.Connected);
        Thread.sleep(50); // the host's first accept has come and gone before the first packet exists
        bob.send(Node.HOST, new Message.Hello("bob", 7, new Builds("0.71.44", 1, 2, new GameFiles(3, List.of()))));
        Node.Event.Received r = (Node.Event.Received) next(h, e -> e instanceof Node.Event.Received);
        assertEquals(bobPeer, r.peer());
    }

    @Test
    void thePersonaNameReachesTheGameThreadOnceSteamHasStarted() throws Exception {
        assertEquals("Steam user", host().personaName());
    }

    @Test
    void membersBecomePeersAndMessagesGoBothWays() throws Exception {
        SteamNode h = host();
        SteamNode bob = join(2, h.lobby());
        assertEquals(Node.HOST, ((Node.Event.Connected) next(bob, e -> e instanceof Node.Event.Connected)).peer());
        int bobPeer = ((Node.Event.Connected) next(h, e -> e instanceof Node.Event.Connected)).peer();
        assertEquals(1, bobPeer, "the host numbers members from 1");

        bob.send(Node.HOST, new Message.Hello("Bob", 2, new Builds("0.71.44", 1, 2, new GameFiles(3, List.of()))));
        Node.Event.Received r = (Node.Event.Received) next(h, e -> e instanceof Node.Event.Received);
        assertEquals(bobPeer, r.peer());
        assertEquals(new Message.Hello("Bob", 2, new Builds("0.71.44", 1, 2, new GameFiles(3, List.of()))), r.message());

        h.send(bobPeer, new Message.Start());
        assertEquals(new Message.Start(), ((Node.Event.Received) next(bob, e -> e instanceof Node.Event.Received)).message());

        SteamNode cid = join(3, h.lobby());
        next(cid, e -> e instanceof Node.Event.Connected);
        assertEquals(2, ((Node.Event.Connected) next(h, e -> e instanceof Node.Event.Connected)).peer());
    }

    @Test
    void aMemberLeavingIsADisconnectAndTheOwnerLeavingLosesTheHost() throws Exception {
        SteamNode h = host();
        SteamNode bob = join(2, h.lobby());
        next(bob, e -> e instanceof Node.Event.Connected);
        next(h, e -> e instanceof Node.Event.Connected);
        SteamNode cid = join(3, h.lobby());
        next(cid, e -> e instanceof Node.Event.Connected);
        next(h, e -> e instanceof Node.Event.Connected);

        bob.close();
        assertEquals(1, ((Node.Event.Disconnected) next(h, e -> e instanceof Node.Event.Disconnected)).peer());

        h.close();
        Node.Event.Disconnected d = (Node.Event.Disconnected) next(cid, e -> e instanceof Node.Event.Disconnected);
        assertEquals(Node.HOST, d.peer());
        assertTrue(d.reason().contains("host"), d.reason());
    }

    /** The next message, failing on a disconnect before it: a blip must not drop anyone (SOS-177). */
    private static Node.Event.Received nextMessage(SteamNode node) throws InterruptedException {
        return (Node.Event.Received) next(node, e -> {
            assertFalse(e instanceof Node.Event.Disconnected d && d.peer() >= 0, "no disconnect: " + e);
            return e instanceof Node.Event.Received;
        });
    }

    @Test
    void aMemberWhoOnlyLostSteamStaysAPeerBothWays() throws Exception {
        SteamNode h = host();
        FakeSteam.Port[] bobPort = new FakeSteam.Port[1];
        SteamNode bob = SteamNode.join(() -> bobPort[0] = steam.port(2), h.lobby());
        nodes.add(bob);
        next(bob, e -> e instanceof Node.Event.Connected);
        int bobPeer = ((Node.Event.Connected) next(h, e -> e instanceof Node.Event.Connected)).peer();

        bobPort[0].loseSteam(h.lobby()); // the host hears Disconnected; Bob hears he was kicked for it
        Thread.sleep(50);
        h.send(bobPeer, new Message.Start());
        assertEquals(new Message.Start(), nextMessage(bob).message());
        bob.send(Node.HOST, new Message.Chat(1, "back"));
        assertEquals(new Message.Chat(1, "back"), nextMessage(h).message());
    }

    @Test
    void theHostLosingSteamKeepsItsPlayers() throws Exception {
        FakeSteam.Port[] hostPort = new FakeSteam.Port[1];
        SteamNode h = SteamNode.host(() -> hostPort[0] = steam.port(1), 4);
        nodes.add(h);
        waitFor("the lobby", () -> h.lobby() != 0);
        SteamNode bob = join(2, h.lobby());
        next(bob, e -> e instanceof Node.Event.Connected);
        int bobPeer = ((Node.Event.Connected) next(h, e -> e instanceof Node.Event.Connected)).peer();

        hostPort[0].loseSteam(h.lobby());
        Thread.sleep(50);
        bob.send(Node.HOST, new Message.Chat(1, "still here"));
        assertEquals(new Message.Chat(1, "still here"), nextMessage(h).message());
        h.send(bobPeer, new Message.Start());
        assertEquals(new Message.Start(), nextMessage(bob).message());
    }

    /**
     * The host's line to Bob breaks for good while messages are under way both ways (SOS-178): his internet drops, the
     * session fails past Steam's timeout and loses what was unread, then he is back. Every message arrives once and in
     * order, both ways, and nobody is dropped.
     */
    @Test
    void aFailedSessionResumesAndLosesNothing() throws Exception {
        SteamNode h = host();
        SteamNode bob = join(2, h.lobby());
        next(bob, e -> e instanceof Node.Event.Connected);
        int bobPeer = ((Node.Event.Connected) next(h, e -> e instanceof Node.Event.Connected)).peer();
        h.send(bobPeer, new Message.Chat(0, "before"));
        assertEquals(new Message.Chat(0, "before"), nextMessage(bob).message());

        steam.down(2, true);
        for (int i = 0; i < 3; i++) {
            h.send(bobPeer, new Message.Chat(0, "host " + i));
            bob.send(Node.HOST, new Message.Chat(1, "bob " + i));
        }
        Thread.sleep(100);
        steam.failSession(1, 2);
        Thread.sleep(SteamNode.RESUME_MS + 500); // both ask to resume into the outage
        steam.down(2, false);
        for (int i = 0; i < 3; i++) {
            assertEquals(new Message.Chat(0, "host " + i), nextMessage(bob).message());
            assertEquals(new Message.Chat(1, "bob " + i), nextMessage(h).message());
        }
        h.send(bobPeer, new Message.Chat(0, "after"));
        assertEquals(new Message.Chat(0, "after"), nextMessage(bob).message());
        Thread.sleep(300);
        assertNoMessage(bob);
        assertNoMessage(h);
    }

    /** Nothing more for the sessions but the network's own notes: no repeat, no disconnect. */
    private static void assertNoMessage(SteamNode node) {
        Node.Event e;
        while ((e = node.poll()) != null)
            assertTrue(e instanceof Node.Event.Disconnected d && d.peer() < 0, "nothing more: " + e);
    }

    @Test
    void packetsFromOutsideTheLobbyAreNeverRead() throws Exception {
        SteamNode h = host();
        FakeSteam.Port stranger = steam.port(9);
        stranger.send(1, Protocol.encode(new Message.Hello("Eve", 9, new Builds("0.71.44", 1, 2, new GameFiles(3, List.of())))));
        SteamNode bob = join(2, h.lobby());
        next(h, e -> e instanceof Node.Event.Connected);
        next(bob, e -> e instanceof Node.Event.Connected);
        bob.send(Node.HOST, new Message.Hello("Bob", 2, new Builds("0.71.44", 1, 2, new GameFiles(3, List.of()))));
        Node.Event.Received r = (Node.Event.Received) next(h, e -> e instanceof Node.Event.Received);
        assertEquals(new Message.Hello("Bob", 2, new Builds("0.71.44", 1, 2, new GameFiles(3, List.of()))), r.message(), "Eve's hello never arrives");
    }

    @Test
    void aMissingLobbyOrNoSteamIsAFailureTheScreenCanShow() throws Exception {
        SteamNode lost = join(2, 4242);
        Node.Event.Disconnected d = (Node.Event.Disconnected) next(lost, e -> e instanceof Node.Event.Disconnected);
        assertEquals(Node.HOST, d.peer());
        assertNotNull(lost.failure());

        SteamNode none = SteamNode.host(() -> {
            throw new IllegalStateException("Steam isn't running");
        }, 4);
        nodes.add(none);
        next(none, e -> e instanceof Node.Event.Disconnected);
        assertTrue(none.failure().contains("Steam isn't running"), none.failure());
    }

    @Test
    void aBrowsingPcSeesFriendsLobbiesAndJoinsOneOrAnAcceptedInvite() throws Exception {
        SteamNode h = host();
        SteamNode browser = SteamNode.browse(() -> steam.port(2));
        nodes.add(browser);
        waitFor("the friend's lobby", () -> browser.friendLobbies().stream().anyMatch(f -> f.lobby() == h.lobby()));
        assertEquals("user1", browser.friendLobbies().get(0).friend());
        browser.join(h.lobby());
        assertEquals(Node.HOST, ((Node.Event.Connected) next(browser, e -> e instanceof Node.Event.Connected)).peer());
        next(h, e -> e instanceof Node.Event.Connected);

        FakeSteam.Port[] cidPort = new FakeSteam.Port[1];
        SteamNode cid = SteamNode.browse(() -> cidPort[0] = steam.port(3));
        nodes.add(cid);
        waitFor("Steam started", () -> cidPort[0] != null);
        cidPort[0].acceptInvite(h.lobby());
        waitFor("the invite", () -> cid.invited() == h.lobby());
    }

    /** SOS-103: the main menu's watch node closes itself, Steam with it, once the menu stops wanting it. */
    @Test
    void aNodeNoLongerWantedClosesItselfAndSteam() throws Exception {
        FakeSteam.Port[] port = new FakeSteam.Port[1];
        boolean[] wanted = {true};
        SteamNode watch = SteamNode.browse(() -> port[0] = steam.port(5));
        nodes.add(watch);
        watch.closeWhenUnwanted(() -> wanted[0]);
        waitFor("Steam started", () -> port[0] != null);
        Thread.sleep(50);
        assertFalse(port[0].closed, "open while wanted");
        wanted[0] = false;
        waitFor("Steam closed", () -> port[0].closed);
    }
}
