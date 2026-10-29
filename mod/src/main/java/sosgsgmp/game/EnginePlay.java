package sosgsgmp.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import game.GAME;
import game.faction.FACTIONS;
import snake2d.LOG;
import sosdet.Assault;
import sosdet.DesyncHold;
import sosdet.Engine;
import sosdet.BattleOutcome;
import sosdet.OwnBattle;
import sosdet.Stats;
import sosdet.View;
import sosdet.hash.Desync;
import sosgsgmp.net.StreamKind;
import sosgsgmp.session.BattleSaves;
import sosgsgmp.session.EnginePort;
import sosgsgmp.session.WorldResync;

/**
 * SOSGSGMP: the running game's engine calls (game-over-network.md): the start at {@code Go}, the four streams the
 * session carries, and what the panel shows. Game thread only, outside the step.
 */
public final class EnginePlay implements EnginePort {

    /** The host runs at most this many steps past the slowest PC (about 17 s at 1x; SOS-86, was 60). */
    private static final long PACE_LEAD = 1000;
    /** The speed every PC starts at: the engine's agreed speed at step 0, 1x by default. */
    private static final double AGREED_START = 1;
    private static final byte[] NONE = new byte[0];

    /** Who holds the engine at a step: a campaign save (SOS-64), or a player gone (SOS-65). */
    public enum Hold {
        SAVE, GONE
    }

    /** Each hold's step: the engine runs to the earliest, and freely once there is none. */
    private final Map<Hold, Long> holds = new EnumMap<>(Hold.class);

    /** Battle saves from the BATTLE stream and this PC's own, until its engine loads them (plan 0006). */
    private final BattleSaves battles = new BattleSaves();
    /** The size of the last battle save this PC sent or loaded, for the playtest log. */
    private int battleBytes;

    /**
     * Every PC at {@code Go}: the game unpauses at the agreed speed (spec §5.5, the automatic unpause), the game speed
     * follows the agreed one from then on, the host paces by the slowest of {@code peerPcs}, and the session runs. A
     * PC's own game speed sets its pace, so a PC still paused here would never step, and the host's later speed would
     * never reach it: it arrives through the log at a step ahead (two-PC test T5). A peer can't run ahead of the host's
     * AI log.
     */
    public void start(boolean host, int[] peerPcs) {
        GAME.SPEED.speedSet(AGREED_START);
        Engine.followAgreedSpeed();
        if (host && peerPcs.length > 0)
            Engine.pace(peerPcs, PACE_LEAD);
        Engine.battleHold(true); // the campaign is saved before every battle (spec §11, SOS-67)
        Engine.runFreely();
    }

    @Override
    public byte[] out(StreamKind kind) {
        return switch (kind) {
            case AI_LOG -> Engine.aiLogOut();
            case ORDERS -> Engine.ordersOut();
            case REPORTS -> Engine.reportsOut();
            case HASHES -> Engine.hashOut();
            case BATTLE -> battleOut();
        };
    }

    /** On the battle PC, once per battle: the battle it generated, which this PC loads too. */
    private byte[] battleOut() {
        byte[] save = Engine.battleOut();
        if (save == null)
            return NONE;
        long step = Engine.battle().step;
        battles.own(step, save);
        battleBytes = save.length;
        return BattleSaves.frame(step, save);
    }

    /**
     * Every frame, outside the step: while a battle waits for its save, the save loads as soon as it is here (every PC
     * holds at the Engage step until then).
     */
    public void battleFrame() {
        OwnBattle b = Engine.battle();
        if (b == null || !b.awaiting())
            return;
        byte[] save = battles.take(b.step);
        if (save == null)
            return;
        battleBytes = save.length;
        Engine.battleIn(save);
    }

    /** The battle on the battle map, or null (SOSDET plan 0006). */
    public OwnBattle battle() {
        return Engine.battle();
    }

    /** The assault on a human's capital running, or null (SOS-92 B). */
    public Assault assault() {
        return Engine.assault();
    }

    /**
     * The next NPC invasion of a player's capital, as {step, slot, invading faction or -1}, or null (SOS-92 F): the
     * same on every PC, each handed out once.
     */
    public long[] invasionNoticeTake() {
        return Engine.invasionNoticeTake();
    }

    /** The realm name of the faction at {@code index}; "Rebels" for -1, the invaders with no faction. */
    public String factionName(int index) {
        return index < 0 ? "Rebels" : FACTIONS.getByIndex(index).name.toString();
    }

    /** Once, as a battle between two players ends: who won and lost, and each side's losses; else null (SOS-83). */
    public BattleOutcome battleOutcomeTake() {
        return Engine.battleOutcomeTake();
    }

    /** The size of the last battle save this PC sent or loaded; 0 before the first. */
    public int battleBytes() {
        return battleBytes;
    }

    /**
     * Every load of a whole game in the session (a battle's start and end) builds a new game without what the mod put
     * in the old one: {@code again} puts it in again.
     */
    public void afterEachLoad(Runnable again) {
        Engine.afterEachLoad(again);
    }

    @Override
    public void in(StreamKind kind, int pc, byte[] bytes) {
        switch (kind) {
            case AI_LOG -> Engine.aiLogIn(bytes);
            case ORDERS -> Engine.ordersIn(pc, bytes);
            case REPORTS -> Engine.reportsIn(pc, bytes);
            case HASHES -> Engine.hashIn(pc, bytes);
            case BATTLE -> battles.in(pc, bytes);
        }
    }

    @Override
    public long step() {
        return Engine.step();
    }

    /** Holds every step past {@code step} for {@code who}, until {@link #release}; another hold may stop it sooner. */
    public void hold(Hold who, long step) {
        holds.put(who, step);
        applyHolds();
    }

    /** {@code who}'s hold ends; the steps run on unless another holds them. */
    public void release(Hold who) {
        holds.remove(who);
        applyHolds();
    }

    private void applyHolds() {
        long until = Long.MAX_VALUE;
        for (long step : holds.values())
            until = Math.min(until, step);
        if (until == Long.MAX_VALUE)
            Engine.runFreely();
        else
            Engine.runUntil(until);
    }

    /**
     * Host, between steps: removes the human in {@code slot}, whose player left (spec §11 Removing a player). The engine
     * logs it for a step ahead, so every PC removes it at that step. Check {@link #removeRefusal} first.
     */
    public void removeHuman(int slot) {
        Engine.removeHuman(slot);
    }

    /** Why {@link #removeHuman} would refuse {@code slot} now, as a sentence for the player; null when it wouldn't. */
    public String removeRefusal(int slot) {
        return Engine.removeRefusal(slot, false);
    }

    /** Whether the human in {@code slot} is removed: it owns nothing now, and nothing runs for it. */
    public boolean retired(int slot) {
        return Engine.retired(slot);
    }

    /** Whether the engine's session runs: it ends at Save and exit. */
    public boolean active() {
        return Engine.active();
    }

    /**
     * After a whole-game load outside a battle (a city view's, SOS-82): the load leaves the game paused, and this PC's
     * game speed sets its pace, so it unpauses at the agreed speed, as at {@link #start}. A battle's load stays paused
     * for its deployment.
     */
    public void resumeAfterLoad() {
        if (Engine.battle() == null)
            GAME.SPEED.speedSet(agreedSpeed());
    }

    /** The speed every PC runs at now. Builds the engine's stats: for the panel's refresh, not every frame. */
    public double agreedSpeed() {
        return Engine.stats().agreedSpeed;
    }

    /** The engine's counters now. Builds them: for a status every few seconds, not every frame. */
    public Stats stats() {
        return Engine.stats();
    }

    /** The last step PC {@code pc}'s hashes reached, -1 before its first. */
    public long peerStep(int pc) {
        return Engine.peerStep(pc);
    }

    /** This PC's state hash at {@code step}: a multiple of the hash interval among the last 64 hashed. */
    public long hash(long step) {
        return Engine.hash(step);
    }

    /**
     * The step a battle's Engage applies at while every PC holds before it, or -1 (SOSDET SOS-70): this PC's last step
     * run is the one before it.
     */
    public long battleAhead() {
        return Engine.battleAhead();
    }

    /** Every PC, held before an Engage: the battle begins. */
    public void battleGo() {
        Engine.battleGo();
    }

    /** The world desync every PC holds for (SOS-53), or null. */
    public DesyncHold desyncHold() {
        return Engine.desyncHold();
    }

    /**
     * Host, held at a desync's hold step, Resync chosen: the world's sections saved and the engine's world state at
     * this step, as every PC loads them ({@link #worldResyncLoad}). Between steps only.
     */
    public WorldResync worldResyncSave() {
        Path file = tempFile();
        try {
            byte[] state = Engine.worldResyncSave(file);
            return new WorldResync(Engine.step(), state, Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: reading the world resync save " + file, e);
        } finally {
            delete(file);
        }
    }

    /**
     * Every PC at the resync's step: loads the host's world, keeping this PC's city, and runs on (the load holds the
     * engine at the step; spec §11 World desyncs: play continues). Between steps only.
     */
    public void worldResyncLoad(WorldResync r) {
        Path file = tempFile();
        try {
            Files.write(file, r.save());
            Engine.worldResyncLoad(file, r.state());
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: writing the world resync save " + file, e);
        } finally {
            delete(file);
        }
        applyHolds(); // the load held the engine at its step: back to what the mod's holds say
    }

    private static Path tempFile() {
        try {
            return Files.createTempFile("sosgsgmp-resync", ".save");
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: no temporary file for a world resync", e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // A leftover temporary file costs nothing; the resync itself went through.
            LOG.ln("SOSGSGMP: couldn't delete " + file + ": " + e);
        }
    }

    /** The engine's report of its first desync: the parts that differ and the AI's last orders; null if it found none. */
    public String desyncReport() {
        return Engine.desync() == null ? null : Engine.desyncReport();
    }

    /** Host: the seconds the slowest PC held it in so far, and the longest hold (SOS-42). */
    public double heldSeconds() {
        return Engine.heldSeconds();
    }

    public double longestHoldSeconds() {
        return Engine.longestHoldSeconds();
    }

    /** The steps a second this PC could run, or -1 before the first measure (SOS-94). */
    public int capacity() {
        return Engine.capacity();
    }

    /** Another PC's capacity from its last hash, or -1 before the first (SOS-94). */
    public int capacity(int pc) {
        return Engine.capacity(pc);
    }

    /** The top bar's speed line while a PC or the link holds the speed down, or null. */
    public String speedNotice() {
        return Engine.speedNotice();
    }

    // ---------------------------------------------------------------- city views (spec §10, SOS-82)

    /** Host: why a view of {@code owner}'s city by the player in {@code viewer} would be cancelled now; null if it can begin. */
    public String viewRefusal(int viewer, int owner) {
        return Engine.viewRefusal(viewer, owner);
    }

    /** Host, between steps: the view begins on every PC at a step ahead (the owner allowed it). */
    public void viewBegin(int viewer, int owner) {
        Engine.viewBegin(viewer, owner);
    }

    /** Host, between steps: the view of the player in {@code viewer} ends, for {@code why} ({@code View.CLOSED} to {@code View.DESYNC}). */
    public void viewEnd(int viewer, int why) {
        Engine.viewEnd(viewer, why);
    }

    /** The view of the player in slot {@code viewer}, the same on every PC, or null. */
    public View view(int viewer) {
        return Engine.view(viewer);
    }

    /** The owner's PC, at a view's freeze step: its city for the viewer, once; this PC then reloads it. Else null. */
    public byte[] viewOut() {
        return Engine.viewOut();
    }

    /** The viewer's PC, at its view's freeze step: loads the owner's city. */
    public void viewIn(byte[] city) {
        Engine.viewIn(city);
    }

    /** The owner's PC: its view hashes for the viewer in {@code viewer} since the last call. */
    public byte[] viewHashOut(int viewer) {
        return Engine.viewHashOut(viewer);
    }

    /** The viewer's PC: more of the owner's view hashes. */
    public void viewHashIn(byte[] bytes) {
        Engine.viewHashIn(bytes);
    }

    /** The viewer's PC: whether its copy of the city lost step with the owner's. */
    public boolean viewDesync() {
        return Engine.viewDesync() != null;
    }

    /** The viewer's PC: the limit it hit ({@code View.NOT_CAUGHT_UP}, {@code View.FELL_BEHIND}), or {@code View.OPEN}. */
    public int viewLimit() {
        return Engine.viewLimit();
    }

    /** The viewer's PC: seconds from its view load to catching up, or -1 before. */
    public double viewCaughtUpSeconds() {
        return Engine.viewCaughtUpSeconds();
    }

    /** Whether this PC runs another player's city now: it views it, so its own isn't loaded. */
    public boolean viewingHere() {
        return Engine.viewing() != null;
    }

    /** How long this PC's last view load took, in ms. */
    public double lastViewLoadMs() {
        return Engine.lastViewLoadMs();
    }

    /** How many steps this PC is behind the host's confirmed step: a viewer catching up. */
    public long behindHost() {
        return Engine.behindHost();
    }

    /** The first step whose hashes differed between two PCs, or -1 while they all match. */
    public long desyncStep() {
        Desync d = Engine.desync();
        return d == null ? -1 : d.step;
    }
}
