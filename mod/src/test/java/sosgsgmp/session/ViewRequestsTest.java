package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.net.Message;
import sosgsgmp.net.Protocol;

/**
 * City viewing's request flow (spec §10, SOS-82): the host at slot 0, Bob at 1 and Cid at 2, each message going
 * through the protocol's bytes as on the network, delivered when {@link #flush} runs.
 */
class ViewRequestsTest {

    private long now;
    /** Messages on the way: from slot, to slot, bytes. */
    private final ArrayDeque<Object[]> wire = new ArrayDeque<>();
    private final ViewRequests[] pcs = new ViewRequests[3];

    ViewRequestsTest() {
        pcs[0] = new ViewRequests(true, 0, 0, (to, m) -> wire.add(new Object[] {0, to, Protocol.encode(m)}), () -> now);
        for (int slot = 1; slot < pcs.length; slot++) {
            int from = slot;
            pcs[slot] = new ViewRequests(false, slot, 0, (to, m) -> wire.add(new Object[] {from, to, Protocol.encode(m)}), () -> now);
        }
    }

    private ViewRequests host() {
        return pcs[0];
    }

    /** Delivers everything on the way, and what that sends, as the network would; every message is a fit one. */
    private void flush() {
        Object[] w;
        while ((w = wire.poll()) != null) {
            Message m;
            try {
                m = Protocol.decode((byte[]) w[2]);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            assertNull(pcs[(Integer) w[1]].received((Integer) w[0], m), m.toString());
        }
    }

    /** The host's checks pass (the engine would begin the view), and its begins begin. */
    private void hostAllowsEverything() {
        ViewRequests.Pair p;
        while ((p = host().takeCheck()) != null)
            host().checked(p, null);
        flush();
    }

    private void hostBegins() {
        ViewRequests.Pair p;
        while ((p = host().takeBegin()) != null)
            host().begun(p, null);
        flush();
    }

    @Test
    void anAllowedAskBeginsTheViewAndTheCityAndHashesReachOnlyTheViewer() {
        pcs[1].ask(2);
        assertThrows(IllegalStateException.class, () -> pcs[1].ask(2), "one ask at a time");
        flush();
        assertEquals(new ViewRequests.Pair(1, 2), host().takeCheck());
        assertNull(pcs[2].ask(), "not passed on before the host's check");
        host().checked(new ViewRequests.Pair(1, 2), null);
        flush();
        assertEquals(1, pcs[2].ask().viewer());
        pcs[2].answer(1, true);
        flush();
        assertNull(pcs[1].takeAnswer(), "the viewer hears once the view began");
        assertEquals(new ViewRequests.Pair(1, 2), host().takeBegin());
        host().begun(new ViewRequests.Pair(1, 2), null);
        flush();
        Message.ViewAnswer a = pcs[1].takeAnswer();
        assertTrue(a.allowed());
        assertEquals(-1, pcs[1].asked());

        byte[] city = new byte[Protocol.MAX_STREAM_PIECE * 2 + 5];
        for (int i = 0; i < city.length; i++)
            city[i] = (byte) (i * 3);
        pcs[2].city(1, 900, city);
        pcs[2].hashes(1, new byte[] {7, 8});
        pcs[2].hashes(1, new byte[] {9});
        flush();
        assertNull(pcs[1].takeCity(930), "another view's step");
        assertArrayEquals(city, pcs[1].takeCity(900), "whole and in order");
        assertNull(pcs[1].takeCity(900), "taken once");
        assertArrayEquals(new byte[] {7, 8, 9}, pcs[1].takeHashes());
        assertEquals(0, pcs[1].takeHashes().length);
        assertNull(host().takeCity(900), "the host passes it on only");

        pcs[1].close(ViewRequests.CLOSED);
        flush();
        assertEquals(new ViewRequests.End(1, ViewRequests.CLOSED), host().takeEnd());
        assertNull(host().takeEnd());
    }

    @Test
    void anAssaultsViewIsAskedByNoOneAndItsCityStillReachesTheViewer() {
        host().opened(1, 0);
        host().opened(2, 0);
        host().city(1, 900, new byte[] {1, 2, 3});
        host().hashes(1, new byte[] {7});
        flush();
        assertArrayEquals(new byte[] {1, 2, 3}, pcs[1].takeCity(900));
        assertArrayEquals(new byte[] {7}, pcs[1].takeHashes());
        assertNull(pcs[2].takeCity(900), "only the viewer it is for");
    }

    private void hashesArrive(byte... bytes) {
        assertNull(pcs[1].received(0, new Message.ViewHashes(1, bytes)));
    }

    @Test
    void hashesThatArriveWhileTheViewLoadsWaitForIt() {
        hashesArrive((byte) 1, (byte) 2); // over a slow link they come right behind the city
        assertEquals(0, pcs[1].takeHashes(true, false).length);
        hashesArrive((byte) 3);
        assertArrayEquals(new byte[] {1, 2, 3}, pcs[1].takeHashes(true, true), "the first piece holds the header");
    }

    @Test
    void hashesLeftFromAnEndedViewDontReachTheNextOne() {
        hashesArrive((byte) 1);
        assertEquals(0, pcs[1].takeHashes(false, false).length);
        hashesArrive((byte) 2);
        assertArrayEquals(new byte[] {2}, pcs[1].takeHashes(true, true));
    }

    @Test
    void aRefusalOrNoAnswerIn30SecondsReachesTheViewer() {
        pcs[1].ask(2);
        flush();
        hostAllowsEverything();
        pcs[2].answer(1, false);
        flush();
        assertEquals(ViewRequests.REFUSED, pcs[1].takeAnswer().reason());
        assertNull(host().takeBegin());

        now = 1_000;
        pcs[1].ask(2);
        flush();
        hostAllowsEverything();
        now += ViewRequests.ANSWER_NANOS - 1;
        assertEquals(List.of(), pcs[2].expire());
        now++;
        assertEquals(List.of(1), pcs[2].expire());
        assertNull(pcs[2].ask());
        flush();
        Message.ViewAnswer a = pcs[1].takeAnswer();
        assertFalse(a.allowed());
        assertEquals(ViewRequests.NO_ANSWER, a.reason());
        pcs[2].answer(1, true); // a click after the timeout changes nothing
        flush();
        assertNull(host().takeBegin());
    }

    @Test
    void theHostRefusesAtOnceWhatTheEngineWouldCancelOrAnotherAskHolds() {
        pcs[1].ask(2);
        flush();
        ViewRequests.Pair p = host().takeCheck();
        host().checked(p, "Its player is viewing another city.");
        flush();
        assertNull(pcs[2].ask(), "never reaches the owner");
        assertEquals("Its player is viewing another city.", pcs[1].takeAnswer().reason());

        pcs[1].ask(2);
        flush();
        host().ask(2); // the host asks the same owner while Bob's ask waits
        Message.ViewAnswer busy = host().takeAnswer();
        assertFalse(busy.allowed());
        assertEquals(-1, host().asked());
        assertEquals(new ViewRequests.Pair(1, 2), host().takeCheck());
        assertNull(host().takeCheck(), "the host's own ask went no further");
    }

    @Test
    void theHostAsViewerOrOwnerTakesNoDetourThroughTheNetwork() {
        host().ask(1);
        assertTrue(wire.isEmpty(), "checked on the host first");
        hostAllowsEverything();
        assertEquals(0, pcs[1].ask().viewer());
        pcs[1].answer(0, true);
        flush();
        hostBegins();
        assertTrue(host().takeAnswer().allowed());
        pcs[1].city(0, 60, new byte[] {1, 2, 3});
        flush();
        assertArrayEquals(new byte[] {1, 2, 3}, host().takeCity(60));

        pcs[2].ask(0);
        flush();
        hostAllowsEverything();
        assertEquals(2, host().ask().viewer(), "the host's own popup");
        host().answer(2, true);
        hostBegins();
        assertTrue(pcs[2].takeAnswer().allowed());
        host().city(2, 90, new byte[0]);
        host().hashes(2, new byte[] {4});
        flush();
        assertArrayEquals(new byte[0], pcs[2].takeCity(90), "an empty city is whole too");
        assertArrayEquals(new byte[] {4}, pcs[2].takeHashes());
    }

    /** Spec section 8: a running NPC battle is watched without asking, and its tape waits while the watcher loads. */
    @Test
    void aBattleIsWatchedWithoutAskingAndItsTapeComesInWholePieces() {
        pcs[1].ask(2);
        flush();
        ViewRequests.Pair p = host().takeCheck();
        host().checkedNoAsk(p);
        assertNull(pcs[2].ask(), "the fighting player isn't asked");
        assertEquals(p, host().takeBegin());
        host().begun(p, null);
        flush();
        pcs[2].tape(1, new byte[] {1, 2});
        pcs[2].tape(1, new byte[] {3});
        flush();
        assertArrayEquals(new byte[] {1, 2}, pcs[1].takeTape());
        assertArrayEquals(new byte[] {3}, pcs[1].takeTape());
        assertNull(pcs[1].takeTape());
        pcs[2].tape(1, new byte[] {4});
        flush();
        pcs[1].dropTape();
        assertNull(pcs[1].takeTape(), "an older watch's tape goes");
    }

    @Test
    void messagesFromTheWrongPcBreakTheProtocol() {
        assertNotNull(host().received(1, new Message.ViewAsk(2, 0)), "Bob asks as Cid");
        assertNotNull(host().received(1, new Message.ViewAnswer(2, 0, true, "")), "Bob answers for the host");
        assertNotNull(host().received(1, new Message.ViewClose(2, 0)), "Bob closes Cid's view");
        assertNotNull(host().received(1, new Message.ViewCity(2, 30, 1, 0, new byte[1])), "a city for no view");
        assertNotNull(host().received(1, new Message.ViewHashes(2, new byte[1])), "hashes for no view");
        assertNotNull(host().received(1, new Message.ViewTape(2, new byte[1])), "a tape for no view");
        assertNotNull(pcs[1].received(2, new Message.ViewHashes(1, new byte[1])), "not from the host");
        assertNotNull(pcs[1].received(0, new Message.ViewCity(1, 30, 4, 2, new byte[1])), "a city that didn't start");
        assertNull(pcs[1].received(0, new Message.ViewAnswer(1, 2, true, "")), "an answer to no ask: too late, dropped");
        assertNull(pcs[1].takeAnswer());
    }

    /** Spec §11: a campaign save waits for views, so none may start under it; the allowed one starts after. */
    @Test
    void anAllowedViewWaitsOutACampaignSave() {
        pcs[1].ask(2);
        flush();
        host().checked(host().takeCheck(), null);
        flush();
        host().holdBegins(true);
        pcs[2].answer(1, true);
        flush();
        assertNull(host().takeBegin(), "no view begins under the save");
        assertNull(pcs[1].takeAnswer(), "the viewer still waits");
        host().holdBegins(false);
        ViewRequests.Pair begin = host().takeBegin();
        assertEquals(new ViewRequests.Pair(1, 2), begin);
        host().begun(begin, null);
        flush();
        assertTrue(pcs[1].takeAnswer().allowed());
    }

    /** A save that ends the session closes the views in its way: the same end as the viewer's own Close. */
    @Test
    void theHostCanEndAView() {
        host().end(1);
        assertEquals(new ViewRequests.End(1, ViewRequests.CLOSED), host().takeEnd());
        assertNull(host().takeEnd());
    }

    @Test
    void aPlayerWhoLeavesEndsTheirAsksAndViews() {
        pcs[1].ask(2);
        flush();
        hostAllowsEverything();
        host().gone(2);
        flush();
        assertEquals(ViewRequests.LEFT, pcs[1].takeAnswer().reason());

        pcs[1].ask(0);
        flush();
        hostAllowsEverything();
        host().answer(1, true);
        hostBegins();
        assertTrue(pcs[1].takeAnswer().allowed());
        host().gone(1);
        assertEquals(new ViewRequests.End(1, ViewRequests.CLOSED), host().takeEnd(), "Bob's view ends on every PC");
    }
}
