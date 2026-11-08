package sosgsgmp.net;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test tooling (SOS-175): a {@link Node} whose connections suffer {@link NetConditions}: delay, jitter and resends on
 * every message each way, and on request an outage (nothing passes either way for some seconds) or a cut (the
 * connection closes as a lost one does). It wraps the real node only when {@link NetConditions#VARIABLE} is set
 * ({@link #wrapIfAsked}); in normal play no instance exists.
 *
 * <p>Its own thread (AGENTS.md "State and Lifecycle", the approved network exception; test runs only) moves messages
 * through the {@link DelayLine}s, so a frozen game thread doesn't hold them, as the real node's I/O thread doesn't. It
 * reads the wrapped node's events through its {@code poll}, which is a concurrent queue in both nodes. The game thread
 * only queues and polls here, as with the real nodes.
 */
public final class ConditionedNode implements Node {

    private static final long TICK_MS = 1;
    private static final int ALL = -1;

    private sealed interface Out {
        record Send(int peer, Message message) implements Out {}
        record Drop(int peer) implements Out {}
    }

    private record Stamped<T>(T item, long atMs) {}

    private final Node inner;
    private final NetConditions conditions;
    private final Queue<Stamped<Out>> outgoing = new ConcurrentLinkedQueue<>();
    private final Queue<Event> ready = new ConcurrentLinkedQueue<>();
    private final Queue<Integer> cuts = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile long outageUntil;
    private volatile boolean closing;
    private volatile String stats = "no messages yet";
    private final AtomicInteger outages = new AtomicInteger();

    // Owned by the thread.
    private final Random random;
    private final Map<Integer, DelayLine<Out>> outLines = new HashMap<>();
    private final Map<Integer, DelayLine<Event>> inLines = new HashMap<>();
    private long cutCount, statsAt;

    /** The node itself, or the node under the conditions the environment asks for. */
    public static Node wrapIfAsked(Node node) {
        NetConditions c = NetConditions.fromEnvironment();
        return c == null ? node : new ConditionedNode(node, c);
    }

    /** The real node under a {@link ConditionedNode}, or the node itself. */
    public static Node unwrap(Node node) {
        return node instanceof ConditionedNode c ? c.inner : node;
    }

    ConditionedNode(Node inner, NetConditions conditions) {
        this.inner = inner;
        this.conditions = conditions;
        this.random = new Random(conditions.seed());
        thread = new Thread(this::run, "SOSGSGMP netem");
        thread.setDaemon(true);
        thread.start();
    }

    public NetConditions conditions() {
        return conditions;
    }

    /** Nothing passes either way for {@code seconds} from now, then everything due passes at once. */
    public void outage(int seconds) {
        if (seconds < 1 || seconds > NetConditions.MAX_OUTAGE_SECONDS)
            throw new IllegalArgumentException("SOSGSGMP: an outage of " + seconds + " s, not 1 to " + NetConditions.MAX_OUTAGE_SECONDS);
        outageUntil = Math.max(outageUntil, nowMs() + seconds * 1000L);
        outages.incrementAndGet();
    }

    /**
     * This PC's internet drops: every connection it has closes as a lost one, and each side hears it (this PC from
     * here, the other from its own node). On a joining PC that is the host's connection.
     */
    public void cutAll() {
        cuts.add(ALL);
    }

    /** What the conditions did so far, for the playtest log. */
    public String stats() {
        return stats;
    }

    @Override
    public void send(int peer, Message m) {
        outgoing.add(new Stamped<>(new Out.Send(peer, m), nowMs()));
    }

    @Override
    public void disconnect(int peer) {
        outgoing.add(new Stamped<>(new Out.Drop(peer), nowMs()));
    }

    @Override
    public Event poll() {
        return ready.poll();
    }

    @Override
    public String describe() {
        return inner.describe() + " (test network: " + conditions.describe() + ")";
    }

    /** Sends everything still held at once, then closes the real node. */
    @Override
    public void close() {
        closing = true;
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        inner.close();
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    // ------------------------------------------------------------ the thread

    /** Top-level boundary of the thread: a failure ends the conditions and reports it, as the real nodes do. */
    private void run() {
        try {
            while (!closing) {
                step(nowMs());
                Thread.sleep(TICK_MS);
            }
            flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            ready.add(new Event.Disconnected(-1, "SOSGSGMP: the test network conditions failed: " + e));
        }
    }

    private void step(long now) {
        long until = outageUntil;
        while (cuts.poll() != null)
            for (Integer peer : peers())
                cut(peer);
        Stamped<Out> o;
        while ((o = outgoing.poll()) != null) {
            int peer = o.item() instanceof Out.Send s ? s.peer() : ((Out.Drop) o.item()).peer();
            line(outLines, peer, until).add(o.item(), o.atMs());
        }
        Event e;
        while ((e = inner.poll()) != null) {
            int peer = peerOf(e);
            if (peer < 0)
                ready.add(e); // a problem not tied to one connection: for the log, at once
            else
                line(inLines, peer, until).add(e, now);
        }
        for (DelayLine<Out> l : outLines.values()) {
            l.holdUntil(until);
            Out out;
            while ((out = l.poll(now)) != null)
                deliver(out);
        }
        for (DelayLine<Event> l : inLines.values()) {
            l.holdUntil(until);
            Event in;
            while ((in = l.poll(now)) != null)
                ready.add(in);
        }
        if (now - statsAt >= 1000) {
            statsAt = now;
            stats = describeStats(now < until);
        }
    }

    /** A peer's line one way; a new one starts held by an outage under way. */
    private <T> DelayLine<T> line(Map<Integer, DelayLine<T>> lines, int peer, long until) {
        DelayLine<T> l = lines.get(peer);
        if (l == null) {
            l = new DelayLine<>(conditions.delayMs() / 2, conditions.jitterMs(), conditions.lossPercent(), random);
            l.holdUntil(until);
            lines.put(peer, l);
        }
        return l;
    }

    private void deliver(Out out) {
        switch (out) {
            case Out.Send s -> inner.send(s.peer(), s.message());
            case Out.Drop d -> inner.disconnect(d.peer());
        }
    }

    /** The peers this PC has had traffic with, as the lines know them. */
    private Set<Integer> peers() {
        Set<Integer> all = new TreeSet<>(outLines.keySet());
        all.addAll(inLines.keySet());
        return all;
    }

    private void cut(int peer) {
        cutCount++;
        outLines.remove(peer);
        inLines.remove(peer);
        inner.disconnect(peer);
        ready.add(new Event.Disconnected(peer, "Test: the connection was cut (SOS-175)."));
    }

    private static int peerOf(Event e) {
        return switch (e) {
            case Event.Connected c -> c.peer();
            case Event.Received r -> r.peer();
            case Event.Disconnected d -> d.peer();
        };
    }

    /** At close: everything still held goes out at once, in order. */
    private void flush() {
        for (DelayLine<Out> l : outLines.values()) {
            Out out;
            while ((out = l.poll(Long.MAX_VALUE)) != null)
                deliver(out);
        }
    }

    private String describeStats(boolean inOutage) {
        long outN = 0, outLost = 0, outMean = 0, outMax = 0, inN = 0, inLost = 0, inMean = 0, inMax = 0;
        for (DelayLine<Out> l : outLines.values()) {
            outN += l.count();
            outLost += l.lost();
            outMean = Math.max(outMean, l.meanAddedMs());
            outMax = Math.max(outMax, l.maxAddedMs());
        }
        for (DelayLine<Event> l : inLines.values()) {
            inN += l.count();
            inLost += l.lost();
            inMean = Math.max(inMean, l.meanAddedMs());
            inMax = Math.max(inMax, l.maxAddedMs());
        }
        return String.format("out %d msgs, +%d ms mean, +%d ms max, %d resent; in %d msgs, +%d ms mean, +%d ms max, %d resent; "
                + "outages %d%s, cuts %d", outN, outMean, outMax, outLost, inN, inMean, inMax, inLost, outages.get(),
                inOutage ? " (one now)" : "", cutCount);
    }
}
