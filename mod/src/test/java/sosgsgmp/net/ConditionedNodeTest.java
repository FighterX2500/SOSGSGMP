package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

class ConditionedNodeTest {

    /** A node that records what is sent and dropped, and delivers the events a test gives it. */
    private static final class Recorder implements Node {
        final List<Integer> sentTo = new CopyOnWriteArrayList<>();
        final List<Message> sent = new CopyOnWriteArrayList<>();
        final List<Integer> dropped = new CopyOnWriteArrayList<>();
        final Queue<Event> events = new ConcurrentLinkedQueue<>();
        volatile boolean closed;

        @Override
        public void send(int peer, Message m) {
            sentTo.add(peer);
            sent.add(m);
        }

        @Override
        public void disconnect(int peer) {
            dropped.add(peer);
        }

        @Override
        public Event poll() {
            return events.poll();
        }

        @Override
        public String describe() {
            return "recorder";
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static Message chat(int i) {
        return new Message.Chat(0, "m" + i);
    }

    /** Polls the node until {@code count} events came, or fails after {@code ms}. */
    private static List<Node.Event> events(Node n, int count, long ms) throws InterruptedException {
        List<Node.Event> got = new java.util.ArrayList<>();
        long end = System.currentTimeMillis() + ms;
        while (got.size() < count && System.currentTimeMillis() < end) {
            Node.Event e = n.poll();
            if (e == null)
                Thread.sleep(1);
            else
                got.add(e);
        }
        return got;
    }

    private static void waitFor(java.util.function.BooleanSupplier ok, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean() && System.currentTimeMillis() < end)
            Thread.sleep(1);
    }

    @Test
    void withoutTheVariableTheNodeIsUntouched() {
        Recorder r = new Recorder();
        if (NetConditions.fromEnvironment() == null) // the test JVM never sets it
            assertSame(r, ConditionedNode.wrapIfAsked(r));
        assertSame(r, ConditionedNode.unwrap(r));
    }

    @Test
    void messagesLeaveDelayedAndInOrder() throws InterruptedException {
        Recorder r = new Recorder();
        ConditionedNode n = new ConditionedNode(r, NetConditions.parse("delay=200,jitter=50,seed=3"));
        long start = System.currentTimeMillis();
        for (int i = 0; i < 20; i++)
            n.send(0, chat(i));
        Thread.sleep(40);
        assertEquals(0, r.sent.size(), "nothing before the shortest wait (100 - 50 ms)");
        waitFor(() -> r.sent.size() == 20, 2000);
        assertTrue(System.currentTimeMillis() - start >= 50);
        for (int i = 0; i < 20; i++)
            assertEquals(chat(i), r.sent.get(i));
        n.close();
        assertTrue(r.closed);
    }

    @Test
    void anOutageHoldsBothWaysThenEverythingPasses() throws InterruptedException {
        Recorder r = new Recorder();
        ConditionedNode n = new ConditionedNode(r, NetConditions.parse(""));
        n.outage(1);
        n.send(0, chat(1));
        r.events.add(new Node.Event.Received(0, chat(2)));
        Thread.sleep(500);
        assertEquals(0, r.sent.size());
        assertNull(n.poll());
        List<Node.Event> got = events(n, 1, 2000);
        waitFor(() -> r.sent.size() == 1, 1000);
        assertEquals(1, r.sent.size());
        assertEquals(new Node.Event.Received(0, chat(2)), got.get(0));
        n.close();
    }

    @Test
    void aCutClosesEveryConnectionAndTellsThisPc() throws InterruptedException {
        Recorder r = new Recorder();
        ConditionedNode n = new ConditionedNode(r, NetConditions.parse(""));
        n.send(1, chat(1));
        n.send(2, chat(2));
        waitFor(() -> r.sent.size() == 2, 1000);
        n.cutAll();
        List<Node.Event> got = events(n, 2, 1000);
        assertEquals(2, got.size());
        assertInstanceOf(Node.Event.Disconnected.class, got.get(0));
        assertEquals(1, ((Node.Event.Disconnected) got.get(0)).peer());
        assertEquals(2, ((Node.Event.Disconnected) got.get(1)).peer());
        assertEquals(List.of(1, 2), r.dropped);
        n.close();
    }

    @Test
    void anOutageIsAtMostSixtySeconds() {
        ConditionedNode n = new ConditionedNode(new Recorder(), NetConditions.parse(""));
        assertThrows(IllegalArgumentException.class, () -> n.outage(61));
        assertThrows(IllegalArgumentException.class, () -> n.outage(0));
        n.close();
    }
}
