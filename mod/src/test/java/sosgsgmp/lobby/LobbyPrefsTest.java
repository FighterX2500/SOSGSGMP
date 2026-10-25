package sosgsgmp.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LobbyPrefsTest {

    @TempDir
    Path dir;

    @Test
    void noFileGivesDefaults() throws IOException {
        LobbyPrefs p = LobbyPrefs.load(dir.resolve("none.properties"));
        assertEquals("Player", p.name);
        assertEquals("", p.address);
        assertEquals(LobbyPrefs.DEFAULT_PORT, p.port);
        assertEquals(256, p.mapSize);
        assertTrue(p.keyIsNew, "a made key has to be saved");
    }

    @Test
    void roundTrip() throws IOException {
        Path file = dir.resolve("sub/lobby.properties");
        LobbyPrefs p = LobbyPrefs.load(file);
        p.name = "Änn = host";
        p.address = "26.10.0.7";
        p.port = 5000;
        p.mapSize = 512;
        p.save(file);

        LobbyPrefs back = LobbyPrefs.load(file);
        assertEquals(p.name, back.name);
        assertEquals(p.address, back.address);
        assertEquals(p.port, back.port);
        assertEquals(512, back.mapSize);
        assertEquals(p.playerKey, back.playerKey);
        assertFalse(back.keyIsNew, "the key was read");
    }

    @Test
    void aFileWithoutAMapSizeOrAnOddOneGetsTheDefault() throws IOException {
        Path file = dir.resolve("lobby.properties");
        Files.writeString(file, "format=1\nname=Ann\n");
        assertEquals(256, LobbyPrefs.load(file).mapSize);
        Files.writeString(file, "format=1\nmapSize=320\n");
        assertEquals(256, LobbyPrefs.load(file).mapSize);
    }

    @Test
    void anotherFormatIsRefused() throws IOException {
        Path file = dir.resolve("lobby.properties");
        Files.writeString(file, "format=2\n");
        assertThrows(IOException.class, () -> LobbyPrefs.load(file));
    }

    @Test
    void portsAreChecked() {
        assertEquals(47510, LobbyPrefs.parsePort(" 47510 "));
        assertEquals(-1, LobbyPrefs.parsePort(""));
        assertEquals(-1, LobbyPrefs.parsePort("0"));
        assertEquals(-1, LobbyPrefs.parsePort("65536"));
        assertEquals(-1, LobbyPrefs.parsePort("12a"));
        assertEquals(-1, LobbyPrefs.parsePort("-5"));
    }
}
