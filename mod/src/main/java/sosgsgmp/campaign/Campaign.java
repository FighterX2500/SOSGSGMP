package sosgsgmp.campaign;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A campaign as every PC in it knows it (spec §11, SOS-64): the host sends it with every save order, and it goes into
 * every PC's records. The id is the first game's start id, so it is the same on every PC from the start.
 */
public record Campaign(long id, String name, int seed, String difficulty, List<Player> players) {

    /** Longest campaign name, in characters. */
    public static final int MAX_NAME = 48;

    /**
     * A player of the campaign: the key its PC sends in its hello, its name, its human's slot, and whether the host
     * removed them (spec §11 Removing a player, SOS-65): a removed player stays listed, but never plays again.
     */
    public record Player(long key, String name, int slot, boolean removed) {
        public Player {
            if (name == null || name.isEmpty())
                throw new IllegalArgumentException("SOSGSGMP: a campaign player without a name (slot " + slot + ")");
            if (slot < 0)
                throw new IllegalArgumentException("SOSGSGMP: campaign player " + name + " in slot " + slot);
        }

        /** A player still in the campaign. */
        public Player(long key, String name, int slot) {
            this(key, name, slot, false);
        }
    }

    public Campaign {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME)
            throw new IllegalArgumentException("SOSGSGMP: campaign name '" + name + "'");
        if (difficulty == null || difficulty.isEmpty())
            throw new IllegalArgumentException("SOSGSGMP: campaign " + name + " has no difficulty");
        players = List.copyOf(players);
        if (players.size() < 2)
            throw new IllegalArgumentException("SOSGSGMP: campaign " + name + " with " + players.size() + " players");
        for (int i = 0; i < players.size(); i++)
            for (int j = i + 1; j < players.size(); j++)
                if (players.get(i).key() == players.get(j).key() || players.get(i).slot() == players.get(j).slot())
                    throw new IllegalArgumentException("SOSGSGMP: campaign " + name + " has two players with one key or slot: "
                            + players.get(i) + ", " + players.get(j));
    }

    /** The player whose PC sends {@code key}, or null. */
    public Player player(long key) {
        for (Player p : players)
            if (p.key() == key)
                return p;
        return null;
    }

    /** The players not removed: the ones a resume waits for. */
    public List<Player> playing() {
        List<Player> in = new ArrayList<>();
        for (Player p : players)
            if (!p.removed())
                in.add(p);
        return in;
    }

    /** This campaign with the players in {@code slots} removed (spec §11 Removing a player). */
    public Campaign removing(Collection<Integer> slots) {
        List<Player> now = new ArrayList<>(players.size());
        for (Player p : players)
            now.add(slots.contains(p.slot()) && !p.removed() ? new Player(p.key(), p.name(), p.slot(), true) : p);
        return new Campaign(id, name, seed, difficulty, now);
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
