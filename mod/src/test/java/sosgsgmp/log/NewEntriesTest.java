package sosgsgmp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class NewEntriesTest {

    /** A game list's entry whose text can change, as WorldLog reuses its oldest entry's object. */
    private static final class Entry {
        String text;

        Entry(String text) {
            this.text = text;
        }
    }

    private final NewEntries<Entry> news = new NewEntries<>(e -> e.text);

    private int look(List<Entry> list) {
        return news.firstNew(list, list.size(), list::get);
    }

    @Test
    void theFirstLookAndANewListOnlyNoteWhatIsThere() {
        List<Entry> l = new ArrayList<>(List.of(new Entry("a"), new Entry("b")));
        assertEquals(2, look(l));
        l.add(new Entry("c"));
        assertEquals(2, look(l));
        List<Entry> loaded = new ArrayList<>(List.of(new Entry("x")));
        assertEquals(1, look(loaded)); // a load: nothing new
    }

    @Test
    void aFullListDropsItsOldestAndAReusedObjectIsNotTheOneSeen() {
        List<Entry> l = new ArrayList<>(List.of(new Entry("a"), new Entry("b")));
        look(l);
        Entry oldest = l.remove(0); // full: the oldest goes, and its object comes back as the newest
        oldest.text = "c";
        l.add(oldest);
        assertEquals(1, look(l)); // "b" was the last seen
        Entry b = l.remove(0);
        b.text = "d";
        l.add(b);
        Entry c = l.remove(0);
        c.text = "e";
        l.add(c);
        assertEquals(0, look(l)); // the last one seen ("c") was reused: all is new
    }

    @Test
    void anEmptyListThatGrows() {
        List<Entry> l = new ArrayList<>();
        assertEquals(0, look(l));
        l.add(new Entry("a"));
        assertEquals(0, look(l));
        assertEquals(1, look(l));
    }
}
