package sosgsgmp.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import game.GameSpec;
import init.paths.PATHS;
import snake2d.LOG;
import snake2d.util.file.FilePutter;
import snake2d.util.misc.ACTION;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapWorld;
import world.WORLD;
import world.WorldGen;

/**
 * SOSGSGMP: the map step's one place for game calls (setup-map-step.md §5): the world size of a new game, generating
 * terrain and landmarks from the host's options, the terrain hash, the map type names, and the guard that no engine
 * session runs during setup (§7). Reflection handles are resolved once, when this is made. Game thread only.
 */
public final class WorldGenAdapter implements MapWorld {

    /** Room for the terrain and landmark sections, in bytes per world tile; they need a few bytes per layer. */
    private static final int HASH_BYTES_PER_TILE = 64;

    private final Field specWidth;
    private final Field specHeight;
    /** sosdet.Engine.active(), or null when SOSDET isn't loaded. */
    private final Method engineActive;
    private List<String> mapTypes;

    public WorldGenAdapter() {
        specWidth = specField("wx");
        specHeight = specField("wy");
        engineActive = engineActive();
    }

    private static Field specField(String name) {
        try {
            Field f = GameSpec.class.getField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | SecurityException | InaccessibleObjectException e) {
            throw new IllegalStateException("SOSGSGMP: can't reach game.GameSpec." + name + " (Songs of Syx V71): " + e, e);
        }
    }

    private static Method engineActive() {
        Class<?> engine;
        try {
            engine = Class.forName("sosdet.Engine", false, WorldGenAdapter.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // SOSDET isn't loaded, so no engine session can run: there is nothing to guard against.
            LOG.ln("SOSGSGMP: sosdet.Engine not found; the map step's engine-session guard is off");
            return null;
        }
        try {
            return engine.getMethod("active");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("SOSGSGMP: sosdet.Engine has no active(); this mod needs a SOSDET with it", e);
        }
    }

    /**
     * Sets the world size of a game about to be built: {@code GameSpec.wx/wy} are public final fields, filled from
     * TILE_DIMENSION, and WORLD takes its size only from them.
     */
    public void setWorldSize(GameSpec spec, int size) {
        if (size < MapOptions.MIN_SIZE || size > MapOptions.MAX_SIZE)
            throw new IllegalArgumentException("SOSGSGMP: world size " + size + " is outside " + MapOptions.MIN_SIZE + ".." + MapOptions.MAX_SIZE);
        try {
            specWidth.setInt(spec, size);
            specHeight.setInt(spec, size);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSGSGMP: can't set game.GameSpec.wx/wy (Songs of Syx V71): " + e, e);
        }
    }

    /** Vanilla's preset map types: the files in sprite/world/generatorMaps, the names WorldGen.map takes. */
    public List<String> mapTypes() {
        if (mapTypes == null)
            mapTypes = List.of(PATHS.SPRITE().getFolder("world").getFolder("generatorMaps").getFiles());
        return mapTypes;
    }

    @Override
    public boolean hasMapType(String mapType) {
        return mapType.isEmpty() || mapTypes().contains(mapType);
    }

    /**
     * Vanilla's Generate (view/world/generator/StageTerrain) without its progress print, which renders the view
     * between generator steps and so could draw from the seeded random stream (setup-map-step.md §7).
     */
    @Override
    public long generate(MapOptions options) {
        requireNoEngineSession();
        if (options.size() != WORLD.TWIDTH() || options.size() != WORLD.THEIGHT())
            throw new IllegalStateException("SOSGSGMP: options for size " + options.size() + " in a world of " + WORLD.TWIDTH() + "x" + WORLD.THEIGHT());
        WorldGen gen = WORLD.GEN();
        gen.map = options.isRandom() ? null : options.mapType();
        gen.lat = options.latitude() / 100.0;
        gen.seed = options.seed();
        WORLD.TERRAIN().saver().generate(ACTION.NOP);
        WORLD.LANDMARKS().saver().generate(ACTION.NOP);
        WORLD.MINIMAP().repaint();
        return terrainHash();
    }

    @Override
    public void lock() {
        WORLD.GEN().hasGeneratedTerrain = true;
    }

    /**
     * The first 8 bytes of the SHA-256 of the terrain and landmark save sections, as the game's own FilePutter
     * writes them: the game's byte layout, never a mod format.
     */
    private static long terrainHash() {
        Path file;
        try {
            file = Files.createTempFile("SOSGSGMP-terrain", ".bin");
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't make a temporary file for the terrain hash", e);
        }
        try {
            FilePutter fp = new FilePutter(file, HASH_BYTES_PER_TILE * WORLD.TWIDTH() * WORLD.THEIGHT());
            WORLD.TERRAIN().saver().save(fp);
            WORLD.LANDMARKS().saver().save(fp);
            fp.save();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return ByteBuffer.wrap(digest).getLong();
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't read the terrain written to " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SOSGSGMP: this Java has no SHA-256", e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.err("SOSGSGMP: can't delete " + file + ": " + e.getMessage());
            }
        }
    }

    /** With an engine session, SOSDET sends every random draw outside a step elsewhere, so the seed would do nothing. */
    private void requireNoEngineSession() {
        if (engineActive == null)
            return;
        boolean active;
        try {
            active = (Boolean) engineActive.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("SOSGSGMP: calling sosdet.Engine.active() failed: " + e, e);
        }
        if (active)
            throw new IllegalStateException("SOSGSGMP: an engine session is running; the map would differ between PCs (setup-map-step.md §7)");
    }
}
