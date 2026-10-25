package sosgsgmp.net;

/**
 * The engine's byte streams the game carries over the network (game-over-network.md §1). Each is one byte sequence per
 * PC that makes it, read by the engine "in any pieces".
 */
public enum StreamKind {
    /** The host's AI log: the general, the host's actions, every peer's orders stamped by the host. Host to peers. */
    AI_LOG,
    /** A peer's orders and world commands. Peer to host. */
    ORDERS,
    /** A PC's city reports (own-city mode). Every PC to every other. */
    REPORTS,
    /** A PC's state hashes. Every PC to every other. */
    HASHES
}
