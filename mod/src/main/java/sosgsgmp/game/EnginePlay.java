package sosgsgmp.game;

import sosdet.Engine;
import sosdet.hash.Desync;
import sosgsgmp.net.StreamKind;
import sosgsgmp.session.EnginePort;

/**
 * SOSGSGMP: the running game's engine calls (game-over-network.md): the start at {@code Go}, the four streams the
 * session carries, and what the panel shows. Game thread only, outside the step.
 */
public final class EnginePlay implements EnginePort {

    /** The host runs at most this many steps past the slowest PC (1 s at 60 steps a second; SOSDET rig's default). */
    private static final long PACE_LEAD = 60;

    /**
     * Every PC at {@code Go}: the game speed follows the agreed one; the host paces by the slowest of {@code peerPcs};
     * then the session runs. A peer can't run ahead of the host's AI log.
     */
    public void start(boolean host, int[] peerPcs) {
        Engine.followAgreedSpeed();
        if (host && peerPcs.length > 0)
            Engine.pace(peerPcs, PACE_LEAD);
        Engine.runFreely();
    }

    @Override
    public byte[] out(StreamKind kind) {
        return switch (kind) {
            case AI_LOG -> Engine.aiLogOut();
            case ORDERS -> Engine.ordersOut();
            case REPORTS -> Engine.reportsOut();
            case HASHES -> Engine.hashOut();
        };
    }

    @Override
    public void in(StreamKind kind, int pc, byte[] bytes) {
        switch (kind) {
            case AI_LOG -> Engine.aiLogIn(bytes);
            case ORDERS -> Engine.ordersIn(pc, bytes);
            case REPORTS -> Engine.reportsIn(pc, bytes);
            case HASHES -> Engine.hashIn(pc, bytes);
        }
    }

    @Override
    public long step() {
        return Engine.step();
    }

    /** The speed every PC runs at now. Builds the engine's stats: for the panel's refresh, not every frame. */
    public double agreedSpeed() {
        return Engine.stats().agreedSpeed;
    }

    /** The last step PC {@code pc}'s hashes reached, -1 before its first. */
    public long peerStep(int pc) {
        return Engine.peerStep(pc);
    }

    /** The first step whose hashes differed between two PCs, or -1 while they all match. */
    public long desyncStep() {
        Desync d = Engine.desync();
        return d == null ? -1 : d.step;
    }
}
