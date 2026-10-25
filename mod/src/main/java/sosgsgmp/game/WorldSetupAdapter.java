package sosgsgmp.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.player.Player;
import settlement.stats.POP;
import snake2d.LOG;
import sosdet.Engine;
import sosdet.HumanStart;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionWorld;
import view.main.VIEW;
import world.WORLD;

/**
 * SOSGSGMP: the factions step's game and engine calls (setup-factions-step.md §5): this PC's picks as the game holds
 * them, the engine's location rule, the host's world build and save, and on every PC the load's engine session, its
 * own city and the world hash. Each PC changes only its own {@code FACTIONS.player()} with vanilla's race buttons and
 * Customize Faction section; this reads them back. Game thread only.
 */
public final class WorldSetupAdapter implements FactionWorld {

    /** The save's name in its temporary folder; the game adds its own file ending. */
    private static final String SAVE_NAME = "world";

    /**
     * This PC's picks as the game holds them: race, faction name and banner, at world tile {@code x, y} (or
     * {@link FactionPick#NONE} twice). Null while the faction has no name: a pick needs one.
     */
    public FactionPick pick(int x, int y) {
        Player p = FACTIONS.player();
        String name = p.name.toString();
        if (name.isBlank())
            return null;
        return new FactionPick(p.race().key, name, Engine.bannerBytes(), x, y);
    }

    @Override
    public String locationProblem(int x, int y, int[] otherX, int[] otherY) {
        if (x < 0 || y < 0 || x >= WORLD.TWIDTH() || y >= WORLD.THEIGHT())
            return "outside the map";
        return Engine.locationProblem(x, y, otherX, otherY);
    }

    /**
     * The engine builds the world as vanilla's capital and finish stages do, with every human's capital (SOSDET SOS-55),
     * the humans in slots 0 to N-1. The lobby's difficulty goes on every human's Player: with the engine, a difficulty
     * booster counts only for its own Player (SOSDET patch 15). The game saves to a temporary folder; the bytes are the
     * save as the game wrote it, already compressed.
     */
    @Override
    public byte[] buildWorld(List<FactionPick> humans, String difficulty) {
        if (Engine.active())
            throw new IllegalStateException("SOSGSGMP: the world is built before any engine session");
        HumanStart[] starts = new HumanStart[humans.size()];
        for (int i = 0; i < starts.length; i++) {
            FactionPick p = humans.get(i);
            starts[i] = new HumanStart(p.race(), p.name(), p.banner(), p.x(), p.y());
        }
        Engine.buildWorld(starts);
        for (int slot = 0; slot < starts.length; slot++) {
            if (!(FACTIONS.getByIndex(slot) instanceof Player p))
                throw new IllegalStateException("SOSGSGMP: slot " + slot + " isn't a human after the world build: " + FACTIONS.getByIndex(slot));
            p.bonusesCustom.setMode(difficulty);
        }
        // As vanilla's StageFinish before its save: the save records the current view, and the generator's isn't one,
        // so a save taken in it would load into a new world generator (VIEW.setFirstView).
        WORLD.FOW().toggled.set(true);
        VIEW.world().activate();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("sosgsgmp-world");
            Path file = GAME.saver().save(dir, SAVE_NAME, true);
            if (file == null)
                throw new IllegalStateException("SOSGSGMP: the game couldn't save the new world in " + dir);
            byte[] save = Files.readAllBytes(file);
            LOG.ln("SOSGSGMP: the new world, " + humans.size() + " humans, saved: " + save.length + " bytes");
            return save;
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: saving the new world failed", e);
        } finally {
            deleteTree(dir);
        }
    }

    /** Writes the host's save to a temporary file for the game's loader; {@link #deleteSaveFile} removes it after. */
    public Path saveFile(byte[] save) {
        try {
            Path file = Files.createTempFile("sosgsgmp-world", ".save");
            Files.write(file, save);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't write the host's world for loading", e);
        }
    }

    public void deleteSaveFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.err("SOSGSGMP: can't delete the temporary world " + file + ": " + e);
        }
    }

    /**
     * The engine session the setup's load runs in, before the load (as SOSDET's rig starts an own-city run): the host
     * runs the AI general, the others follow its log; this PC's number is its slot; only the world is lockstep. The
     * load draws from the simulation stream seeded with {@code seed}, so every PC loads the same world. The session
     * holds at step 0 until the game starts.
     */
    public void beginSession(boolean host, int pc, long startId, int seed) {
        if (Engine.active())
            throw new IllegalStateException("SOSGSGMP: an engine session is already running at the setup's load");
        Engine.begin();
        if (host)
            Engine.aiHost(startId);
        else
            Engine.aiPeer(startId);
        Engine.localPc(pc);
        Engine.ownCity();
        Engine.armLoadSeed(loadSeed(seed));
    }

    /** Leaving the setup from the loaded game: the game is vanilla again for the menu and whatever is played next. */
    public void endSession() {
        if (Engine.active())
            Engine.end();
    }

    /** After the load, at step 0: every PC's human (PC number = slot), the same on every PC, and the shared seed. */
    public void humans(int[] slots, int seed) {
        for (int slot : slots)
            Engine.pcHuman(slot, slot);
        Engine.seed(seed);
    }

    /**
     * A joining PC's own city at its capital: the save holds the host's city, and each PC runs only its own (own-city
     * mode, ADR 0005). The engine makes it as vanilla's StageFinish does, keeping the world state the city's generation
     * would change on this PC only (SOSDET Engine.ownCityAt, hook 18; the landing view's Regenerate goes through it too).
     */
    public void ownCity(int x, int y) {
        Engine.ownCityAt(x, y);
    }

    /** This PC's landing party is placed: its city has people, as vanilla's landing view checks (SettViewStart). */
    public boolean landed() {
        return POP.tot() > 0;
    }

    /** The engine's world hash at step 0, compared on every PC (SOSDET Engine.worldHash). */
    public long worldHash() {
        return Engine.worldHash();
    }

    /** Names the start state for the AI log: the first 8 bytes of the save's SHA-256, the same on every PC. */
    public static long startId(byte[] save) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(save)).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SOSGSGMP: no SHA-256 in this Java", e);
        }
    }

    /** Never 0: 0 means vanilla loading (Engine.armLoadSeed). */
    private static int loadSeed(int seed) {
        return seed == 0 ? 1 : seed;
    }

    private static void deleteTree(Path dir) {
        if (dir == null)
            return;
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator)
                Files.deleteIfExists(f);
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            LOG.err("SOSGSGMP: can't delete the temporary folder " + dir + ": " + e);
        }
    }
}
