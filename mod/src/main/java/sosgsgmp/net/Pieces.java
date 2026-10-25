package sosgsgmp.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Data larger than one message, sent as numbered pieces under one transfer number: the host's terrain in the map
 * step ({@link Message.MapTerrain}). {@link #split} cuts the data; a {@link Receiver} puts it back together. Transfer
 * numbers only grow: a piece of an older transfer, a repeated piece or one that doesn't fit changes nothing.
 */
public final class Pieces {

    /** Data bytes in one piece; the rest of a message's room is for its header. */
    public static final int PIECE_BYTES = 60 * 1024;
    /** Most pieces in one transfer: 16 MB of data. */
    public static final int MAX_PIECES = 16 * 1024 * 1024 / PIECE_BYTES;

    private Pieces() {}

    /** The data in pieces of {@link #PIECE_BYTES}, the last one shorter; empty data is one empty piece. */
    public static List<byte[]> split(byte[] data) {
        int count = Math.max(1, (data.length + PIECE_BYTES - 1) / PIECE_BYTES);
        if (count > MAX_PIECES)
            throw new IllegalArgumentException("SOSGSGMP: " + data.length + " bytes need " + count + " pieces; at most " + MAX_PIECES);
        List<byte[]> pieces = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            pieces.add(Arrays.copyOfRange(data, i * PIECE_BYTES, Math.min(data.length, (i + 1) * PIECE_BYTES)));
        return pieces;
    }

    /** Collects the pieces of the latest transfer. Game thread only. */
    public static final class Receiver {

        /** The transfer being collected; 0 before the first. */
        private int transfer;
        private byte[][] pieces = new byte[0][];
        private int received;

        /**
         * Takes one piece. Returns the whole data when this piece completes its transfer, otherwise null. A newer
         * transfer drops the pieces collected so far; a piece of an older or completed transfer, a repeat, or one
         * whose count or index doesn't fit changes nothing.
         */
        public byte[] accept(int transfer, int index, int count, byte[] bytes) {
            if (transfer < this.transfer || count < 1 || count > MAX_PIECES)
                return null;
            if (transfer > this.transfer) {
                this.transfer = transfer;
                pieces = new byte[count][];
                received = 0;
            }
            if (received == pieces.length || count != pieces.length || index < 0 || index >= count || pieces[index] != null)
                return null;
            pieces[index] = bytes.clone();
            received++;
            if (received < count)
                return null;
            int length = 0;
            for (byte[] p : pieces)
                length += p.length;
            byte[] data = new byte[length];
            int at = 0;
            for (byte[] p : pieces) {
                System.arraycopy(p, 0, data, at, p.length);
                at += p.length;
            }
            return data;
        }
    }
}
