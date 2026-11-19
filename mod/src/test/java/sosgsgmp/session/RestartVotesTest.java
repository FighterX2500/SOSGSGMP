package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.net.Message;
import sosgsgmp.net.Protocol;

/**
 * The host's restart votes (spec §8, SOS-231): one at a time, each opening and result announced once, in the host's
 * order, and a vote whose battle can't restart any more called off.
 */
class RestartVotesTest {

    private static final List<Integer> PLAYERS = List.of(0, 4, 7);
    private static final long T0 = 50_000;

    @Test
    void aVoteOpensAndPassesOnTheAnswerThatMakesTheMajority() throws Exception {
        RestartVotes votes = new RestartVotes();
        Message.RestartVote open = votes.call(4, 99, PLAYERS, T0);
        assertEquals(Message.RestartVote.OPEN, open.state());
        assertEquals(4, open.callerId());
        assertEquals(99, open.battle());
        assertEquals(1, open.yes());
        assertEquals(3, open.voters());
        assertNotNull(votes.open());
        assertNull(votes.answer(0, open.vote(), false, PLAYERS, T0 + 10), "1 to 1: open still");
        Message.RestartVote passed = votes.answer(7, open.vote(), true, PLAYERS, T0 + 20);
        assertEquals(Message.RestartVote.PASSED, passed.state());
        assertEquals(2, passed.yes());
        assertEquals(open.vote(), passed.vote());
        assertNull(votes.open(), "closed");
        assertEquals(passed, Protocol.decode(Protocol.encode(passed)), "as every PC hears it");
        assertNull(votes.answer(0, open.vote(), true, PLAYERS, T0 + 30), "a late answer changes nothing");
    }

    @Test
    void oneVoteAtATime() {
        RestartVotes votes = new RestartVotes();
        Message.RestartVote first = votes.call(0, 99, PLAYERS, T0);
        Message.RestartVote second = votes.call(7, 98, PLAYERS, T0 + 1);
        assertEquals(Message.RestartVote.REFUSED, second.state(), "for its caller only");
        assertEquals(7, second.callerId());
        assertEquals("Another restart vote is open.", second.reason());
        assertEquals(99, votes.open().battle(), "the first stays open");
        votes.answer(4, first.vote(), false, PLAYERS, T0 + 2);
        votes.answer(7, first.vote(), false, PLAYERS, T0 + 3);
        Message.RestartVote third = votes.call(7, 98, PLAYERS, T0 + 4);
        assertEquals(Message.RestartVote.OPEN, third.state(), "once the first is over; no limit on votes");
        assertEquals(first.vote() + 1, third.vote());
    }

    @Test
    void theTimeRunsOutWithTheSilentCountedAsNo() {
        RestartVotes votes = new RestartVotes();
        votes.call(0, 99, PLAYERS, T0);
        assertNull(votes.check(PLAYERS, T0 + RestartTally.ANSWER_MS - 1));
        Message.RestartVote failed = votes.check(PLAYERS, T0 + RestartTally.ANSWER_MS);
        assertEquals(Message.RestartVote.FAILED, failed.state());
        assertEquals(1, failed.yes());
        assertEquals(3, failed.voters());
        assertNull(votes.check(PLAYERS, T0 + 2 * RestartTally.ANSWER_MS), "announced once");
    }

    @Test
    void aVoterWhoLeavesNoLongerCounts() {
        RestartVotes votes = new RestartVotes();
        Message.RestartVote open = votes.call(0, 99, List.of(0, 4, 7, 9), T0);
        assertNull(votes.check(List.of(0, 4, 7, 9), T0 + 1), "1 of 4");
        assertNull(votes.check(List.of(0, 4), T0 + 2), "1 of the 2 still here: a tie so far");
        Message.RestartVote passed = votes.answer(4, open.vote(), true, List.of(0, 4), T0 + 3);
        assertEquals(Message.RestartVote.PASSED, passed.state());
        assertEquals(2, passed.voters());
    }

    @Test
    void aBattleThatEndedCallsTheVoteOff() {
        RestartVotes votes = new RestartVotes();
        assertNull(votes.cancel("That battle is over."), "no vote open");
        votes.call(0, 99, PLAYERS, T0);
        Message.RestartVote off = votes.cancel("That battle is over.");
        assertEquals(Message.RestartVote.CANCELLED, off.state());
        assertEquals("That battle is over.", off.reason());
        assertNull(votes.open());
    }

    @Test
    void everyPcTakesWhatItHeardOnceInOrder() {
        RestartVotes votes = new RestartVotes();
        Message.RestartVote a = new Message.RestartVote(1, 0, 99, Message.RestartVote.OPEN, 1, 3, "");
        Message.RestartVote b = new Message.RestartVote(1, 0, 99, Message.RestartVote.FAILED, 1, 3, "");
        assertEquals(0, votes.heardOpen());
        votes.heard(a);
        assertEquals(1, votes.heardOpen(), "the vote this PC's player answers");
        votes.heard(new Message.RestartVote(0, 4, 98, Message.RestartVote.REFUSED, 0, 0, "Another restart vote is open."));
        assertEquals(1, votes.heardOpen(), "another player's refused call changes nothing");
        votes.heard(b);
        assertEquals(0, votes.heardOpen(), "over");
        assertEquals(a, votes.take());
        votes.take();
        assertEquals(b, votes.take());
        assertNull(votes.take());
    }
}
