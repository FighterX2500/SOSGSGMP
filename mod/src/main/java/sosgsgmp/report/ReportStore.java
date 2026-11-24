package sosgsgmp.report;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import sosgsgmp.log.RecorderStream;
import sosgsgmp.net.Message;

/**
 * The host's reports from one joining PC (SOS-133): each piece written at its offset into that PC's folder, under a
 * name made here from the one sent, never a path. A repeated piece, or one past a gap, changes nothing, so a file
 * already here is never sent twice. Per session, at most {@link #MAX_LOGS} of logs and {@link #MAX_FILES} of whole
 * files. A joining PC's recorder stream ({@value RecorderStream#NAME}, SOS-248) is kept apart, outside the caps, as a
 * ring of two files of at most {@link #STREAM_FILE} bytes each: the stream itself, and {@code recorder.stream.1}, the
 * part before it. Each starts with {@link #STREAM_MAGIC}, then whole records. A store's first piece (a new session's:
 * a resume or a hot-join's restart, in the same campaign folder) starts a new file, the old one becoming the older half. Plain Java; game thread only.
 */
public final class ReportStore {

    public static final long MAX_LOGS = 32L * 1024 * 1024;
    public static final long MAX_FILES = 64L * 1024 * 1024;

    /** Each of the stream's two files at most: 16 MB for a joining PC's stream in all. */
    public static final long STREAM_FILE = 8L * 1024 * 1024;
    /** The first bytes of a stream file: the magic, then format 1 as an int, then an int of 0. */
    public static final byte[] STREAM_MAGIC = { 'S', 'O', 'S', 'G', 'S', 'T', 'R', 'M', 0, 0, 0, 1, 0, 0, 0, 0 };

    private final Path dir;
    /** The joining PC's stream offset this store expects next: this session's stream starts at 0. */
    private long streamNext;
    private boolean streamFailed;
    /** Whether this store has written its session's stream yet: a new session starts its own file. */
    private boolean streamStarted;
    private final String who;
    private long logs;
    private long files;
    private boolean capped;

    /** The folder for one PC's files, and its player's name for the log's lines. */
    public ReportStore(Path dir, String who) {
        this.dir = dir;
        this.who = who;
    }

    /** {@code name} as a file name here: letters, digits, space, '.', '-' and '_', not starting with a dot. */
    public static String clean(String name) {
        String s = name.replaceAll("[^A-Za-z0-9 ._-]", "_").strip();
        while (s.startsWith("."))
            s = s.substring(1);
        return s.isEmpty() ? "file" : s;
    }

    /**
     * Writes the piece. Returns a line for the playtest log when a whole file is complete, the first time a cap stops
     * a piece, or the write fails; null otherwise.
     */
    public String accept(Message.Report r) {
        if (r.name().equals(RecorderStream.NAME) && r.total() < 0)
            return stream(r);
        boolean log = r.total() < 0;
        if ((log ? logs : files) + r.length() > (log ? MAX_LOGS : MAX_FILES)) {
            if (capped)
                return null;
            capped = true;
            return "reports: " + who + "'s " + (log ? "logs" : "files") + " are over this session's cap; the rest is dropped";
        }
        Path file = dir.resolve(clean(r.name()));
        try {
            Files.createDirectories(dir);
            long have = Files.isRegularFile(file) ? Files.size(file) : 0;
            if (r.offset() != have)
                return null; // a repeat, a file already here, or a gap
            try (FileChannel out = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                out.position(have);
                ByteBuffer b = ByteBuffer.wrap(r.bytes());
                while (b.hasRemaining())
                    out.write(b);
            }
        } catch (IOException e) {
            return "reports: can't write " + who + "'s " + file.getFileName() + ": " + e;
        }
        if (log)
            logs += r.length();
        else
            files += r.length();
        if (!log && r.offset() + r.length() == r.total())
            return String.format(Locale.ROOT, "reports: %s's %s, %.1f MB", who, file.getFileName(), r.total() / 1048576.0);
        return null;
    }

    /**
     * A piece of the recorder stream: appended when it is the next one (a repeat changes nothing); a full file becomes
     * the ring's older half. A line for the playtest log the first time a write fails; null otherwise.
     */
    private String stream(Message.Report r) {
        if (r.offset() != streamNext)
            return null; // a repeat, or one past a gap
        streamNext += r.length();
        Path file = dir.resolve(RecorderStream.NAME);
        try {
            Files.createDirectories(dir);
            long have = Files.isRegularFile(file) ? Files.size(file) : 0;
            boolean newSession = !streamStarted; // a resume's or a restart's store: the last session's stream is the older half
            streamStarted = true;
            if (have > 0 && (newSession || have + r.length() > STREAM_FILE)) {
                Files.move(file, dir.resolve(RecorderStream.NAME + ".1"), StandardCopyOption.REPLACE_EXISTING);
                have = 0;
            }
            try (FileChannel out = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                out.position(have);
                if (have == 0)
                    write(out, STREAM_MAGIC);
                write(out, r.bytes());
            }
        } catch (IOException e) {
            if (streamFailed)
                return null;
            streamFailed = true;
            return "reports: can't keep " + who + "'s recorder stream: " + e;
        }
        return null;
    }

    private static void write(FileChannel out, byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining())
            out.write(b);
    }
}
