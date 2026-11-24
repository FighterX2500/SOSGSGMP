package sosgsgmp.log;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * A joining PC's recorder entries on their way to the host (SOS-248): only the kinds that are this PC's alone
 * ({@link Recorder#local}), so the host holds a joiner's last second before a crash; what every PC records alike the
 * host records itself. A piece is whole records: an entry's 32 bytes, then, for a text kind, its text's bytes (its size
 * says how many; 0 when the ring dropped them). The host keeps the pieces in order as one growing file, {@value #NAME}.
 * A stream more than {@link #BEHIND_NANOS} behind the recorder jumps to its last second and says so in a
 * {@link Recorder#GAP} record (A: the entries skipped). Plain Java; game thread only.
 */
public final class RecorderStream {

    public static final String NAME = "recorder.stream";
    public static final long BEHIND_NANOS = 2_000_000_000L;
    private static final long JUMP_TO_NANOS = 1_000_000_000L;

    private final Recorder recorder;
    private final byte[] entry = new byte[Recorder.ENTRY];
    private final byte[] text = new byte[Recorder.MAX_TEXT];
    /** The next entry to look at, of all the recorder wrote. */
    private long next;
    /** Bytes streamed so far: the next piece's offset. */
    private long sent;

    public RecorderStream(Recorder recorder) {
        this.recorder = recorder;
        this.next = Math.max(0, recorder.written() - Recorder.ENTRIES);
    }

    /** Whether the recorder holds entries not looked at yet: nothing is made while it doesn't. */
    public boolean waiting() {
        return recorder.written() > next;
    }

    /** The next piece's offset in the stream. */
    public long sent() {
        return sent;
    }

    /** The next piece, at most {@code max} bytes of whole records, or null when there is none; {@link #sent()} moves past it. */
    public byte[] next(int max) {
        long written = recorder.written();
        if (written <= next || max < Recorder.ENTRY)
            return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 4096));
        long now = recorder.now();
        if (next < written - Recorder.ENTRIES || time(next) < now - BEHIND_NANOS)
            gap(out, written, now);
        while (next < written) {
            recorder.entry(next, entry, 0);
            int kind = Bytes.getShort(entry, Recorder.E_KIND);
            if (!Recorder.local(kind)) {
                next++;
                continue;
            }
            int size = Recorder.text(kind) ? Bytes.getInt(entry, Recorder.E_SIZE) : 0;
            if (size > 0 && !recorder.payload(Bytes.getLong(entry, Recorder.E_A), size, text, 0)) {
                size = 0; // the ring dropped its text
                Bytes.putInt(entry, Recorder.E_SIZE, 0);
            }
            if (out.size() + Recorder.ENTRY + size > max)
                break;
            out.write(entry, 0, Recorder.ENTRY);
            out.write(text, 0, size);
            next++;
        }
        if (out.size() == 0)
            return null;
        byte[] piece = out.toByteArray();
        sent += piece.length;
        return piece;
    }

    private long time(long index) {
        recorder.entry(index, entry, 0);
        return Bytes.getLong(entry, Recorder.E_TIME);
    }

    /** Jumps to the recorder's last second, and writes the gap's record. */
    private void gap(ByteArrayOutputStream out, long written, long now) {
        long to = written;
        long oldest = Math.max(next, written - Recorder.ENTRIES);
        while (to > oldest && time(to - 1) >= now - JUMP_TO_NANOS)
            to--;
        long skipped = to - next;
        next = to;
        Arrays.fill(entry, (byte) 0);
        Bytes.putLong(entry, Recorder.E_TIME, now);
        Bytes.putInt(entry, Recorder.E_STEP, -1);
        Bytes.putShort(entry, Recorder.E_KIND, Recorder.GAP);
        Bytes.putShort(entry, Recorder.E_PEER, -1);
        Bytes.putLong(entry, Recorder.E_A, skipped);
        out.write(entry, 0, Recorder.ENTRY);
    }

    /** Big-endian numbers in a byte array, as the recorder's file has them. */
    static final class Bytes {
        private Bytes() {
        }

        static short getShort(byte[] b, int at) {
            return (short) ((b[at] & 0xFF) << 8 | b[at + 1] & 0xFF);
        }

        static int getInt(byte[] b, int at) {
            return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
        }

        static long getLong(byte[] b, int at) {
            return (long) getInt(b, at) << 32 | getInt(b, at + 4) & 0xFFFFFFFFL;
        }

        static void putShort(byte[] b, int at, int v) {
            b[at] = (byte) (v >> 8);
            b[at + 1] = (byte) v;
        }

        static void putInt(byte[] b, int at, int v) {
            for (int i = 0; i < 4; i++)
                b[at + i] = (byte) (v >> 24 - 8 * i);
        }

        static void putLong(byte[] b, int at, long v) {
            putInt(b, at, (int) (v >> 32));
            putInt(b, at + 4, (int) v);
        }
    }
}
