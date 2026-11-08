package sosgsgmp.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resumable message streams (SOS-178): each message to a peer gets the next sequence number and is kept until the
 * peer says it arrived ({@link Message.Heartbeat#received}); after a broken connection, what the peer lacks is sent
 * again ({@link #after}). Each message from a peer is taken once and in order ({@link #in}). A broken connection then
 * loses nothing and repeats nothing, so the sessions above never learn of it. Plain Java; the node's I/O thread only.
 */
final class Resend {

    /** What to do with a message from the network. */
    enum Verdict {
        /** The next one: deliver it. */
        NEXT,
        /** Had already: drop it. */
        REPEAT,
        /** Past a gap, as after a Steam session that lost messages: drop it, and ask for those after {@link #received}. */
        GAP
    }

    private record Kept(long seq, byte[] wire) {}

    private static final class Line {
        long sent;
        long received;
        final ArrayDeque<Kept> kept = new ArrayDeque<>();
        long keptBytes;
    }

    private final Map<Integer, Line> lines = new HashMap<>();

    /** A new peer: both streams start at 0. */
    void add(int peer) {
        lines.put(peer, new Line());
    }

    void remove(int peer) {
        lines.remove(peer);
    }

    boolean has(int peer) {
        return lines.containsKey(peer);
    }

    /** {@code encoded} ({@link Protocol#encode}) as the next message to {@code peer}, kept until acknowledged. */
    byte[] wrap(int peer, byte[] encoded) {
        Line l = line(peer);
        long seq = ++l.sent;
        byte[] wire = Protocol.sequenced(seq, encoded);
        l.kept.add(new Kept(seq, wire));
        l.keptBytes += wire.length;
        return wire;
    }

    /** {@code peer} has had our first {@code received} messages: they are no longer kept. */
    void acked(int peer, long received) {
        Line l = lines.get(peer);
        if (l == null)
            return;
        Kept k;
        while ((k = l.kept.peek()) != null && k.seq() <= received) {
            l.kept.poll();
            l.keptBytes -= k.wire().length;
        }
    }

    /** What {@code peer} lacks after having our first {@code received}, to send again in order. */
    List<byte[]> after(int peer, long received) {
        Line l = line(peer);
        if (received > l.sent)
            throw new IllegalArgumentException("SOSGSGMP: peer " + peer + " says it had " + received + " messages; " + l.sent + " were sent");
        acked(peer, received);
        List<byte[]> again = new ArrayList<>(l.kept.size());
        for (Kept k : l.kept)
            again.add(k.wire());
        return again;
    }

    /** Message {@code seq} from {@code peer} arrived; on {@link Verdict#NEXT} it counts as had. */
    Verdict in(int peer, long seq) {
        Line l = line(peer);
        if (seq <= l.received)
            return Verdict.REPEAT;
        if (seq > l.received + 1)
            return Verdict.GAP;
        l.received = seq;
        return Verdict.NEXT;
    }

    /** How many messages from {@code peer} were taken; 0 for a peer this node doesn't know. */
    long received(int peer) {
        Line l = lines.get(peer);
        return l == null ? 0 : l.received;
    }

    /** The bytes kept for {@code peer}, waiting for its acknowledgement. */
    long keptBytes(int peer) {
        Line l = lines.get(peer);
        return l == null ? 0 : l.keptBytes;
    }

    private Line line(int peer) {
        Line l = lines.get(peer);
        if (l == null)
            throw new IllegalArgumentException("SOSGSGMP: no stream with peer " + peer);
        return l;
    }
}
