package sosgsgmp.game;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import game.GameSpec;
import init.paths.PATHS;
import snake2d.LOG;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.misc.ACTION;
import sosdet.Engine;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapWorld;
import world.WORLD;
import world.WorldGen;

/**
 * SOSGSGMP: the map step's one place for game calls (setup-map-step.md §5): the world size of a new game, generating
 * terrain and landmarks from the host's options, loading the host's terrain on a joining PC, the terrain hash, the map
 * type names, the guard that no engine session runs during setup (§7), and the engine's rule for capital locations
 * (setup-factions-step.md §3). Reflection handles are resolved once, when
 * this is made. Game thread only.
 */
public final class WorldGenAdapter implements MapWorld {

    /** Room for the terrain and landmark sections, in bytes per world tile; they need a few bytes per layer. */
    private static final int SECTION_BYTES_PER_TILE = 64;

    private final Field specWidth;
    private final Field specHeight;
    private List<String> mapTypes;

    public WorldGenAdapter() {
        specWidth = specField("wx");
        specHeight = specField("wy");
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

    /**
     * Vanilla's Generate (view/world/generator/StageTerrain) without its progress print, which renders the view
     * between generator steps. The map is sent to the joining PCs rather than generated there: vanilla's river
     * generator shuffles with Java's own unseeded random numbers, so the same seed gives another map on another PC
     * (setup-map-step.md §7).
     */
    @Override
    public Terrain generate(MapOptions options) {
        requireNoEngineSession();
        requireSize(options);
        setOptions(options);
        WORLD.TERRAIN().saver().generate(ACTION.NOP);
        WORLD.LANDMARKS().saver().generate(ACTION.NOP);
        WORLD.MINIMAP().repaint();
        byte[] sections = sections();
        return new Terrain(hash(sections), deflate(sections));
    }

    @Override
    public long load(MapOptions options, byte[] terrain) {
        requireNoEngineSession();
        requireSize(options);
        byte[] sections = inflate(terrain, maxSectionBytes());
        Path file = tempFile();
        try {
            Files.write(file, sections);
            FileGetter in = new FileGetter(file);
            WORLD.TERRAIN().saver().load(in);
            WORLD.LANDMARKS().saver().load(in);
        } catch (IOException e) {
            LOG.err("SOSGSGMP: the host's terrain (" + sections.length + " bytes) doesn't load in this game: " + e);
            throw new IllegalArgumentException("SOSGSGMP: the host's terrain doesn't load: " + e, e);
        } finally {
            delete(file);
        }
        setOptions(options);
        WORLD.MINIMAP().repaint();
        return hash(sections());
    }

    @Override
    public void lock() {
        WORLD.GEN().hasGeneratedTerrain = true;
    }

    @Override
    public String locationProblem(int x, int y, int[] otherX, int[] otherY) {
        if (x < 0 || y < 0 || x >= WORLD.TWIDTH() || y >= WORLD.THEIGHT())
            return "outside the map";
        return Engine.locationProblem(x, y, otherX, otherY);
    }

    private static void requireSize(MapOptions options) {
        if (options.size() != WORLD.TWIDTH() || options.size() != WORLD.THEIGHT())
            throw new IllegalStateException("SOSGSGMP: options for size " + options.size() + " in a world of " + WORLD.TWIDTH() + "x" + WORLD.THEIGHT());
    }

    /** The options the map was made with, as vanilla keeps them in the world (saved with it). */
    private static void setOptions(MapOptions options) {
        WorldGen gen = WORLD.GEN();
        gen.map = options.isRandom() ? null : options.mapType();
        gen.lat = options.latitude() / 100.0;
        gen.seed = options.seed();
    }

    private static int maxSectionBytes() {
        return SECTION_BYTES_PER_TILE * WORLD.TWIDTH() * WORLD.THEIGHT();
    }

    /**
     * The terrain and landmark save sections as the game's own FilePutter writes them: the game's byte layout, never a
     * mod format. A long follows them, since vanilla's FileGetter refuses a double in a file's last 8 bytes.
     */
    private static byte[] sections() {
        Path file = tempFile();
        try {
            FilePutter fp = new FilePutter(file, maxSectionBytes());
            WORLD.TERRAIN().saver().save(fp);
            WORLD.LANDMARKS().saver().save(fp);
            fp.l(0);
            fp.save();
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't read the terrain written to " + file, e);
        } finally {
            delete(file);
        }
    }

    /** The first 8 bytes of the sections' SHA-256. */
    private static long hash(byte[] sections) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sections);
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SOSGSGMP: this Java has no SHA-256", e);
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4);
            byte[] buffer = new byte[64 * 1024];
            while (!d.finished())
                out.write(buffer, 0, d.deflate(buffer));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    /** The host's sections; refuses damaged data and anything larger than this world's sections can be. */
    private static byte[] inflate(byte[] data, int max) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buffer = new byte[64 * 1024];
            while (!inf.finished()) {
                int n = inf.inflate(buffer);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary()))
                    throw new IllegalArgumentException("SOSGSGMP: the host's terrain is cut short");
                out.write(buffer, 0, n);
                if (out.size() > max)
                    throw new IllegalArgumentException("SOSGSGMP: the host's terrain is over " + max + " bytes");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("SOSGSGMP: the host's terrain is damaged: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
    }

    private static Path tempFile() {
        try {
            return Files.createTempFile("SOSGSGMP-terrain", ".bin");
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't make a temporary file for the terrain", e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.err("SOSGSGMP: can't delete " + file + ": " + e.getMessage());
        }
    }

    /** With an engine session, SOSDET sends every random draw outside a step elsewhere, so the seed would do nothing. */
    private static void requireNoEngineSession() {
        if (Engine.active())
            throw new IllegalStateException("SOSGSGMP: an engine session is running; the map would differ between PCs (setup-map-step.md §7)");
    }
}
