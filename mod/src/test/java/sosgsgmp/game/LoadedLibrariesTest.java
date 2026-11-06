package sosgsgmp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Finding Steam's library in a Linux process's mappings (SOS-157), on listings shaped as the kernel writes them. */
public class LoadedLibrariesTest {

    private static final String MAPS = String.join("\n",
            "55d0c0a00000-55d0c0a01000 r--p 00000000 103:05 1311017                   /home/deck/.local/share/Steam/steamapps/common/Songs of Syx/jre/bin/java",
            "7f1b2c000000-7f1b2c021000 rw-p 00000000 00:00 0 ",
            "7f1b3a400000-7f1b3a46a000 r--p 00000000 00:2a 412                        /tmp/steamworks4j/1.9.0/libsteamworks4j.so",
            "7f1b3a600000-7f1b3a65f000 r--p 00000000 00:2a 415                        /tmp/steamworks4j/1.9.0/libsteam_api.so",
            "7f1b3a65f000-7f1b3a6e1000 r-xp 0005f000 00:2a 415                        /tmp/steamworks4j/1.9.0/libsteam_api.so",
            "7ffd5e3f2000-7ffd5e413000 rw-p 00000000 00:00 0                          [stack]",
            "");

    @Test
    void theFirstMappingOfTheLibraryGivesItsPath() {
        assertEquals("/tmp/steamworks4j/1.9.0/libsteam_api.so", LoadedLibraries.path(MAPS, "libsteam_api.so"));
    }

    @Test
    void aLibraryWhoseNameOnlyEndsTheSameIsNotIt() {
        assertNull(LoadedLibraries.path(MAPS, "steam_api.so"));
        assertEquals("/tmp/steamworks4j/1.9.0/libsteamworks4j.so", LoadedLibraries.path(MAPS, "libsteamworks4j.so"));
    }

    @Test
    void aPathWithSpacesComesWhole() {
        String maps = "7f00-7f01 r--p 00000000 103:05 99    /home/deck/Songs of Syx/steamworks4j/libsteam_api.so\n";
        assertEquals("/home/deck/Songs of Syx/steamworks4j/libsteam_api.so", LoadedLibraries.path(maps, "libsteam_api.so"));
    }

    @Test
    void aLibraryNotLoadedIsNull() {
        assertNull(LoadedLibraries.path(MAPS, "libsdkencryptedappticket.so"));
        assertNull(LoadedLibraries.path("", "libsteam_api.so"));
    }
}
