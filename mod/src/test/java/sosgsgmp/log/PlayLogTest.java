package sosgsgmp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayLogTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T21:04:05.678Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    @Test
    void eachLineIsTimedAndALongTextStaysTogether() throws IOException {
        try (PlayLog log = PlayLog.open(dir.resolve("logs"), "host-Ann B/ö", CLOCK)) {
            assertEquals(dir.resolve("logs").resolve("20261025-210405-host-Ann_B__.log"), log.file());
            log.line("step 600 hash 1a");
            log.line("desync report\r\nsection world\nsection divisions");
            assertEquals(List.of("21:04:05.678 step 600 hash 1a", "21:04:05.678 desync report",
                    "             section world", "             section divisions"), lines(log.file()));
            assertNull(log.failure());
        }
    }

    @Test
    void anErrorCarriesItsStackTrace() throws IOException {
        try (PlayLog log = PlayLog.open(dir, "join", CLOCK)) {
            log.error("the game over the network, step 42", new IllegalStateException("no session"));
            List<String> l = lines(log.file());
            assertEquals("21:04:05.678 ERROR the game over the network, step 42", l.get(0));
            assertEquals("             java.lang.IllegalStateException: no session", l.get(1));
            assertTrue(l.get(2).trim().startsWith("at sosgsgmp.log.PlayLogTest."), l.get(2));
        }
    }

    @Test
    void aLogThatCantBeWrittenWritesNothingAndSaysWhy() throws IOException {
        Path notADir = Files.writeString(dir.resolve("taken"), "a file where the folder goes");
        PlayLog log = PlayLog.open(notADir, "host", CLOCK);
        assertNotNull(log.failure());
        log.line("lost");
        log.close();
        assertEquals(List.of("a file where the folder goes"), lines(notADir));
    }

    @Test
    void longNamesAreCut() {
        assertEquals(32, PlayLog.fileName("x".repeat(80)).length());
    }
}
