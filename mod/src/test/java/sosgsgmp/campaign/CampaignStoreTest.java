package sosgsgmp.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CampaignStoreTest {

    public static final Campaign CAMPAIGN_FOR_TESTS = new Campaign(0xF00DCAFE12345678L, "Ann's campaign", 777, "normal",
            List.of(new Campaign.Player(-5, "Ann", 0), new Campaign.Player(0x7FFF_0000_0000_0001L, "Bob=1", 1)));

    @TempDir
    Path dir;

    static CampaignRecord record(int number, CampaignRecord.Kind kind, long time) {
        return new CampaignRecord(CAMPAIGN_FOR_TESTS, number, kind, time, 1, "MP Ann's campaign " + number + ".save", 0x8000_0000_0000_0042L);
    }

    @Test
    void aRecordReadsBackAsItWasWritten() {
        CampaignRecord r = record(3, CampaignRecord.Kind.AUTO, 1_700_000_000_000L);
        assertEquals(r, CampaignRecord.fromText(r.toText()));
        assertEquals("Bob=1", r.player().name(), "a value may hold '='");
    }

    @Test
    void aRemovedPlayerStaysListedAndIsKeptByTheRecord() {
        Campaign c = CAMPAIGN_FOR_TESTS.removing(java.util.Set.of(0));
        assertEquals(List.of(new Campaign.Player(-5, "Ann", 0, true), CAMPAIGN_FOR_TESTS.players().get(1)), c.players());
        assertEquals(List.of(CAMPAIGN_FOR_TESTS.players().get(1)), c.playing(), "a resume waits only for Bob");
        assertEquals(c, c.removing(java.util.Set.of(0)), "removing twice changes nothing");
        CampaignRecord r = new CampaignRecord(c, 4, CampaignRecord.Kind.AUTO, 9, 1, "b.save", 77);
        assertEquals(r, CampaignRecord.fromText(r.toText()));
    }

    @Test
    void aFormat1RecordLoadsWithNobodyRemoved() {
        CampaignRecord r = record(2, CampaignRecord.Kind.SAVE, 5);
        String v1 = r.toText().replace("format=2", "format=1").replaceAll("player\\.\\d+\\.removed=false\n", "");
        assertEquals(r, CampaignRecord.fromText(v1));
    }

    @Test
    void anUnknownFormatOrABrokenRecordIsRefused() {
        String text = record(1, CampaignRecord.Kind.SAVE, 1).toText();
        assertThrows(IllegalArgumentException.class, () -> CampaignRecord.fromText(text.replace("format=2", "format=3")));
        assertThrows(IllegalArgumentException.class, () -> CampaignRecord.fromText(text.replace("player.0.removed=false",
                "player.0.removed=yes")));
        assertThrows(IllegalArgumentException.class, () -> CampaignRecord.fromText(text.replace("\nslot=1\n", "\n")));
        assertThrows(IllegalArgumentException.class, () -> CampaignRecord.fromText(text.replace("\nslot=1\n", "\nslot=5\n")),
                "a slot the campaign doesn't have");
        assertThrows(IllegalArgumentException.class, () -> new Campaign(1, "x", 1, "normal",
                List.of(new Campaign.Player(1, "A", 0), new Campaign.Player(1, "B", 1))), "two players with one key");
    }

    @Test
    void theStoreListsNewestFirstFindsAndNumbers() throws IOException {
        CampaignStore store = new CampaignStore(dir.resolve("campaigns"));
        assertTrue(store.all().isEmpty(), "no folder yet");
        assertEquals(1, store.nextNumber(CAMPAIGN_FOR_TESTS.id()));
        store.write(record(1, CampaignRecord.Kind.SAVE, 100));
        store.write(record(2, CampaignRecord.Kind.AUTO, 300));
        store.write(record(3, CampaignRecord.Kind.AUTO, 200));
        Files.writeString(dir.resolve("campaigns").resolve("broken.txt"), "format=1\n");
        assertEquals(List.of(2, 3, 1), store.all().stream().map(CampaignRecord::number).toList());
        assertEquals(1, store.problems().size(), "the broken one, with why");
        assertEquals(4, store.nextNumber(CAMPAIGN_FOR_TESTS.id()));
        assertEquals(1, store.nextNumber(42), "another campaign");
        assertEquals(300, store.find(CAMPAIGN_FOR_TESTS.id(), 2).time());
        assertNull(store.find(CAMPAIGN_FOR_TESTS.id(), 9));
    }

    @Test
    void rotationKeepsTheNewestAutosavesAndEverySave() {
        CampaignStore store = new CampaignStore(dir);
        store.write(record(1, CampaignRecord.Kind.SAVE, 100));
        for (int n = 2; n <= 6; n++)
            store.write(record(n, CampaignRecord.Kind.AUTO, 100 + n));
        List<CampaignRecord> gone = store.rotate(CAMPAIGN_FOR_TESTS.id(), 2);
        assertEquals(List.of(4, 3, 2), gone.stream().map(CampaignRecord::number).toList());
        assertEquals(List.of(6, 5, 1), store.all().stream().map(CampaignRecord::number).toList());
    }
}
