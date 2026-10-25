package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class NotesTest {

    @Test
    void notesComeOutOldestFirstAndOnlyTheLatestAreKept() {
        Notes notes = new Notes();
        for (int i = 0; i < Notes.MAX + 5; i++)
            notes.add("note " + i);
        assertEquals("note 5", notes.take(), "the oldest five went");
        for (int i = 6; i < Notes.MAX + 5; i++)
            assertEquals("note " + i, notes.take());
        assertNull(notes.take());
    }
}
