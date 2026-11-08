package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TcpNodeTest {

    private TcpNode host;
    private TcpNode joiner;

    @AfterEach
    void closeAll() {
        if (joiner != null)
            joiner.close();
        if (host != null)
            host.close();
    }

    private static void waitFor(String what, long ms, BooleanSupplier ok) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!ok.getAsBoolean()) {
            if (System.currentTimeMillis() > end)
                fail("timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    /** Drains events, failing on any but Connected: heartbeats never reach the game. */
    private static void drain(Node n) {
        Node.Event e;
        while ((e = n.poll()) != null)
            assertTrue(e instanceof Node.Event.Connected, "only the connection, no heartbeat: " + e);
    }

    /**
     * Two idle connections stay heard through the heartbeats, which measure the round trip; a test outage on one side
     * makes the other hear the silence, and it ends with the outage (SOS-177).
     */
    @Test
    void heartbeatsKeepAnIdleConnectionHeardAndATestOutageSilencesIt() throws Exception {
        host = TcpNode.listen(0);
        joiner = TcpNode.connect("127.0.0.1", host.port());
        waitFor("round trips both ways", 5_000, () -> host.rttMs(0) >= 0 && joiner.rttMs(Node.HOST) >= 0);
        Thread.sleep(2_500); // idle: only heartbeats
        drain(host);
        drain(joiner);
        assertTrue(host.silentMs(0) < 1_500, "the joiner heard: " + host.silentMs(0));
        assertTrue(joiner.silentMs(Node.HOST) < 1_500, "the host heard: " + joiner.silentMs(Node.HOST));
        assertTrue(host.rttMs(0) < 1_000, "a loopback round trip: " + host.rttMs(0));

        joiner.silence(3);
        waitFor("the host hearing the silence", 3_000, () -> host.silentMs(0) >= 2_000);
        assertTrue(joiner.silentMs(Node.HOST) >= 2_000, "nor the joiner the host: " + joiner.silentMs(Node.HOST));
        waitFor("the silence over", 4_000, () -> host.silentMs(0) < 1_500 && joiner.silentMs(Node.HOST) < 1_500);
        drain(host);
        drain(joiner);
        assertEquals(0, host.silentMs(9), "a peer it doesn't know");
        assertFalse(host.rttMs(0) < 0);
    }
}
