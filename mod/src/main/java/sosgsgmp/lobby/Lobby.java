package sosgsgmp.lobby;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The host's lobby: its settings, who is in it, and when it can start (spec §5, lobby steps 1 to 4). */
public final class Lobby {

    /** Longest player name, in characters; longer names are cut. */
    public static final int MAX_NAME = 24;

    private final LobbySettings settings;
    private final List<LobbyPlayer> players = new ArrayList<>();
    private int nextId;
    private boolean started;

    public Lobby(LobbySettings settings, String hostName) {
        this.settings = settings;
        players.add(new LobbyPlayer(nextId++, cleanName(hostName), true));
    }

    public LobbySettings settings() {
        return settings;
    }

    public List<LobbyPlayer> players() {
        return Collections.unmodifiableList(new ArrayList<>(players));
    }

    /** Why a new player can't join now, or null if they can. */
    public Refusal refusal() {
        if (started)
            return Refusal.STARTED;
        if (players.size() >= settings.maxPlayers())
            return Refusal.FULL;
        return null;
    }

    /** Adds a player and returns their id. Check {@link #refusal()} first. */
    public int join(String name) {
        Refusal r = refusal();
        if (r != null)
            throw new IllegalStateException("SOSGSGMP: can't join the lobby: " + r);
        int id = nextId++;
        players.add(new LobbyPlayer(id, cleanName(name), false));
        return id;
    }

    /** Removes a joined player; the host can't leave this way (they close the lobby). */
    public void leave(int id) {
        if (id == players.get(0).id())
            throw new IllegalArgumentException("SOSGSGMP: the host can't leave the lobby, only close it");
        players.removeIf(p -> p.id() == id);
    }

    /** Start Lobby is active with 2 or more players, until the lobby has started. */
    public boolean canStart() {
        return !started && players.size() >= LobbySettings.MIN_PLAYERS;
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
