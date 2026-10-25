package sosgsgmp.session;

import java.nio.ByteBuffer;

/**
 * A world resync the host made at a desync (spec §11 World desyncs): the step every PC loads it at, the engine's world
 * state at that step and the world save. It travels as one transfer in {@link sosgsgmp.net.Message.Resync} pieces:
 * the state's length (an int), the state, then the save. Immutable.
 */
public final class WorldResync {

    private final long step;
    private final byte[] state;
    private final byte[] save;

    public WorldResync(long step, byte[] state, byte[] save) {
        if (step < 0 || state.length == 0 || save.length == 0)
            throw new IllegalArgumentException("SOSGSGMP: a resync at step " + step + " with " + state.length + " state bytes and a save of " + save.length);
        this.step = step;
        this.state = state.clone();
        this.save = save.clone();
    }

    public long step() {
        return step;
    }

    public byte[] state() {
        return state.clone();
    }

    public byte[] save() {
        return save.clone();
    }

    /** The bytes the transfer carries. */
    byte[] pack() {
        return ByteBuffer.allocate(4 + state.length + save.length).putInt(state.length).put(state).put(save).array();
    }

    /** The resync a whole transfer for {@code step} carried; throws if the bytes aren't one. */
    static WorldResync unpack(long step, byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int length = bytes.length >= 4 ? b.getInt() : -1;
        if (length <= 0 || length >= bytes.length - 4)
            throw new IllegalArgumentException("SOSGSGMP: a resync of " + bytes.length + " bytes with " + length + " state bytes");
        byte[] state = new byte[length];
        b.get(state);
        byte[] save = new byte[b.remaining()];
        b.get(save);
        return new WorldResync(step, state, save);
    }
}
