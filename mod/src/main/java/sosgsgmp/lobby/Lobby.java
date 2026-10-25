package sosgsgmp.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The host's lobby: its settings, who is in it, and when it can start (spec §5, lobby steps 1 to 4). */
public final class Lobby {

    /** Longest player name, in characters; longer names are cut. */
    public static final int MAX_NAME = 24;

    private final LobbySettings settings;
    private final List<LobbyPlayer> players = new ArrayList<>();
    /** Each player's key, as its PC sent it: who they are in a campaign (SOS-64). */
    private final Map<Integer, Long> keys = new HashMap<>();
    private int nextId;
    private boolean started;

    public Lobby(LobbySettings settings, String hostName, long hostKey) {
        this.settings = settings;
        if (settings.resume() != null && settings.resume().campaign().player(hostKey) == null)
            throw new IllegalArgumentException("SOSGSGMP: this PC isn't a player of campaign " + settings.resume().campaign().name());
        keys.put(nextId, hostKey);
        players.add(new LobbyPlayer(nextId++, cleanName(hostName), true));
    }

    public LobbySettings settings() {
        return settings;
    }

    public List<LobbyPlayer> players() {
        return Collections.unmodifiableList(new ArrayList<>(players));
    }

    /** Why the player whose PC sends {@code key} can't join now, or null if they can. */
    public Refusal refusal(long key) {
        if (started)
            return Refusal.STARTED;
        if (settings.resume() != null && settings.resume().campaign().player(key) == null)
            return Refusal.NOT_IN_CAMPAIGN;
        if (keys.containsValue(key))
            return Refusal.ALREADY_JOINED;
        if (players.size() >= settings.maxPlayers())
            return Refusal.FULL;
        return null;
    }

    /** Adds a player and returns their id. Check {@link #refusal(long)} first. */
    public int join(String name, long key) {
        Refusal r = refusal(key);
        if (r != null)
            throw new IllegalStateException("SOSGSGMP: can't join the lobby: " + r);
        int id = nextId++;
        keys.put(id, key);
        players.add(new LobbyPlayer(id, cleanName(name), false));
        return id;
    }

    /** Every player's key by player id. */
    public Map<Integer, Long> keys() {
        return Map.copyOf(keys);
    }

    /** Removes a joined player; the host can't leave this way (they close the lobby). */
    public void leave(int id) {
        if (id == players.get(0).id())
            throw new IllegalArgumentException("SOSGSGMP: the host can't leave the lobby, only close it");
        players.removeIf(p -> p.id() == id);
        keys.remove(id);
    }

    /** Start Lobby is active with 2 or more players, or for a resume with every player of the campaign, until started. */
    public boolean canStart() {
        if (started)
            return false;
        if (settings.resume() != null)
            return players.size() == settings.resume().campaign().players().size();
        return players.size() >= LobbySettings.MIN_PLAYERS;
    }

    public void start() {
        if (!canStart())
            throw new IllegalStateException("SOSGSGMP: the lobby can't start with " + players.size() + " players (started: " + started + ")");
        started = true;
    }

    public boolean started() {
        return started;
    }

    /** A name as the lobby shows it: trimmed, cut to {@link #MAX_NAME}, "Player" when empty. */
    public static String cleanName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty())
            return "Player";
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }
}
