package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    void membersBecomePeersAndMessagesGoBothWays() throws Exception {
        SteamNode h = host();
        SteamNode bob = join(2, h.lobby());
        assertEquals(Node.HOST, ((Node.Event.Connected) next(bob, e -> e instanceof Node.Event.Connected)).peer());
        int bobPeer = ((Node.Event.Connected) next(h, e -> e instanceof Node.Event.Connected)).peer();
        assertEquals(1, bobPeer, "the host numbers members from 1");

        bob.send(Node.HOST, new Message.Hello("Bob", 2));
        Node.Event.Received r = (Node.Event.Received) next(h, e -> e instanceof Node.Event.Received);
        assertEquals(bobPeer, r.peer());
        assertEquals(new Message.Hello("Bob", 2), r.message());

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

    @Test
    void packetsFromOutsideTheLobbyAreNeverRead() throws Exception {
        SteamNode h = host();
        FakeSteam.Port stranger = steam.port(9);
        stranger.send(1, Protocol.encode(new Message.Hello("Eve", 9)));
        SteamNode bob = join(2, h.lobby());
        next(h, e -> e instanceof Node.Event.Connected);
        next(bob, e -> e instanceof Node.Event.Connected);
        bob.send(Node.HOST, new Message.Hello("Bob", 2));
        Node.Event.Received r = (Node.Event.Received) next(h, e -> e instanceof Node.Event.Received);
        assertEquals(new Message.Hello("Bob", 2), r.message(), "Eve's hello never arrives");
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
}
