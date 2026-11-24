package sosgsgmp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RecorderStreamTest {

    /** Not a JUnit temp folder: Windows can't delete a file while it is mapped, and a mapping goes only with the GC. */
    private final Path dir = Path.of("build", "tmp", "recorder-tests", getClass().getSimpleName());

    private long clock;

    /** A stream's records: kind, A, and the text when there is one. */
    private static List<String> records(byte[] piece) {
        List<String> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(piece);
        while (b.hasRemaining()) {
            int at = b.position();
            int kind = b.getShort(at + Recorder.E_KIND);
            int size = b.getInt(at + Recorder.E_SIZE);
            long a = b.getLong(at + Recorder.E_A);
            b.position(at + Recorder.ENTRY);
            String text = "";
            if (Recorder.text(kind)) {
                byte[] t = new byte[size];
                b.get(t);
                text = " " + new String(t, StandardCharsets.UTF_8);
            }
            out.add(kind + ":" + a + text);
        }
        return out;
    }

    private Recorder recorder() throws IOException {
        return Recorder.open(dir.resolve("join-B.rec"), false, "B", 0, () -> clock);
    }

    @Test
    void onlyThisPcsOwnKindsGoWithTheirTexts() throws IOException {
        Recorder r = recorder();
        RecorderStream s = new RecorderStream(r);
        assertFalse(s.waiting());
        r.record(Recorder.NET_IN, 0, 100, 5, 0);
        r.record(Recorder.COMMAND, 1, 0, 7012, 0); // every PC records it: the host has it
        r.text(Recorder.LINE_SAME, -1, "= 600 world: x");
        r.text(Recorder.LINE_LOCAL, -1, "message: Riot");
        r.record(Recorder.HOLD_START, -1, 0, 600, 0);
        byte[] piece = s.next(4096);
        assertEquals(List.of(Recorder.NET_IN + ":5", Recorder.LINE_LOCAL + ":" + "= 600 world: x".length() + " message: Riot",
                Recorder.HOLD_START + ":600"), records(piece));
        assertEquals(piece.length, s.sent());
        assertNull(s.next(4096));
        assertFalse(s.waiting());
    }

    @Test
    void aPieceHoldsWholeRecordsOnly() throws IOException {
        Recorder r = recorder();
        RecorderStream s = new RecorderStream(r);
        for (int i = 0; i < 5; i++)
            r.record(Recorder.FRAME, -1, 0, i, 0);
        byte[] first = s.next(3 * Recorder.ENTRY + 10);
        assertEquals(3 * Recorder.ENTRY, first.length);
        assertEquals(List.of(Recorder.FRAME + ":3", Recorder.FRAME + ":4"), records(s.next(4096)));
        assertEquals(5 * Recorder.ENTRY, s.sent());
        assertNull(s.next(Recorder.ENTRY - 1));
    }

    @Test
    void aStreamFarBehindJumpsToTheLastSecondAndSaysSo() throws IOException {
        Recorder r = recorder();
        RecorderStream s = new RecorderStream(r);
        for (int i = 0; i < 10; i++) {
            r.record(Recorder.FRAME, -1, 0, i, 0);
            clock += 500_000_000L; // half a second each
        }
        // now 5 s on; entries 8 and 9 were written within the last second
        List<String> got = records(s.next(4096));
        assertEquals(List.of(Recorder.GAP + ":8", Recorder.FRAME + ":8", Recorder.FRAME + ":9"), got);
    }

    @Test
    void entriesTheRingDroppedAreAGap() throws IOException {
        Recorder r = recorder();
        RecorderStream s = new RecorderStream(r);
        for (int i = 0; i < Recorder.ENTRIES + 3; i++)
            r.record(Recorder.FRAME, -1, 0, i, 0);
        List<String> got = records(s.next(Recorder.ENTRY * 4));
        assertEquals(Recorder.GAP + ":3", got.get(0)); // the clock stood still: the whole ring is the last second, less the 3 dropped
        assertEquals(Recorder.FRAME + ":3", got.get(1));
    }
}
