package sosgsgmp.setup;

/**
 * The map the host picks (spec §5 Map, step 1): the world's size and vanilla's map type, latitude and seed. Terrain
 * and landmarks are a function of these and the game's data, so every PC generates the same map from them
 * (setup-map-step.md §7).
 *
 * @param size     world width and height in tiles, {@link #MIN_SIZE} to {@link #MAX_SIZE}
 * @param mapType  a vanilla preset (a file in the game's sprite/world/generatorMaps folder), or {@link #RANDOM}
 * @param latitude 0 to {@link #MAX_LATITUDE}, as vanilla's slider; the game's latitude is this / 100.0
 * @param seed     0 to {@link Integer#MAX_VALUE}, as vanilla's seed field
 */
public record MapOptions(int size, String mapType, int latitude, int seed) {

    /** The world sizes the game supports (its TILE_DIMENSION limits). */
    public static final int MIN_SIZE = 128;
    public static final int MAX_SIZE = 512;
    /** Vanilla's size: the first choice, and the only size that offers presets (setup-map-step.md risk 1). */
    public static final int DEFAULT_SIZE = 256;
    public static final int MAX_MAP_TYPE = 64;
    public static final int MAX_LATITUDE = 100;
    public static final int DEFAULT_LATITUDE = 50;
    /** The map type of vanilla's procedural ("Random") terrain. */
    public static final String RANDOM = "";

    /** The sizes the host can pick: steps the noise and region code divide well. */
    private static final int[] SIZES = {128, 192, 256, 384, 512};

    public MapOptions {
        if (size < MIN_SIZE || size > MAX_SIZE)
            throw new IllegalArgumentException("SOSGSGMP: map size " + size + " is outside " + MIN_SIZE + ".." + MAX_SIZE);
        if (mapType == null || mapType.length() > MAX_MAP_TYPE)
            throw new IllegalArgumentException("SOSGSGMP: map type '" + mapType + "' is missing or over " + MAX_MAP_TYPE + " characters");
        if (latitude < 0 || latitude > MAX_LATITUDE)
            throw new IllegalArgumentException("SOSGSGMP: latitude " + latitude + " is outside 0.." + MAX_LATITUDE);
        if (seed < 0)
            throw new IllegalArgumentException("SOSGSGMP: seed " + seed + " is negative");
    }

    public boolean isRandom() {
        return mapType.isEmpty();
    }

    /**
     * Whether presets can be picked at this size. Vanilla scales a preset's heights by the world size instead of 256,
     * so presets come out wrong at any other size, and the preview crashes below it.
     */
    public static boolean presetsAllowed(int size) {
        return size == DEFAULT_SIZE;
    }

    /** Whether the host can pick this size. */
    public static boolean isOfferedSize(int size) {
        for (int s : SIZES)
            if (s == size)
                return true;
        return false;
    }

    /** The offered size one step smaller ({@code step} -1) or bigger (+1) than {@code size}, or {@code size} at either end. */
    public static int stepSize(int size, int step) {
        if (step != -1 && step != 1)
            throw new IllegalArgumentException("SOSGSGMP: size step " + step + " is not -1 or 1");
        int result = size;
        for (int s : SIZES) {
            if (step < 0 && s < size)
                result = s;
            if (step > 0 && s > size)
                return s;
        }
        return result;
    }

    /** A seed typed by the host, or -1 when it isn't one (digits only, 0 to {@link Integer#MAX_VALUE}). */
    public static int parseSeed(CharSequence text) {
        String s = text.toString().strip();
        if (s.isEmpty() || s.length() > 10)
            return -1;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) < '0' || s.charAt(i) > '9')
                return -1;
        long seed = Long.parseLong(s);
        return seed <= Integer.MAX_VALUE ? (int) seed : -1;
    }
}
