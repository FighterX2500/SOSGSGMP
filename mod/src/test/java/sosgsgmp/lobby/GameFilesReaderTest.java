package sosgsgmp.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameFilesReaderTest {

    @TempDir
    Path dir;
    private Path jar, zip, scripts, modA, modB;

    /** A game as the reader finds it: its jar, its data in a zip, its script folder, and two mods' V71 folders. */
    @BeforeEach
    void game() throws IOException {
        jar = write("SongsOfSyx.jar", "classes");
        zip = dir.resolve("data.zip");
        zip("data/assets/init/room/Farm.txt", "BOOSTS: 1", "data/assets/text/room/Farm.txt", "Farm",
                "data/assets/sprite/room/Farm.png", "png");
        scripts = Files.createDirectories(dir.resolve("base/script"));
        write("base/script/000_Tutorial.jar", "tutorial");
        modA = mod("A");
        modB = mod("B");
    }

    private Path mod(String name) throws IOException {
        Path root = dir.resolve("mods/" + name + "/V71");
        write("mods/" + name + "/V71/assets/init/race/Elf.txt", name);
        write("mods/" + name + "/V71/assets/text/race/Elf.txt", "Elves");
        write("mods/" + name + "/V71/assets/sprite/race/Elf.png", "png");
        write("mods/" + name + "/V71/script/" + name + ".jar", "jar " + name);
        return root;
    }

    private Path write(String path, String text) throws IOException {
        Path p = dir.resolve(path);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, text);
    }

    private void zip(String... namesAndTexts) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndTexts.length; i += 2) {
                z.putNextEntry(new ZipEntry(namesAndTexts[i]));
                z.write(namesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    private GameFiles read() throws IOException {
        try (FileSystem data = FileSystems.newFileSystem(zip)) {
            return GameFilesReader.read(jar, data.getPath("/data/assets/init"), scripts,
                    List.of(new GameFilesReader.ModFolder("A", "1", modA), new GameFilesReader.ModFolder("B", "2", modB)));
        }
    }

    @Test
    void readingTwiceGivesTheSameFiles() throws IOException {
        GameFiles f = read();
        assertEquals(f, read());
        assertEquals(List.of("A", "B"), f.mods().stream().map(GameFiles.Mod::name).toList());
        assertEquals("2", f.mods().get(1).version());
    }

    @Test
    void textAndSpritesAreLeftOut() throws IOException {
        GameFiles before = read();
        zip("data/assets/init/room/Farm.txt", "BOOSTS: 1", "data/assets/text/room/Farm.txt", "Ferme",
                "data/assets/sprite/room/Farm.png", "another png");
        write("mods/A/V71/assets/text/race/Elf.txt", "Elfes");
        write("mods/B/V71/assets/sprite/race/Elf.png", "another png");
        write("mods/B/V71/assets/text/race/Dwarf.txt", "Dwarves");
        assertEquals(before, read());
    }

    @Test
    void theGamesInitDataJarAndScriptsCount() throws IOException {
        GameFiles before = read();
        zip("data/assets/init/room/Farm.txt", "BOOSTS: 2", "data/assets/text/room/Farm.txt", "Farm",
                "data/assets/sprite/room/Farm.png", "png");
        GameFiles data = read();
        assertNotEquals(before.game(), data.game());
        assertEquals(before.mods(), data.mods());
        write("SongsOfSyx.jar", "other classes");
        assertNotEquals(data.game(), read().game());
        GameFiles jarChanged = read();
        write("base/script/001_Tutorial.jar", "tutorial 2");
        assertNotEquals(jarChanged.game(), read().game());
    }

    @Test
    void aModsInitFilesAndScriptsCount() throws IOException {
        GameFiles before = read();
        write("mods/A/V71/assets/init/race/Elf.txt", "A changed");
        GameFiles changed = read();
        assertNotEquals(before.mods().get(0).hash(), changed.mods().get(0).hash());
        assertEquals(before.mods().get(1), changed.mods().get(1));
        assertEquals(before.game(), changed.game());
        write("mods/B/V71/assets/init/race/Orc.txt", "new");
        assertNotEquals(changed.mods().get(1).hash(), read().mods().get(1).hash());
        GameFiles added = read();
        write("mods/B/V71/script/B.jar", "jar B 2");
        assertNotEquals(added.mods().get(1).hash(), read().mods().get(1).hash());
    }

    @Test
    void aRenamedFileCounts() throws IOException {
        GameFiles before = read();
        Files.move(dir.resolve("mods/A/V71/assets/init/race/Elf.txt"), dir.resolve("mods/A/V71/assets/init/race/Elf2.txt"));
        assertNotEquals(before.mods().get(0).hash(), read().mods().get(0).hash());
    }

    @Test
    void aModWithoutInitOrScriptsIsRead() throws IOException {
        Path empty = Files.createDirectories(dir.resolve("mods/Empty/V71"));
        assertEquals(GameFilesReader.mod(empty), GameFilesReader.mod(dir.resolve("mods/Missing/V71")));
    }
}
