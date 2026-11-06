package sosgsgmp.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class GameFilesTest {

    private static final GameFiles.Mod ENGINE = new GameFiles.Mod("SOSDET", "1.0-beta", 1);
    private static final GameFiles.Mod MOD = new GameFiles.Mod("SOS GSG MP", "1.0-beta", 2);
    private static final GameFiles.Mod RR = new GameFiles.Mod("Region rework", "0.17", 3);
    private static final GameFiles HOST = new GameFiles(9, List.of(ENGINE, MOD, RR));

    private static final Builds BUILDS = new Builds("0.71.44", 1, 2, HOST);

    @Test
    void theSameFilesMatch() {
        GameFiles same = new GameFiles(9, List.of(ENGINE, MOD, RR));
        assertEquals(List.of(), HOST.differences(same));
        assertEquals(HOST.checksum(), same.checksum());
        assertEquals(HOST.code(), same.code());
        assertEquals(4, HOST.code().length());
        assertNull(BUILDS.mismatch(new Builds("0.71.44", 1, 2, same)));
    }

    @Test
    void anExtraModIsNamed() {
        GameFiles joiner = new GameFiles(9, List.of(ENGINE, MOD, RR, new GameFiles.Mod("Hats", "2", 4)));
        assertEquals(List.of("Mod 'Hats' 2: you have it enabled, the host doesn't."), HOST.differences(joiner));
        assertNotEquals(HOST.checksum(), joiner.checksum());
        assertEquals(Refusal.WRONG_FILES, BUILDS.mismatch(new Builds("0.71.44", 1, 2, joiner)));
    }

    @Test
    void aMissingModIsNamed() {
        GameFiles joiner = new GameFiles(9, List.of(ENGINE, MOD));
        assertEquals(List.of("Mod 'Region rework' 0.17: the host has it enabled, you don't."), HOST.differences(joiner));
    }

    @Test
    void anotherVersionIsNamed() {
        GameFiles joiner = new GameFiles(9, List.of(ENGINE, MOD, new GameFiles.Mod("Region rework", "0.18", 3)));
        assertEquals(List.of("Mod 'Region rework': 0.18 here, 0.17 on the host."), HOST.differences(joiner));
    }

    @Test
    void changedFilesOfTheSameVersionAreNamed() {
        GameFiles joiner = new GameFiles(9, List.of(ENGINE, MOD, new GameFiles.Mod("Region rework", "0.17", 30)));
        assertEquals(List.of("Mod 'Region rework' 0.17: its files differ from the host's."), HOST.differences(joiner));
    }

    @Test
    void theGamesOwnFilesAndTheModsOrderAreNamed() {
        List<String> game = HOST.differences(new GameFiles(8, List.of(ENGINE, MOD, RR)));
        assertEquals(1, game.size());
        assertTrue(game.get(0).startsWith("The game's own files differ"), game.get(0));
        GameFiles reordered = new GameFiles(9, List.of(ENGINE, RR, MOD));
        assertEquals(List.of("The mods are in another order than the host's: SOSDET, SOS GSG MP, Region rework."),
                HOST.differences(reordered));
        assertNotEquals(HOST.checksum(), reordered.checksum());
    }

    @Test
    void manyDifferencesAreCut() {
        List<GameFiles.Mod> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++)
            many.add(new GameFiles.Mod("Mod" + i, "1", i));
        List<String> d = new GameFiles(9, List.of()).differences(new GameFiles(9, many));
        assertEquals(9, d.size());
        assertEquals("And 12 more.", d.get(8));
    }

    @Test
    void ourOwnModsArentUnsupported() {
        assertEquals(List.of("Region rework"), HOST.unsupported());
        assertEquals(List.of(), new GameFiles(9, List.of(ENGINE, MOD)).unsupported());
    }

    /** Our own builds are checked first: their refusals tell the player to let Steam finish updating. */
    @Test
    void ourBuildsAreCheckedBeforeTheFiles() {
        GameFiles other = new GameFiles(8, List.of());
        assertEquals(Refusal.WRONG_ENGINE, BUILDS.mismatch(new Builds("0.71.44", 7, 2, other)));
        assertEquals(Refusal.WRONG_MOD, BUILDS.mismatch(new Builds("0.71.44", 1, 7, other)));
    }
}
