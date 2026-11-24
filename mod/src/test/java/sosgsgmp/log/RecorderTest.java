package sosgsgmp.log;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class RecorderTest {

    /** Not a JUnit temp folder: Windows can't delete a file while it is mapped, and a mapping goes only with the GC. */
    private final Path dir = Path.of("build", "tmp", "recorder-tests", getClass().getSimpleName());

    private long clock = 1_000;

    private Recorder open(String name) throws IOException {
        return Recorder.open(dir.resolve("rec").resolve(name), false, "Bö", 1_764_000_000_000L, () -> clock);
    }

    @Test
    void theHeaderAndTheEntriesReadBackAsWritten() throws IOException {
        Recorder r = open("join-B.rec");
        long[] step = { 41 };
        r.stepFrom(() -> step[0]);
        clock += 500;
        r.record(Recorder.NET_IN, 2, 48_000, 17, 0);
        step[0] = 42;
        r.text(Recorder.LINE_SAME, -1, "= 42 world: news: Ann ⚔ Bo");
        r.record(Recorder.HOLD_END, -1, 0, 42, 1500);
        r.close();
        assertEquals(Recorder.size(), Files.size(r.file()));
        RecorderReader in = new RecorderReader(r.file());
        assertEquals(1_764_000_000_000L, in.start);
        assertFalse(in.host);
        assertEquals("Bö", in.name);
        List<RecorderReader.Entry> e = in.entries;
        assertEquals(3, e.size());
        assertEquals(new RecorderReader.Entry(500, 41, Recorder.NET_IN, 2, 48_000, 17, 0, null), e.get(0));
        assertEquals(Recorder.LINE_SAME, e.get(1).kind());
        assertEquals(42, e.get(1).step());
        assertEquals("= 42 world: news: Ann ⚔ Bo", e.get(1).text());
        assertEquals(1500, e.get(2).b());
        assertNull(r.failure());
    }

    @Test
    void theEntryRingWrapsAndKeepsTheNewest() throws IOException {
        Recorder r = open("wrap.rec");
        int n = Recorder.ENTRIES + 10;
        for (int i = 0; i < n; i++)
            r.record(Recorder.FRAME, -1, 0, i, 0);
        r.close();
        RecorderReader in = new RecorderReader(r.file());
        assertEquals(n, in.written);
        assertEquals(Recorder.ENTRIES, in.entries.size());
        assertEquals(10, in.entries.get(0).a());
        assertEquals(n - 1, in.entries.get(Recorder.ENTRIES - 1).a());
    }

    @Test
    void thePayloadRingWrapsAndAnOverwrittenTextIsGone() throws IOException {
        Recorder r = open("payload.rec");
        String text = "x".repeat(1000) + "é";
        int n = Recorder.PAYLOAD / 1002 + 5; // past the ring's end
        for (int i = 0; i < n; i++)
            r.text(Recorder.LINE_LOCAL, -1, text);
        r.close();
        RecorderReader in = new RecorderReader(r.file());
        assertEquals(n, in.entries.size());
        assertNull(in.entries.get(0).text()); // overwritten
        assertEquals(text, in.entries.get(n - 1).text()); // this one wraps round the ring's end
        assertEquals(text, in.entries.get(n - 2).text());
        byte[] back = new byte[1002];
        assertTrue(r.payload(in.entries.get(n - 1).a(), 1002, back, 0) || r.closed());
    }

    @Test
    void aLongTextIsCutAtWholeCharacters() throws IOException {
        Recorder r = open("cut.rec");
        r.text(Recorder.LINE_LOCAL, -1, "a" + "€".repeat(Recorder.MAX_TEXT));
        r.close();
        RecorderReader.Entry e = new RecorderReader(r.file()).entries.get(0);
        assertTrue(e.size() <= Recorder.MAX_TEXT && e.size() > Recorder.MAX_TEXT - 3, "size " + e.size());
        assertEquals(1 + (Recorder.MAX_TEXT - 1) / 3, e.text().length());
    }

    @Test
    void anIncidentCopyIsTheFileAsItIsNow() throws IOException {
        Recorder r = open("copy.rec");
        r.record(Recorder.DESYNC, -1, 0, 600, 0);
        Path copy = dir.resolve("rec").resolve("copy-600-desync.rec");
        r.copy(copy);
        r.record(Recorder.FRAME, -1, 0, 70, 0); // after the copy
        r.close();
        assertEquals(1, new RecorderReader(copy).entries.size());
        assertEquals(2, new RecorderReader(r.file()).entries.size());
        assertArrayEquals(Recorder.MAGIC, java.util.Arrays.copyOf(Files.readAllBytes(copy), 8));
    }

    @Test
    void theMessageTypesAreWrittenAgainBeforeTheRingsDropThem() throws IOException {
        Recorder r = open("types.rec");
        r.text(Recorder.TYPES, -1, "message types: 0=Hello 1=Welcome");
        for (int i = 0; i < 3 * Recorder.ENTRIES; i++)
            r.record(Recorder.FRAME, -1, 0, i, 0);
        String big = "y".repeat(Recorder.MAX_TEXT);
        for (int i = 0; i < Recorder.PAYLOAD / Recorder.MAX_TEXT * 2; i++)
            r.text(Recorder.LINE_LOCAL, -1, big); // texts only: the payload ring wraps sooner than the entries
        long typesAt = r.types();
        r.close();
        RecorderReader in = new RecorderReader(r.file());
        RecorderReader.Entry last = in.entries.stream().filter(e -> e.kind() == Recorder.TYPES).reduce((x, y) -> y).orElseThrow();
        assertEquals("message types: 0=Hello 1=Welcome", last.text(), "the ring holds the names, their text not overwritten");
        assertTrue(in.written - typesAt <= Recorder.ENTRIES / 2 + 1);
    }

    @Test
    void anEngineRingsEntryKeepsItsOwnStep() throws IOException {
        Recorder r = open("rings.rec");
        r.stepFrom(() -> 900);
        r.recordAt(870, Recorder.COMMAND, 1, 3, 55, 7012 | 1 << 16);
        r.close();
        RecorderReader.Entry e = new RecorderReader(r.file()).entries.get(0);
        assertEquals(870, e.step());
        assertEquals(7012, e.b() & 0xFFFF);
        assertEquals(1, e.b() >> 16);
    }

    @Test
    void aClosedRecorderRecordsNothing() throws IOException {
        Recorder r = open("closed.rec");
        r.close();
        r.record(Recorder.FRAME, -1, 0, 1, 0);
        assertEquals(0, r.written());
        assertNull(r.failure());
    }
}
