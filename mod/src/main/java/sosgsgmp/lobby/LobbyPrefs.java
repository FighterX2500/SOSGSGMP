package sosgsgmp.lobby;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Properties;

import sosgsgmp.setup.MapOptions;

/**
 * What the lobby screens remember between sessions: the player's name, the last host address and port, the last
 * difficulty, player limit and map size, and the player key that identifies this player to a host over direct IP (player-slots.md §2). Stored as a plain
 * key=value file; a missing or unreadable file gives the defaults.
 */
public final class LobbyPrefs {

    public static final int DEFAULT_PORT = 47510;

    private static final int FORMAT = 1;

    public String name = "Player";
    public String address = "";
    public int port = DEFAULT_PORT;
    /** Key of the last difficulty mode; empty for the game's first. */
    public String difficulty = "";
    public int maxPlayers = 4;
    /** The host's last map size; one of the offered sizes. */
    public int mapSize = MapOptions.DEFAULT_SIZE;
    public long playerKey;
    /**
     * The key was made by this load, not read: it is who this player is in a campaign (SOS-64), so it must be saved
     * before any lobby uses it. Not stored.
     */
    public boolean keyIsNew;

    private LobbyPrefs() {}

    /** The defaults, with a new player key. */
    public static LobbyPrefs defaults() {
        LobbyPrefs p = new LobbyPrefs();
        p.playerKey = new SecureRandom().nextLong();
        p.keyIsNew = true;
        return p;
    }

    /** Reads the file, or returns {@link #defaults()} when there is none. */
    public static LobbyPrefs load(Path file) throws IOException {
        if (!Files.isRegularFile(file))
            return defaults();
        LobbyPrefs p = new LobbyPrefs();
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        }
        int format = intOf(props, "format", FORMAT);
        if (format != FORMAT)
            throw new IOException("SOSGSGMP: " + file + " has format " + format + "; this version reads " + FORMAT);
        p.name = props.getProperty("name", p.name);
        p.address = props.getProperty("address", p.address);
        p.port = intOf(props, "port", DEFAULT_PORT);
        p.difficulty = props.getProperty("difficulty", p.difficulty);
        p.maxPlayers = Math.clamp(intOf(props, "maxPlayers", p.maxPlayers), LobbySettings.MIN_PLAYERS, LobbySettings.MAX_PLAYERS);
        int size = intOf(props, "mapSize", p.mapSize);
        p.mapSize = MapOptions.isOfferedSize(size) ? size : MapOptions.DEFAULT_SIZE;
        String key = props.getProperty("playerKey");
        p.playerKey = key == null ? new SecureRandom().nextLong() : parseLong(key);
        p.keyIsNew = key == null;
        return p;
    }

    public void save(Path file) throws IOException {
        Properties props = new Properties();
        props.setProperty("format", Integer.toString(FORMAT));
        props.setProperty("name", name);
        props.setProperty("address", address);
        props.setProperty("port", Integer.toString(port));
        props.setProperty("difficulty", difficulty);
        props.setProperty("maxPlayers", Integer.toString(maxPlayers));
        props.setProperty("mapSize", Integer.toString(mapSize));
        props.setProperty("playerKey", Long.toString(playerKey));
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(out, "SOS GSG MP lobby settings");
        }
    }

    /** A port typed by the player, or -1 when it isn't one (1 to 65535). */
    public static int parsePort(CharSequence text) {
        String s = text.toString().strip();
        if (s.isEmpty() || s.length() > 5)
            return -1;
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) < '0' || s.charAt(i) > '9')
                return -1;
        int port = Integer.parseInt(s);
        return port >= 1 && port <= 65535 ? port : -1;
    }

    private static int intOf(Properties props, String key, int fallback) throws IOException {
        String v = props.getProperty(key);
        if (v == null)
            return fallback;
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new IOException("SOSGSGMP: lobby settings: " + key + " is '" + v + "', not a number", e);
        }
    }

    private static long parseLong(String v) throws IOException {
        try {
            return Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            throw new IOException("SOSGSGMP: lobby settings: playerKey is '" + v + "', not a number", e);
        }
    }
}
