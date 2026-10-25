package sosgsgmp.session;

import java.util.ArrayDeque;

/**
 * Network happenings for the playtest log (SOS-63), oldest first, each taken once. Past {@link #MAX} the oldest go: a
 * session nobody logs keeps only the latest.
 */
final class Notes {

    static final int MAX = 100;

    private final ArrayDeque<String> notes = new ArrayDeque<>();

    void add(String note) {
        if (notes.size() == MAX)
            notes.removeFirst();
        notes.addLast(note);
    }

    /** The oldest note not taken yet, or null. */
    String take() {
        return notes.pollFirst();
    }
}
