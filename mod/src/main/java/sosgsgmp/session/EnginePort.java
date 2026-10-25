package sosgsgmp.session;

import sosgsgmp.net.StreamKind;

/** The engine's streams as {@link EngineLink} sees them: the adapter calls SOSDET's {@code Engine}. Game thread only. */
public interface EnginePort {

    /** What this PC's engine made of stream {@code kind} since the last call; empty when nothing. */
    byte[] out(StreamKind kind);

    /** More of PC {@code pc}'s stream {@code kind}, in order, each byte once. */
    void in(StreamKind kind, int pc, byte[] bytes);

    /** The last step this PC completed, for the pieces' log field. */
    long step();
}
