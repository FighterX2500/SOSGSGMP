package sosgsgmp.setup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The setup after the map (spec §5 Factions and starting locations; setup-factions-step.md §3): every player's picks,
 * the host's Initialize World, every PC's load of that world, its landing party, and the start.
 *
 * <p>One class for both sides, as {@link MapStep}. The host holds every player's latest picks and checks each
 * location on receipt; it initializes the world, numbers the transfer of its save and collects every PC's world hash
 * and start spot. A joining PC follows the host's tables. A stale or repeated message changes nothing. Game thread
 * only.
 */
public final class FactionStep {

    /** Why a location can't be picked, or null: the engine's rule (the terrain, and 6 tiles from the others). */
    @FunctionalInterface
    public interface LocationRule {
        String problem(int x, int y, int[] otherX, int[] otherY);
    }

    /**
     * A player's latest picks as the host holds them; {@code refused}: why the host cleared the location, or null;
     * {@code ready}: they pressed Ready (SOS-120), so their picks stay as they are until they press it again.
     */
    public record PlayerPick(int playerId, FactionPick pick, String refused, boolean ready) {}

    /** A player's place in the world the host built: slot 0 is the host's, then the joiners in join order. */
    public record PlayerSlot(int playerId, int slot) {}

    /** A player's progress once the world is initialized. Sent as its ordinal (in WorldPlayers), so only append. */
    public enum Progress {
        /** Getting and loading the host's world. */
        LOADING,
        /** Loaded, with the host's world hash; placing the landing party. */
        LANDING,
        /** Loaded, but the world hash differs from the host's: the game can't start. */
        MISMATCH,
        /** The landing party is placed. */
        READY
    }

    /** One player's progress, as the host tells every PC. */
    public record PlayerProgress(int playerId, Progress progress) {}

    private final boolean host;
    private final int localId;
    private final LocationRule rule;
    /** Host: the players still in the setup, in slot order (the host first, then the joiners in join order). */
    private final List<Integer> order = new ArrayList<>();
    /** Every player's latest picks: on the host its own record, on a joining PC as the host last sent them. */
    private final Map<Integer, PlayerPick> picks = new LinkedHashMap<>();
    /** This PC's own latest picks, before the host's table echoes them. */
    private FactionPick ownPick;
    /** This PC's player pressed Ready, before the host's table echoes it. */
    private boolean ownReady;
    /** The save's transfer number once the world is initialized; 0 before. */
    private int transfer;
    private final Map<Integer, Integer> slots = new HashMap<>();
    /** Host: each PC's world hash after the load. */
    private final Map<Integer, Long> hashes = new HashMap<>();
    /** Host: the PCs whose landing party is placed. */
    private final Set<Integer> landed = new HashSet<>();
    /** Joining PC: every player's progress as the host last sent it. */
    private final Map<Integer, Progress> hostView = new HashMap<>();
    private boolean go;

    private FactionStep(boolean host, int localId, LocationRule rule) {
        this.host = host;
        this.localId = localId;
        this.rule = rule;
    }

    /** The host's side, with the players who joined, in join order. */
    public static FactionStep host(int hostId, Collection<Integer> joinerIds, LocationRule rule) {
        FactionStep step = new FactionStep(true, hostId, rule);
        step.order.add(hostId);
        for (int id : joinerIds) {
            if (id == hostId || step.order.contains(id))
                throw new IllegalArgumentException("SOSGSGMP: player " + id + " listed twice");
            step.order.add(id);
        }
        return step;
    }

    /**
     * The host's side of a resumed campaign (SOS-64): every player already has the slot the campaign gave them, and the
     * world is "initialized" (transfer 1); each PC loads its own save, and the game starts as a new one does once every
     * PC loaded the same world and reported its start spot. {@code slots}: player id to slot, the host's among them;
     * the host may be alone, the others removed or missing (SOS-65).
     */
    public static FactionStep resumed(int hostId, Map<Integer, Integer> slots) {
        if (!slots.containsKey(hostId))
            throw new IllegalArgumentException("SOSGSGMP: a resume of slots " + slots + " by host " + hostId);
        FactionStep step = new FactionStep(true, hostId, null);
        slots.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e -> step.order.add(e.getKey()));
        step.slots.putAll(slots);
        step.transfer = 1;
        return step;
    }

    /** A joining PC's side. */
    public static FactionStep joiner(int localId) {
        return new FactionStep(false, localId, null);
    }

    // ------------------------------------------------------------ host

    /**
     * Host: a player's latest picks (the host's own too). A location that breaks the rule against the locations already
     * held is cleared, and the reason kept: the first pick received wins. Returns whether the table changed.
     */
    public boolean onPick(int playerId, FactionPick pick) {
        requireHost("collect picks");
        if (transfer > 0 || !order.contains(playerId) || isReady(playerId))
            return false;
        PlayerPick held = new PlayerPick(playerId, pick, null, false);
        if (pick.hasLocation()) {
            String problem = problem(playerId, pick.x(), pick.y(), rule);
            if (problem != null)
                held = new PlayerPick(playerId, pick.at(FactionPick.NONE, FactionPick.NONE), problem, false);
        }
        if (held.equals(picks.get(playerId)))
            return false;
        picks.put(playerId, held);
        if (playerId == localId)
            ownPick = held.pick();
        return true;
    }

    /** Why {@code playerId}'s location can't be at {@code x, y} by {@code rule}, with the others' locations held. */
    private String problem(int playerId, int x, int y, LocationRule rule) {
        int n = 0;
        for (PlayerPick p : picks.values())
            if (p.playerId() != playerId && p.pick().hasLocation())
                n++;
        int[] ox = new int[n];
        int[] oy = new int[n];
        int i = 0;
        for (PlayerPick p : picks.values()) {
            if (p.playerId() != playerId && p.pick().hasLocation()) {
                ox[i] = p.pick().x();
                oy[i] = p.pick().y();
                i++;
            }
        }
        return rule.problem(x, y, ox, oy);
    }

    /**
     * Why this PC's location can't be at {@code x, y} by {@code rule} (the engine's), with the other players' locations
     * as this PC knows them; null when it can. The host checks again when the pick arrives.
     */
    public String locationProblem(int x, int y, LocationRule rule) {
        return problem(localId, x, y, rule);
    }

    /**
     * Host: a player pressed Ready or pressed it again (SOS-120). Ready needs a location. False, changing nothing, when
     * the world is built, the player isn't in the setup, or nothing changes.
     */
    public boolean onReady(int playerId, boolean ready) {
        requireHost("collect ready");
        PlayerPick p = picks.get(playerId);
        if (transfer > 0 || !order.contains(playerId) || p == null || p.ready() == ready || (ready && !p.pick().hasLocation()))
            return false;
        picks.put(playerId, new PlayerPick(playerId, p.pick(), p.refused(), ready));
        if (playerId == localId)
            ownReady = ready;
        return true;
    }

    /** This PC's player pressed Ready or pressed it again; a joining PC sends it to the host. */
    public void ownReady(boolean ready) {
        ownReady = ready;
    }

    /** Whether this PC's player is ready: the setup's buttons stay greyed but for Ready itself. */
    public boolean ready() {
        return ownReady;
    }

    /** This PC's player can press Ready: the host's table holds their location. */
    public boolean canReady() {
        PlayerPick p = picks.get(localId);
        return transfer == 0 && p != null && p.pick().hasLocation();
    }

    private boolean isReady(int playerId) {
        PlayerPick p = picks.get(playerId);
        return p != null && p.ready();
    }

    /** Host: every player is ready: each holds a location, as Ready needs one. */
    private boolean everyoneReady() {
        for (int id : order)
            if (!isReady(id))
                return false;
        return !order.isEmpty();
    }

    /** Initialize World is active when every player is ready, until it is pressed. */
    public boolean canInitialize() {
        return host && transfer == 0 && everyoneReady();
    }

    /** Host: locks every pick and gives every player its slot. Returns the save's transfer number. */
    public int initialize() {
        requireHost("initialize the world");
        if (!canInitialize())
            throw new IllegalStateException("SOSGSGMP: the world can't be initialized now (picks " + picks.keySet() + ", players " + order + ")");
        transfer = 1;
        for (int i = 0; i < order.size(); i++)
            slots.put(order.get(i), i);
        return transfer;
    }

    /** Host: the picks in slot order, the host first: the world's humans. */
    public List<FactionPick> humans() {
        requireHost("list the humans");
        List<FactionPick> all = new ArrayList<>(order.size());
        for (int id : order)
            all.add(picks.get(id).pick());
        return all;
    }

    /** Host: every player's slot, once the world is initialized. */
    public List<PlayerSlot> slotMap() {
        requireHost("list the slots");
        List<PlayerSlot> all = new ArrayList<>(order.size());
        for (int id : order)
            all.add(new PlayerSlot(id, slots.get(id)));
        return all;
    }

    /** Host: a PC loaded the world. False, changing nothing, when stale, repeated or unknown. */
    public boolean onLoaded(int playerId, int transfer, long hash) {
        requireHost("collect loads");
        if (this.transfer == 0 || transfer != this.transfer || !order.contains(playerId) || hashes.containsKey(playerId))
            return false;
        hashes.put(playerId, hash);
        return true;
    }

    /** Host: a PC's landing party is placed. False, changing nothing, when stale, repeated, unknown or not loaded. */
    public boolean onStartSpot(int playerId, int transfer) {
        requireHost("collect start spots");
        if (this.transfer == 0 || transfer != this.transfer || !hashes.containsKey(playerId) || landed.contains(playerId))
            return false;
        landed.add(playerId);
        return true;
    }

    /** Host: every player's progress, the host first, for every PC's rows; empty before the world is initialized. */
    public List<PlayerProgress> progress() {
        requireHost("tell the progress");
        List<PlayerProgress> all = new ArrayList<>();
        if (transfer == 0)
            return all;
        for (int id : order)
            all.add(new PlayerProgress(id, progressOf(id)));
        return all;
    }

    private Progress progressOf(int id) {
        Long hash = hashes.get(id);
        if (hash == null)
            return Progress.LOADING;
        Long reference = hashes.get(localId); // the host's; until it has loaded, nothing to compare with
        if (reference != null && !hash.equals(reference))
            return Progress.MISMATCH;
        return landed.contains(id) ? Progress.READY : Progress.LANDING;
    }

    /** The game starts when every PC loaded the host's world and placed its landing party. */
    public boolean canGo() {
        if (!host || transfer == 0 || go || !hashes.containsKey(localId))
            return false;
        for (int id : order)
            if (progressOf(id) != Progress.READY)
                return false;
        return true;
    }

    /** Host: starts the game on every PC. Check {@link #canGo()} first. */
    public void go() {
        requireHost("start the game");
        if (!canGo())
            throw new IllegalStateException("SOSGSGMP: the game can't start now (progress " + progress() + ")");
        go = true;
    }

    /**
     * Host: a player left. Before the world is built they are dropped; after it, their slot exists and the game can't
     * start without them: the session stops the setup. False if they weren't in the setup.
     */
    public boolean remove(int playerId) {
        requireHost("remove players");
        if (playerId == localId || !order.remove((Integer) playerId))
            return false;
        picks.remove(playerId);
        return true;
    }

    // ------------------------------------------------------------ joining PC

    /** Joining PC: the host's table of picks. Returns whether it changed; nothing changes once the world is built. */
    public boolean onPicks(List<PlayerPick> table) {
        requireJoiner("follow the picks");
        if (transfer > 0)
            return false;
        Map<Integer, PlayerPick> now = new LinkedHashMap<>();
        for (PlayerPick p : table)
            now.put(p.playerId(), p);
        if (now.equals(picks))
            return false;
        picks.clear();
        picks.putAll(now);
        PlayerPick mine = now.get(localId);
        if (mine != null && !mine.pick().hasLocation())
            ownReady = false; // the host refused the location this PC pressed Ready on
        return true;
    }

    /**
     * Joining PC: the host initialized the world: every player's slot, and the number of the save's transfer. False,
     * changing nothing, when repeated or when this PC has no slot in it.
     */
    public boolean onSlotMap(int transfer, List<PlayerSlot> map) {
        requireJoiner("follow the slots");
        if (this.transfer > 0 || transfer < 1)
            return false;
        Map<Integer, Integer> now = new HashMap<>();
        for (PlayerSlot s : map)
            now.put(s.playerId(), s.slot());
        if (!now.containsKey(localId))
            return false;
        this.transfer = transfer;
        slots.putAll(now);
        return true;
    }

    /** Joining PC: every player's progress as the host sees it. Returns whether it changed. */
    public boolean onProgress(int transfer, List<PlayerProgress> all) {
        requireJoiner("follow the progress");
        if (transfer != this.transfer)
            return false;
        Map<Integer, Progress> now = new HashMap<>();
        for (PlayerProgress p : all)
            now.put(p.playerId(), p.progress());
        if (now.equals(hostView))
            return false;
        hostView.clear();
        hostView.putAll(now);
        return true;
    }

    /** Joining PC: the host started the game. False, changing nothing, when stale or repeated. */
    public boolean onGo(int transfer) {
        requireJoiner("follow the start");
        if (go || this.transfer == 0 || transfer != this.transfer)
            return false;
        go = true;
        return true;
    }

    // ------------------------------------------------------------ both

    /** This PC's own latest picks, as it sends them (the host also records them with {@link #onPick}). */
    public void ownPick(FactionPick pick) {
        if (transfer > 0)
            throw new IllegalStateException("SOSGSGMP: the picks are locked; the world is built");
        ownPick = pick;
    }

    /** This PC's latest picks, or null before the first. */
    public FactionPick ownPick() {
        return ownPick;
    }

    /** This PC loaded the world; the host records its hash as the reference. */
    public void loaded(long hash) {
        if (transfer == 0)
            throw new IllegalStateException("SOSGSGMP: loaded a world before it was initialized");
        if (host)
            hashes.put(localId, hash);
    }

    /** This PC's landing party is placed. */
    public void startSpot() {
        if (host && !landed.contains(localId)) {
            if (!hashes.containsKey(localId))
                throw new IllegalStateException("SOSGSGMP: a start spot before this PC loaded the world");
            landed.add(localId);
        }
    }

    /** Every player's latest picks, the host's first: on the host its record, on a joining PC as last sent. */
    public List<PlayerPick> table() {
        List<PlayerPick> all = new ArrayList<>(picks.size());
        if (host) {
            for (int id : order)
                if (picks.containsKey(id))
                    all.add(picks.get(id));
        } else {
            all.addAll(picks.values());
        }
        return all;
    }

    /** A player's progress once the world is initialized; null before, or for a player this PC knows nothing of. */
    public Progress progress(int playerId) {
        if (transfer == 0)
            return null;
        if (host)
            return order.contains(playerId) ? progressOf(playerId) : null;
        return hostView.get(playerId);
    }

    /** Whether the world is initialized: the host pressed Initialize World (seen on a joining PC as its slot map). */
    public boolean initialized() {
        return transfer > 0;
    }

    /** The save's transfer number; 0 before the world is initialized. */
    public int transfer() {
        return transfer;
    }

    /** A player's slot in the world; -1 before the world is initialized or for a player without one. */
    public int slotOf(int playerId) {
        Integer s = slots.get(playerId);
        return s == null ? -1 : s;
    }

    public boolean started() {
        return go;
    }

    public boolean isHost() {
        return host;
    }

    public int localPlayerId() {
        return localId;
    }

    private void requireHost(String what) {
        if (!host)
            throw new IllegalStateException("SOSGSGMP: only the host can " + what);
    }

    private void requireJoiner(String what) {
        if (host)
            throw new IllegalStateException("SOSGSGMP: the host doesn't " + what + "; it decides");
    }
}
