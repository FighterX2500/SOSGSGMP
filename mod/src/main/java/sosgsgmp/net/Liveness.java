package sosgsgmp.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether each connection is alive (SOS-177). A transport's I/O thread sends every peer a {@link Message.Heartbeat}
 * about once a second ({@link #due}) and counts any data it reads from a peer as hearing it ({@link #heard}); the game
 * thread asks how long a peer has been silent ({@link #silentMs}) and the round trip the heartbeats measured
 * ({@link #rttMs}). A PC whose game is frozen still beats, since the I/O thread does it; a big transfer counts as
 * heard while its bytes arrive. Plain Java; the caller gives the time, from {@link #now}.
 */
final class Liveness {

    /** How often a heartbeat goes to each peer. */
    static final long BEAT_MS = 1_000;

    private static final class Line {
        volatile long heardMs;
        volatile int rttMs = -1;
        // I/O thread only
        long nextBeatMs = Long.MIN_VALUE;
        long echoMs;
        long echoHeardMs;
        boolean echoed;

        Line(long now) {
            heardMs = now;
        }
    }

    private final Map<Integer, Line> lines = new ConcurrentHashMap<>();
    /** Test outages (SOS-175): until then nothing is sent or heard, as if the line were down. */
    private volatile long silentUntilMs = Long.MIN_VALUE;

    /** Milliseconds of a clock that only goes forward; only ever compared on this PC. */
    static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /** I/O thread: a connection came up; it counts as just heard. */
    void add(int peer, long now) {
        lines.put(peer, new Line(now));
    }

    /** I/O thread: a connection is gone. */
    void remove(int peer) {
        lines.remove(peer);
    }

    /** I/O thread: data from {@code peer} arrived. */
    void heard(int peer, long now) {
        Line l = lines.get(peer);
        if (l != null && now >= silentUntilMs)
            l.heardMs = now;
    }

    /** I/O thread: {@code peer}'s heartbeat arrived: heard, and its echo gives the round trip. */
    void beat(int peer, Message.Heartbeat h, long now) {
        Line l = lines.get(peer);
        if (l == null || now < silentUntilMs)
            return;
        l.heardMs = now;
        l.echoMs = h.sentMs();
        l.echoHeardMs = now;
        l.echoed = true;
        if (h.heldMs() >= 0)
            l.rttMs = (int) Math.max(0, Math.min(Integer.MAX_VALUE, now - h.echoMs() - h.heldMs()));
    }

    /** I/O thread: the heartbeat due to {@code peer} now, or null; once returned, the next is due a beat later. */
    Message.Heartbeat due(int peer, long now) {
        Line l = lines.get(peer);
        if (l == null || now < silentUntilMs || now < l.nextBeatMs)
            return null;
        l.nextBeatMs = now + BEAT_MS;
        int held = !l.echoed ? -1 : (int) Math.min(Integer.MAX_VALUE, now - l.echoHeardMs);
        return new Message.Heartbeat(now, l.echoMs, held);
    }

    /** How long {@code peer} has been silent; 0 for a peer this PC doesn't know. Any thread. */
    long silentMs(int peer, long now) {
        Line l = lines.get(peer);
        return l == null ? 0 : Math.max(0, now - l.heardMs);
    }

    /** {@code peer}'s last measured round trip, or -1 before the first. Any thread. */
    int rttMs(int peer) {
        Line l = lines.get(peer);
        return l == null ? -1 : l.rttMs;
    }

    /** Test outages only (SOS-175): no heartbeat goes and nothing counts as heard until {@code untilMs}. */
    void silence(long untilMs) {
        silentUntilMs = untilMs;
    }
}
