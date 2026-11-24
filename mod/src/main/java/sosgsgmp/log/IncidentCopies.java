package sosgsgmp.log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The recorder's incident copies (SOS-248) in its folder: {@code <log name>-<step>-<why>.rec}, whose log name starts
 * with the log's date, so with a digit. The current files, {@code <host|join>-<player>.rec}, are never touched. Plain
 * Java.
 */
public final class IncidentCopies {

    /** The copies kept, the newest. */
    public static final int KEEP = 20;

    private IncidentCopies() {
    }

    /** Deletes all but the {@code keep} newest copies in {@code dir}; how many went. A folder not there has none. */
    public static int prune(Path dir, int keep) throws IOException {
        if (!Files.isDirectory(dir))
            return 0;
        List<Path> copies = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.rec")) {
            for (Path f : files)
                if (Character.isDigit(f.getFileName().toString().charAt(0)))
                    copies.add(f);
        }
        if (copies.size() <= keep)
            return 0;
        List<FileTime> times = new ArrayList<>();
        for (Path f : copies)
            times.add(Files.getLastModifiedTime(f));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < copies.size(); i++)
            order.add(i);
        order.sort(Comparator.comparing(times::get, Comparator.reverseOrder()));
        int gone = 0;
        for (int i = keep; i < order.size(); i++) {
            Files.deleteIfExists(copies.get(order.get(i)));
            gone++;
        }
        return gone;
    }
}
