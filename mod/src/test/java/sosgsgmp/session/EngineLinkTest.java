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
        EngineLink host = new EngineLink(true, 0, hp);
        for (StreamKind k : StreamKind.values())
            hp.make(k, (byte) 1);
        hp.step = 42;
        List<Message.Stream> out = host.out();
        assertEquals(List.of(StreamKind.AI_LOG, StreamKind.REPORTS, StreamKind.HASHES), out.stream().map(Message.Stream::kind).toList());
        assertTrue(out.stream().allMatch(s -> s.pc() == 0 && s.step() == 42 && s.offset() == 0));

        FakePort pp = new FakePort();
        EngineLink peer = new EngineLink(false, 3, pp);
        for (StreamKind k : StreamKind.values())
            pp.make(k, (byte) 1);
        assertEquals(List.of(StreamKind.ORDERS, StreamKind.REPORTS, StreamKind.HASHES), peer.out().stream().map(Message.Stream::kind).toList());
        assertTrue(peer.out().isEmpty(), "nothing new");
    }

    @Test
    void piecesCarryTheirOffsetsAndALongStreamIsSplit() {
        FakePort hp = new FakePort();
        EngineLink host = new EngineLink(true, 0, hp);
        hp.make(StreamKind.AI_LOG, new byte[10]);
        assertEquals(0, host.out().get(0).offset());
        hp.make(StreamKind.AI_LOG, new byte[Protocol.MAX_STREAM_PIECE + 5]);
        List<Message.Stream> out = host.out();
        assertEquals(2, out.size());
        assertEquals(10, out.get(0).offset());
        assertEquals(Protocol.MAX_STREAM_PIECE, out.get(0).length());
        assertEquals(10 + Protocol.MAX_STREAM_PIECE, out.get(1).offset());
        assertEquals(5, out.get(1).length());
    }

    @Test
    void aRepeatedPieceChangesNothingAndAnOverlapFeedsOnlyItsNewBytes() {
        FakePort pp = new FakePort();
        EngineLink peer = new EngineLink(false, 1, pp);
        Message.Stream a = new Message.Stream(StreamKind.AI_LOG, 0, 0, 5, new byte[] {1, 2, 3});
        assertTrue(peer.in(a));
        assertFalse(peer.in(a), "the same piece again");
        assertTrue(peer.in(new Message.Stream(StreamKind.AI_LOG, 0, 2, 6, new byte[] {3, 4, 5})));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, pp.took(StreamKind.AI_LOG, 0));
        assertEquals(2, pp.taken.size());
    }

    @Test
    void aGapOrAStreamThisPcDoesntTakeBreaksTheProtocol() {
        EngineLink peer = new EngineLink(false, 1, new FakePort());
        assertNotNull(peer.problem(new Message.Stream(StreamKind.AI_LOG, 0, 3, 0, new byte[] {1})), "a gap");
        assertNotNull(peer.problem(new Message.Stream(StreamKind.ORDERS, 2, 0, 0, new byte[] {1})), "a peer takes no orders");
        assertNotNull(peer.problem(new Message.Stream(StreamKind.AI_LOG, 2, 0, 0, new byte[] {1})), "the log is the host's");
        assertNotNull(peer.problem(new Message.Stream(StreamKind.HASHES, 1, 0, 0, new byte[] {1})), "its own hashes");
        assertNull(peer.problem(new Message.Stream(StreamKind.HASHES, 2, 0, 0, new byte[] {1})));
        assertThrows(IllegalArgumentException.class, () -> peer.in(new Message.Stream(StreamKind.AI_LOG, 0, 3, 0, new byte[] {1})));

        EngineLink host = new EngineLink(true, 0, new FakePort());
        assertNull(host.problem(new Message.Stream(StreamKind.ORDERS, 2, 0, 0, new byte[] {1})));
        assertNotNull(host.problem(new Message.Stream(StreamKind.AI_LOG, 2, 0, 0, new byte[] {1})));
    }

    @Test
    void theHostIsPcZeroAndOnlyThere() {
        assertThrows(IllegalArgumentException.class, () -> new EngineLink(true, 1, new FakePort()));
        assertThrows(IllegalArgumentException.class, () -> new EngineLink(false, 0, new FakePort()));
    }
}
