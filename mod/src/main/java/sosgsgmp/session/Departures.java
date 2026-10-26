package sosgsgmp.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Players whose PC left the running game, and the hold for them (spec §11 Disconnects, SOS-65). When a player other
 * than the host drops, every PC holds at one step and waits for the host: Continue without them, or Return to the last
 * save. Plain Java; the sessions feed it, the game side holds the engine and shows the window.
 *
 * <p>A second drop while a hold is open joins that hold. After Continue the hold is over, and a later drop opens a new
 * one.
 */
public final class Departures {

    /**
     * How far past the host's step the hold is: a peer runs at most this many steps past the host's (SOSDET's AI log
     * delay), so no PC has passed the hold step yet.
     */
    public static final long LEAD = 30;

    /** A player gone: their id, name and slot (their PC number). */
    public record Gone(int playerId, String name, int slot) {}

    public enum Choice {
        CONTINUE, RETURN
    }

    private final List<Gone> gone = new ArrayList<>();
    private long holdStep = -1;
    private Choice choice;
    private final List<Gone> removed = new ArrayList<>();

    /** Host: player {@code g}'s PC left while the host is at step {@code step}; returns the hold step for every PC. */
    long left(Gone g, long step) {
        if (choice == Choice.RETURN)
            throw new IllegalStateException("SOSGSGMP: a player left after Return to the last save");
        if (holdStep < 0)
            holdStep = step + LEAD;
        add(g);
        return holdStep;
    }

    /** Joining PC: the host said player {@code g} is gone, holding at {@code hold}. Returns whether it was news. */
    boolean heard(Gone g, long hold) {
        if (holdStep >= 0 && holdStep != hold)
            throw new IllegalStateException("SOSGSGMP: player " + g + " gone at hold " + hold + " while the hold at " + holdStep
                    + " is open");
        holdStep = hold;
        return add(g);
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
