package sosgsgmp.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import game.GAME;
import snake2d.LOG;
import sosdet.DesyncHold;
import sosdet.Engine;
import sosdet.OwnBattle;
import sosdet.Stats;
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

    /** Battle saves from the BATTLE stream and this PC's own, until its engine loads them (plan 0006). */
    private final BattleSaves battles = new BattleSaves();

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
        if (save != null)
            Engine.battleIn(save);
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
        Engine.runFreely();
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

    /** The first step whose hashes differed between two PCs, or -1 while they all match. */
    public long desyncStep() {
        Desync d = Engine.desync();
        return d == null ? -1 : d.step;
    }
}
