package sosgsgmp.setup;

/**
 * The game's side of the map step, as the setup sessions use it: the game adapter implements it, tests fake it.
 * Game thread only.
 */
public interface MapWorld {

    /**
     * A generated map: the terrain hash, and the terrain and landmarks as the game saves them, compressed, for the
     * joining PCs to load.
     */
    record Terrain(long hash, byte[] bytes) {}

    /** Host: generates the current game's terrain and landmarks with these options. */
    Terrain generate(MapOptions options);

    /**
     * Joining PC: loads the host's terrain, {@link Terrain#bytes()} as the host's {@link #generate} made it with these
     * options, into the current game and returns this PC's terrain hash after the load. Throws
     * {@link IllegalArgumentException} when the bytes aren't a terrain of this game.
     */
    long load(MapOptions options, byte[] terrain);

    /** Locks the confirmed map in the current game. */
    void lock();
}
