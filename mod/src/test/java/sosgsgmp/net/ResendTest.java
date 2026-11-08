package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class ResendTest {

    private static byte[] chat(int i) {
        return Protocol.encode(new Message.Chat(0, "m" + i));
    }

    @Test
    void messagesAreNumberedAndKeptUntilAcknowledged() throws Exception {
        Resend r = new Resend();
        r.add(1);
        byte[] first = r.wrap(1, chat(1));
        r.wrap(1, chat(2));
        r.wrap(1, chat(3));
        assertEquals(new Message.Sequenced(1, new Message.Chat(0, "m1")), Protocol.decode(first));
        assertEquals(3, r.after(1, 0).size());
        long kept = r.keptBytes(1);
        r.acked(1, 2);
        assertEquals(1, r.after(1, 0).size(), "acknowledged ones are no longer kept");
        assertEquals(new Message.Sequenced(3, new Message.Chat(0, "m3")), Protocol.decode(r.after(1, 2).get(0)));
        assertEquals(kept - first.length - Protocol.sequenced(2, chat(2)).length, r.keptBytes(1));
        r.acked(1, 3);
        assertEquals(List.of(), r.after(1, 3));
        assertEquals(0, r.keptBytes(1));
    }

    @Test
    void whatThePeerLacksGoesAgainInOrderAndAClaimPastWhatWasSentIsRefused() throws Exception {
        Resend r = new Resend();
        r.add(4);
        for (int i = 1; i <= 5; i++)
            r.wrap(4, chat(i));
        List<byte[]> again = r.after(4, 2);
        assertEquals(3, again.size());
        for (int i = 0; i < 3; i++)
            assertArrayEquals(Protocol.sequenced(3 + i, chat(3 + i)), again.get(i));
        assertThrows(IllegalArgumentException.class, () -> r.after(4, 6));
        assertThrows(IllegalArgumentException.class, () -> r.wrap(5, chat(1)), "a peer it doesn't know");
    }

    /** Each number is taken once and in order: a repeat changes nothing, and one past a gap waits for the rest. */
    @Test
    void eachMessageIsTakenOnceAndInOrder() {
        Resend r = new Resend();
        r.add(2);
        assertEquals(Resend.Verdict.NEXT, r.in(2, 1));
        assertEquals(Resend.Verdict.REPEAT, r.in(2, 1));
        assertEquals(Resend.Verdict.GAP, r.in(2, 3));
        assertEquals(1, r.received(2), "a gap counts nothing");
        assertEquals(Resend.Verdict.NEXT, r.in(2, 2));
        assertEquals(Resend.Verdict.NEXT, r.in(2, 3));
        assertEquals(Resend.Verdict.REPEAT, r.in(2, 2));
        assertEquals(3, r.received(2));
        assertEquals(0, r.received(9));
        r.remove(2);
        assertEquals(0, r.received(2));
    }
}
