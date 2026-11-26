package sosgsgmp.campaign;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import sosgsgmp.setup.TitlesMode;

/**
 * A campaign as every PC in it knows it (spec §11, SOS-64): the host sends it with every save order, and it goes into
 * every PC's records. The id is the first game's start id, so it is the same on every PC from the start. {@code titles}:
 * the lobby's titles setting (spec §5 Titles), which a hot-join's newcomer follows.
 */
public record Campaign(long id, String name, int seed, String difficulty, List<Player> players, TitlesMode titles) {

    /** Longest campaign name, in characters. */
    public static final int MAX_NAME = 48;

    /**
     * A player of the campaign: the key its PC sends in its hello, its name, its human's slot, whether the host removed
     * them (spec §11 Removing a player, SOS-65): a removed player stays listed, with their slot, and never plays in that
     * slot again (they may join the running game again as a new player: a new entry of the same key,
     * {@link Campaign#adding}), and whether that was by elimination (SOS-111): only they may come back, to watch, and no
     * resume waits for them. A frozen player
     * (spec §11 Freezing a player, SOS-194) left with their city kept: no resume waits for them, and they may come back.
     */
    public record Player(long key, String name, int slot, boolean removed, boolean eliminated, boolean frozen) {
        public Player {
            if (name == null || name.isEmpty())
                throw new IllegalArgumentException("SOSGSGMP: a campaign player without a name (slot " + slot + ")");
            if (slot < 0)
                throw new IllegalArgumentException("SOSGSGMP: campaign player " + name + " in slot " + slot);
            if (frozen && removed)
                throw new IllegalArgumentException("SOSGSGMP: campaign player " + name + " both frozen and removed");
        }

        /** A player still in the campaign. */
        public Player(long key, String name, int slot) {
            this(key, name, slot, false, false, false);
        }

        /** A player removed or not, not by elimination. */
        public Player(long key, String name, int slot, boolean removed) {
            this(key, name, slot, removed, false, false);
        }

        /** A player removed or not, eliminated or not, not frozen. */
        public Player(long key, String name, int slot, boolean removed, boolean eliminated) {
            this(key, name, slot, removed, eliminated, false);
        }

        /** Whether this player can't join a resume. */
        public boolean refused() {
            return removed && !eliminated;
        }
    }

    public Campaign {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME)
            throw new IllegalArgumentException("SOSGSGMP: campaign name '" + name + "'");
        if (difficulty == null || difficulty.isEmpty())
            throw new IllegalArgumentException("SOSGSGMP: campaign " + name + " has no difficulty");
        players = List.copyOf(players);
        if (titles == null)
            throw new IllegalArgumentException("SOSGSGMP: campaign " + name + " without a titles setting");
        if (players.size() < 2)
            throw new IllegalArgumentException("SOSGSGMP: campaign " + name + " with " + players.size() + " players");
        // every human slot is listed once, removed ones too; one key may come back as a new player after removals, so
        // it has at most one entry that isn't refused
        for (int i = 0; i < players.size(); i++)
            for (int j = i + 1; j < players.size(); j++) {
                Player a = players.get(i);
                Player b = players.get(j);
                if (a.slot() == b.slot() || a.key() == b.key() && !a.refused() && !b.refused())
                    throw new IllegalArgumentException("SOSGSGMP: campaign " + name + " has two players with one key or slot: "
                            + a + ", " + b);
            }
    }

    /** A campaign with no titles: one from before the setting (spec §5 Titles). */
    public Campaign(long id, String name, int seed, String difficulty, List<Player> players) {
        this(id, name, seed, difficulty, players, TitlesMode.NONE);
    }

    /**
     * The player whose PC sends {@code key}, or null: the entry of that key that isn't refused, if any; otherwise the
     * last one, removed. Several entries share a key when a removed player joined again as a new player.
     */
    public Player player(long key) {
        Player last = null;
        for (Player p : players)
            if (p.key() == key) {
                if (!p.refused())
                    return p;
                last = p;
            }
        return last;
    }

    /** The players neither removed nor frozen: the ones a resume waits for. */
    public List<Player> playing() {
        List<Player> in = new ArrayList<>();
        for (Player p : players)
            if (!p.removed() && !p.frozen())
                in.add(p);
        return in;
    }

    /** This campaign with the frozen player in {@code slot} back (spec §11 Freezing a player, SOS-195). */
    public Campaign thawing(int slot) {
        List<Player> now = new ArrayList<>(players.size());
        for (Player p : players)
            now.add(p.slot() == slot && p.frozen() ? new Player(p.key(), p.name(), p.slot()) : p);
        return new Campaign(id, name, seed, difficulty, now, titles);
    }

    /** This campaign with the players in {@code slots} frozen (spec §11 Freezing a player, SOS-194). */
    public Campaign freezing(Collection<Integer> slots) {
        List<Player> now = new ArrayList<>(players.size());
        for (Player p : players)
            now.add(slots.contains(p.slot()) && !p.removed() ? new Player(p.key(), p.name(), p.slot(), false, false, true) : p);
        return new Campaign(id, name, seed, difficulty, now, titles);
    }

    /** This campaign with the players in {@code slots} removed (spec §11 Removing a player); a frozen one too, for good. */
    public Campaign removing(Collection<Integer> slots) {
        List<Player> now = new ArrayList<>(players.size());
        for (Player p : players)
            now.add(slots.contains(p.slot()) && !p.removed() ? new Player(p.key(), p.name(), p.slot(), true, false) : p);
        return new Campaign(id, name, seed, difficulty, now, titles);
    }

    /**
     * This campaign with a player who joined a running game in {@code slot} (hot-join, SOS-190). A removed, not
     * eliminated player of the same key may join again as a new player (spec §11 Removing a player): their old entry
     * stays listed with its slot, since the host's world still has that human, and {@link #player} gives the new one.
     */
    public Campaign adding(long key, String name, int slot) {
        List<Player> now = new ArrayList<>(players);
        now.add(new Player(key, name, slot));
        return new Campaign(id, this.name, seed, difficulty, now, titles);
    }

    /** This campaign with the player in {@code slot} eliminated (spec §9): they may come back as a spectator. */
    public Campaign eliminating(int slot) {
        List<Player> now = new ArrayList<>(players.size());
        for (Player p : players)
            now.add(p.slot() == slot ? new Player(p.key(), p.name(), p.slot(), true, true) : p);
        return new Campaign(id, name, seed, difficulty, now, titles);
    }

    /** The player in {@code slot}, or null. */
    public Player inSlot(int slot) {
        for (Player p : players)
            if (p.slot() == slot)
                return p;
        return null;
    }

    /** The slots of {@code c}'s players still playing that aren't among {@code present}: a resume's missing players. */
    public static List<Integer> missingSlots(Campaign c, Collection<Integer> present) {
        List<Integer> out = new ArrayList<>();
        for (Player p : c.playing())
            if (!present.contains(p.slot()))
                out.add(p.slot());
        return out;
    }

    /** A first game's campaign id, as its start id: the first 8 bytes of the world save's SHA-256, the same on every PC. */
    public static long idOf(byte[] worldSave) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(worldSave)).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SOSGSGMP: no SHA-256 in this Java", e);
        }
    }

    /** A campaign name from the first host's player name, cut to {@link #MAX_NAME}. */
    public static String nameFor(String hostName) {
        String n = hostName + "'s campaign";
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }
}
