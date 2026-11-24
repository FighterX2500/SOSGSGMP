package sosgsgmp.log;

/**
 * The playtest log's lines every PC writes alike (SOS-247): after the line's own time stamp, a mark, a key and the text,
 * and nothing about this PC. {@code tools/logdiff.py} lines two PCs' logs up by key and prints the first that differs.
 *
 * <ul>
 * <li>{@code = 123450 text}: a kind a twopc run showed the same on every PC, keyed by the step it happened at.</li>
 * <li>{@code ~ 123450 text}: a kind meant to be the same that no twopc run has confirmed yet; {@code logdiff.py --all}
 * compares these too. A kind moves from {@code ~} to {@code =} once a run shows it equal (batch-15.md).</li>
 * <li>A key {@code d812} instead of a step: the game day, for what the log finds by polling and can't place at a step
 * (the world's news, scripted events).</li>
 * </ul>
 * Plain Java.
 */
public final class SameLine {

    public static final char CONFIRMED = '=';
    public static final char CANDIDATE = '~';

    private SameLine() {
    }

    /** A line keyed by {@code step}. */
    public static String atStep(char mark, long step, String text) {
        return format(mark, Long.toString(step), text);
    }

    /** A line keyed by the game's {@code day}. */
    public static String onDay(char mark, int day, String text) {
        return format(mark, "d" + day, text);
    }

    private static String format(char mark, String key, String text) {
        if (mark != CONFIRMED && mark != CANDIDATE)
            throw new IllegalArgumentException("SOSGSGMP: no line mark '" + mark + "'");
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
            throw new IllegalArgumentException("SOSGSGMP: a line every PC writes alike is one line: " + text);
        return mark + " " + key + " " + text;
    }
}
