package sosgsgmp.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.player.Player;
import game.save.SaveFile;
import settlement.main.SETT;
import settlement.stats.POP;
import snake2d.LOG;
import sosdet.Engine;
import sosdet.HumanStart;
import sosgsgmp.campaign.Campaign;
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
     * This PC's picks as the game holds them: race, faction name, ruler name (SOS-98) and banner, at world tile {@code x, y} (or
     * {@link FactionPick#NONE} twice). Null while the faction has no name: a pick needs one.
     */
    public FactionPick pick(int x, int y) {
        Player p = FACTIONS.player();
        String name = p.name.toString();
        if (name.isBlank())
            return null;
        return new FactionPick(p.race().key, name, p.rulerName.toString(), Engine.bannerBytes(), x, y);
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
        HumanStart[] starts = starts(humans);
        // The generator, to come back to: the build opens the host's city (its finish stage generates it), and the
        // save below needs the world view; the generator's factions screen pumps the session (two-PC tests T2, T3).
        VIEW.ViewSubSimple generator = VIEW.current();
        Engine.buildWorld(starts);
        setModes(starts.length, difficulty);
        try {
            return saveWorld(humans.size() + " humans");
        } finally {
            generator.activate();
        }
    }

    @Override
    public String settleProblem(int x, int y, int[] otherX, int[] otherY) {
        if (x < 0 || y < 0 || x >= WORLD.TWIDTH() || y >= WORLD.THEIGHT())
            return "outside the map";
        return Engine.settleProblem(x, y, otherX, otherY);
    }

    /**
     * The engine builds the world around the host's capital alone, as {@link #buildWorld} does for every human, keeping
     * NPC slots 1 to {@code humans - 1} free for the others (SOSDET SOS-187). Every PC loads this save to settle on.
     */
    @Override
    public byte[] buildForSettling(FactionPick host, int humans) {
        if (Engine.active())
            throw new IllegalStateException("SOSGSGMP: the world is built before any engine session");
        VIEW.ViewSubSimple generator = VIEW.current();
        Engine.buildWorldForCarving(starts(List.of(host))[0], humans);
        try {
            return saveWorld("living, the host alone, room for " + humans + " humans");
        } finally {
            generator.activate();
        }
    }

    /**
     * On the world built for settling, loaded on this PC: the engine carves every other human's city-state at their spot
     * in slot order (SOSDET SOS-187; a spot an earlier carve made unfit moves at most 3 tiles), then this PC saves.
     */
    @Override
    public byte[] settle(List<FactionPick> humans, String difficulty) {
        if (Engine.active())
            throw new IllegalStateException("SOSGSGMP: the humans settle before any engine session");
        String carved = Engine.carveHumans(starts(humans));
        LOG.ln("SOSGSGMP: the humans settled: " + carved);
        setModes(humans.size(), difficulty);
        return saveWorld("living, " + humans.size() + " humans settled");
    }

    /** A living world's human in {@code slot}: its capital's centre, which its carve may have moved from the spot. */
    public int[] capitalOf(int slot) {
        if (!(FACTIONS.getByIndex(slot) instanceof Player p) || p.capitolRegion() == null)
            throw new IllegalStateException("SOSGSGMP: slot " + slot + " has no human with a capital: " + FACTIONS.getByIndex(slot));
        return new int[] { p.capitolRegion().cx(), p.capitolRegion().cy() };
    }

    // ------------------------------------------------------------------ hot-join (SOS-190, docs/plan/hot-join-restart.md)

    /** The slot a player joining this game would take, or -1 when the map has no room (SOSDET Engine.joinSlot). */
    public int joinSlot() {
        return Engine.joinSlot();
    }

    /**
     * Why a joining player's city-state can't go at {@code x, y} on this PC's world, or null: the carving rule, and
     * 6 tiles or more from every human's capital (SOSDET Engine.settleProblem). Between steps, or on a snapshot.
     */
    public String joinProblem(int x, int y) {
        int n = 0;
        int[] xs = new int[FACTIONS.MAX()];
        int[] ys = new int[FACTIONS.MAX()];
        for (int i = 0; i < FACTIONS.MAX(); i++) {
            if (FACTIONS.getByIndex(i) instanceof Player p && p.capitolRegion() != null) {
                xs[n] = p.capitolRegion().cx();
                ys[n] = p.capitolRegion().cy();
                n++;
            }
        }
        return settleProblem(x, y, java.util.Arrays.copyOf(xs, n), java.util.Arrays.copyOf(ys, n));
    }

    /**
     * Hot-join: the newcomer's credits before its landing. A late joiner starts as every player did (owner's decision 8,
     * docs/plan/hot-join.md): vanilla's new game starts at 0 and the landing adds its bonus, which the newcomer's landing
     * does too.
     */
    public double joinCredits() {
        return 0;
    }

    /**
     * At a hot-join restart, after this PC's load and before step 0: the newcomer's city-state is carved at
     * {@code pick}'s spot and its slot made human, the same on every PC (SOSDET Engine.addHuman). Returns a line for
     * the log. The game is then saved ({@link #saveTemp}) and loaded again.
     */
    public String addHuman(FactionPick pick, double credits) {
        return Engine.addHuman(new HumanStart(pick.race(), pick.name(), pick.ruler(), pick.banner(), pick.x(), pick.y()),
                credits);
    }

    /**
     * At a rejoin's restart, on the returning PC after its freeze save's load (SOS-204): the human slots of
     * {@code hostSlots} its game lacks, players who joined since it froze, become stand-ins the host's world replaces
     * (SOSDET Engine.addMissingHumans). Returns a line for the log, empty when none was missing; otherwise the game is
     * then saved ({@link #saveTemp}) and loaded again.
     */
    public String addMissingHumans(int[] hostSlots) {
        return Engine.addMissingHumans(hostSlots);
    }

    /** This PC's game in a temporary folder, for a load right after; {@link #deleteTemp} removes it. */
    public Path saveTemp() {
        try {
            Path dir = Files.createTempDirectory("sosgsgmp-restart");
            Path file = GAME.saver().save(dir, "restart", false);
            if (file == null)
                throw new IllegalStateException("SOSGSGMP: the game couldn't save in " + dir);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't make a temporary folder for the restart's save", e);
        }
    }

    /** Removes a {@link #saveTemp} save and its folder. */
    public void deleteTemp(Path file) {
        deleteTree(file.getParent());
    }

    /**
     * Host, a rejoin (SOS-195), right after its founding save, at that save's step: the world's sections as the save had
     * them and the world's state no save holds (SOSDET Engine.rejoinOut), packed for every PC's {@link #rejoin}.
     */
    public byte[] rejoinOut() {
        Path file = null;
        try {
            file = Files.createTempFile("sosgsgmp-rejoin", ".save");
            byte[] state = Engine.rejoinOut(file);
            byte[] world = Files.readAllBytes(file);
            return ByteBuffer.allocate(4 + world.length + state.length).putInt(world.length).put(world).put(state).array();
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't write the rejoin's world", e);
        } finally {
            if (file != null)
                deleteSaveFile(file);
        }
    }

    /**
     * Every PC, a rejoin (SOS-195), after its load and the humans, before step 0: the host's {@code files}
     * ({@link #rejoinOut}) load into this game, keeping its city, and the frozen human in {@code slot} thaws (SOSDET
     * Engine.rejoinLoad, thawHuman).
     */
    public void rejoin(byte[] files, int slot) {
        ByteBuffer b = ByteBuffer.wrap(files);
        int length = b.getInt();
        if (length <= 0 || length > b.remaining())
            throw new IllegalArgumentException("SOSGSGMP: rejoin files with a world of " + length + " bytes");
        byte[] world = new byte[length];
        b.get(world);
        byte[] state = new byte[b.remaining()];
        b.get(state);
        Path file = saveFile(world);
        try {
            Engine.rejoinLoad(file, state);
        } finally {
            deleteSaveFile(file);
        }
        if (!Engine.frozen(slot))
            throw new IllegalStateException("SOSGSGMP: the rejoin's world has no frozen human in slot " + slot);
        Engine.thawHuman(slot);
    }

    private static HumanStart[] starts(List<FactionPick> humans) {
        HumanStart[] starts = new HumanStart[humans.size()];
        for (int i = 0; i < starts.length; i++) {
            FactionPick p = humans.get(i);
            starts[i] = new HumanStart(p.race(), p.name(), p.ruler(), p.banner(), p.x(), p.y());
        }
        return starts;
    }

    /**
     * The lobby's difficulty on every human's Player: with the engine, a difficulty booster counts only for its own Player
     * (SOSDET patch 15).
     */
    private static void setModes(int humans, String difficulty) {
        for (int slot = 0; slot < humans; slot++) {
            if (!(FACTIONS.getByIndex(slot) instanceof Player p))
                throw new IllegalStateException("SOSGSGMP: slot " + slot + " isn't a human after the world build: " + FACTIONS.getByIndex(slot));
            p.bonusesCustom.setMode(difficulty);
        }
    }

    /**
     * Saves the world as vanilla's StageFinish does before its save: the save records the current view, and the
     * generator's isn't one, so a save taken in it would load into a new world generator (VIEW.setFirstView). The game
     * saves to a temporary folder; the bytes are the save as the game wrote it, already compressed.
     */
    private static byte[] saveWorld(String what) {
        WORLD.FOW().toggled.set(true);
        VIEW.world().activate();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("sosgsgmp-world");
            Path file = GAME.saver().save(dir, SAVE_NAME, true);
            if (file == null)
                throw new IllegalStateException("SOSGSGMP: the game couldn't save the new world in " + dir);
            byte[] save = Files.readAllBytes(file);
            LOG.ln("SOSGSGMP: the new world (" + what + ") saved: " + save.length + " bytes");
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
        Engine.localBattles(true); // a battle against NPCs runs on its player's PC, the world running on (spec §8)
        Engine.workFolder(DataFolders.layout().engine()); // the battle and view saves stay out of the player's saves (SOS-155)
        Engine.armLoadSeed(loadSeed(seed));
    }

    /**
     * Saves this PC's game into the mod's saves folder as {@code name} with vanilla's time stamp (spec §11, Save and
     * exit at a world desync; SOS-155): the world and this PC's city. Returns the save's file name.
     */
    public String saveGame(String name) {
        Path dir = DataFolders.layout().saves();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't make the saves folder " + dir, e);
        }
        Path file = GAME.saver().save(dir, SaveFile.stamp(name), false);
        if (file == null)
            throw new IllegalStateException("SOSGSGMP: the game couldn't save '" + name + "' in " + dir);
        LOG.ln("SOSGSGMP: saved " + file);
        return file.getFileName().toString();
    }

    /** A campaign save by its file name, as {@link #saveGame} returns it (SOS-64): the mod's folder, else the game's (SOS-155). */
    public Path savePath(String file) {
        return DataFolders.layout().save(file);
    }

    /** Deletes a campaign save wherever it is: an autosave the campaign's rotation dropped. */
    public void deleteSave(String file) {
        try {
            for (Path p : DataFolders.layout().saveCandidates(file))
                Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't delete the old campaign autosave " + file, e);
        }
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
     * At step 0, before the game starts: the human in {@code slot} is removed, its player missing at a resume (spec §11
     * Resuming, SOSDET Engine.removeHumanAtStart). Every PC does it for the same slots.
     */
    public void removeAtStart(int slot) {
        String refusal = Engine.removeRefusal(slot, true);
        if (refusal != null)
            throw new IllegalStateException("SOSGSGMP: the missing player in slot " + slot + " can't be removed: " + refusal);
        Engine.removeHumanAtStart(slot);
        LOG.ln("SOSGSGMP: removed the missing player in slot " + slot + " at the start");
    }

    /**
     * A joining PC's own city at its capital: the save holds the host's city, and each PC runs only its own (own-city
     * mode, ADR 0005). The engine makes it as vanilla's StageFinish does, keeping the world state the city's generation
     * would change on this PC only (SOSDET Engine.ownCityAt, hook 18; the landing view's Regenerate goes through it too).
     */
    public void ownCity(int x, int y) {
        Engine.ownCityAt(x, y);
    }

    /** This PC's own city now: how many of its entry points are active (none: no roads, SOS-75). */
    public int activeEntries() {
        return SETT.ENTRY().points.active().size();
    }

    /**
     * Opens vanilla's landing view while this PC's city has no people, as a new game starts in it. A city's generation
     * opens it ({@code SETT.CreateFromWorldMap} clears the city view), so a joiner's own city has it; a loaded save
     * opens it too ({@code SettView}'s loader), but the save's first view, the world, replaces it, so the host's city
     * didn't (owner's playtest, 2026-10-25). Until the landing the game can't start.
     */
    public void openLanding() {
        if (POP.tot() == 0)
            VIEW.s().clear();
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
        return Campaign.idOf(save);
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
