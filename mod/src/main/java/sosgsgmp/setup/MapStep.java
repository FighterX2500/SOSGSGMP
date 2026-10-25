package sosgsgmp.setup;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The map step's state (spec §5 Map; setup-map-step.md §3): the current build and generation, each PC's result, and
 * the confirm. The host numbers builds and generations from 1. With no world tick before the world exists, these
 * numbers order the step: a reply for an older build or generation (stale) or a second one for the same (repeat)
 * changes nothing, and nothing changes after the confirm.
 *
 * <p>One class for both sides, since both run the same builds and generations. The host decides them
 * ({@link #build}, {@link #generate}, {@link #confirm}) and collects the joiners' replies; a joining PC follows the
 * host ({@link #onBuild}, {@link #onGenerate}, {@link #onConfirm}). Every PC reports its own progress through
 * {@link #built} and {@link #generated}. Game thread only.
 */
public final class MapStep {

    /** A player's progress, as the map panel shows it. */
    public enum PlayerState {
        /** Building the game for the current build. */
        BUILDING,
        /** Built; no generation yet for this build. */
        READY,
        /** Generating the latest generation, or (seen from the host) its result hasn't arrived. */
        GENERATING,
        /** This PC's own result for the latest generation; the host's is the reference. */
        GENERATED,
        /** A joiner's hash for the latest generation equals the host's. */
        MATCH,
        MISMATCH,
        /** A PC couldn't generate: an unknown map type or a wrong build. */
        ERROR
    }

    private static final class Progress {
        int builtId;
        int resultGenId;
        long hash;
        MapStatus status;
    }

    private final boolean host;
    private final int localId;
    /** Host: the joined players still in the setup, by player id. Empty on a joining PC. */
    private final Map<Integer, Progress> joiners = new LinkedHashMap<>();
    private final Progress own = new Progress();
    private int buildId;
    private int size;
    private int genId;
    /** The latest generation's options; null before the first generation of the current build. */
    private MapOptions options;
    /** Joining PC: the latest generation is accepted but not run yet. */
    private boolean generationPending;
    private boolean confirmed;
    private long confirmedHash;

    private MapStep(boolean host, int localId) {
        this.host = host;
        this.localId = localId;
    }

    /** The host's side, with the players who joined the lobby. */
    public static MapStep host(int hostId, Collection<Integer> joinerIds) {
        MapStep step = new MapStep(true, hostId);
        for (int id : joinerIds) {
            if (id == hostId)
                throw new IllegalArgumentException("SOSGSGMP: the host " + id + " is listed as a joiner");
            step.joiners.put(id, new Progress());
        }
        return step;
    }

    /** A joining PC's side. */
    public static MapStep joiner(int localId) {
        return new MapStep(false, localId);
    }

    // ------------------------------------------------------------ host

    /** Host: starts the next build at {@code size}, which voids every result so far. Returns the build's number. */
    public int build(int size) {
        requireHost("build");
        if (confirmed)
            throw new IllegalStateException("SOSGSGMP: the map is confirmed; no new build");
        checkSize(size);
        buildId++;
        this.size = size;
        newBuild();
        return buildId;
    }

    /** Generate is active on the host once its own game for the current build is up, until the confirm. */
    public boolean canGenerate() {
        return host && !confirmed && buildId > 0 && own.builtId == buildId;
    }

    /**
     * Host: starts the next generation with these options and returns its number. The host then generates and
     * reports through {@link #generated}. Check {@link #canGenerate()} first.
     */
    public int generate(MapOptions o) {
        requireHost("generate");
        if (!canGenerate())
            throw new IllegalStateException("SOSGSGMP: can't generate now (build " + buildId + ", built " + own.builtId + ", confirmed " + confirmed + ")");
        if (o.size() != size)
            throw new IllegalArgumentException("SOSGSGMP: options for size " + o.size() + " in a build of size " + size);
        if (!o.isRandom() && !MapOptions.presetsAllowed(size))
            throw new IllegalArgumentException("SOSGSGMP: map type '" + o.mapType() + "' at size " + size + "; presets only at " + MapOptions.DEFAULT_SIZE);
        genId++;
        options = o;
        return genId;
    }

    /** Host: a joiner's game for {@code buildId} is up. False, changing nothing, when stale, repeated or unknown. */
    public boolean onBuildReady(int playerId, int buildId) {
        requireHost("collect builds");
        Progress p = joiners.get(playerId);
        if (p == null || confirmed || buildId != this.buildId || p.builtId == buildId)
            return false;
        p.builtId = buildId;
        return true;
    }

    /** Host: a joiner's result. False, changing nothing, when stale, repeated or unknown. */
    public boolean onResult(int playerId, int genId, long hash, MapStatus status) {
        requireHost("collect results");
        Progress p = joiners.get(playerId);
        if (p == null || confirmed || options == null || genId != this.genId || p.resultGenId == genId)
            return false;
        p.resultGenId = genId;
        p.hash = hash;
        p.status = status;
        return true;
    }

    /** Host: a player left; they no longer count for the confirm. False if they weren't in the setup. */
    public boolean remove(int playerId) {
        requireHost("remove players");
        return joiners.remove(playerId) != null;
    }

    /** Confirm is active when every PC's result for the latest generation is OK and equals the host's hash. */
    public boolean canConfirm() {
        if (!host || confirmed || options == null || own.resultGenId != genId || own.status != MapStatus.OK)
            return false;
        for (Progress p : joiners.values())
            if (p.resultGenId != genId || p.status != MapStatus.OK || p.hash != own.hash)
                return false;
        return true;
    }

    /** Host: locks the map. Check {@link #canConfirm()} first. */
    public void confirm() {
        requireHost("confirm");
        if (!canConfirm())
            throw new IllegalStateException("SOSGSGMP: the map can't be confirmed now (generation " + genId + ")");
        confirmed = true;
        confirmedHash = own.hash;
    }

    // ------------------------------------------------------------ joining PC

    /** Joining PC: the host's build. False, changing nothing, when stale, repeated or after the confirm. */
    public boolean onBuild(int buildId, int size) {
        requireJoiner("follow a build");
        if (confirmed || buildId <= this.buildId)
            return false;
        checkSize(size);
        this.buildId = buildId;
        this.size = size;
        newBuild();
        return true;
    }

    /**
     * Joining PC: the host's generation. False, changing nothing, when stale, repeated or after the confirm. If it
     * names a build this PC doesn't have, its result is {@link MapStatus#WRONG_BUILD} at once; otherwise it waits in
     * {@link #pendingGeneration()}.
     */
    public boolean onGenerate(int genId, int buildId, String mapType, int latitude, int seed) {
        requireJoiner("follow a generation");
        if (confirmed || genId <= this.genId)
            return false;
        this.genId = genId;
        if (buildId != this.buildId || own.builtId != buildId) {
            options = null;
            generationPending = false;
            setOwn(genId, 0, MapStatus.WRONG_BUILD);
            return true;
        }
        options = new MapOptions(size, mapType, latitude, seed);
        generationPending = true;
        return true;
    }

    /** Joining PC: the generation to run now, or null. */
    public MapOptions pendingGeneration() {
        return generationPending ? options : null;
    }

    /** Joining PC: the host confirmed. False, changing nothing, when stale, repeated or not generated here. */
    public boolean onConfirm(int genId, long hash) {
        requireJoiner("follow the confirm");
        if (confirmed || genId != this.genId || own.resultGenId != genId)
            return false;
        confirmed = true;
        confirmedHash = hash;
        return true;
    }

    // ------------------------------------------------------------ both

    /** The build this PC still has to run (it leaves its current game for it), or 0 when its game is current. */
    public int pendingBuild() {
        return buildId > 0 && own.builtId != buildId ? buildId : 0;
    }

    /** This PC's game for {@code buildId}, the current build, is up. */
    public void built(int buildId) {
        if (buildId != this.buildId || own.builtId == buildId)
            throw new IllegalStateException("SOSGSGMP: built " + buildId + ", but the current build is " + this.buildId + " (built " + own.builtId + ")");
        own.builtId = buildId;
    }

    /** This PC ran generation {@code genId}, the latest. */
    public void generated(int genId, long hash, MapStatus status) {
        boolean running = host ? options != null && own.resultGenId != genId : generationPending;
        if (genId != this.genId || !running)
            throw new IllegalStateException("SOSGSGMP: generated " + genId + ", but the latest generation is " + this.genId + " (result for " + own.resultGenId + ")");
        generationPending = false;
        setOwn(genId, hash, status);
    }

    public boolean isHost() {
        return host;
    }

    public int localPlayerId() {
        return localId;
    }

    /** The current build's number; 0 before the first. */
    public int buildId() {
        return buildId;
    }

    /** The current build's world size; 0 before the first build. */
    public int size() {
        return size;
    }

    /** The latest generation's number; 0 before the first. */
    public int genId() {
        return genId;
    }

    /** The latest generation's options; null before the first generation of the current build. */
    public MapOptions options() {
        return options;
    }

    /** This PC's result for the latest generation, or null when it has none. */
    public MapStatus status() {
        return genId > 0 && own.resultGenId == genId ? own.status : null;
    }

    /** This PC's terrain hash for the latest generation; meaningful when {@link #status()} is OK. */
    public long hash() {
        return own.hash;
    }

    public boolean confirmed() {
        return confirmed;
    }

    /** The host's hash of the confirmed map. */
    public long confirmedHash() {
        return confirmedHash;
    }

    /** A player's progress: this PC's own, or (on the host) a joiner's. Null for a player this PC knows nothing of. */
    public PlayerState state(int playerId) {
        if (playerId == localId)
            return ownState();
        Progress p = joiners.get(playerId);
        if (p == null)
            return null;
        if (p.builtId != buildId)
            return PlayerState.BUILDING;
        if (options == null)
            return PlayerState.READY;
        if (p.resultGenId != genId)
            return PlayerState.GENERATING;
        if (p.status != MapStatus.OK)
            return PlayerState.ERROR;
        if (own.resultGenId != genId)
            return PlayerState.GENERATED;
        return p.hash == own.hash ? PlayerState.MATCH : PlayerState.MISMATCH;
    }

    private PlayerState ownState() {
        if (own.builtId != buildId)
            return PlayerState.BUILDING;
        if (generationPending)
            return PlayerState.GENERATING;
        MapStatus s = status();
        if (s == null)
            return PlayerState.READY;
        return s == MapStatus.OK ? PlayerState.GENERATED : PlayerState.ERROR;
    }

    /** A new build voids every result: the old terrain is gone with the old game. */
    private void newBuild() {
        options = null;
        generationPending = false;
        own.resultGenId = 0;
        for (Progress p : joiners.values())
            p.resultGenId = 0;
    }

    private void setOwn(int genId, long hash, MapStatus status) {
        own.resultGenId = genId;
        own.hash = hash;
        own.status = status;
    }

    private static void checkSize(int size) {
        if (size < MapOptions.MIN_SIZE || size > MapOptions.MAX_SIZE)
            throw new IllegalArgumentException("SOSGSGMP: map size " + size + " is outside " + MapOptions.MIN_SIZE + ".." + MapOptions.MAX_SIZE);
    }

    private void requireHost(String what) {
        if (!host)
            throw new IllegalStateException("SOSGSGMP: only the host can " + what);
    }

    private void requireJoiner(String what) {
        if (host)
            throw new IllegalStateException("SOSGSGMP: only a joining PC can " + what);
    }
}
