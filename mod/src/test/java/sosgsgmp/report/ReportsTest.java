package sosgsgmp.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sosgsgmp.net.Message;

/** A joining PC's outbox into the host's store, as the network would carry it (SOS-133). */
class ReportsTest {

    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path joiner;
    @TempDir
    Path host;

    /** Every piece the outbox gives over {@code seconds}, one frame each 1/60 s, into the store. */
    private static int run(ReportOutbox out, ReportStore store, long from, double seconds) {
        int pieces = 0;
        for (long t = from; t < from + (long) (seconds * SECOND); t += SECOND / 60) {
            Message.Report r = out.next(t);
            if (r != null) {
                store.accept(r);
                pieces++;
            }
        }
        return pieces;
    }

    @Test
    void aSaveAndAGrowingLogArriveWholeWithinTheBudget() throws IOException {
        byte[] save = new byte[200 * 1024];
        new Random(1).nextBytes(save);
        Path saveFile = Files.write(joiner.resolve("MP campaign-1.save"), save);
        Path log = Files.writeString(joiner.resolve("20261103-join-Adam_Smith.log"), "first line\n", StandardCharsets.UTF_8);
        ReportOutbox out = new ReportOutbox();
        ReportStore store = new ReportStore(host, "Adam Smith");
        boolean[] done = new boolean[1];
        out.follow(log);
        out.add(saveFile, () -> done[0] = true);

        run(out, store, 0, 3);
        assertTrue(Files.size(host.resolve(saveFile.getFileName())) <= 4 * ReportOutbox.BYTES_PER_SECOND, "the budget holds");
        assertEquals("first line\n", Files.readString(host.resolve(log.getFileName())));

        Files.writeString(log, "second line\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        run(out, store, 3 * SECOND, 10);
        assertTrue(done[0]);
        assertArrayEquals(save, Files.readAllBytes(host.resolve(saveFile.getFileName())));
        assertEquals("first line\nsecond line\n", Files.readString(host.resolve(log.getFileName())));
        assertEquals(0, out.waiting());
    }

    @Test
    void aRepeatOrAGapChangesNothingAndNamesAreCleaned() throws IOException {
        ReportStore store = new ReportStore(host, "Bob");
        assertNull(store.accept(new Message.Report("..\\..\\evil.log", -1, 0, new byte[] {1, 2})));
        Path written = host.resolve("_.._evil.log");
        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(written));
        store.accept(new Message.Report("..\\..\\evil.log", -1, 0, new byte[] {9, 9}));
        store.accept(new Message.Report("..\\..\\evil.log", -1, 5, new byte[] {9}));
        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(written));
        assertEquals(1, Files.list(host).count());

        String line = store.accept(new Message.Report("a.save", 3, 0, new byte[] {1, 2, 3}));
        assertNotNull(line, "a whole file's end is logged");
        assertNull(store.accept(new Message.Report("a.save", 3, 0, new byte[] {1, 2, 3})), "a file already here isn't taken twice");
    }

    @Test
    void theCapStopsTheRestOnce() {
        ReportStore store = new ReportStore(host, "Cid");
        byte[] piece = new byte[48 * 1024];
        long offset = 0;
        String capLine = null;
        int capLines = 0;
        while (offset <= ReportStore.MAX_LOGS) {
            String line = store.accept(new Message.Report("big.log", -1, offset, piece));
            if (line != null) {
                capLine = line;
                capLines++;
            }
            offset += piece.length;
        }
        assertNotNull(capLine);
        assertEquals(1, capLines);
    }
}
