package sosgsgmp.session;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One vote to restart a battle (spec §8, SOS-231): every player in the session votes, those outside the battle and
 * spectators too, and more than half of them must answer Yes. A tie fails, and no answer in {@link #ANSWER_MS} counts as
 * No. The player who called it votes Yes. Plain Java; the host keeps it.
 */
public final class RestartTally {

    /** How long the players have to answer, in milliseconds of real time (owner, 2026-11-18). */
    public static final long ANSWER_MS = 30_000;

    public enum Outcome {
        OPEN, PASSED, FAILED
    }

    private final int vote;
    private final int caller;
    private final long battle;
    private final long openedMs;
    /** Each voter's answer: null while they haven't answered. */
    private final Map<Integer, Boolean> answers = new LinkedHashMap<>();

    /** Vote {@code vote}, called by {@code caller} at {@code nowMs} for {@code battle}; {@code voters} are every player now. */
    public RestartTally(int vote, int caller, long battle, List<Integer> voters, long nowMs) {
        if (!voters.contains(caller))
            throw new IllegalArgumentException("SOSGSGMP: player " + caller + " called a restart vote but isn't among the voters " + voters);
        this.vote = vote;
        this.caller = caller;
        this.battle = battle;
        this.openedMs = nowMs;
        for (int v : voters)
            answers.put(v, null);
        answers.put(caller, true);
    }

    public int vote() {
        return vote;
    }

    public int caller() {
        return caller;
    }

    /** The battle, as SOSDET's {@code BattleKey} long. */
    public long battle() {
        return battle;
    }

    /** Player {@code id} answers; false when they don't vote here or answered already: the first answer stands. */
    public boolean answer(int id, boolean yes) {
        if (!answers.containsKey(id) || answers.get(id) != null)
            return false;
        answers.put(id, yes);
        return true;
    }

    /** Only the players in {@code present} vote from now: one who left the session no longer counts either way. */
    public void keepOnly(List<Integer> present) {
        answers.keySet().retainAll(present);
    }

    /** True when player {@code id} votes here and hasn't answered yet. */
    public boolean waitsFor(int id) {
        return answers.containsKey(id) && answers.get(id) == null;
    }

    public int voters() {
        return answers.size();
    }

    public int yes() {
        return count(Boolean.TRUE);
    }

    /** The No answers given; after the time is up, the unanswered count as No too ({@link #outcome}). */
    public int no() {
        return count(Boolean.FALSE);
    }

    private int count(Boolean value) {
        int n = 0;
        for (Boolean a : answers.values())
            if (value.equals(a))
                n++;
        return n;
    }

    /**
     * Passed as soon as more than half of the voters said Yes; failed as soon as that can't happen any more, or when the
     * time is up without it: a tie fails, and no answer counts as No.
     */
    public Outcome outcome(long nowMs) {
        int n = voters();
        int yes = yes();
        if (yes * 2 > n)
            return Outcome.PASSED;
        int open = n - yes - no();
        if ((yes + open) * 2 <= n || nowMs - openedMs >= ANSWER_MS)
            return Outcome.FAILED;
        return Outcome.OPEN;
    }

    /** The milliseconds left to answer, never below 0. */
    public long leftMs(long nowMs) {
        return Math.max(0, ANSWER_MS - (nowMs - openedMs));
    }
}
