package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.lobby.LobbyPlayer;

class ChatTest {

    private static final List<LobbyPlayer> PLAYERS = List.of(new LobbyPlayer(4, "Ann", true), new LobbyPlayer(9, "Bob", false));
    private static final long SECOND = 1_000_000_000L;

    @Test
    void cleaningDropsWhatTheGameCantDrawAndKeepsOtherLanguages() {
        assertEquals("hello", Chat.clean("  he\tl\nlo\u0000\u007F  "));
        assertEquals("ab", Chat.clean("a￿b"), "U+FFFF is past the end of the game's font table");
        assertEquals("ab", Chat.clean("a😀b"));
        assertEquals("Привет", Chat.clean("Привет"));
        assertEquals("", Chat.clean(" \n "));
    }

    @Test
    void aLongMessageIsCutAtTheLimit() {
        assertEquals(Chat.MAX_TEXT, Chat.clean("x".repeat(Chat.MAX_TEXT * 3)).length());
    }

    @Test
    void aLineCarriesItsSpeakerAndTheirPlace() {
        Chat c = new Chat();
        c.heard(9, PLAYERS, "hi");
        assertEquals(new Chat.Line(9, "Bob", 1, "hi"), c.line(0));
    }

    @Test
    void aPlayerNotInTheSessionAndAnEmptyMessageAddNothing() {
        Chat c = new Chat();
        c.heard(5, PLAYERS, "hi");
        c.heard(4, PLAYERS, "\n");
        assertEquals(0, c.size());
        assertEquals(0, c.added());
    }

    @Test
    void theOldestLinesGoPastTheLimit() {
        Chat c = new Chat();
        for (int i = 0; i < Chat.MAX_LINES + 3; i++)
            c.heard(4, PLAYERS, "m" + i);
        assertEquals(Chat.MAX_LINES, c.size());
        assertEquals("m3", c.line(0).text());
        assertEquals("m" + (Chat.MAX_LINES + 2), c.line(c.size() - 1).text());
        assertEquals(Chat.MAX_LINES + 3, c.added());
    }

    @Test
    void aBurstIsLimitedAndTheSenderIsTold() {
        Chat c = new Chat();
        for (int i = 0; i < Chat.BURST; i++)
            assertEquals("m", c.outgoing("m", i));
        assertNull(c.outgoing("m", Chat.BURST));
        assertEquals(Chat.NOTICE, c.line(0).playerId());
        assertEquals("m", c.outgoing("m", Chat.BURST_NANOS), "the oldest of the burst has aged out");
        assertNull(c.outgoing("m", Chat.BURST_NANOS), "the next oldest hasn't");
    }

    @Test
    void anEmptyMessageDoesntCountAgainstTheLimit() {
        Chat c = new Chat();
        for (int i = 0; i < Chat.BURST * 2; i++)
            assertNull(c.outgoing("  ", i * SECOND));
        assertEquals("m", c.outgoing("m", 0));
        assertEquals(0, c.size());
    }

    @Test
    void sendingTooFastSaysSoOnceWhileNothingElseIsSaid() {
        Chat c = new Chat();
        for (int i = 0; i < Chat.BURST; i++)
            c.outgoing("m", 0);
        assertNull(c.outgoing("m", 0));
        assertNull(c.outgoing("m", 0));
        assertEquals(1, c.size());
    }

    @Test
    void aRosterChangeSaysWhoJoinedOrLeftAndTheFirstSaysNothing() {
        Chat c = new Chat();
        c.roster(List.of(PLAYERS.get(0)));
        assertEquals(0, c.size());
        c.roster(PLAYERS);
        c.roster(List.of(PLAYERS.get(1)));
        assertEquals(PLAYERS.get(1).name() + " joined.", c.line(0).text());
        assertEquals(PLAYERS.get(0).name() + " left.", c.line(1).text());
    }
}
