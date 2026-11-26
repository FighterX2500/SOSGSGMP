package sosgsgmp.setup;

import java.util.Arrays;
import java.util.Objects;

/**
 * One player's picks in the setup (spec §5 Factions and starting locations; setup-factions-step.md §3): race, faction
 * name, banner and world location, as the player's PC sends them whenever they change. Immutable.
 *
 * @param race the race's key, as the game names it
 * @param name the faction's name
 * @param banner the banner in the game's own save layout, as the engine gives it; carried, never read
 * @param x the location's world tile, or {@link #NONE} while none is picked
 * @param y the location's world tile, or {@link #NONE} while none is picked
 * @param titles the titles the faction holds, as the game's title indices (spec §5 Titles), at most
 *        {@link TitlesMode#MAX_TITLES}, each once; empty for none
 * @param titleRaces for each of {@code titles}, the races the picking player's profile earned it with
 */
public record FactionPick(String race, String name, String ruler, byte[] banner, int x, int y, int[] titles,
        int[] titleRaces) {

    /** No location picked yet. */
    public static final int NONE = -1;
    /** Longest race key accepted. */
    public static final int MAX_RACE = 64;
    /** Longest faction name: the game keeps 24 characters. */
    public static final int MAX_NAME = 24;
    /** Largest banner accepted, in bytes: the game's is a 12x12 bitmap and four colors; 16 of them fit one message. */
    public static final int MAX_BANNER = 1024;
    /** Highest title index and race count accepted: the game has 35 titles and fewer races. */
    public static final int MAX_TITLE_VALUE = 255;

    public FactionPick {
        if (race == null || race.isEmpty() || race.length() > MAX_RACE)
            throw new IllegalArgumentException("SOSGSGMP: race key '" + race + "'");
        if (name == null || name.isBlank() || name.length() > MAX_NAME)
            throw new IllegalArgumentException("SOSGSGMP: faction name '" + name + "'");
        if (ruler == null || ruler.length() > MAX_NAME)
            throw new IllegalArgumentException("SOSGSGMP: ruler name '" + ruler + "'");
        if (banner.length > MAX_BANNER)
            throw new IllegalArgumentException("SOSGSGMP: banner of " + banner.length + " bytes");
        if ((x == NONE) != (y == NONE) || x < NONE || y < NONE)
            throw new IllegalArgumentException("SOSGSGMP: location " + x + "," + y);
        if (titles.length > TitlesMode.MAX_TITLES || titles.length != titleRaces.length)
            throw new IllegalArgumentException("SOSGSGMP: " + titles.length + " titles and " + titleRaces.length + " race counts");
        for (int i = 0; i < titles.length; i++) {
            if (titles[i] < 0 || titles[i] > MAX_TITLE_VALUE || titleRaces[i] < 0 || titleRaces[i] > MAX_TITLE_VALUE)
                throw new IllegalArgumentException("SOSGSGMP: title " + titles[i] + " of " + titleRaces[i] + " races");
            for (int j = 0; j < i; j++)
                if (titles[j] == titles[i])
                    throw new IllegalArgumentException("SOSGSGMP: title " + titles[i] + " twice");
        }
        banner = banner.clone();
        titles = titles.clone();
        titleRaces = titleRaces.clone();
    }

    /** Picks holding no titles. */
    public FactionPick(String race, String name, String ruler, byte[] banner, int x, int y) {
        this(race, name, ruler, banner, x, y, new int[0], new int[0]);
    }

    @Override
    public byte[] banner() {
        return banner.clone();
    }

    @Override
    public int[] titles() {
        return titles.clone();
    }

    @Override
    public int[] titleRaces() {
        return titleRaces.clone();
    }

    /** The same picks holding {@code titles}, each earned with {@code titleRaces[i]} races. */
    public FactionPick withTitles(int[] titles, int[] titleRaces) {
        return new FactionPick(race, name, ruler, banner, x, y, titles, titleRaces);
    }

    public boolean hasLocation() {
        return x != NONE;
    }

    /** The same picks with another location ({@link #NONE}, {@link #NONE}: none). */
    public FactionPick at(int x, int y) {
        return new FactionPick(race, name, ruler, banner, x, y, titles, titleRaces);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FactionPick p && x == p.x && y == p.y && race.equals(p.race) && name.equals(p.name) && ruler.equals(p.ruler)
                && Arrays.equals(banner, p.banner) && Arrays.equals(titles, p.titles) && Arrays.equals(titleRaces, p.titleRaces);
    }

    @Override
    public int hashCode() {
        return Objects.hash(race, name, ruler, Arrays.hashCode(banner), x, y, Arrays.hashCode(titles), Arrays.hashCode(titleRaces));
    }

    @Override
    public String toString() {
        return "FactionPick[" + race + ", " + name + ", ruler " + ruler + ", banner " + banner.length + " bytes, at " + x + "," + y
                + ", titles " + Arrays.toString(titles) + " of races " + Arrays.toString(titleRaces) + "]";
    }
}
