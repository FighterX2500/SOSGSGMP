package sosgsgmp.lobby;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads {@link GameFiles} from the folders the game plays with (SOS-159). What changes the game's state is hashed: the
 * game's jar, the data under {@code assets/init} and the script jars. Text, sprites and sounds are left out, so players
 * may use other languages. The folders may be on any file system, the game's data inside {@code base\data.zip} too.
 */
public final class GameFilesReader {

    /** An enabled mod, as the game found it: its name and version, and its folder for this game version ({@code V71}). */
    public record ModFolder(String name, String version, Path root) {}

    private GameFilesReader() {
    }

    /**
     * The game's part from its jar, its data's {@code assets/init} folder and its {@code base/script} folder; a part per
     * mod in {@code mods}, in load order.
     */
    public static GameFiles read(Path gameJar, Path dataInit, Path baseScripts, List<ModFolder> mods) {
        MessageDigest game = digest();
        file(game, gameJar, "game");
        tree(game, dataInit);
        jars(game, baseScripts);
        List<GameFiles.Mod> parts = new ArrayList<>();
        for (ModFolder m : mods)
            parts.add(new GameFiles.Mod(m.name, m.version, mod(m.root)));
        return new GameFiles(value(game), parts);
    }

    /** A mod's part: its {@code assets/init} files and its script jars. */
    static long mod(Path root) {
        MessageDigest d = digest();
        tree(d, root.resolve("assets").resolve("init"));
        jars(d, root.resolve("script"));
        return value(d);
    }

    /** Every file under {@code folder}, by its path in it and its bytes, in name order; nothing if it is missing. */
    private static void tree(MessageDigest d, Path folder) {
        if (!Files.isDirectory(folder))
            return;
        List<String> names = new ArrayList<>();
        try (Stream<Path> all = Files.walk(folder)) {
            all.filter(Files::isRegularFile).forEach(p -> names.add(folder.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't list " + folder, e);
        }
        names.sort(null);
        for (String name : names)
            file(d, folder.resolve(name), name);
    }

    /** The {@code .jar} files right in {@code folder}, in name order; nothing if it is missing. */
    private static void jars(MessageDigest d, Path folder) {
        if (!Files.isDirectory(folder))
            return;
        List<String> names = new ArrayList<>();
        try (Stream<Path> all = Files.list(folder)) {
            all.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                    .forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't list " + folder, e);
        }
        names.sort(null);
        for (String name : names)
            file(d, folder.resolve(name), "script/" + name);
    }

    /** One file, under {@code name}: a file moved or renamed changes the hash as much as one changed. */
    private static void file(MessageDigest d, Path file, String name) {
        d.update(name.getBytes(StandardCharsets.UTF_8));
        d.update((byte) 0);
        byte[] buffer = new byte[64 * 1024];
        long length = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                d.update(buffer, 0, n);
                length += n;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't read " + file, e);
        }
        d.update(ByteBuffer.allocate(Long.BYTES).putLong(length).array());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SOSGSGMP: no SHA-256 in this Java", e);
        }
    }

    private static long value(MessageDigest d) {
        return ByteBuffer.wrap(d.digest()).getLong();
    }
}
