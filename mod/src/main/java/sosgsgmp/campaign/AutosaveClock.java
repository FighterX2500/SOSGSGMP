package sosgsgmp.campaign;

/**
 * The host's autosave interval (spec §11, SOS-229): vanilla's Auto-Saving interval is real minutes, and here only the
 * minutes in which the world ran count. A frame counts once the world's step has moved since the last one, and the
 * session isn't paused by the pause every PC shares; the host's own game speed says neither, since a world held by a
 * paused or slow PC stands still while the host's game runs. A step after a long stand-still counts at most
 * {@link #MAX_CREDIT_MS}. Plain Java; the caller feeds it every frame between steps.
 */
public final class AutosaveClock {

    /** The most real time one move of the step counts for: a world that stood still longer counts no more. */
    static final long MAX_CREDIT_MS = 1_000;

    private long millis;
    private long step = Long.MIN_VALUE;
    /** The real time of the step's last move while the world ran, or -1 while it stands still counted as stopped. */
    private long movedMs = -1;

    /**
     * Each frame: the world's {@code step} now, whether the count stands still anyway ({@code stopped}: the session's
     * pause, or autosaves off), and the real time.
     */
    public void frame(long step, boolean stopped, long nowMs) {
        if (step != this.step) {
            if (!stopped && movedMs >= 0)
                millis += Math.min(nowMs - movedMs, MAX_CREDIT_MS);
            this.step = step;
            movedMs = nowMs;
        }
        if (stopped)
            movedMs = -1;
    }

    /** Whether {@code dueMs} of running real time have counted since the count last started. */
    public boolean elapsed(long dueMs) {
        return millis >= dueMs;
    }

    /**
     * Whether the world moved since the last campaign save, made at {@code savedStep} (-1 before the first): an autosave
     * of a world still at that step is skipped, so one state never fills every autosave slot (owner, 2026-11-18).
     */
    public boolean movedSince(long savedStep) {
        return step != savedStep;
    }

    /** A campaign save is ordered, or an autosave skipped: the count starts again. */
    public void restart() {
        millis = 0;
    }
}
