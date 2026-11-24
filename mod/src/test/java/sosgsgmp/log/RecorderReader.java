package sosgsgmp.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A reader of the recorder's format 1 in Java, for the tests: what {@code tools/recorder.py} reads. */
final class RecorderReader {

    record Entry(long time, int step, int kind, int peer, int size, long a, int b, String text) {}

    final long start;
    final boolean host;
    final String name;
    final long written;
    final List<Entry> entries = new ArrayList<>();

    RecorderReader(Path file) throws IOException {
        ByteBuffer f = ByteBuffer.wrap(Files.readAllBytes(file));
        byte[] magic = new byte[8];
        f.get(0, magic);
        if (!Arrays.equals(magic, Recorder.MAGIC) || f.getInt(Recorder.H_VERSION) != 1)
            throw new IOException("not a recorder file of format 1");
        int header = f.getInt(Recorder.H_SIZE);
        int count = f.getInt(Recorder.H_ENTRIES), size = f.getInt(Recorder.H_ENTRY), payload = f.getInt(Recorder.H_PAYLOAD);
        start = f.getLong(Recorder.H_START);
        host = f.get(Recorder.H_ROLE) == 0;
        byte[] n = new byte[f.getShort(Recorder.H_NAME)];
        f.get(Recorder.H_NAME + 2, n);
        name = new String(n, StandardCharsets.UTF_8);
        written = f.getLong(Recorder.H_WRITTEN);
        long payloadWritten = f.getLong(Recorder.H_PAYLOAD_WRITTEN);
        int payloadAt = header + count * size;
        for (long i = Math.max(0, written - count); i < written; i++) {
            int at = header + (int) (i % count) * size;
            int kind = f.getShort(at + Recorder.E_KIND);
            int length = f.getInt(at + Recorder.E_SIZE);
            long a = f.getLong(at + Recorder.E_A);
            String text = null;
            if (Recorder.text(kind) && a >= payloadWritten - payload) {
                byte[] t = new byte[length];
                for (int k = 0; k < length; k++)
                    t[k] = f.get(payloadAt + (int) ((a + k) % payload));
                text = new String(t, StandardCharsets.UTF_8);
            }
            entries.add(new Entry(f.getLong(at + Recorder.E_TIME), f.getInt(at + Recorder.E_STEP), kind, f.getShort(at + Recorder.E_PEER),
                    length, a, f.getInt(at + Recorder.E_B), text));
        }
    }
}
