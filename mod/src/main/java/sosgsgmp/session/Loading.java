package sosgsgmp.session;

import java.util.ArrayDeque;

/**
 * The loading window on this PC (SOS-158): open while the game does something that freezes the PCs for seconds, so a
 * player sees why nothing moves. The host opens and closes each one on every PC; during play each PC also opens one
 * itself at the step the lockstep announces, so it is up before the freeze. Only shown: it changes nothing in the
 * simulation and carries no tick. Plain Java; the sessions feed it, the game side draws it and the log reads it.
 */
public final class Loading {

    /** What the window waits for. Sent as its ordinal, so only append. */
    public enum Kind {
        WORLD_GENERATION, WORLD_INITIALIZATION, BATTLE_START, VIEW_START, VIEW_END
    }

    /** A window closes by itself after this long, whatever the host does: no window may hold the screen for good. */
    public static final long CEILING_MS = 120_000;
    /** Closed windows remembered, so a PC that still sees the freeze ahead doesn't open it again. */
    private static final int REMEMBERED = 64;

    /** The window open now: what it waits for, its key (a step, or the host's number), the text shown, since when. */
    public record Window(Kind kind, long key, String text, long openedMs) {}

    /**
     * A window that closed, for the playtest log: how long it was open, and this PC's longest frame meanwhile, which is
     * this PC's own freeze in it.
     */
    public record Closed(Kind kind, long key, String text, long openMs, long longestFrameMs, String how) {}

    private Window open;
    private long lastFrameMs = -1;
    private long longestFrameMs;
    /** The closed windows' ids, a ring: {@link #open} runs every frame while a freeze is ahead, so it allocates nothing. */
    private final long[] done = new long[REMEMBERED];
    private int doneCount;
    private final ArrayDeque<Window> opened = new ArrayDeque<>();
    private final ArrayDeque<Closed> closed = new ArrayDeque<>();

    /**
     * Opens the window for {@code kind} and {@code key}, unless it is open or was closed already. Another window open
     * now is closed first: the newer freeze is the one ahead.
     */
    public void open(Kind kind, long key, String text, long nowMs) {
        if (isOpen(kind, key) || wasClosed(kind, key))
            return;
        if (open != null)
            close(open.kind(), open.key(), nowMs, "replaced");
        open = new Window(kind, key, text, nowMs);
        lastFrameMs = nowMs;
        longestFrameMs = 0;
        opened.add(open);
    }

    /** Closes the window for {@code kind} and {@code key}, and remembers it so it never opens again. */
    public void close(Kind kind, long key, long nowMs, String how) {
        remember(id(kind, key));
        if (!isOpen(kind, key))
            return;
        closed.add(new Closed(kind, key, open.text(), nowMs - open.openedMs(), longestFrameMs, how));
        open = null;
    }

    /** Once per frame: notes this PC's longest frame while a window is open, and closes one open too long. */
    public void frame(long nowMs) {
        if (open == null)
            return;
        if (lastFrameMs >= 0)
            longestFrameMs = Math.max(longestFrameMs, nowMs - lastFrameMs);
        lastFrameMs = nowMs;
        if (nowMs - open.openedMs() >= CEILING_MS)
            close(open.kind(), open.key(), nowMs, "after " + CEILING_MS / 1000 + " s, by this PC");
    }

    /** The window to show now, or null. */
    public Window shown() {
        return open;
    }

    public boolean isOpen(Kind kind, long key) {
        return open != null && open.kind() == kind && open.key() == key;
    }

    /** The next window opened since the last call, for the playtest log; null when none. */
    public Window takeOpened() {
        return opened.poll();
    }

    /** The next window closed since the last call, for the playtest log; null when none. */
    public Closed takeClosed() {
        return closed.poll();
    }

    /** True when this window was closed already, so it stays closed. */
    public boolean wasClosed(Kind kind, long key) {
        long id = id(kind, key);
        for (int i = 0; i < Math.min(doneCount, REMEMBERED); i++)
            if (done[i] == id)
                return true;
        return false;
    }

    private void remember(long id) {
        if (doneCount > 0 && done[(doneCount - 1) % REMEMBERED] == id)
            return;
        done[doneCount % REMEMBERED] = id;
        doneCount++;
    }

    /** A window's id: its key with its kind in the low bits; keys are steps or the host's numbers, never negative. */
    private static long id(Kind kind, long key) {
        if (key < 0)
            throw new IllegalArgumentException("SOSGSGMP: a loading window's key " + key + " for " + kind);
        return key * 8 + kind.ordinal();
    }
}
