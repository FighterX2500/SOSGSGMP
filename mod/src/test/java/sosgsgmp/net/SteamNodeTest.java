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
import sosgsgmp.lobby.Refusal;

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
    void theNetworkNamesTheLobbyOnlyOnceSteamHasNamedIt() throws InterruptedException {
        steam.silentJoins();
        SteamNode j = join(2, 1000);
        assertEquals("Steam P2P, joining a lobby", j.describe()); // the lobby log's first line: Steam hasn't answered yet
        SteamNode h = host();
        assertEquals("Steam P2P, hosting lobby " + h.lobby(), h.describe());
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

    /** A host that is only a Steam user: its lobby made, nothing answered. */
    private FakeSteam.Port bareHost() {
        FakeSteam.Port hp = steam.port(1);
        hp.listener(new SteamPort.Listener() {
            @Override
            public void lobbyCreated(long lobby, String error) {
            }

            @Override
            public void lobbyEntered(long lobby, String error) {
            }

            @Override
            public void memberJoined(long lobby, long user) {
            }

            @Override
            public void memberLeft(long lobby, long user, boolean lostSteam) {
            }

            @Override
            public void lobbyLost(long lobby, boolean lostSteam) {
            }

            @Override
            public void joinRequested(long lobby) {
            }

            @Override
            public void sessionRequested(long user) {
            }

            @Override
            public void sessionFailed(long user, String reason) {
            }
        });
        hp.createLobby(4);
        hp.runCallbacks();
        return hp;
    }

    @Test
    void aLobbyOfAnotherVersionEndsTheJoinAtOnce() throws InterruptedException {
        FakeSteam.Port hp = bareHost();
        hp.lobbyData(1000, SteamNode.LOBBY_KEY, "31");
        SteamNode j = join(2, 1000);
        Node.Event.Disconnected d = (Node.Event.Disconnected) next(j, e -> e instanceof Node.Event.Disconnected);
        assertEquals("The host runs another version of the mod (protocol 31).", d.reason());
    }

    @Test
    void anotherVersionsAnswerBeforeTheHandshakeReachesTheSessions() throws InterruptedException {
        FakeSteam.Port hp = bareHost(); // a lobby without the version: the host's answer is all there is
        SteamNode j = join(2, 1000);
        waitFor("the joiner in the lobby", () -> j.lobby() != 0);
        hp.send(2, new byte[] {0, 31, 0}); // protocol 31's version and a type
        Node.Event.Received r = (Node.Event.Received) next(j, e -> e instanceof Node.Event.Received);
        assertEquals(new Message.Unsupported(31), r.message());
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

        h.send(bobPeer, new Message.Start(false));
        assertEquals(new Message.Start(false), ((Node.Event.Received) next(bob, e -> e instanceof Node.Event.Received)).message());

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
        h.send(bobPeer, new Message.Start(false));
        assertEquals(new Message.Start(false), nextMessage(bob).message());
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
        h.send(bobPeer, new Message.Start(false));
        assertEquals(new Message.Start(false), nextMessage(bob).message());
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

    private static Message.Hello hello(String name) {
        return new Message.Hello(name, 2, new Builds("0.71.44", 1, 2, new GameFiles(3, List.of())));
    }

    /** The next event from {@code node} that matches; fails after 5 s, naming {@code what} and every event skipped. */
    private static Node.Event expect(SteamNode node, String what, Predicate<Node.Event> match) throws InterruptedException {
        List<Node.Event> skipped = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Node.Event e = node.poll();
            if (e != null && match.test(e))
                return e;
            if (e == null)
                Thread.sleep(2);
            else
                skipped.add(e);
        }
        fail("no " + what + "; events instead: " + skipped);
        return null;
    }

    /**
     * Bob and the host exchange messages, then Bob's line breaks for good: he loses Steam (out of the lobby), his
     * internet goes down, the session fails, and his game closes, with no Left for the host. The host keeps his line and
     * queues {@code whileAway} for it. Returns his peer number on the host.
     */
    private int bobsGameEndsWhileHisLineIsBroken(SteamNode h, Message whileAway, boolean removeHim) throws Exception {
        FakeSteam.Port[] oldPort = new FakeSteam.Port[1];
        SteamNode old = SteamNode.join(() -> oldPort[0] = steam.port(2), h.lobby());
        nodes.add(old);
        next(old, e -> e instanceof Node.Event.Connected);
        int oldPeer = ((Node.Event.Connected) next(h, e -> e instanceof Node.Event.Connected)).peer();
        old.send(Node.HOST, hello("Bob"));
        assertEquals(hello("Bob"), nextMessage(h).message());
        for (int i = 0; i < 3; i++) {
            h.send(oldPeer, new Message.Chat(0, "host " + i));
            old.send(Node.HOST, new Message.Chat(1, "bob " + i));
            assertEquals(new Message.Chat(0, "host " + i), nextMessage(old).message());
            assertEquals(new Message.Chat(1, "bob " + i), nextMessage(h).message());
        }

        oldPort[0].loseSteam(h.lobby());
        steam.down(2, true);
        Thread.sleep(100);
        steam.failSession(1, 2);
        expect(h, "the host's note that Bob's line broke",
                e -> e instanceof Node.Event.Disconnected d && d.peer() < 0 && d.reason().contains("broke"));
        h.send(oldPeer, whileAway);
        if (removeHim)
            h.disconnect(oldPeer);
        old.close();
        steam.down(2, false);
        return oldPeer;
    }

    /**
     * Bob's game starts again under the same Steam ID while the host still holds his broken line (a restart, or Leave
     * and join again): the old line ends as a disconnect, the new game is a new peer, and nothing of the old stream
     * reaches it.
     */
    @Test
    void aGameThatStartsAgainWhileItsLineIsBrokenIsANewPeer() throws Exception {
        SteamNode h = host();
        int oldPeer = bobsGameEndsWhileHisLineIsBroken(h, new Message.Chat(0, "for the old game"), false);

        SteamNode bob = join(2, h.lobby());
        next(bob, e -> e instanceof Node.Event.Connected);
        bob.send(Node.HOST, hello("Bob again"));
        Node.Event.Disconnected d = (Node.Event.Disconnected) expect(h, "a disconnect of the old line",
                e -> e instanceof Node.Event.Disconnected x && x.peer() >= 0);
        assertEquals(oldPeer, d.peer());
        int newPeer = ((Node.Event.Connected) expect(h, "the new game as a peer", e -> e instanceof Node.Event.Connected)).peer();
        assertTrue(newPeer != oldPeer, "a new peer, not " + oldPeer);
        Node.Event.Received r = (Node.Event.Received) expect(h, "the new game's hello", e -> e instanceof Node.Event.Received);
        assertEquals(newPeer, r.peer());
        assertEquals(hello("Bob again"), r.message());

        h.send(newPeer, new Message.Chat(0, "for the new game"));
        assertEquals(new Message.Chat(0, "for the new game"), nextMessage(bob).message());
        Thread.sleep(SteamNode.RESUME_MS + 500); // the old line would ask to resume by now
        assertNoMessage(bob);
        assertNoMessage(h);
    }

    /** As above, after the host removed the old line while it was broken: it ends silently, and its refusal stays with it. */
    @Test
    void aGameThatStartsAgainAfterItsLineWasRemovedIsANewPeer() throws Exception {
        SteamNode h = host();
        bobsGameEndsWhileHisLineIsBroken(h, new Message.Refused(Refusal.REMOVED), true);

        SteamNode bob = join(2, h.lobby());
        next(bob, e -> e instanceof Node.Event.Connected);
        bob.send(Node.HOST, hello("Bob again"));
        int newPeer = ((Node.Event.Connected) expect(h, "the new game as a peer", e -> {
            assertFalse(e instanceof Node.Event.Disconnected x && x.peer() >= 0, "no disconnect: " + e);
            return e instanceof Node.Event.Connected;
        })).peer();
        Node.Event.Received r = (Node.Event.Received) expect(h, "the new game's hello", e -> e instanceof Node.Event.Received);
        assertEquals(newPeer, r.peer());
        assertEquals(hello("Bob again"), r.message());

        h.send(newPeer, new Message.Chat(0, "for the new game"));
        assertEquals(new Message.Chat(0, "for the new game"), nextMessage(bob).message());
        Thread.sleep(SteamNode.RESUME_MS + 500);
        assertNoMessage(bob);
        assertNoMessage(h);
    }

    /**
     * Bob joins the host, which sends him {@code Refused} and drops him in the same update, as the sessions refuse a
     * PC (batch 12, item 1B).
     */
    private void refusedInOneUpdate(SteamNode h, SteamNode bob) throws Exception {
        next(bob, e -> e instanceof Node.Event.Connected);
        int bobPeer = ((Node.Event.Connected) next(h, e -> e instanceof Node.Event.Connected)).peer();
        bob.send(Node.HOST, hello("Bob"));
        assertEquals(hello("Bob"), nextMessage(h).message());
        h.send(bobPeer, new Message.Start(false)); // once Bob has it, his line's handshake is done
        assertEquals(new Message.Start(false), nextMessage(bob).message());
        h.send(bobPeer, new Message.Refused(Refusal.JOIN_BUSY));
        h.disconnect(bobPeer);
        assertEquals(new Message.Refused(Refusal.JOIN_BUSY), nextMessage(bob).message(), "the refusal arrives");
    }

    /** A dropped peer gets what was sent to it; its Steam session closes once it leaves the lobby. */
    @Test
    void aDroppedPeerGetsItsRefusalAndItsSessionClosesWhenItLeaves() throws Exception {
        FakeSteam.Port[] hostPort = new FakeSteam.Port[1];
        SteamNode h = SteamNode.host(() -> hostPort[0] = steam.port(1), 4);
        nodes.add(h);
        waitFor("the lobby", () -> h.lobby() != 0);
        SteamNode bob = join(2, h.lobby());
        refusedInOneUpdate(h, bob);
        assertTrue(hostPort[0].inSession(2), "the session stays while Bob's game reads");
        bob.close();
        waitFor("the session closed", () -> !hostPort[0].inSession(2));
        assertNoMessage(h);
    }

    /** A dropped peer that never leaves the lobby: its session closes at the limit. */
    @Test
    void aDroppedPeersSessionClosesAtTheLimitIfItStaysInTheLobby() throws Exception {
        FakeSteam.Port[] hostPort = new FakeSteam.Port[1];
        SteamNode h = SteamNode.host(() -> hostPort[0] = steam.port(1), 4, 300);
        nodes.add(h);
        waitFor("the lobby", () -> h.lobby() != 0);
        SteamNode bob = join(2, h.lobby());
        refusedInOneUpdate(h, bob);
        waitFor("the session closed at the limit", () -> !hostPort[0].inSession(2));
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
