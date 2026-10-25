package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class PiecesTest {

    private static byte[] data(int length) {
        byte[] b = new byte[length];
        new Random(length).nextBytes(b);
        return b;
    }

    @Test
    void dataSplitsIntoPiecesAndComesBackInAnyOrder() {
        byte[] d = data(2 * Pieces.PIECE_BYTES + 5);
        List<byte[]> pieces = Pieces.split(d);
        assertEquals(3, pieces.size());
        assertEquals(5, pieces.get(2).length);
        Pieces.Receiver r = new Pieces.Receiver();
        assertNull(r.accept(1, 2, 3, pieces.get(2)));
        assertNull(r.accept(1, 0, 3, pieces.get(0)));
        assertArrayEquals(d, r.accept(1, 1, 3, pieces.get(1)));
    }

    @Test
    void emptyAndExactDataSplitCleanly() {
        assertEquals(1, Pieces.split(new byte[0]).size());
        assertEquals(1, Pieces.split(data(Pieces.PIECE_BYTES)).size());
        assertArrayEquals(new byte[0], new Pieces.Receiver().accept(1, 0, 1, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Pieces.split(new byte[Pieces.MAX_PIECES * Pieces.PIECE_BYTES + 1]));
    }

    @Test
    void aRepeatedPieceChangesNothing() {
        byte[] d = data(Pieces.PIECE_BYTES + 1);
        List<byte[]> pieces = Pieces.split(d);
        Pieces.Receiver r = new Pieces.Receiver();
        assertNull(r.accept(1, 0, 2, pieces.get(0)));
        assertNull(r.accept(1, 0, 2, pieces.get(0)));
        assertArrayEquals(d, r.accept(1, 1, 2, pieces.get(1)));
        assertNull(r.accept(1, 1, 2, pieces.get(1)), "a completed transfer gives its data once");
    }

    @Test
    void aNewerTransferDropsTheOlderOneAndOlderPiecesAreIgnored() {
        List<byte[]> old = Pieces.split(data(Pieces.PIECE_BYTES + 1));
        byte[] d = data(10);
        Pieces.Receiver r = new Pieces.Receiver();
        assertNull(r.accept(1, 0, 2, old.get(0)));
        assertArrayEquals(d, r.accept(2, 0, 1, d));
        assertNull(r.accept(1, 1, 2, old.get(1)));
    }

    @Test
    void piecesThatDontFitAreIgnored() {
        Pieces.Receiver r = new Pieces.Receiver();
        byte[] p = data(3);
        assertNull(r.accept(1, 0, 2, p));
        assertNull(r.accept(1, 1, 3, p), "another count for the same transfer");
        assertNull(r.accept(1, 2, 2, p));
        assertNull(r.accept(1, -1, 2, p));
        assertNull(r.accept(1, 0, 0, p));
        assertNull(r.accept(1, 0, Pieces.MAX_PIECES + 1, p));
    }
}
