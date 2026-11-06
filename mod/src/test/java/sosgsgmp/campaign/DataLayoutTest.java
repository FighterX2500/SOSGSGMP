package sosgsgmp.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sosgsgmp.campaign.CampaignStoreTest.record;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The mod's data folder and the move from the older layout (SOS-155), on temporary folders. */
public class DataLayoutTest {

    @TempDir
    Path root;

    private Path oldSaves;
    private DataLayout layout;

    @BeforeEach
    void folders() throws IOException {
        oldSaves = Files.createDirectories(root.resolve("saves").resolve("saves"));
        layout = new DataLayout(root, oldSaves, Files.createDirectories(root.resolve("saves").resolve("profile")));
    }

    /** The older layout as a PC had it: two campaign saves with records, a vanilla save, logs, reports, lobby settings. */
    private void oldLayout() throws IOException {
        CampaignStore old = new CampaignStore(layout.oldCampaigns());
        old.write(record(1, CampaignRecord.Kind.SAVE, 100));
        old.write(record(2, CampaignRecord.Kind.AUTO, 200));
        write(oldSaves.resolve(record(1, CampaignRecord.Kind.SAVE, 0).file()), "save 1");
        write(oldSaves.resolve(record(2, CampaignRecord.Kind.AUTO, 0).file()), "save 2");
        write(oldSaves.resolve("MP not ours.save"), "a vanilla save with the prefix");
        write(oldSaves.resolve("__battle.save"), "vanilla's battle");
        write(layout.oldLogs().resolve("20261106-host-Ann.log"), "log");
        write(layout.oldReports().resolve("f00dcafe12345678").resolve("1-Bob").resolve("a.log"), "report");
        write(layout.oldAutopilot().resolve("events.jsonl"), "{}");
        write(layout.oldLobbyPrefs(), "key=abc");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String save(int number) {
        return record(number, CampaignRecord.Kind.AUTO, 0).file();
    }

    @Test
    void theOldLayoutMovesOnceAndOnlyTheModsFiles() throws IOException {
        oldLayout();
        assertEquals(0, layout.version());
        DataLayout.Migration m = layout.migrate();
        assertTrue(m.done(), "everything moved: " + m.left());
        assertEquals(DataLayout.VERSION, layout.version());

        assertEquals("save 1", read(layout.saves().resolve(save(1))));
        assertEquals("save 2", read(layout.saves().resolve(save(2))));
        assertEquals(layout.saves().resolve(save(1)), layout.save(save(1)), "the lookup finds it in the new folder");
        assertTrue(Files.exists(oldSaves.resolve("MP not ours.save")), "no record names it: a vanilla save stays");
        assertTrue(Files.exists(oldSaves.resolve("__battle.save")), "vanilla's own working saves stay");
        assertFalse(Files.exists(oldSaves.resolve(save(1))));

        assertEquals(List.of(2, 1), layout.campaignStore().all().stream().map(CampaignRecord::number).toList());
        assertFalse(Files.exists(layout.oldCampaigns()), "the old folder went whole");
        assertEquals("log", read(layout.logs().resolve("20261106-host-Ann.log")));
        assertEquals("report", read(layout.reports().resolve("f00dcafe12345678").resolve("1-Bob").resolve("a.log")));
        assertEquals("{}", read(layout.autopilot().resolve("events.jsonl")));
        assertEquals("key=abc", read(layout.lobbyPrefs()));
        assertEquals(layout.lobbyPrefs(), layout.lobbyPrefsToRead());
        assertFalse(Files.exists(layout.oldLobbyPrefs()));

        // a second start moves nothing, even what an older version wrote since
        write(layout.oldLogs().resolve("later.log"), "an older version ran again");
        DataLayout.Migration again = layout.migrate();
        assertEquals(0, again.moved());
        assertFalse(again.done());
        assertTrue(Files.exists(layout.oldLogs().resolve("later.log")));
    }

    @Test
    void theNewLayoutAloneIsLeftAsItIs() throws IOException {
        layout.campaignStore().write(record(3, CampaignRecord.Kind.AUTO, 300));
        write(layout.saves().resolve(save(3)), "save 3");
        DataLayout.Migration m = layout.migrate();
        assertEquals(0, m.moved());
        assertTrue(m.done(), "nothing old: the layout is marked at once");
        assertEquals(layout.saves().resolve(save(3)), layout.save(save(3)));
        assertEquals(List.of(3), layout.campaignStore().all().stream().map(CampaignRecord::number).toList());
    }

    @Test
    void withNothingAnywhereLookupsPointToTheNewFolders() {
        assertEquals(layout.saves().resolve("x.save"), layout.save("x.save"));
        assertEquals(layout.lobbyPrefs(), layout.lobbyPrefsToRead());
        assertTrue(layout.campaignStore().all().isEmpty());
        assertTrue(layout.migrate().done());
    }

    @Test
    void whatBothLayoutsHaveTheNewOneWinsAndTheOldStays() throws IOException {
        oldLayout();
        write(layout.lobbyPrefs(), "key=new");
        write(layout.saves().resolve(save(1)), "new save 1");
        write(layout.logs().resolve("20261106-host-Ann.log"), "new log");
        layout.campaignStore().write(record(1, CampaignRecord.Kind.SAVE, 999));

        DataLayout.Migration m = layout.migrate();
        assertTrue(m.done(), "a file the new place has is no reason to wait: " + m.left());
        assertEquals("key=new", read(layout.lobbyPrefsToRead()));
        assertEquals("key=abc", read(layout.oldLobbyPrefs()), "the old one stays untouched");
        assertEquals("new save 1", read(layout.save(save(1))));
        assertEquals("save 2", read(layout.save(save(2))), "moved: the new folder didn't have it");
        assertEquals("new log", read(layout.logs().resolve("20261106-host-Ann.log")));
        assertEquals(999, layout.campaignStore().find(CampaignStoreTest.CAMPAIGN_FOR_TESTS.id(), 1).time(),
                "a record in both counts once, the new one");
        assertEquals(2, layout.campaignStore().all().size());
    }

    @Test
    void aHalfMovedLayoutIsFinished() throws IOException {
        oldLayout();
        // an earlier start moved the records and one save, then stopped
        Files.createDirectories(layout.home());
        Files.move(layout.oldCampaigns(), layout.campaigns());
        Files.createDirectories(layout.saves());
        Files.move(oldSaves.resolve(save(1)), layout.saves().resolve(save(1)));
        assertEquals(oldSaves.resolve(save(2)), layout.save(save(2)), "before the move the lookup finds the old one");

        DataLayout.Migration m = layout.migrate();
        assertTrue(m.done(), String.valueOf(m.left()));
        assertEquals("save 2", read(layout.save(save(2))));
        assertEquals(layout.saves().resolve(save(2)), layout.save(save(2)));
        assertEquals("key=abc", read(layout.lobbyPrefs()));
    }

    @Test
    void recordsInTheOldFolderAreReadAndRotatedThere() {
        CampaignStore old = new CampaignStore(layout.oldCampaigns());
        for (int n = 1; n <= 3; n++)
            old.write(record(n, CampaignRecord.Kind.AUTO, n));
        CampaignStore store = layout.campaignStore();
        store.write(record(4, CampaignRecord.Kind.AUTO, 4));
        assertEquals(List.of(4, 3, 2, 1), store.all().stream().map(CampaignRecord::number).toList());
        assertEquals(5, store.nextNumber(CampaignStoreTest.CAMPAIGN_FOR_TESTS.id()));
        assertEquals(List.of(2, 1), store.rotate(CampaignStoreTest.CAMPAIGN_FOR_TESTS.id(), 2).stream()
                .map(CampaignRecord::number).toList());
        assertEquals(List.of(4, 3), store.all().stream().map(CampaignRecord::number).toList(), "deleted in the old folder too");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS) // an open FileInputStream keeps a file from moving only on Windows
    void aLockedFileStaysFoundAndALaterStartMovesIt() throws IOException {
        oldLayout();
        Path lockedSave = oldSaves.resolve(save(2));
        Path lockedLog = layout.oldLogs().resolve("20261106-host-Ann.log");
        write(layout.oldLogs().resolve("other.log"), "other");
        try (FileInputStream a = new FileInputStream(lockedSave.toFile());
                FileInputStream b = new FileInputStream(lockedLog.toFile())) {
            assertTrue(a.getFD().valid() && b.getFD().valid(), "both open, so both locked");
            DataLayout.Migration m = layout.migrate();
            assertFalse(m.done());
            assertEquals(2, m.left().size(), String.valueOf(m.left()));
            assertEquals(0, layout.version(), "layout.txt waits for the locked files");
            assertEquals(lockedSave, layout.save(save(2)), "the campaign still resumes from the old place");
            assertEquals(layout.saves().resolve(save(1)), layout.save(save(1)));
            assertEquals("other", read(layout.logs().resolve("other.log")), "the rest of the folder moved file by file");
            assertTrue(Files.exists(lockedLog));
        }
        DataLayout.Migration later = layout.migrate();
        assertTrue(later.done(), String.valueOf(later.left()));
        assertEquals("save 2", read(layout.save(save(2))));
        assertEquals(layout.saves().resolve(save(2)), layout.save(save(2)));
        assertEquals("log", read(layout.logs().resolve("20261106-host-Ann.log")));
        assertFalse(Files.exists(layout.oldLogs()), "the emptied old folder is gone");
    }
}
