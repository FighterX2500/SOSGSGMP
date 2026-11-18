package sosgsgmp.session;

/**
 * Keeps this PC's game pause on the pause every PC shares (spec §4, SOS-110, SOS-229). Each frame it reads this PC's
 * own pause and the shared one, and says what to send and what to set here. Plain Java; the game side feeds it.
 *
 * <ul>
 * <li>On the world, and in a battle every PC runs, a change of this PC's pause is the player's press: it is sent, and
 * a heard pause is set here.</li>
 * <li>On a PC whose game is a battle against NPCs (its player's, one it watches or joined as an ally), the game's
 * pause is that battle's own (spec §8): nothing is sent or set; a heard pause waits.</li>
 * <li>While any player fights a battle against NPCs the world runs on (spec §4): a pause pressed or heard on the world
 * waits, and stops the game once the last such battle has ended. A press then turns the shared pause over.</li>
 * <li>For {@link #SETTLE_NANOS} after a battle starts or ends, or a load, the engine and the load set the speed: no
 * change is a press. Once settled, the game comes back to the shared pause; only a pause this PC holds alone
 * then counts as its player's press, since neither the engine nor a load ever pauses.</li>
 * <li>A spectator's press is undone (spec §11).</li>
 * </ul>
 */
public final class PauseSync {

    /** What this PC's game is: its game pause is the world's, a battle's every PC runs (loading or running), or a local battle's. */
    public enum Mode {
        WORLD, BATTLE_LOADING, BATTLE, LOCAL_BATTLE
    }

    /** After a battle's start or end or a load, the game speed settles for this long before a change of it is a press. */
    public static final long SETTLE_NANOS = 2_000_000_000L;

    /** {@link #frame} result bits: send a pause, send an unpause, pause this PC's game, unpause it. */
    public static final int SEND_PAUSE = 1, SEND_UNPAUSE = 2, PAUSE_HERE = 4, UNPAUSE_HERE = 8;

    /** This PC's pause as last left: a change this class didn't make is the player's. */
    private boolean seen;
    private Mode mode = Mode.WORLD;
    private int loads;
    private long settledAt;
    private boolean settling = true;
    private boolean npcBattles;
    /** The game comes back to the shared pause at the next settled frame. */
    private boolean due;

    public PauseSync(long now) {
        settledAt = now + SETTLE_NANOS;
    }

    /**
     * Each frame: what this PC's game is ({@code mode}), how many whole-game loads it has had ({@code loads}),
     * whether any player fights a battle against NPCs, whether this PC only watches, its game's pause, the shared one,
     * and whether a pause or unpause was heard since the last frame. Returns the {@code SEND_*} and {@code *_HERE} bits
     * for the caller to carry out. No allocation.
     */
    public int frame(Mode mode, int loads, boolean npcBattles, boolean spectator, boolean pausedHere, boolean shared, boolean heard,
            long now) {
        if (mode != this.mode || loads != this.loads) {
            this.mode = mode;
            this.loads = loads;
            settledAt = now + SETTLE_NANOS;
        }
        if (mode == Mode.BATTLE_LOADING || mode == Mode.LOCAL_BATTLE || now - settledAt < 0) {
            settling = true;
            seen = pausedHere;
            return 0;
        }
        boolean held = npcBattles && mode == Mode.WORLD;
        int out = 0;
        if (settling) { // settled: a pause this PC holds alone is the player's press meanwhile, anything else comes back
            settling = false;
            due = true;
            if (pausedHere && !shared && !held && !spectator) {
                out |= SEND_PAUSE;
                seen = true;
                due = false;
            }
        }
        if (heard || npcBattles != this.npcBattles)
            due = true;
        this.npcBattles = npcBattles;
        if (pausedHere != seen) { // the player's press
            seen = pausedHere;
            if (spectator) {
                due = true;
            } else if (held) { // the world runs on: the press turns the shared pause over, which waits
                out |= shared ? SEND_UNPAUSE : SEND_PAUSE;
                due = true;
            } else {
                out |= pausedHere ? SEND_PAUSE : SEND_UNPAUSE;
            }
        }
        if (due) {
            due = false;
            boolean want = shared && !held;
            if (want != pausedHere)
                out |= want ? PAUSE_HERE : UNPAUSE_HERE;
            seen = want;
        }
        return out;
    }
}
