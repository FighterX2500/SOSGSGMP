package sosgsgmp.setup;

import java.util.ArrayList;
import java.util.List;

/**
 * Which titles the players' factions hold (spec §5 Titles, owner 2026-11-26), a lobby setting the campaign keeps for
 * hot-joins. Each title is as strong as the profile of the player who picked it makes it.
 */
public enum TitlesMode {
    /** Nobody holds titles: the setup before this setting. */
    NONE("No titles", "No faction holds titles."),
    /** The host picks titles from their profile, and every player's faction holds the same ones, as strong. */
    HOST("Host-Titles", "The host picks titles from their profile; every player's faction holds the same ones."),
    /** Each player picks titles from their own profile. */
    UNIQUE("Unique", "Each player picks titles from their own profile's unlocked ones.");

    /** Vanilla's limit: a faction holds at most this many titles. */
    public static final int MAX_TITLES = 5;

    public final String label;
    public final String desc;

    TitlesMode(String label, String desc) {
        this.label = label;
        this.desc = desc;
    }

    /** Whether a player picks titles in the setup: everyone in Unique, the host alone in Host-Titles. */
    public boolean picks(boolean host) {
        return this == UNIQUE || this == HOST && host;
    }

    /** {@code pick} with the titles this mode gives it, {@code host} being the host's own pick. */
    public FactionPick apply(FactionPick pick, FactionPick host) {
        return switch (this) {
            case NONE -> pick.withTitles(new int[0], new int[0]);
            case HOST -> pick.withTitles(host.titles(), host.titleRaces());
            case UNIQUE -> pick;
        };
    }

    /** Every human's pick with the titles this mode gives it; the host's is the first, slot 0's. */
    public List<FactionPick> apply(List<FactionPick> humans) {
        List<FactionPick> out = new ArrayList<>(humans.size());
        for (FactionPick p : humans)
            out.add(apply(p, humans.get(0)));
        return out;
    }

    /** The mode a campaign record names; null for a name this mod doesn't know. */
    public static TitlesMode byName(String name) {
        for (TitlesMode m : values())
            if (m.name().equals(name))
                return m;
        return null;
    }
}
