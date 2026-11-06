package sosgsgmp.campaign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where this PC keeps the mod's files (SOS-155): everything under {@code songsofsyx\sosgsgmp}, with the layout of
 * older versions, spread over {@code songsofsyx}, still read. Plain Java; the game's folders come from the adapter.
 *
 * <pre>
 * sosgsgmp\layout.txt         the layout's version, written once everything old has moved
 * sosgsgmp\saves\             every multiplayer save, file names as before
 * sosgsgmp\campaigns\         the campaign records
 * sosgsgmp\logs\              playtest and Steam logs
 * sosgsgmp\reports\           the joining PCs' files on the host
 * sosgsgmp\autopilot\         test tooling
 * sosgsgmp\engine\            the engine's working saves
 * sosgsgmp\lobby.properties   the lobby settings
 * </pre>
 */
public final class DataLayout {

    /** Raise when the layout changes; {@link #migrate} moves what an older one left. */
    public static final int VERSION = 1;

    private static final String LAYOUT = "layout.txt";

    private final Path root;
    private final Path oldSaves;
    private final Path oldProfile;
    private final Path home;

    /**
     * @param root       the game's data folder, {@code %APPDATA%\songsofsyx}
     * @param oldSaves   the game's saves folder, where older versions kept multiplayer saves
     * @param oldProfile the game's profile folder, where older versions kept the lobby settings
     */
    public DataLayout(Path root, Path oldSaves, Path oldProfile) {
        this.root = root;
        this.oldSaves = oldSaves;
        this.oldProfile = oldProfile;
        this.home = root.resolve("sosgsgmp");
    }

    public Path home() {
        return home;
    }

    public Path saves() {
        return home.resolve("saves");
    }

    public Path campaigns() {
        return home.resolve("campaigns");
    }

    public Path logs() {
        return home.resolve("logs");
    }

    public Path reports() {
        return home.resolve("reports");
    }

    public Path autopilot() {
        return home.resolve("autopilot");
    }

    public Path engine() {
        return home.resolve("engine");
    }

    /** The lobby settings, where they are written. */
    public Path lobbyPrefs() {
        return home.resolve("lobby.properties");
    }

    /** The lobby settings to read: the new file, or an older version's while it hasn't moved. */
    public Path lobbyPrefsToRead() {
        return newOrOld(lobbyPrefs(), oldLobbyPrefs());
    }

    /** A multiplayer save by its file name: in the saves folder, or in the game's while it hasn't moved. */
    public Path save(String file) {
        return newOrOld(saves().resolve(file), oldSaves.resolve(file));
    }

    /** Both places a save of this name can be, the new one first. */
    public List<Path> saveCandidates(String file) {
        return List.of(saves().resolve(file), oldSaves.resolve(file));
    }

    /** This PC's campaign records, the older versions' folder read too. */
    public CampaignStore campaignStore() {
        return new CampaignStore(campaigns(), oldCampaigns());
    }

    Path oldCampaigns() {
        return root.resolve("sosgsgmp-campaigns");
    }

    Path oldLogs() {
        return root.resolve("sosgsgmp-logs");
    }

    Path oldReports() {
        return root.resolve("sosgsgmp-reports");
    }

    Path oldAutopilot() {
        return root.resolve("sosgsgmp-autopilot");
    }

    Path oldLobbyPrefs() {
        return oldProfile.resolve("SOSGSGMP-lobby.properties");
    }

    /** The layout version this PC's folder is at: 0 before the first move. */
    public int version() {
        Path f = home.resolve(LAYOUT);
        if (!Files.isRegularFile(f))
            return 0;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8))
                if (line.startsWith("version="))
                    return Integer.parseInt(line.substring("version=".length()).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
        return 0;
    }

    /**
     * Moves an older version's files into this layout, once: the records, logs, reports, autopilot files, lobby
     * settings, and each save a campaign record names (never a save by its name alone, which a vanilla save can share).
     * Each move is a rename on the same drive. A file the new place already has stays where it is; the lookups read
     * the new one. A file that can't move (locked) stays and is named in the result, and {@code layout.txt} waits for
     * a later start that moves it; every lookup reads the old place meanwhile.
     */
    public Migration migrate() {
        Migration m = new Migration();
        if (version() >= VERSION)
            return m;
        moveTree(oldCampaigns(), campaigns(), m);
        moveTree(oldLogs(), logs(), m);
        moveTree(oldReports(), reports(), m);
        moveTree(oldAutopilot(), autopilot(), m);
        moveFile(oldLobbyPrefs(), lobbyPrefs(), m);
        for (CampaignRecord r : campaignStore().all())
            moveFile(oldSaves.resolve(r.file()), saves().resolve(r.file()), m);
        if (!m.left.isEmpty())
            return m;
        try {
            Files.createDirectories(home);
            Files.writeString(home.resolve(LAYOUT), "version=" + VERSION + "\n", StandardCharsets.UTF_8);
            m.done = true;
        } catch (IOException e) {
            m.left.add(home.resolve(LAYOUT) + ": " + e.getMessage());
        }
        return m;
    }

    private static void moveFile(Path from, Path to, Migration m) {
        if (!Files.isRegularFile(from) || Files.exists(to))
            return;
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            m.moved++;
        } catch (IOException e) {
            m.left.add(from + ": " + e);
        }
    }

    /** A folder: renamed whole when the new one doesn't exist yet, file by file otherwise or when the rename fails. */
    private static void moveTree(Path from, Path to, Migration m) {
        if (!Files.isDirectory(from))
            return;
        if (!Files.exists(to)) {
            try {
                Files.createDirectories(to.getParent());
                Files.move(from, to);
                m.moved++;
                return;
            } catch (IOException e) {
                m.notes.add(from + " moves file by file: " + e); // a locked file inside: what can move, moves
            }
        }
        List<Path> files = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> all = Files.walk(from)) {
            for (Path p : (Iterable<Path>) all::iterator)
                (Files.isDirectory(p) ? dirs : files).add(p);
        } catch (IOException e) {
            m.left.add(from + ": " + e);
            return;
        }
        for (Path f : files)
            moveFile(f, to.resolve(from.relativize(f).toString()), m);
        dirs.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path d : dirs) {
            try (Stream<Path> inside = Files.list(d)) {
                if (inside.findAny().isPresent())
                    continue; // a file that stayed: its folder stays with it
            } catch (IOException e) {
                m.notes.add(d + ": " + e);
                continue;
            }
            try {
                Files.delete(d);
            } catch (IOException e) {
                m.notes.add(d + ": " + e); // an empty folder left behind holds nothing to find
            }
        }
    }

    private static Path newOrOld(Path now, Path old) {
        return !Files.exists(now) && Files.exists(old) ? old : now;
    }

    /** What a {@link #migrate} did. */
    public static final class Migration {

        private int moved;
        private boolean done;
        private final List<String> left = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        /** Files and folders moved, a folder renamed whole counting once. */
        public int moved() {
            return moved;
        }

        /** True when this call finished the move and wrote {@code layout.txt}. */
        public boolean done() {
            return done;
        }

        /** What couldn't move, one line each; it stays in the old place, where the lookups still find it. */
        public List<String> left() {
            return List.copyOf(left);
        }

        /** Folders that moved file by file and empty old folders left behind, one line each; nothing in them is needed. */
        public List<String> notes() {
            return List.copyOf(notes);
        }
    }
}
