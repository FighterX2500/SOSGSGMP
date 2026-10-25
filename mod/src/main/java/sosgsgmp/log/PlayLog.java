package sosgsgmp.log;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The playtest log (SOS-63): one file per PC per game session, a timestamped line per event, flushed at once so a crash
 * loses nothing written before it. A text of several lines keeps its lines together, indented. Writing stops at the
 * first failure, which {@link #failure()} keeps for the caller to report once. Plain Java; game thread only.
 */
public final class PlayLog implements AutoCloseable {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_NAME = 32;

    private final Clock clock;
    private final Path file;
    private Writer out;
    private IOException failure;

    private PlayLog(Clock clock, Path file, Writer out, IOException failure) {
        this.clock = clock;
        this.file = file;
        this.out = out;
        this.failure = failure;
    }

    /** A new file in {@code dir} named by the time and {@code name}; on failure, a log that writes nothing. */
    public static PlayLog open(Path dir, String name, Clock clock) {
        Path file = dir.resolve(LocalDateTime.now(clock).format(FILE_TIME) + "-" + fileName(name) + ".log");
        try {
            Files.createDirectories(dir);
            Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            return new PlayLog(clock, file, out, null);
        } catch (IOException e) {
            return new PlayLog(clock, file, null, e);
        }
    }

    /** {@code name} as a file name part: letters, digits, '-' and '_' only, at most 32 characters. */
    static String fileName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }

    /** One entry: the time, then {@code text}; its further lines indented under it. */
    public void line(String text) {
        if (out == null)
            return;
        String time = LocalDateTime.now(clock).format(LINE_TIME);
        try {
            out.write(time + " " + text.replace("\r", "").replace("\n", "\n             ") + "\n");
            out.flush();
        } catch (IOException e) {
            fail(e);
        }
    }

    /** An entry for an exception: {@code context}, then its stack trace. */
    public void error(String context, Throwable t) {
        StringWriter trace = new StringWriter();
        t.printStackTrace(new PrintWriter(trace));
        line("ERROR " + context + "\n" + trace.toString().strip());
    }

    private void fail(IOException e) {
        failure = e;
        close();
    }

    /** The file, written or meant to be. */
    public Path file() {
        return file;
    }

    /** Why the log stopped writing, or null while it writes. */
    public IOException failure() {
        return failure;
    }

    @Override
    public void close() {
        if (out == null)
            return;
        Writer w = out;
        out = null;
        try {
            w.close();
        } catch (IOException e) {
            if (failure == null)
                failure = e;
        }
    }
}
