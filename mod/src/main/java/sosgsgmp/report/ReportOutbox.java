package sosgsgmp.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sosgsgmp.log.RecorderStream;
import sosgsgmp.net.Message;
import sosgsgmp.net.Protocol;

/**
 * A joining PC's files on their way to the host's reports (SOS-133), a piece at a time within a budget of bytes per
 * second, so the game's own traffic always comes first. A followed log is sent from its start and then as it grows; a
 * whole file (a save, a crash file) is read once when it is added. The recorder's stream (SOS-248), when there is one,
 * goes first, every quarter second, within a budget of its own. Plain Java; game thread only.
 */
public final class ReportOutbox {

    /** Bytes per second at most: about half of what a game sends now (SOS-96). */
    public static final int BYTES_PER_SECOND = 32 * 1024;
    /** A followed log's size is looked at this often, in nanoseconds: its new lines reach the host within it. */
    static final long LOOK_NANOS = 5_000_000_000L;
    private static final int PIECE = Protocol.MAX_STREAM_PIECE;
    /** The recorder stream's bytes per second at most (SOS-248), on top of the files'. */
    public static final int STREAM_BYTES_PER_SECOND = 16 * 1024;
    private static final int STREAM_PIECE = 16 * 1024;
    private RecorderStream stream;
    private double streamBudget;
    /**
     * A stream piece goes every quarter second at most: the host writes each to its file, which it must not do every
     * frame for every joining PC; a quarter second is well inside the last second the host is to hold.
     */
    public static final long STREAM_INTERVAL_NANOS = 250_000_000L;
    /** The time of the last stream piece, or none yet. */
    private long streamNanos = Long.MIN_VALUE;

    private final List<Followed> logs = new ArrayList<>();
    private final List<Whole> files = new ArrayList<>();
    private double budget;
    /** The time of the last call and of the last look at the logs; none before the first call. */
    private long lastNanos, lookedNanos;
    private boolean timed;

    /** A log that grows: sent from its start, then each new part. */
    public void follow(Path file) {
        logs.add(new Followed(file, file.getFileName().toString()));
    }

    /**
     * A whole file, read now and sent after what is already queued; {@code done} runs once its last piece is out. A
     * file of that name already queued is not queued twice.
     */
    public void add(Path file, Runnable done) {
        String name = file.getFileName().toString();
        if (queued(name))
            return;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't read " + file + " for the host's reports", e);
        }
        add(name, bytes, done);
    }

    /**
     * A whole file's {@code bytes} as {@code name}, read and changed by the caller (an {@code hs_err} file without its
     * environment, SOS-246), sent as {@link #add(Path, Runnable)} sends a file.
     */
    public void add(String name, byte[] bytes, Runnable done) {
        if (queued(name))
            return;
        if (bytes.length > Message.Report.MAX_TOTAL)
            throw new IllegalArgumentException("SOSGSGMP: " + name + " of " + bytes.length + " bytes is too large for a report");
        files.add(new Whole(name, bytes.clone(), done));
    }

    private boolean queued(String name) {
        for (Whole w : files)
            if (w.name.equals(name))
                return true;
        return false;
    }

    /** The recorder's stream, sent ahead of the logs and files as it grows (SOS-248). */
    public void stream(RecorderStream s) {
        stream = s;
    }

    /**
     * The next piece to send, or null while the budget or nothing waits. Call every frame with the time, a second time
     * in the frame for a log's or a file's piece after the stream's.
     */
    public Message.Report next(long nanos) {
        if (timed) {
            budget = Math.min(PIECE, budget + (nanos - lastNanos) * 1e-9 * BYTES_PER_SECOND);
            streamBudget = Math.min(STREAM_PIECE, streamBudget + (nanos - lastNanos) * 1e-9 * STREAM_BYTES_PER_SECOND);
        }
        lastNanos = nanos;
        if (stream != null && (streamNanos == Long.MIN_VALUE || nanos - streamNanos >= STREAM_INTERVAL_NANOS) && stream.waiting()) {
            timed = true;
            streamNanos = nanos;
            long at = stream.sent();
            byte[] piece = stream.next((int) streamBudget);
            if (piece != null) {
                streamBudget -= piece.length;
                return new Message.Report(RecorderStream.NAME, -1, at, piece);
            }
        }
        if (logs.isEmpty() && files.isEmpty())
            return null; // every frame: nothing made when nothing waits
        if (!timed || nanos - lookedNanos >= LOOK_NANOS) {
            lookedNanos = nanos;
            for (Followed f : logs)
                f.look();
        }
        timed = true;
        for (Followed f : logs)
            if (f.size > f.sent) {
                int n = (int) Math.min(PIECE, f.size - f.sent);
                if (budget < n)
                    return null;
                budget -= n;
                return f.piece(n);
            }
        if (files.isEmpty())
            return null;
        Whole w = files.get(0);
        int n = Math.min(PIECE, w.bytes.length - w.sent);
        if (budget < n)
            return null;
        budget -= n;
        Message.Report r = new Message.Report(w.name, w.bytes.length, w.sent, Arrays.copyOfRange(w.bytes, w.sent, w.sent + n));
        w.sent += n;
        if (w.sent == w.bytes.length) {
            files.remove(0);
            w.done.run();
        }
        return r;
    }

    /** Bytes still to send, of the whole files and of the logs as last looked at. */
    public long waiting() {
        long n = 0;
        for (Followed f : logs)
            n += f.size - f.sent;
        for (Whole w : files)
            n += w.bytes.length - w.sent;
        return n;
    }

    private static final class Followed {
        private final Path file;
        private final String name;
        private long size;
        private long sent;

        Followed(Path file, String name) {
            this.file = file;
            this.name = name;
        }

        /** Its size now; a file that went or can't be read is left as it was. */
        void look() {
            try {
                size = Math.max(sent, Files.size(file));
            } catch (IOException e) {
                // the log isn't there (yet): nothing to send from it
            }
        }

        Message.Report piece(int n) {
            ByteBuffer buffer = ByteBuffer.allocate(n);
            try (SeekableByteChannel in = Files.newByteChannel(file)) {
                in.position(sent);
                while (buffer.hasRemaining() && in.read(buffer) > 0) {
                    // reads until the piece is full or the file ends
                }
            } catch (IOException e) {
                size = sent; // read again at the next look
                return null;
            }
            byte[] bytes = new byte[buffer.position()];
            buffer.flip();
            buffer.get(bytes);
            Message.Report r = new Message.Report(name, -1, sent, bytes);
            sent += bytes.length;
            if (bytes.length < n)
                size = sent;
            return r;
        }
    }

    private static final class Whole {
        private final String name;
        private final byte[] bytes;
        private final Runnable done;
        private int sent;

        Whole(String name, byte[] bytes, Runnable done) {
            this.name = name;
            this.bytes = bytes;
            this.done = done;
        }
    }
}
