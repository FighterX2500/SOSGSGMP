package sosgsgmp.log;

import java.util.IdentityHashMap;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The entries a game list gained since the last look (SOS-247), for lists that only grow at the end and may lose any
 * entry: the world's news ({@code WorldLog}, which drops its oldest and reuses that object for the next entry) and the
 * player's messages (dropped from the front when full, and from anywhere by "Delete all read messages"). Every entry
 * of the last look is kept by identity with its text, so a reused object is not taken for the one seen, and an entry
 * removed from the middle doesn't make the rest look new. The new entries are those after the last one seen. A list seen
 * for the first time, or a new list (a load made the game again), is only noted: what it holds is old. The two maps are
 * reused; they hold the last look's entries only. Plain Java; between steps.
 *
 * @param <T> the list's entries
 */
public final class NewEntries<T> {

    private final Function<T, String> text;
    private Object list;
    private IdentityHashMap<T, String> seen = new IdentityHashMap<>();
    private IdentityHashMap<T, String> now = new IdentityHashMap<>();

    /** {@code text}: an entry's text, which tells a reused entry from the one seen. */
    public NewEntries(Function<T, String> text) {
        this.text = text;
    }

    /**
     * The index of the first entry of {@code list} (its {@code size} entries, read through {@code at}) not seen before;
     * {@code size} when none is new. Everything up to the end counts as seen afterwards.
     */
    public int firstNew(Object list, int size, IntFunction<T> at) {
        boolean fresh = list != this.list;
        this.list = list;
        now.clear();
        int first = fresh ? size : 0;
        for (int i = 0; i < size; i++) {
            T e = at.apply(i);
            String t = text.apply(e);
            now.put(e, t);
            if (!fresh && t.equals(seen.get(e)))
                first = i + 1; // seen: what is new comes after it
        }
        IdentityHashMap<T, String> was = seen;
        seen = now;
        now = was;
        return first;
    }
}
