package sosgsgmp.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HsErrTest {

    private static String redact(String text) {
        return new String(HsErr.redact(text.getBytes(StandardCharsets.ISO_8859_1), null, null), StandardCharsets.ISO_8859_1);
    }

    @Test
    void theEnvironmentSectionGoesAndTheRestStays() {
        String file = "# A fatal error has been detected by the Java Runtime Environment:\n"
                + "#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ff8\n\n"
                + "Environment Variables:\n"
                + "USERNAME=Ann\n"
                + "PATH=C:\\Users\\Ann\\bin\n"
                + "STEAM_KEY=secret\n"
                + "\n"
                + "---------------  S Y S T E M  ---------------\n";
        String out = redact(file);
        assertEquals("# A fatal error has been detected by the Java Runtime Environment:\n"
                + "#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ff8\n\n"
                + "Environment Variables:\n"
                + HsErr.REMOVED + "\n"
                + "\n"
                + "---------------  S Y S T E M  ---------------\n", out);
    }

    @Test
    void windowsLineEndsAndAFileEndingInsideTheSection() {
        assertEquals("a\r\nEnvironment Variables:\r\n" + HsErr.REMOVED + "\r\n\r\nb\r\n",
                redact("a\r\nEnvironment Variables:\r\nUSER=x\r\n\r\nb\r\n"));
        String cut = redact("a\nEnvironment Variables:\nUSER=x\nHOME=/home/x");
        assertEquals("a\nEnvironment Variables:\n" + HsErr.REMOVED + "\n", cut);
        assertFalse(cut.contains("/home/x"));
    }

    @Test
    void theUserNameAndHomeAreReplacedEverywhere() {
        String file = "Command Line: -Xmx4096m -cp C:\\Users\\Ann\\games;SongsOfSyx.jar init.MainProcess\n"
                + "java_class_path (initial): c:/users/ann/AppData/x.jar\n"
                + "OS: Windows 10, user ANN on host Annex; Ann's PC\n";
        String out = new String(HsErr.redact(file.getBytes(StandardCharsets.ISO_8859_1), "Ann", "C:\\Users\\Ann"),
                StandardCharsets.ISO_8859_1);
        assertEquals("Command Line: -Xmx4096m -cp <home>\\games;SongsOfSyx.jar init.MainProcess\n"
                + "java_class_path (initial): <home>/AppData/x.jar\n"
                + "OS: Windows 10, user <user> on host Annex; <user>'s PC\n", out);
    }

    @Test
    void onlyTheGamesJvmFileIsTheGames() {
        assertTrue(HsErr.isGame("VM Arguments:\njava_command: init.MainProcess\n".getBytes(StandardCharsets.ISO_8859_1)));
        assertTrue(HsErr.isGame("Command Line: -cp C:\\Songs of Syx\\SongsOfSyx.jar launcher\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        // another program's JVM in the shared temp folder, though its environment names the game
        assertFalse(HsErr.isGame(("Command Line: -Xmx2g org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10\n"
                + "java_command: org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.10\n"
                + "Environment Variables:\nSOS_GAME_DIR=C:\\Songs of Syx\\SongsOfSyx.jar\n").getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void aFileWithoutTheSectionIsUnchangedByteForByte() {
        byte[] file = new byte[256];
        for (int i = 0; i < file.length; i++)
            file[i] = (byte) i;
        assertArrayEquals(file, HsErr.redact(file, null, null));
    }
}
