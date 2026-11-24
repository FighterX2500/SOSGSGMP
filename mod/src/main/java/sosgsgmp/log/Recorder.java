package sosgsgmp.log;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.LongSupplier;

/**
 * The recorder (SOS-248): what this PC did and heard, in a ring of small fixed entries in a memory-mapped file, so a
 * crash of the process keeps everything written (the pages belong to the OS). Plain Java; game thread only.
 *
 * <p>Format 1, big-endian:
 * <ul>
 * <li>A header of {@link #HEADER} bytes: the magic {@code SOSGSREC}, the format version, the header's size, the
 * session's start (epoch ms), the role (0 host, 1 joining PC), the entry ring's size and an entry's, the payload ring's
 * size, the entries written and the payload bytes written (both counting from the session's start, never wrapping),
 * and the player's name (a length, then UTF-8).</li>
 * <li>{@link #ENTRIES} entries of {@link #ENTRY} bytes: the time (ns since the session's start), the step (-1 for
 * none), the kind, the peer or human, a size, argument A (a long) and argument B (an int).</li>
 * <li>A payload ring of {@link #PAYLOAD} bytes: texts, UTF-8; a text entry's A is where its text starts (a count of
 * payload bytes written), its size the text's length in bytes.</li>
 * </ul>
 * An entry is written before the count of entries moves past it, so a crash mid-write loses only that entry.
 *
 * <p>A write is a few puts into the mapped buffer at a wrapping index: no allocation, no logging, no I/O call. Any
 * exception turns the recorder off for the session; {@link #failure()} says why, for the caller to log once.
 */
public final class Recorder implements AutoCloseable {

    public static final byte[] MAGIC = "SOSGSREC".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    public static final int HEADER = 256;
    public static final int ENTRIES = 65536;
    public static final int ENTRY = 32;
    public static final int PAYLOAD = 2 * 1024 * 1024;
    /** The longest text kept, in bytes: a longer one is cut. */
    public static final int MAX_TEXT = 2048;
    private static final int MAX_NAME = 64;

    // header offsets
    static final int H_VERSION = 8, H_SIZE = 12, H_START = 16, H_ROLE = 24, H_ENTRIES = 28, H_ENTRY = 32, H_PAYLOAD = 36,
            H_WRITTEN = 40, H_PAYLOAD_WRITTEN = 48, H_NAME = 56;
    // entry offsets
    static final int E_TIME = 0, E_STEP = 8, E_KIND = 12, E_PEER = 14, E_SIZE = 16, E_A = 20, E_B = 28;

    /** The kinds of entry, format 1. Arguments: see {@code tools/recorder.py}, which names them. */
    public static final short NET_IN = 1, NET_OUT = 2, NET_CONNECT = 3, NET_DISCONNECT = 4, COMMAND = 5, AI = 6, ARMY = 7,
            TRANSFER = 8, HASHES = 9, DESYNC = 10, HOLD_START = 11, HOLD_END = 12, SPEED = 13, FRAME = 14,
            LINE_SAME = 15, LINE_LOCAL = 16, LOADING = 17, SESSION = 18, TYPES = 19, GAP = 20;

    /** Whether a kind's entries are this PC's alone: what the host can't record itself (the stream, SOS-248). */
    public static boolean local(int kind) {
        return switch (kind) {
            case NET_IN, NET_OUT, NET_CONNECT, NET_DISCONNECT, HOLD_START, HOLD_END, SPEED, FRAME, LINE_LOCAL, LOADING, SESSION,
                    TYPES, GAP -> true;
            default -> false;
        };
    }

    /** Whether a kind's entries hold a text in the payload ring. */
    public static boolean text(int kind) {
        return kind == LINE_SAME || kind == LINE_LOCAL || kind == SESSION || kind == TYPES;
    }

    private final Path file;
    private final MappedByteBuffer map;
    private final long startNanos;
    /** The clock: {@code System::nanoTime}, or a test's. */
    private final LongSupplier nanos;
    private LongSupplier step = () -> -1;
    private long written, payloadWritten;
    private Exception failure;
    private boolean closed;

    private Recorder(Path file, MappedByteBuffer map, LongSupplier nanos) {
        this.file = file;
        this.map = map;
        this.nanos = nanos;
        this.startNanos = nanos.getAsLong();
    }

    /**
     * A new recording in {@code file} (made, or reused from the start, at its full size), for this PC's {@code role} (true: the
     * host) and player {@code name}, the session started at {@code startMillis}. Throws if the file can't be made.
     */
    public static Recorder open(Path file, boolean host, String name, long startMillis) throws IOException {
        return open(file, host, name, startMillis, System::nanoTime);
    }

    /** {@link #open(Path, boolean, String, long)} with the clock {@code nanos}, for the tests. */
    static Recorder open(Path file, boolean host, String name, long startMillis, LongSupplier nanos) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        MappedByteBuffer map;
        // not truncated: Windows refuses that while an earlier session's mapping of the file lives (it goes only with
        // the garbage collector); a header with no entries written makes the old ones unread
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            map = ch.map(FileChannel.MapMode.READ_WRITE, 0, size()); // the mapping outlives the channel
        }
        map.put(0, MAGIC);
        map.putInt(H_VERSION, VERSION);
        map.putInt(H_SIZE, HEADER);
        map.putLong(H_START, startMillis);
        map.put(H_ROLE, (byte) (host ? 0 : 1));
        map.putInt(H_ENTRIES, ENTRIES);
        map.putInt(H_ENTRY, ENTRY);
        map.putInt(H_PAYLOAD, PAYLOAD);
        map.putLong(H_WRITTEN, 0);
        map.putLong(H_PAYLOAD_WRITTEN, 0);
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(n.length, MAX_NAME);
        map.putShort(H_NAME, (short) len);
        map.put(H_NAME + 2, n, 0, len);
        return new Recorder(file, map, nanos);
    }

    /** The file's size: the header, the entries and the payload ring. */
    public static long size() {
        return HEADER + (long) ENTRIES * ENTRY + PAYLOAD;
    }

    /** Where the step of each entry comes from from now on: the engine's, once it runs. Must not allocate. */
    public void stepFrom(LongSupplier step) {
        this.step = step;
    }

    public Path file() {
        return file;
    }

    /** Why the recorder stopped, or null while it records. */
    public Exception failure() {
        return failure;
    }

    /** Entries written since the session's start. */
    public long written() {
        return written;
    }

    /** An entry at the step now. */
    public void record(int kind, int peer, int size, long a, int b) {
        if (failure != null || closed)
            return;
        try {
            put(kind, peer, size, a, b);
        } catch (RuntimeException e) {
            // the recorder's boundary: it stops for the session, and the playtest log says so once
            failure = e;
        }
    }

    /** A text entry at the step now: {@code text} in the payload ring, cut at {@link #MAX_TEXT} bytes. */
    public void text(int kind, int peer, CharSequence text) {
        if (failure != null || closed)
            return;
        try {
            long at = payloadWritten;
            int n = utf8(text);
            put(kind, peer, n, at, 0);
        } catch (RuntimeException e) {
            // the recorder's boundary: it stops for the session, and the playtest log says so once
            failure = e;
        }
    }

    private void put(int kind, int peer, int size, long a, int b) {
        int at = HEADER + (int) (written % ENTRIES) * ENTRY;
        map.putLong(at + E_TIME, nanos.getAsLong() - startNanos);
        map.putInt(at + E_STEP, (int) step.getAsLong());
        map.putShort(at + E_KIND, (short) kind);
        map.putShort(at + E_PEER, (short) peer);
        map.putInt(at + E_SIZE, size);
        map.putLong(at + E_A, a);
        map.putInt(at + E_B, b);
        written++;
        map.putLong(H_WRITTEN, written); // after the entry: a crash mid-write loses only it
    }

    /** Writes {@code text} as UTF-8 into the payload ring, whole characters up to {@link #MAX_TEXT} bytes; its length. */
    private int utf8(CharSequence text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i);
            if (Character.isHighSurrogate((char) c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                if (n + 4 > MAX_TEXT)
                    break;
                int cp = Character.toCodePoint((char) c, text.charAt(++i));
                payload(0xF0 | cp >> 18);
                payload(0x80 | cp >> 12 & 0x3F);
                payload(0x80 | cp >> 6 & 0x3F);
                payload(0x80 | cp & 0x3F);
                n += 4;
            } else if (c < 0x80) {
                if (n + 1 > MAX_TEXT)
                    break;
                payload(c);
                n++;
            } else if (c < 0x800) {
                if (n + 2 > MAX_TEXT)
                    break;
                payload(0xC0 | c >> 6);
                payload(0x80 | c & 0x3F);
                n += 2;
            } else {
                if (n + 3 > MAX_TEXT)
                    break;
                payload(0xE0 | c >> 12);
                payload(0x80 | c >> 6 & 0x3F);
                payload(0x80 | c & 0x3F);
                n += 3;
            }
        }
        map.putLong(H_PAYLOAD_WRITTEN, payloadWritten);
        return n;
    }

    private void payload(int b) {
        map.put(HEADER + ENTRIES * ENTRY + (int) (payloadWritten % PAYLOAD), (byte) b);
        payloadWritten++;
    }

    /** Copies the file as it is now to {@code to} (an incident's copy). */
    public void copy(Path to) throws IOException {
        map.force();
        Files.createDirectories(to.toAbsolutePath().getParent());
        Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Entry {@code index} (of all written; it must be one the ring still holds) into {@code out}'s next 32 bytes. */
    public void entry(long index, byte[] out, int offset) {
        if (index < 0 || index >= written || index < written - ENTRIES)
            throw new IllegalArgumentException("SOSGSGMP: recorder entry " + index + " of " + written + " is not in the ring");
        map.get(HEADER + (int) (index % ENTRIES) * ENTRY, out, offset, ENTRY);
    }

    /** Payload bytes from {@code at} into {@code out}; false when the ring no longer holds them all. */
    public boolean payload(long at, int length, byte[] out, int offset) {
        if (at < payloadWritten - PAYLOAD || at + length > payloadWritten)
            return false;
        for (int i = 0; i < length; i++)
            out[offset + i] = map.get(HEADER + ENTRIES * ENTRY + (int) ((at + i) % PAYLOAD));
        return true;
    }

    /** The time an entry would have now, ns since the session's start. */
    public long now() {
        return nanos.getAsLong() - startNanos;
    }

    /** No more entries: what is mapped goes to the disk; the mapping itself goes with the garbage collector. */
    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        try {
            map.force();
        } catch (RuntimeException e) {
            // the recorder's boundary: nothing more is written anyway
            if (failure == null)
                failure = e;
        }
    }

    public boolean closed() {
        return closed;
    }
}
