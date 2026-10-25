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
 */
public record FactionPick(String race, String name, byte[] banner, int x, int y) {

    /** No location picked yet. */
    public static final int NONE = -1;
    /** Longest race key accepted. */
    public static final int MAX_RACE = 64;
    /** Longest faction name: the game keeps 24 characters. */
    public static final int MAX_NAME = 24;
    /** Largest banner accepted, in bytes: the game's is a 12x12 bitmap and four colors; 16 of them fit one message. */
    public static final int MAX_BANNER = 1024;

    public FactionPick {
        if (race == null || race.isEmpty() || race.length() > MAX_RACE)
            throw new IllegalArgumentException("SOSGSGMP: race key '" + race + "'");
        if (name == null || name.isBlank() || name.length() > MAX_NAME)
            throw new IllegalArgumentException("SOSGSGMP: faction name '" + name + "'");
        if (banner.length > MAX_BANNER)
            throw new IllegalArgumentException("SOSGSGMP: banner of " + banner.length + " bytes");
        if ((x == NONE) != (y == NONE) || x < NONE || y < NONE)
            throw new IllegalArgumentException("SOSGSGMP: location " + x + "," + y);
        banner = banner.clone();
    }

    @Override
    public byte[] banner() {
        return banner.clone();
    }

    public boolean hasLocation() {
        return x != NONE;
    }

    /** The same picks with another location ({@link #NONE}, {@link #NONE}: none). */
    public FactionPick at(int x, int y) {
        return new FactionPick(race, name, banner, x, y);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FactionPick p && x == p.x && y == p.y && race.equals(p.race) && name.equals(p.name)
                && Arrays.equals(banner, p.banner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(race, name, Arrays.hashCode(banner), x, y);
    }

    @Override
    public String toString() {
        return "FactionPick[" + race + ", " + name + ", banner " + banner.length + " bytes, at " + x + "," + y + "]";
    }
}
