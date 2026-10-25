package sosgsgmp.setup;

/** How a PC's generation of the host's map went. Sent as its ordinal, so only append. */
public enum MapStatus {
    /** Generated (the host) or loaded (a joining PC); the hash is this PC's terrain hash. */
    OK,
    /** The host's terrain couldn't be loaded here: its pieces were damaged, or this game differs from the host's. */
    BAD_TERRAIN,
    /** The generation names a build this PC doesn't have. */
    WRONG_BUILD
}
