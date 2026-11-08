package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LivenessTest {

    @Test
    void aHeartbeatIsDueOnceASecond() {
        Liveness l = new Liveness();
        l.add(1, 1_000);
        assertNotNull(l.due(1, 1_000, 0), "the first at once");
        assertNull(l.due(1, 1_500, 0));
        assertNotNull(l.due(1, 2_000, 0));
        assertNull(l.due(2, 2_000, 0), "nothing for a peer it doesn't know");
    }

    @Test
    void anythingHeardEndsTheSilence() {
        Liveness l = new Liveness();
        l.add(1, 1_000);
        assertEquals(4_000, l.silentMs(1, 5_000));
        l.heard(1, 5_000);
        assertEquals(0, l.silentMs(1, 5_000));
        l.beat(1, new Message.Heartbeat(9, 0, -1, 0), 6_000);
        assertEquals(500, l.silentMs(1, 6_500));
        assertEquals(0, l.silentMs(7, 6_500), "a peer it doesn't know isn't silent");
        l.remove(1);
        assertEquals(0, l.silentMs(1, 9_000));
    }

    /** A sends at 1000; B hears it, holds it 30 ms and echoes it; A hears the echo at 1100: 70 ms there and back. */
    @Test
    void theEchoGivesTheRoundTrip() {
        Liveness a = new Liveness();
        Liveness b = new Liveness();
        a.add(1, 0);
        b.add(1, 0);
        Message.Heartbeat fromA = a.due(1, 1_000, 0);
        assertEquals(-1, fromA.heldMs(), "nothing to echo yet");
        assertEquals(-1, a.rttMs(1));
        b.beat(1, fromA, 50_035); // B's clock is its own
        Message.Heartbeat fromB = b.due(1, 50_065, 0);
        assertEquals(1_000, fromB.echoMs());
        assertEquals(30, fromB.heldMs());
        a.beat(1, fromB, 1_100);
        assertEquals(70, a.rttMs(1));
    }

    @Test
    void aTestOutageStopsTheBeatsBothWays() {
        Liveness l = new Liveness();
        l.add(1, 0);
        l.silence(10_000);
        assertNull(l.due(1, 5_000, 0), "nothing goes");
        l.heard(1, 5_000);
        l.beat(1, new Message.Heartbeat(1, 0, -1, 0), 6_000);
        assertEquals(9_000, l.silentMs(1, 9_000), "nothing counts");
        assertNotNull(l.due(1, 10_000, 0));
        l.heard(1, 10_000);
        assertEquals(0, l.silentMs(1, 10_000));
    }
}
