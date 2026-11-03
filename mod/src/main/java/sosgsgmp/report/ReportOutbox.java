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

import sosgsgmp.net.Message;
import sosgsgmp.net.Protocol;

/**
 * A joining PC's files on their way to the host's reports (SOS-133), a piece at a time within a budget of bytes per
 * second, so the game's own traffic always comes first. A followed log is sent from its start and then as it grows; a
 * whole file (a save, a crash file) is read once when it is added. Plain Java; game thread only.
 */
public final class ReportOutbox {

    /** Bytes per second at most: about half of what a game sends now (SOS-96). */
    public static final int BYTES_PER_SECOND = 32 * 1024;
    /** A followed log's size is looked at this often, in nanoseconds: its new lines reach the host within it. */
    static final long LOOK_NANOS = 5_000_000_000L;
    private static final int PIECE = Protocol.MAX_STREAM_PIECE;

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
        for (Whole w : files)
            if (w.name.equals(name))
                return;
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't read " + file + " for the host's reports", e);
        }
        if (bytes.length > Message.Report.MAX_TOTAL)
            throw new IllegalArgumentException("SOSGSGMP: " + file + " of " + bytes.length + " bytes is too large for a report");
        files.add(new Whole(name, bytes, done));
    }

    /** The next piece to send, or null while the budget or nothing waits. Call every frame with the time. */
    public Message.Report next(long nanos) {
        if (timed)
            budget = Math.min(PIECE, budget + (nanos - lastNanos) * 1e-9 * BYTES_PER_SECOND);
        lastNanos = nanos;
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
