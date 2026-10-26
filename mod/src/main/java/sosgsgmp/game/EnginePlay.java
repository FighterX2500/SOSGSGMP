package sosgsgmp.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import game.GAME;
import snake2d.LOG;
import sosdet.DesyncHold;
import sosdet.Engine;
import sosdet.OwnBattle;
import sosdet.Stats;
import sosdet.game.deal.DealBook;
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

    /** The host runs at most this many steps past the slowest PC (1 s at 60 steps a second; SOSDET rig's default). */
    private static final long PACE_LEAD = 60;
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

    /**
     * Why this PC's player can't propose {@code terms} to the player in {@code slot} now, as a sentence, or null (spec §6,
     * SOS-72). Only this PC's own money and goods are checked here; the other side's are checked when the deal applies.
     */
    public String dealRefusal(int slot, DealBook.Terms terms) {
        return Engine.dealRefusal(slot, terms);
    }

    /** This PC's player proposes {@code terms} to the player in {@code slot}. Check {@link #dealRefusal} first. */
    public void dealPropose(int slot, DealBook.Terms terms) {
        Engine.dealPropose(slot, terms);
    }

    /** Why this PC's player can't accept the proposal {@code id} now, or null. Declining always works. */
    public String dealAcceptRefusal(int id) {
        return Engine.dealAcceptRefusal(id);
    }

    /** This PC's player answers the proposal {@code id} made to them. */
    public void dealAnswer(int id, boolean accept) {
        Engine.dealAnswer(id, accept);
    }

    /** Every open proposal between players, oldest first: a live view. */
    public List<DealBook.Proposal> deals() {
        return Engine.deals();
    }

    /** The latest answers to proposals between players, oldest first: a live view. */
    public List<DealBook.Answer> dealAnswers() {
        return Engine.dealAnswers();
    }

    /** Whether the engine's session runs: it ends at Save and exit. */
    public boolean active() {
        return Engine.active();
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

    /** The first step whose hashes differed between two PCs, or -1 while they all match. */
    public long desyncStep() {
        Desync d = Engine.desync();
        return d == null ? -1 : d.step;
    }
}
