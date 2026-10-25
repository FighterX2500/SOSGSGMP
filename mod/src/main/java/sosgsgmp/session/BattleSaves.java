package sosgsgmp.session;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The battle saves on the BATTLE stream (SOSDET plan 0006, SOS-62): at a battle's Engage the battle PC sends the battle
 * it generated, and every PC, that one too, loads it at the Engage step. Each record is the step (8 bytes), the save's
 * length (4) and the save; a PC's stream arrives in any pieces. Only the newest battle's save is kept. Plain Java.
 */
public final class BattleSaves {

    /** Larger than any battle save: a record claiming more is a broken stream. */
    static final int MAX_SAVE = 1 << 28;
    private static final int HEADER = 12;

    private final Map<Integer, ByteArrayOutputStream> partial = new HashMap<>();
    private long step = -1;
    private byte[] save;

    /** A save as a record of the stream. */
    public static byte[] frame(long step, byte[] save) {
        if (step < 0 || save.length == 0 || save.length > MAX_SAVE)
            throw new IllegalArgumentException("SOSGSGMP: a battle save of " + save.length + " bytes at step " + step);
        return ByteBuffer.allocate(HEADER + save.length).putLong(step).putInt(save.length).put(save).array();
    }

    /** This PC's own battle save, as the battle PC: it loads its own too. */
    public void own(long step, byte[] save) {
        keep(step, save.clone());
    }

    /** More of PC {@code pc}'s stream, in order: each record it completes is the newest battle's save. */
    public void in(int pc, byte[] bytes) {
        ByteArrayOutputStream buf = partial.computeIfAbsent(pc, k -> new ByteArrayOutputStream());
        buf.write(bytes, 0, bytes.length);
        ByteBuffer b = ByteBuffer.wrap(buf.toByteArray());
        while (b.remaining() >= HEADER) {
            int start = b.position();
            long s = b.getLong();
            int length = b.getInt();
            if (s < 0 || length <= 0 || length > MAX_SAVE)
                throw new IllegalArgumentException("SOSGSGMP: PC " + pc + " sent a battle save record of " + length
                        + " bytes at step " + s);
            if (b.remaining() < length) {
                b.position(start);
                break;
            }
            byte[] record = new byte[length];
            b.get(record);
            keep(s, record);
        }
        buf.reset();
        buf.write(b.array(), b.position(), b.remaining());
    }

    private void keep(long s, byte[] record) {
        if (s >= step) {
            step = s;
            save = record;
        }
    }

    /** The save of the battle that began at {@code s}, once; null if it hasn't come in yet. */
    public byte[] take(long s) {
        if (save == null || step != s)
            return null;
        byte[] out = save;
        save = null;
        return out;
    }
}
