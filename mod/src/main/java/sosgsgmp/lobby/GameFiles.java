package sosgsgmp.lobby;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The game data a PC plays with, which every PC of a game shares (SOS-159): a hash of the game's own files and one per
 * enabled mod, in load order. Two PCs whose data differ build other boosts, rooms or races, so their cities can't be
 * read across. Languages, sprites and sounds aren't in it: they change no game state.
 */
public record GameFiles(long game, List<Mod> mods) {

    /** The mods every multiplayer game needs; any other is unsupported, though allowed when every PC has it. */
    private static final List<String> OURS = List.of("SOSDET", "SOS GSG MP");
    /** The most lines {@link #differences} lists before it stops counting. */
    private static final int MAX_LINES = 8;

    /** An enabled mod: its name and version from its {@code _Info.txt}, and a hash of the files that change the game. */
    public record Mod(String name, String version, long hash) {

        public Mod {
            if (name == null || version == null)
                throw new IllegalArgumentException("SOSGSGMP: a mod of name " + name + " and version " + version);
        }

        private String label() {
            return "Mod '" + name + "' " + version;
        }
    }

    public GameFiles {
        mods = List.copyOf(mods);
    }

    /** One hash of everything, in order: equal on two PCs exactly when every part is. */
    public long checksum() {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(ByteBuffer.allocate(Long.BYTES).putLong(game).array());
            for (Mod m : mods) {
                d.update(m.name.getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
                d.update(m.version.getBytes(StandardCharsets.UTF_8));
                d.update((byte) 0);
                d.update(ByteBuffer.allocate(Long.BYTES).putLong(m.hash).array());
            }
            return ByteBuffer.wrap(d.digest()).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SOSGSGMP: no SHA-256 in this Java", e);
        }
    }

    /** The checksum as four hex digits, for players to compare on the lobby screen. */
    public String code() {
        return String.format("%04x", checksum() >>> 48);
    }

    /** The enabled mods other than SOSDET and SOS GSG MP, by name, in load order. */
    public List<String> unsupported() {
        List<String> out = new ArrayList<>();
        for (Mod m : mods)
            if (!OURS.contains(m.name))
                out.add(m.name);
        return out;
    }

    /**
     * How a joining PC's {@code joiner} differ from these, the host's, one line each, as the joining player reads them;
     * empty when they are the same.
     */
    public List<String> differences(GameFiles joiner) {
        List<String> out = new ArrayList<>();
        if (game != joiner.game)
            out.add("The game's own files differ (SongsOfSyx.jar, base\\data.zip or base\\script): reinstall the game, or let Steam verify it.");
        for (Mod m : joiner.mods) {
            Mod host = named(mods, m.name);
            if (host == null)
                out.add(m.label() + ": you have it enabled, the host doesn't.");
            else if (!host.version.equals(m.version))
                out.add("Mod '" + m.name + "': " + m.version + " here, " + host.version + " on the host.");
            else if (host.hash != m.hash)
                out.add(m.label() + ": its files differ from the host's.");
        }
        for (Mod m : mods)
            if (named(joiner.mods, m.name) == null)
                out.add(m.label() + ": the host has it enabled, you don't.");
        if (out.isEmpty() && !mods.equals(joiner.mods))
            out.add("The mods are in another order than the host's: " + String.join(", ", names(mods)) + ".");
        if (out.size() > MAX_LINES) {
            int more = out.size() - MAX_LINES;
            out = new ArrayList<>(out.subList(0, MAX_LINES));
            out.add("And " + more + " more.");
        }
        return out;
    }

    private static Mod named(List<Mod> mods, String name) {
        for (Mod m : mods)
            if (m.name.equals(name))
                return m;
        return null;
    }

    private static List<String> names(List<Mod> mods) {
        List<String> out = new ArrayList<>();
        for (Mod m : mods)
            out.add(m.name);
        return out;
    }
}
