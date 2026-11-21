package sosgsgmp.net;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Steam once per game process (SOS-237, ADR 0012): the Steam API is initialized the first time a {@link SteamNode}
 * needs it and never shut down, since shutting it down while it still delivered a closed session's news crashed the
 * game. One node at a time uses it: a node's I/O thread acquires it when the node starts and releases it when the node
 * ends; a second node waits for the first to let go. Steam's callbacks that waited while no node held it are dropped
 * once they are stale (an invite accepted during a single-player game must not join a lobby minutes later).
 */
public final class SteamLease<S extends SteamLease.Steam> {

    /** The process's Steam, as the lease drives it. */
    public interface Steam {
        /** A node starts using it: per-connection state is fresh; {@code dropStale}: drop the callbacks waiting since the last use. */
        void begin(boolean dropStale);

        /** The node is done with it; Steam stays initialized. */
        void end();
    }

    private final Supplier<S> open;
    private final long waitMs;
    private final long staleMs;
    private final LongSupplier clock;
    private S steam;
    private boolean held;
    /** When the last node let go; 0 before any did. */
    private long releasedAt;

    /**
     * {@code open} initializes Steam, throwing with the reason if it can't (the next acquire tries again); a node waits
     * up to {@code waitMs} for the one before it; callbacks older than {@code staleMs} without a node are dropped.
     */
    public SteamLease(Supplier<S> open, long waitMs, long staleMs, LongSupplier clock) {
        this.open = open;
        this.waitMs = waitMs;
        this.staleMs = staleMs;
        this.clock = clock;
    }

    /** On a node's I/O thread: the process's Steam, opened on first use; throws if it can't open or stays held. */
    public synchronized S acquire() {
        long deadline = System.currentTimeMillis() + waitMs;
        while (held) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0)
                throw new IllegalStateException("SOSGSGMP: Steam is still in use by the last connection after " + waitMs + " ms");
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SOSGSGMP: interrupted while waiting for Steam", e);
            }
        }
        if (steam == null)
            steam = open.get();
        steam.begin(releasedAt != 0 && clock.getAsLong() - releasedAt >= staleMs);
        held = true;
        return steam;
    }

    /** On the node's I/O thread, once it is done with {@code s}: the next node may have it. */
    public synchronized void release(S s) {
        if (!held || s != steam)
            throw new IllegalStateException("SOSGSGMP: released a Steam this lease didn't lend");
        try {
            steam.end();
        } finally {
            held = false;
            releasedAt = Math.max(1, clock.getAsLong());
            notifyAll();
        }
    }
}
