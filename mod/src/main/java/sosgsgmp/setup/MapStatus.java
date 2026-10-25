package sosgsgmp.setup;

/** How a PC's generation of the host's map went. Sent as its ordinal, so only append. */
public enum MapStatus {
    /** Generated; the hash is this PC's terrain hash. */
    OK,
    /** This PC has no such map type: its mods or data differ from the host's. */
    UNKNOWN_MAP_TYPE,
    /** The generation names a build this PC doesn't have. */
    WRONG_BUILD
}
