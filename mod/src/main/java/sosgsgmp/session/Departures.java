package sosgsgmp.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Players whose PC left the running game, and the hold for them (spec §11 Disconnects, SOS-65). When a player other
 * than the host drops, every PC holds at one step and waits for the host: Continue without them, or Return to the last
 * save. Plain Java; the sessions feed it, the game side holds the engine and shows the window.
 *
 * <p>A second drop while a hold is open joins that hold. After Continue the hold is over, and a later drop opens a new
 * one.
 *
 * <p>A player can also be lost (SOS-177): their PC went silent while its connection stayed open. They hold every PC the
 * same way, but may come back: once every player of the hold is back before the host chose, the hold ends by itself.
 * A lost player whose connection then closes has left.
 */
public final class Departures {

    /**
     * How far past the host's step the hold is: a peer runs at most this many steps past the host's (SOSDET's AI log
     * delay), so no PC has passed the hold step yet.
     */
    public static final long LEAD = 30;

    /** A player's PC silent this long, its connection open, is lost (SOS-177); the network beats every second. */
    public static final long LOST_MS = 5_000;

    /** A lost player heard within this is back. */
    public static final long BACK_MS = 2_000;

    /** A player gone: their id, name and slot (their PC number). */
    public record Gone(int playerId, String name, int slot) {}

    public enum Choice {
        CONTINUE, RETURN
    }

    private final List<Gone> gone = new ArrayList<>();
    private long holdStep = -1;
    private Choice choice;
    private final List<Gone> removed = new ArrayList<>();
    /** The players of the open hold who are lost, not gone: their id and since when, in this PC's ms. */
    private final Map<Integer, Long> lostSince = new HashMap<>();

    /** Host: player {@code g}'s PC left while the host is at step {@code step}; returns the hold step for every PC. */
    long left(Gone g, long step) {
        if (choice == Choice.RETURN)
            throw new IllegalStateException("SOSGSGMP: a player left after Return to the last save");
        if (holdStep < 0)
            holdStep = step + LEAD;
        add(g);
        lostSince.remove(g.playerId());
        return holdStep;
    }

    /**
     * Host: player {@code g}'s PC went silent, its connection open, since {@code sinceMs}, while the host is at step
     * {@code step}; returns the hold step for every PC. A player already in the hold stays as they are.
     */
    long lost(Gone g, long step, long sinceMs) {
        if (choice == Choice.RETURN)
            throw new IllegalStateException("SOSGSGMP: a player was lost after Return to the last save");
        if (holdStep < 0)
            holdStep = step + LEAD;
        if (add(g))
            lostSince.put(g.playerId(), sinceMs);
        return holdStep;
    }

    /** Joining PC: the host said player {@code g} is lost, since {@code sinceMs} here, holding at {@code hold}. */
    boolean heardLost(Gone g, long hold, long sinceMs) {
        joinHold(g, hold);
        if (!add(g))
            return false;
        lostSince.put(g.playerId(), sinceMs);
        return true;
    }

    /**
     * Lost player {@code playerId} is heard again before the host chose: they leave the hold, and the hold ends once
     * nobody is left in it. Returns whether they were lost; a player who left, or a choice made, changes nothing.
     */
    boolean back(int playerId) {
        if (choice != null || lostSince.remove(playerId) == null)
            return false;
        gone.removeIf(g -> g.playerId() == playerId);
        if (gone.isEmpty())
            holdStep = -1;
        return true;
    }

    /** Joining PC: the host said lost player {@code playerId} is back, for the hold at {@code hold}. */
    boolean heardBack(int playerId, long hold) {
        return hold == holdStep && back(playerId);
    }

    /** Whether player {@code playerId} is lost in the open hold (not gone). */
    public boolean lost(int playerId) {
        return lostSince.containsKey(playerId);
    }

    /** Since when player {@code playerId} is lost, in this PC's ms; -1 if they aren't. */
    public long lostSince(int playerId) {
        Long since = lostSince.get(playerId);
        return since == null ? -1 : since;
    }

    /**
     * Joining PC: the host said player {@code g} is gone, holding at {@code hold}. Returns whether it was news: a lost
     * player who left is news too.
     */
    boolean heard(Gone g, long hold) {
        joinHold(g, hold);
        boolean added = add(g);
        return lostSince.remove(g.playerId()) != null || added;
    }

    private void joinHold(Gone g, long hold) {
        if (holdStep >= 0 && holdStep != hold)
            throw new IllegalStateException("SOSGSGMP: player " + g + " gone at hold " + hold + " while the hold at " + holdStep
                    + " is open");
        holdStep = hold;
    }

    private boolean add(Gone g) {
        for (Gone o : gone)
            if (o.playerId() == g.playerId())
                return false;
        gone.add(g);
        return true;
    }

    /** The host chose for the open hold: this PC sets it, a joining PC hears it. Returns whether it was news. */
    boolean chosen(long hold, Choice c) {
        if (holdStep < 0 || hold != holdStep || choice != null)
            return false;
        choice = c;
        return true;
    }

    /**
     * After Continue: the game side ran on and the players gone are removed. The hold is over; a later drop opens a new
     * one.
     */
    public void continued() {
        if (choice != Choice.CONTINUE)
            throw new IllegalStateException("SOSGSGMP: continued without the host's Continue (" + choice + ")");
        removed.addAll(gone);
        gone.clear();
        lostSince.clear();
        holdStep = -1;
        choice = null;
    }

    /** Whether a hold is open and the host hasn't chosen yet. */
    public boolean waiting() {
        return holdStep >= 0 && choice == null;
    }

    /** The open hold's step, or -1. */
    public long holdStep() {
        return holdStep;
    }

    /** The host's choice for the open hold, or null. */
    public Choice choice() {
        return choice;
    }

    /** The players of the open hold, in the order they left. */
    public List<Gone> gone() {
        return Collections.unmodifiableList(gone);
    }

    /** Every player removed by Continue in this session. */
    public List<Gone> removed() {
        return Collections.unmodifiableList(removed);
    }

    /** The players of the open hold by name, for the window: "Ann", "Ann and Bob", "Ann, Bob and Cid". */
    public String names() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < gone.size(); i++)
            b.append(i == 0 ? "" : i == gone.size() - 1 ? " and " : ", ").append(gone.get(i).name());
        return b.toString();
    }
}
