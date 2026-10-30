package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sosgsgmp.net.Message;
import sosgsgmp.net.Protocol;
import sosgsgmp.net.StreamKind;

class EngineLinkTest {

    /** An engine that makes what the test queues and records what it takes. */
    static final class FakePort implements EnginePort {
        final Map<StreamKind, ByteArrayOutputStream> made = new EnumMap<>(StreamKind.class);
        final List<String> taken = new ArrayList<>();
        final Map<String, ByteArrayOutputStream> takenBytes = new java.util.HashMap<>();
        long step;

        synchronized void make(StreamKind kind, byte... bytes) {
            made.computeIfAbsent(kind, k -> new ByteArrayOutputStream()).writeBytes(bytes);
        }

        @Override
        public synchronized byte[] out(StreamKind kind) {
            ByteArrayOutputStream b = made.remove(kind);
            return b == null ? new byte[0] : b.toByteArray();
        }

        @Override
        public synchronized void in(StreamKind kind, int pc, byte[] bytes) {
            taken.add(kind + "@" + pc);
            takenBytes.computeIfAbsent(kind + "@" + pc, k -> new ByteArrayOutputStream()).writeBytes(bytes);
        }

        synchronized byte[] took(StreamKind kind, int pc) {
            ByteArrayOutputStream b = takenBytes.get(kind + "@" + pc);
            return b == null ? new byte[0] : b.toByteArray();
        }

        @Override
        public long step() {
            return step;
        }
    }

    @Test
    void theHostMakesTheLogAndAPeerItsOrders() {
        FakePort hp = new FakePort();
        EngineLink host = new EngineLink(true, 0, 0, hp);
        for (StreamKind k : StreamKind.values())
            hp.make(k, (byte) 1);
        hp.step = 42;
        List<Message.Stream> out = host.out();
        assertEquals(List.of(StreamKind.AI_LOG, StreamKind.REPORTS, StreamKind.HASHES, StreamKind.BATTLE), out.stream().map(Message.Stream::kind).toList());
        assertTrue(out.stream().allMatch(s -> s.pc() == 0 && s.step() == 42 && s.offset() == 0));

        FakePort pp = new FakePort();
        EngineLink peer = new EngineLink(false, 3, 0, pp);
        for (StreamKind k : StreamKind.values())
            pp.make(k, (byte) 1);
        assertEquals(List.of(StreamKind.ORDERS, StreamKind.REPORTS, StreamKind.HASHES, StreamKind.BATTLE), peer.out().stream().map(Message.Stream::kind).toList());
        assertTrue(peer.out().isEmpty(), "nothing new");
    }

    /** The pieces {@code from} sends now, fed to {@code to}: whether any had new bytes. */
    private static boolean pass(EngineLink from, EngineLink to) {
        boolean fresh = false;
        for (Message.Stream s : from.out())
            fresh |= to.in(s);
        return fresh;
    }

    /** {@code n} bytes Deflate can't shrink. */
    private static byte[] noise(int n, long seed) {
        byte[] b = new byte[n];
        new java.util.Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void piecesCarryCompressedOffsetsAndALongStreamIsSplit() {
        FakePort hp = new FakePort();
        EngineLink host = new EngineLink(true, 0, 0, hp);
        hp.make(StreamKind.AI_LOG, new byte[1000]);
        List<Message.Stream> first = host.out();
        assertEquals(1, first.size());
        assertEquals(0, first.get(0).offset());
        assertTrue(first.get(0).length() < 100, "1000 zero bytes compress: " + first.get(0).length());
        hp.make(StreamKind.AI_LOG, noise(Protocol.MAX_STREAM_PIECE + 5, 1));
        List<Message.Stream> out = host.out();
        assertEquals(2, out.size());
        assertEquals(first.get(0).length(), out.get(0).offset(), "offsets count compressed bytes");
        assertEquals(Protocol.MAX_STREAM_PIECE, out.get(0).length());
        assertEquals(out.get(0).offset() + Protocol.MAX_STREAM_PIECE, out.get(1).offset());
        assertTrue(host.out().isEmpty(), "nothing new, nothing sent");
    }

    @Test
    void whatOnePcMakesTheOtherTakesByteForByte() {
        FakePort hp = new FakePort();
        FakePort pp = new FakePort();
        EngineLink host = new EngineLink(true, 0, 0, hp);
        EngineLink peer = new EngineLink(false, 1, 0, pp);
        ByteArrayOutputStream made = new ByteArrayOutputStream();
        for (int i = 0; i < 20; i++) {
            byte[] b = i % 3 == 0 ? noise(Protocol.MAX_STREAM_PIECE * 2 + i, i) : new byte[100 + i];
            hp.make(StreamKind.AI_LOG, b);
            made.writeBytes(b);
            assertTrue(pass(host, peer));
        }
        assertArrayEquals(made.toByteArray(), pp.took(StreamKind.AI_LOG, 0));
        EngineLink.Traffic h = host.traffic();
        EngineLink.Traffic p = peer.traffic();
        assertEquals(made.size(), h.rawOut());
        assertEquals(made.size(), p.rawIn());
        assertEquals(h.wireOut(), p.wireIn());
    }

    @Test
    void aRepeatedPieceChangesNothingAndAnOverlapFeedsOnlyItsNewBytes() {
        FakePort hp = new FakePort();
        FakePort pp = new FakePort();
        EngineLink host = new EngineLink(true, 0, 0, hp);
        EngineLink peer = new EngineLink(false, 1, 0, pp);
        hp.make(StreamKind.AI_LOG, (byte) 1, (byte) 2, (byte) 3);
        Message.Stream a = host.out().get(0);
        hp.make(StreamKind.AI_LOG, (byte) 4, (byte) 5);
        Message.Stream b = host.out().get(0);
        assertTrue(peer.in(a));
        assertFalse(peer.in(a), "the same piece again");
        byte[] both = new byte[a.length() + b.length()];
        System.arraycopy(a.bytes(), 0, both, 0, a.length());
        System.arraycopy(b.bytes(), 0, both, a.length(), b.length());
        assertTrue(peer.in(new Message.Stream(StreamKind.AI_LOG, 0, 0, 6, both)), "a's bytes again, then b's");
        assertFalse(peer.in(b));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, pp.took(StreamKind.AI_LOG, 0));
        assertEquals(2, pp.taken.size());
    }

    @Test
    void theHostRelaysAPeersPiecesAsTheyCame() {
        FakePort hp = new FakePort();
        FakePort ap = new FakePort();
        FakePort bp = new FakePort();
        EngineLink host = new EngineLink(true, 0, 0, hp);
        EngineLink a = new EngineLink(false, 1, 0, ap);
        EngineLink b = new EngineLink(false, 2, 0, bp);
        byte[] report = noise(3000, 7);
        for (int i = 0; i < 5; i++) {
            report[i] ^= 1; // the next report is nearly the same
            ap.make(StreamKind.REPORTS, report);
            for (Message.Stream s : a.out())
                if (host.in(s) && EngineLink.relayed(s.kind()))
                    assertTrue(b.in(s));
        }
        assertArrayEquals(hp.took(StreamKind.REPORTS, 1), bp.took(StreamKind.REPORTS, 1));
        assertEquals(5 * 3000, bp.took(StreamKind.REPORTS, 1).length);
        assertTrue(a.traffic().wireOut() < 2 * 3000, "four nearly equal reports in the window cost little: " + a.traffic());
    }

    @Test
    void bytesThatArentTheStreamBreakIt() {
        EngineLink peer = new EngineLink(false, 1, 0, new FakePort());
        assertThrows(IllegalArgumentException.class, () -> peer.in(new Message.Stream(StreamKind.AI_LOG, 0, 0, 0, new byte[] {-1, -1, -1})));
    }

    @Test
    void aGapOrAStreamThisPcDoesntTakeBreaksTheProtocol() {
        EngineLink peer = new EngineLink(false, 1, 0, new FakePort());
        assertNotNull(peer.problem(new Message.Stream(StreamKind.AI_LOG, 0, 3, 0, new byte[] {1})), "a gap");
        assertNotNull(peer.problem(new Message.Stream(StreamKind.ORDERS, 2, 0, 0, new byte[] {1})), "a peer takes no orders");
        assertNotNull(peer.problem(new Message.Stream(StreamKind.AI_LOG, 2, 0, 0, new byte[] {1})), "the log is the host's");
        assertNotNull(peer.problem(new Message.Stream(StreamKind.HASHES, 1, 0, 0, new byte[] {1})), "its own hashes");
        assertNull(peer.problem(new Message.Stream(StreamKind.HASHES, 2, 0, 0, new byte[] {1})));
        assertThrows(IllegalArgumentException.class, () -> peer.in(new Message.Stream(StreamKind.AI_LOG, 0, 3, 0, new byte[] {1})));

        EngineLink host = new EngineLink(true, 0, 0, new FakePort());
        assertNull(host.problem(new Message.Stream(StreamKind.ORDERS, 2, 0, 0, new byte[] {1})));
        assertNotNull(host.problem(new Message.Stream(StreamKind.AI_LOG, 2, 0, 0, new byte[] {1})));
    }

    @Test
    void theHostIsTheHostPcAndOnlyThere() {
        assertThrows(IllegalArgumentException.class, () -> new EngineLink(true, 1, 0, new FakePort()));
        assertThrows(IllegalArgumentException.class, () -> new EngineLink(false, 0, 0, new FakePort()));
        EngineLink peer = new EngineLink(false, 0, 2, new FakePort()); // a resumed campaign hosted by slot 2 (SOS-64)
        assertNull(peer.problem(new Message.Stream(StreamKind.AI_LOG, 2, 0, 0, new byte[] {1})), "the log is slot 2's");
        assertNotNull(peer.problem(new Message.Stream(StreamKind.AI_LOG, 1, 0, 0, new byte[] {1})));
    }
}
