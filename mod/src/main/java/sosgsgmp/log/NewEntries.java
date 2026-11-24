package sosgsgmp.log;

import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The entries a game list gained since the last look (SOS-247), for lists that only grow at the end and drop their
 * oldest when full: the world's news ({@code WorldLog}, which reuses its oldest entry's object for the next) and the
 * player's messages. The last entry seen is kept by identity and by its text, so a reused object is not taken for the
 * one seen. A list seen for the first time, or a new list (a load made the game again), is only noted: what it holds
 * is old. Plain Java; between steps.
 *
 * @param <T> the list's entries
 */
public final class NewEntries<T> {

    private final Function<T, String> text;
    private Object list;
    private T last;
    private String lastText;

    /** {@code text}: an entry's text, which tells a reused entry from the one seen. */
    public NewEntries(Function<T, String> text) {
        this.text = text;
    }

    /**
     * The index of the first entry of {@code list} (its {@code size} entries, read through {@code at}) not seen before;
     * {@code size} when none is new. Everything up to the end counts as seen afterwards.
     */
    public int firstNew(Object list, int size, IntFunction<T> at) {
        int first = list != this.list ? size : find(size, at);
        this.list = list;
        last = size == 0 ? null : at.apply(size - 1);
        lastText = last == null ? null : text.apply(last);
        return first;
    }

    private int find(int size, IntFunction<T> at) {
        if (last == null)
            return 0;
        for (int i = size - 1; i >= 0; i--) {
            T e = at.apply(i);
            if (e == last && text.apply(e).equals(lastText))
                return i + 1;
        }
        return 0; // the one seen dropped out: all of it is new
    }
}
