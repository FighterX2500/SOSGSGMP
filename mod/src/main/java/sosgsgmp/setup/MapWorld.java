package sosgsgmp.setup;

/**
 * The game's side of the map step, as the setup sessions use it: the game adapter implements it, tests fake it.
 * Game thread only.
 */
public interface MapWorld {

    /** Whether this PC has the map type; {@link MapOptions#RANDOM} always. */
    boolean hasMapType(String mapType);

    /** Generates the current game's terrain and landmarks with these options and returns the terrain hash. */
    long generate(MapOptions options);

    /** Locks the confirmed map in the current game. */
    void lock();
}
