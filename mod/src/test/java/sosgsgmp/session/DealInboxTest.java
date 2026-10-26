package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.session.DealInbox.Entry;

/** SOS-72: each proposal to this player pops up once, each answer to their own proposals shows once. */
class DealInboxTest {

    @Test
    void aProposalToMePopsUpUntilShownThenTheNext() {
        DealInbox in = new DealInbox(2);
        in.nextProposal(List.of(), List.of()); // the first look: nothing yet
        List<Entry> open = List.of(new Entry(5, 1, 0), new Entry(6, 1, 2), new Entry(7, 0, 2));
        assertEquals(new Entry(6, 1, 2), in.nextProposal(open, List.of()), "5 is to slot 0");
        assertEquals(new Entry(6, 1, 2), in.nextProposal(open, List.of()), "still unshown");
        in.shown(6);
        assertEquals(new Entry(7, 0, 2), in.nextProposal(open, List.of()));
        in.shown(7);
        assertNull(in.nextProposal(open, List.of()));
    }

    @Test
    void eachAnswerToMyProposalsShowsOnce() {
        DealInbox in = new DealInbox(1);
        in.newAnswers(List.of());
        List<Entry> answers = List.of(new Entry(3, 1, 2), new Entry(4, 2, 1));
        assertEquals(List.of(new Entry(3, 1, 2)), in.newAnswers(answers), "4 was slot 2's proposal");
        assertEquals(List.of(), in.newAnswers(answers));
    }

    @Test
    void anOldAnswerIsNoNewsButAnOpenProposalStillPopsUp() {
        DealInbox in = new DealInbox(1);
        List<Entry> open = List.of(new Entry(8, 2, 1));
        List<Entry> answers = List.of(new Entry(3, 1, 2));
        assertEquals(List.of(), in.newAnswers(answers), "an answer from before the load");
        assertEquals(new Entry(8, 2, 1), in.nextProposal(open, answers), "a resume's open proposal can still be answered");
    }
}
