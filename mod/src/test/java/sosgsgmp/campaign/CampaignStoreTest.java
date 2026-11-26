package sosgsgmp.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return new CampaignRecord(CAMPAIGN_FOR_TESTS, number, kind, time, 1, "MP Ann's campaign " + number + ".save", 0x8000_0000_0000_0042L,
                false);
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
        CampaignRecord r = new CampaignRecord(c, 4, CampaignRecord.Kind.AUTO, 9, 1, "b.save", 77, false);
        assertEquals(r, CampaignRecord.fromText(r.toText()));
        CampaignRecord e = new CampaignRecord(c.eliminating(1), 5, CampaignRecord.Kind.AUTO, 9, 1, "b.save", 77, false);
        assertTrue(e.campaign().inSlot(1).eliminated());
        assertEquals(e, CampaignRecord.fromText(e.toText()));
    }

    /**
     * Spec §11 Resuming: a record written by this version says its save holds every player's last report, so a resume
     * can freeze a missing player (format 5); an older record says not.
     */
    @Test
    void aFormat5RecordSaysItsSaveCanFreezeAndAFormat4OneSaysNot() {
        CampaignRecord r = new CampaignRecord(CAMPAIGN_FOR_TESTS, 3, CampaignRecord.Kind.SAVE, 5, 1, "a.save", 77, true);
        assertTrue(r.toText().startsWith("format=6\n"));
        assertEquals(r, CampaignRecord.fromText(r.toText()));
        String v4 = r.toText().replace("format=6", "format=4").replace("lastReports=true\n", "").replace("titles=NONE\n", "");
        CampaignRecord old = CampaignRecord.fromText(v4);
        assertFalse(old.lastReports(), "a format 4 record's save holds no last reports");
        assertEquals(new CampaignRecord(CAMPAIGN_FOR_TESTS, 3, CampaignRecord.Kind.SAVE, 5, 1, "a.save", 77, false), old);
    }

    @Test
    void aFormat1RecordLoadsWithNobodyRemoved() {
        CampaignRecord r = record(2, CampaignRecord.Kind.SAVE, 5);
        String v1 = r.toText().replace("format=6", "format=1").replace("titles=NONE\n", "").replaceAll("player\\.\\d+\\.(removed|eliminated|frozen)=false\n", "")
                .replace("lastReports=false\n", "");
        assertEquals(r, CampaignRecord.fromText(v1));
    }

    /** Spec §5 Titles: a record keeps the campaign's titles setting; a format 5 one has none, and a bad one is refused. */
    @Test
    void theTitlesSettingIsKeptAndAnOlderRecordHasNone() {
        Campaign c = new Campaign(CAMPAIGN_FOR_TESTS.id(), CAMPAIGN_FOR_TESTS.name(), 777, "normal", CAMPAIGN_FOR_TESTS.players(),
                sosgsgmp.setup.TitlesMode.UNIQUE);
        CampaignRecord r = new CampaignRecord(c, 3, CampaignRecord.Kind.SAVE, 5, 1, "a.save", 77, true);
        assertTrue(r.toText().contains("titles=UNIQUE\n"));
        assertEquals(r, CampaignRecord.fromText(r.toText()));
        assertEquals(sosgsgmp.setup.TitlesMode.UNIQUE, c.adding(9, "Cid", 5).titles(), "a hot-join keeps the setting");
        CampaignRecord v5 = CampaignRecord.fromText(r.toText().replace("format=6", "format=5").replace("titles=UNIQUE\n", ""));
        assertEquals(sosgsgmp.setup.TitlesMode.NONE, v5.campaign().titles());
        assertThrows(IllegalArgumentException.class, () -> CampaignRecord.fromText(r.toText().replace("titles=UNIQUE", "titles=ALL")));
    }

    /** SOS-194: a frozen player stays listed, no resume waits for them, and the record keeps them; format 3 has none. */
    @Test
    void aFrozenPlayerIsKeptByTheRecordAndNoResumeWaitsForThem() {
        Campaign c = CAMPAIGN_FOR_TESTS.freezing(java.util.Set.of(1));
        assertTrue(c.inSlot(1).frozen());
        assertFalse(c.inSlot(1).removed());
        assertFalse(c.inSlot(1).refused(), "a frozen player isn't removed");
        assertEquals(List.of(CAMPAIGN_FOR_TESTS.players().get(0)), c.playing(), "a resume waits only for Ann");
        assertEquals(List.of(), Campaign.missingSlots(c, List.of(0)), "nobody is missing, so nobody is removed at the start");
        CampaignRecord r = new CampaignRecord(c, 6, CampaignRecord.Kind.SAVE, 9, 0, "a.save", 77, false);
        assertEquals(r, CampaignRecord.fromText(r.toText()));
        Campaign removed = c.removing(java.util.Set.of(1));
        assertTrue(removed.inSlot(1).removed(), "the host can remove a frozen player for good");
        assertFalse(removed.inSlot(1).frozen());
        CampaignRecord old = record(2, CampaignRecord.Kind.SAVE, 5);
        String v3 = old.toText().replace("format=6", "format=3").replace("titles=NONE\n", "").replaceAll("player\\.\\d+\\.frozen=false\n", "")
                .replace("lastReports=false\n", "");
        assertEquals(old, CampaignRecord.fromText(v3), "a format 3 record has nobody frozen");
        assertThrows(IllegalArgumentException.class, () -> new Campaign.Player(1, "Ann", 0, true, false, true),
                "frozen and removed at once");
    }

    /**
     * Spec §11 Removing a player: a removed player joins the running game again as a new player, a new entry of the
     * same key. The old entry stays with its slot (the host's world still has that human), and {@code player(key)} gives
     * the new one; removed again and back again, that key has three entries. One key has at most one entry that isn't
     * refused, and a slot one entry: the engine's join slot is never a removed human's (SOSDET GameHumans.joinSlot:
     * retired humans keep their slot).
     */
    @Test
    void aRemovedPlayerAddedAgainKeepsTheirOldEntry() {
        Campaign c = new Campaign(9, "Ann's campaign", 1, "normal", List.of(new Campaign.Player(1, "Ann", 0),
                new Campaign.Player(2, "Bob", 1), new Campaign.Player(3, "Cid", 2))).removing(java.util.Set.of(2));
        Campaign again = c.adding(3, "Cid the Second", 5);
        assertEquals(List.of(new Campaign.Player(1, "Ann", 0), new Campaign.Player(2, "Bob", 1),
                new Campaign.Player(3, "Cid", 2, true), new Campaign.Player(3, "Cid the Second", 5)), again.players());
        assertEquals(new Campaign.Player(3, "Cid the Second", 5), again.player(3), "the new entry");
        assertEquals(new Campaign.Player(3, "Cid", 2, true), again.inSlot(2), "the old one keeps its slot");
        assertEquals(List.of(again.players().get(0), again.players().get(1), again.players().get(3)), again.playing());
        CampaignRecord r = new CampaignRecord(again, 7, CampaignRecord.Kind.AUTO, 9, 5, "c.save", 77, true);
        assertEquals(r, CampaignRecord.fromText(r.toText()), "the record round trip, with both entries");

        Campaign removedAgain = again.removing(java.util.Set.of(5));
        assertEquals(new Campaign.Player(3, "Cid the Second", 5, true), removedAgain.player(3), "every entry refused: the last");
        Campaign third = removedAgain.adding(3, "Cid the Third", 7);
        assertEquals(3, third.players().stream().filter(p -> p.key() == 3).count());
        assertEquals(new Campaign.Player(3, "Cid the Third", 7), third.player(3), "the newest");
        CampaignRecord r3 = new CampaignRecord(third, 9, CampaignRecord.Kind.SAVE, 9, 7, "c.save", 77, true);
        assertEquals(r3, CampaignRecord.fromText(r3.toText()));

        assertThrows(IllegalArgumentException.class, () -> again.adding(3, "Cid", 9), "two entries of one key not refused");
        assertThrows(IllegalArgumentException.class, () -> c.adding(2, "Bob", 5), "a playing player's key");
        assertThrows(IllegalArgumentException.class, () -> c.adding(4, "Dan", 2), "a removed player's slot, another key");
        assertThrows(IllegalArgumentException.class, () -> c.adding(3, "Cid", 2), "a removed player's slot, their key");
        assertThrows(IllegalArgumentException.class, () -> c.adding(4, "Dan", 1), "a playing player's slot");
        Campaign eliminated = c.eliminating(1);
        assertThrows(IllegalArgumentException.class, () -> eliminated.adding(2, "Bob", 5), "an eliminated player's key");
        assertThrows(IllegalArgumentException.class, () -> new Campaign(1, "x", 1, "normal",
                List.of(new Campaign.Player(1, "A", 0), new Campaign.Player(1, "A", 1, true, true))), "a playing and an eliminated entry");
        Campaign frozen = c.freezing(java.util.Set.of(1));
        assertThrows(IllegalArgumentException.class, () -> frozen.adding(2, "Bob", 5), "a frozen player's key");
        assertThrows(IllegalArgumentException.class, () -> frozen.adding(4, "Dan", 1), "a frozen player's slot");
    }

    @Test
    void anUnknownFormatOrABrokenRecordIsRefused() {
        String text = record(1, CampaignRecord.Kind.SAVE, 1).toText();
        assertThrows(IllegalArgumentException.class, () -> CampaignRecord.fromText(text.replace("format=6", "format=7")));
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

    /**
     * SOS-228: Load Saved Lobby lists every save, newest at the top, however many there are: 30 saves of two campaigns,
     * some still in an older version's folder, come back all 30, newest first; two saved in the same millisecond list
     * the higher number first.
     */
    @Test
    void thirtySavesAllListNewestFirst() {
        Campaign other = new Campaign(0x1234L, "Bob's campaign", 5, "normal", CAMPAIGN_FOR_TESTS.players());
        CampaignStore older = new CampaignStore(dir.resolve("old"));
        CampaignStore store = new CampaignStore(dir.resolve("campaigns"), dir.resolve("old"));
        for (int n = 15; n >= 1; n--) { // written newest first, so no listing order comes from the writing
            (n <= 4 ? older : store).write(record(n, n % 3 == 0 ? CampaignRecord.Kind.AUTO : CampaignRecord.Kind.SAVE, 1_000L * n));
            long bobTime = n == 14 ? 15_000 : 1_000L * n + 500; // Bob's 14 in the millisecond of Ann's 15
            store.write(new CampaignRecord(other, n, CampaignRecord.Kind.AUTO, bobTime, 1, "b" + n + ".save", 1, true));
        }
        List<String> listed = store.all().stream().map(r -> (r.campaign().id() == other.id() ? "Bob " : "Ann ") + r.number()).toList();
        assertEquals(30, listed.size(), "every save, not the first few");
        List<String> want = new java.util.ArrayList<>(List.of("Bob 15", "Ann 15", "Bob 14", "Ann 14"));
        for (int n = 13; n >= 1; n--) {
            want.add("Bob " + n);
            want.add("Ann " + n);
        }
        assertEquals(want, listed);
    }

    @Test
    void rotationKeepsTheNewestAutosavesAndEverySave() {
        CampaignStore store = new CampaignStore(dir);
        store.write(record(1, CampaignRecord.Kind.SAVE, 100));
        for (int n = 2; n <= 6; n++)
            store.write(record(n, CampaignRecord.Kind.AUTO, 100 + n));
        List<CampaignRecord> gone = store.rotate(CAMPAIGN_FOR_TESTS.id(), 2, CampaignStore.NO_SAVE);
        assertEquals(List.of(4, 3, 2), gone.stream().map(CampaignRecord::number).toList());
        assertEquals(List.of(6, 5, 1), store.all().stream().map(CampaignRecord::number).toList());
    }

    /**
     * SOS-229: autosaves one PC couldn't make, as many as the slots and more, never rotate out the newest autosave every
     * PC made; it is the one past the newest slots, and the next good one takes its place.
     */
    @Test
    void failedAutosavesNeverRotateOutTheNewestOneEveryPcMade() {
        CampaignStore store = new CampaignStore(dir);
        int good = CampaignStore.NO_SAVE;
        for (int n = 1; n <= 3; n++) { // three good autosaves, 3 slots
            store.write(record(n, CampaignRecord.Kind.AUTO, 100 + n));
            store.rotate(CAMPAIGN_FOR_TESTS.id(), 3, good);
            good = n;
        }
        for (int n = 4; n <= 9; n++) { // six that another PC couldn't make
            store.write(record(n, CampaignRecord.Kind.AUTO, 100 + n));
            store.rotate(CAMPAIGN_FOR_TESTS.id(), 3, good);
        }
        assertEquals(List.of(9, 8, 7, 3), store.all().stream().map(CampaignRecord::number).toList(),
                "the slots hold the failed ones, and save 3 stays: one file more");
        store.write(record(10, CampaignRecord.Kind.AUTO, 110));
        List<CampaignRecord> gone = store.rotate(CAMPAIGN_FOR_TESTS.id(), 3, good);
        assertEquals(List.of(7), gone.stream().map(CampaignRecord::number).toList());
        good = 10;
        store.write(record(11, CampaignRecord.Kind.AUTO, 111));
        gone = store.rotate(CAMPAIGN_FOR_TESTS.id(), 3, good);
        assertEquals(List.of(8, 3), gone.stream().map(CampaignRecord::number).toList(), "a newer good one: the old one goes");
        assertEquals(List.of(11, 10, 9), store.all().stream().map(CampaignRecord::number).toList());
    }
}
