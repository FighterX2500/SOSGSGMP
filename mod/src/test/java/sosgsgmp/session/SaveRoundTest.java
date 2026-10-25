package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.campaign.CampaignStoreTest;
import sosgsgmp.campaign.SaveOrder;

class SaveRoundTest {

    private static final SaveOrder ORDER = new SaveOrder(4, 1260, CampaignRecord.Kind.SAVE, 0, CampaignStoreTest.CAMPAIGN_FOR_TESTS, false);

    @Test
    void completeWhenEveryPcReportedOnce() {
        SaveRound r = new SaveRound(ORDER, 0, List.of(0, 1, 2));
        assertTrue(r.report(1, 7, null));
        assertFalse(r.report(1, 7, null), "repeated");
        assertFalse(r.report(5, 7, null), "not a player");
        assertFalse(r.complete());
        assertTrue(r.report(0, 7, null));
        assertTrue(r.report(2, 7, null));
        assertTrue(r.complete());
        assertEquals("", r.outcome(id -> "P" + id));
    }

    @Test
    void theOutcomeNamesFailuresAndWorldsThatDiffer() {
        SaveRound r = new SaveRound(ORDER, 0, List.of(0, 1, 2));
        r.report(0, 7, null);
        r.report(1, 8, null);
        r.report(2, 0, "past the save step");
        assertTrue(r.complete());
        assertEquals("P1's world differs from the host's\nP2 couldn't save: past the save step", r.outcome(id -> "P" + id));
    }
}
