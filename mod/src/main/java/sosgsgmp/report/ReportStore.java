package sosgsgmp.report;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import sosgsgmp.net.Message;

/**
 * The host's reports from one joining PC (SOS-133): each piece written at its offset into that PC's folder, under a
 * name made here from the one sent, never a path. A repeated piece, or one past a gap, changes nothing, so a file
 * already here is never sent twice. Per session, at most {@link #MAX_LOGS} of logs and {@link #MAX_FILES} of whole
 * files. Plain Java; game thread only.
 */
public final class ReportStore {

    public static final long MAX_LOGS = 32L * 1024 * 1024;
    public static final long MAX_FILES = 64L * 1024 * 1024;

    private final Path dir;
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
}
