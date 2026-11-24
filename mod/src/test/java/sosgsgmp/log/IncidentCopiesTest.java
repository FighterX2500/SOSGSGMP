package sosgsgmp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncidentCopiesTest {

    @TempDir
    Path dir;

    @Test
    void theNewestCopiesStayAndTheCurrentFilesAreNeverTouched() throws IOException {
        for (int i = 0; i < 25; i++) {
            Path f = Files.writeString(dir.resolve("20261124-2100" + (10 + i) + "-join-Bo-" + (600 * i) + "-freeze.rec"), "x");
            Files.setLastModifiedTime(f, FileTime.fromMillis(1_000_000L * (i + 1)));
        }
        Path current = Files.writeString(dir.resolve("join-Bo.rec"), "current");
        Files.setLastModifiedTime(current, FileTime.fromMillis(1)); // the oldest of all, and kept
        assertEquals(5, IncidentCopies.prune(dir, 20));
        assertTrue(Files.exists(current));
        assertFalse(Files.exists(dir.resolve("20261124-210010-join-Bo-0-freeze.rec")));
        assertTrue(Files.exists(dir.resolve("20261124-210034-join-Bo-14400-freeze.rec")));
        try (var s = Files.list(dir)) {
            assertEquals(21, s.count());
        }
        assertEquals(0, IncidentCopies.prune(dir.resolve("none"), 20));
    }
}
