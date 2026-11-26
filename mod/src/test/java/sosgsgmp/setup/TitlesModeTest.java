package sosgsgmp.setup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Spec §5 Titles: who picks titles, and which every human's start holds. */
class TitlesModeTest {

    private static FactionPick pick(String name, int[] titles, int[] races) {
        return new FactionPick("HUMAN", name, "Ruler", new byte[0], 4, 5, titles, races);
    }

    private final FactionPick host = pick("Host", new int[] {1, 2}, new int[] {3, 4});
    private final FactionPick guest = pick("Guest", new int[] {7}, new int[] {9});

    @Test
    void noTitlesStripsEveryHumansTitles() {
        List<FactionPick> out = TitlesMode.NONE.apply(List.of(host, guest));
        assertEquals(0, out.get(0).titles().length);
        assertEquals(0, out.get(1).titles().length);
        assertEquals("Guest", out.get(1).name(), "only the titles change");
    }

    @Test
    void hostTitlesGivesEveryHumanTheHostsTitlesAsStrong() {
        List<FactionPick> out = TitlesMode.HOST.apply(List.of(host, guest));
        assertEquals(host, out.get(0));
        assertArrayEquals(new int[] {1, 2}, out.get(1).titles());
        assertArrayEquals(new int[] {3, 4}, out.get(1).titleRaces());
        assertEquals(guest.at(4, 5).name(), out.get(1).name());
    }

    @Test
    void uniqueKeepsEachHumansOwn() {
        assertEquals(List.of(host, guest), TitlesMode.UNIQUE.apply(List.of(host, guest)));
    }

    @Test
    void whoPicks() {
        assertFalse(TitlesMode.NONE.picks(true));
        assertTrue(TitlesMode.HOST.picks(true));
        assertFalse(TitlesMode.HOST.picks(false));
        assertTrue(TitlesMode.UNIQUE.picks(false));
    }

    @Test
    void namesReadBack() {
        for (TitlesMode m : TitlesMode.values())
            assertEquals(m, TitlesMode.byName(m.name()));
        assertNull(TitlesMode.byName("ALL"));
    }
}
