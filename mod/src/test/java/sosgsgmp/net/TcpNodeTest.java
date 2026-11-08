package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosgsgmp.lobby.Refusal;

class TcpNodeTest {

    private TcpNode host;
    private TcpNode joiner;

    @AfterEach
    void closeAll() {
        if (joiner != null)
            joiner.close();
        if (host != null)
            host.close();
    }

    private static void waitFor(String what, long ms, BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end)
                fail("timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    /** Drains events, failing on any but Connected: heartbeats never reach the game. */
    private static void drain(Node n) {
        Node.Event e;
        while ((e = n.poll()) != null)
            assertTrue(e instanceof Node.Event.Connected, "only the connection, no heartbeat: " + e);
    }

    /**
     * Two idle connections stay heard through the heartbeats, which measure the round trip; a test outage on one side
     * makes the other hear the silence, and it ends with the outage (SOS-177).
     */
    @Test
    void heartbeatsKeepAnIdleConnectionHeardAndATestOutageSilencesIt() throws Exception {
        host = TcpNode.listen(0);
        joiner = TcpNode.connect("127.0.0.1", host.port());
        waitFor("round trips both ways", 5_000, () -> host.rttMs(0) >= 0 && joiner.rttMs(Node.HOST) >= 0);
        Thread.sleep(2_500); // idle: only heartbeats
        drain(host);
        drain(joiner);
        assertTrue(host.silentMs(0) < 1_500, "the joiner heard: " + host.silentMs(0));
        assertTrue(joiner.silentMs(Node.HOST) < 1_500, "the host heard: " + joiner.silentMs(Node.HOST));
        assertTrue(host.rttMs(0) < 1_000, "a loopback round trip: " + host.rttMs(0));

        joiner.silence(3);
        waitFor("the host hearing the silence", 3_000, () -> host.silentMs(0) >= 2_000);
        assertTrue(joiner.silentMs(Node.HOST) >= 2_000, "nor the joiner the host: " + joiner.silentMs(Node.HOST));
        waitFor("the silence over", 4_000, () -> host.silentMs(0) < 1_500 && joiner.silentMs(Node.HOST) < 1_500);
        drain(host);
        drain(joiner);
        assertEquals(0, host.silentMs(9), "a peer it doesn't know");
        assertFalse(host.rttMs(0) < 0);
    }

    /** What a node delivered: the chats' texts in order; fails on a disconnect of a peer (SOS-178). */
    private static void collect(Node n, List<String> into) {
        Node.Event e;
        while ((e = n.poll()) != null) {
            if (e instanceof Node.Event.Received r && r.message() instanceof Message.Chat c)
                into.add(c.text());
            else if (e instanceof Node.Event.Disconnected d && d.peer() >= 0)
                fail("a broken line is no disconnect: " + d);
        }
    }

    private static List<String> texts(String who, int n) {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < n; i++)
            all.add(who + i);
        return all;
    }

    /**
     * Messages go both ways while the joining PC's connection is cut twice, and the host's once (SOS-178): the joining
     * PC connects again, each side sends what the other lacks, and every message arrives once, in order.
     */
    @Test
    void cutsMidStreamLoseNothingAndRepeatNothing() throws Exception {
        host = TcpNode.listen(0);
        joiner = TcpNode.connect("127.0.0.1", host.port());
        waitFor("the line", 5_000, () -> host.rttMs(0) >= 0 && joiner.rttMs(Node.HOST) >= 0);
        List<String> atHost = new ArrayList<>(), atJoiner = new ArrayList<>();
        int n = 600;
        for (int i = 0; i < n; i++) {
            host.send(0, new Message.Chat(0, "h" + i));
            joiner.send(Node.HOST, new Message.Chat(1, "j" + i));
            if (i == 150 || i == 300)
                joiner.cutAll();
            if (i == 450)
                host.cutAll();
            if (i % 50 == 0)
                Thread.sleep(30);
            collect(host, atHost);
            collect(joiner, atJoiner);
        }
        waitFor("every message", 15_000, () -> {
            collect(host, atHost);
            collect(joiner, atJoiner);
            return atHost.size() >= n && atJoiner.size() >= n;
        });
        Thread.sleep(300);
        collect(host, atHost);
        collect(joiner, atJoiner);
        assertEquals(texts("j", n), atHost);
        assertEquals(texts("h", n), atJoiner);
    }

    /** A cut inside a test outage stays down until the outage ends, then the line comes back (SOS-178). */
    @Test
    void aCutInAnOutageComesBackOnceItEnds() throws Exception {
        host = TcpNode.listen(0);
        joiner = TcpNode.connect("127.0.0.1", host.port());
        waitFor("the line", 5_000, () -> host.rttMs(0) >= 0);
        joiner.silence(3);
        joiner.cutAll();
        host.send(0, new Message.Chat(0, "while away"));
        waitFor("the host hearing nothing", 4_000, () -> host.silentMs(0) >= 2_500);
        List<String> atJoiner = new ArrayList<>();
        waitFor("the line back", 8_000, () -> {
            collect(joiner, atJoiner);
            return host.silentMs(0) < 1_500 && !atJoiner.isEmpty();
        });
        assertEquals(List.of("while away"), atJoiner);
        collect(host, new ArrayList<>());
    }

    /** A PC the host removed while its line was broken gets what was queued for it, then its connection ends. */
    @Test
    void aPcRemovedWhileAwayIsToldWhenItComesBack() throws Exception {
        host = TcpNode.listen(0);
        joiner = TcpNode.connect("127.0.0.1", host.port());
        waitFor("the line", 5_000, () -> host.rttMs(0) >= 0 && joiner.rttMs(Node.HOST) >= 0);
        drain(joiner);
        joiner.silence(2);
        joiner.cutAll();
        Thread.sleep(300);
        host.send(0, new Message.Refused(Refusal.REMOVED));
        host.disconnect(0);
        Message got = null;
        long end = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < end) {
            Node.Event e = joiner.poll();
            if (e == null) {
                Thread.sleep(20);
                continue;
            }
            if (e instanceof Node.Event.Received r)
                got = r.message();
            if (e instanceof Node.Event.Disconnected d && d.peer() == Node.HOST) {
                assertEquals(new Message.Refused(Refusal.REMOVED), got);
                return;
            }
        }
        fail("the removed PC was never told");
    }

    /** One frame on a raw socket: its length, then its bytes. */
    private static void write(DataOutputStream out, byte[] bytes) throws Exception {
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static Message read(DataInputStream in) throws Exception {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return Protocol.decode(bytes);
    }

    /** A PC resuming a line the host doesn't know is told so (SOS-178); one of another version still reaches the sessions. */
    @Test
    void anUnknownLineIsRefusedAndAnotherVersionStillReachesTheSessions() throws Exception {
        host = TcpNode.listen(0);
        try (Socket s = new Socket("127.0.0.1", host.port())) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            write(out, Protocol.encode(new Message.Resume(77, 5, 1)));
            assertEquals(new Message.Resumed(-1), read(in));
            assertEquals(-1, in.read(), "then the host closes it");
        }
        try (Socket s = new Socket("127.0.0.1", host.port())) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            write(out, new byte[] {0, 25, 6}); // version 25's Leave
            waitFor("the old PC's message", 3_000, () -> {
                Node.Event e;
                while ((e = host.poll()) != null)
                    if (e instanceof Node.Event.Received r)
                        return r.message().equals(new Message.Unsupported(25));
                return false;
            });
        }
    }

    /** A raw joining PC's connection attempt: its Resume as token 77, having had nothing, numbered {@code attempt}. */
    private Socket attempt(int attempt) throws Exception {
        Socket s = new Socket("127.0.0.1", host.port());
        write(new DataOutputStream(s.getOutputStream()), Protocol.encode(new Message.Resume(77, 0, attempt)));
        return s;
    }

    /** The host's events so far; fails on a disconnect of its one peer: a broken line is waited for (SOS-178). */
    private List<Node.Event> hostEvents() {
        List<Node.Event> got = new ArrayList<>();
        Node.Event e;
        while ((e = host.poll()) != null) {
            assertFalse(e instanceof Node.Event.Disconnected d && d.peer() >= 0, "the line stays: " + e);
            got.add(e);
        }
        return got;
    }

    /**
     * After the host's own outage, the attempts a joining PC gave up on wait in its queue with the newest (N1-hcuts,
     * SOS-180): an older attempt read after the newest is refused, and one read first that closes unused leaves the line
     * broken, not ended. The newest carries the line on.
     */
    @Test
    void attemptsTheJoiningPcGaveUpOnNeitherTakeTheLineNorEndIt() throws Exception {
        host = TcpNode.listen(0);
        try (Socket first = attempt(1)) {
            assertEquals(new Message.Resumed(0), read(new DataInputStream(first.getInputStream())));
        } // closed unused: the line is broken only
        Thread.sleep(300);
        assertTrue(hostEvents().stream().anyMatch(e -> e instanceof Node.Event.Connected c && c.peer() == 0));
        try (Socket stale = attempt(2)) {
            assertEquals(new Message.Resumed(0), read(new DataInputStream(stale.getInputStream())));
        } // given up on after the host answered: still only broken
        Thread.sleep(300);
        hostEvents();
        try (Socket newest = attempt(4); Socket older = attempt(3)) {
            DataInputStream in = new DataInputStream(newest.getInputStream());
            assertEquals(new Message.Resumed(0), read(in));
            assertEquals(-1, older.getInputStream().read(), "an older attempt read after the newest is closed");
            write(new DataOutputStream(newest.getOutputStream()), Protocol.sequenced(1, Protocol.encode(new Message.Chat(1, "on"))));
            waitFor("the chat on the newest attempt", 3_000,
                    () -> hostEvents().stream().anyMatch(e -> e instanceof Node.Event.Received r && r.message().equals(new Message.Chat(1, "on"))));
        }
    }
}
