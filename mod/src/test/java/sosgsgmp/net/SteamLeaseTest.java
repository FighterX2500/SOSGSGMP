package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SteamLeaseTest {

    /** A Steam that records what the lease did to it. */
    private static final class Recorded implements SteamLease.Steam {
        final List<String> calls = new ArrayList<>();

        @Override
        public void begin(boolean dropStale) {
            calls.add(dropStale ? "begin, drop stale" : "begin");
        }

        @Override
        public void end() {
            calls.add("end");
        }
    }

    @Test
    void steamOpensOnceAndIsNeverShutDownBetweenNodes() {
        int[] opened = new int[1];
        Recorded steam = new Recorded();
        AtomicLong now = new AtomicLong(1_000);
        SteamLease<Recorded> lease = new SteamLease<>(() -> {
            opened[0]++;
            return steam;
        }, 1_000, 2_000, now::get);

        Recorded first = lease.acquire();
        lease.release(first);
        now.addAndGet(500); // the next screen's node, at once
        Recorded second = lease.acquire();
        lease.release(second);

        assertSame(first, second);
        assertEquals(1, opened[0], "Steam is initialized once per process");
        assertEquals(List.of("begin", "end", "begin", "end"), steam.calls, "a quick handover keeps what Steam reported meanwhile");
    }

    @Test
    void callbacksThatWaitedLongWithoutANodeAreDropped() {
        Recorded steam = new Recorded();
        AtomicLong now = new AtomicLong(1_000);
        SteamLease<Recorded> lease = new SteamLease<>(() -> steam, 1_000, 2_000, now::get);

        lease.release(lease.acquire());
        now.addAndGet(60_000); // a single-player game in between
        lease.acquire();

        assertEquals("begin, drop stale", steam.calls.get(2));
    }

    @Test
    void aSecondNodeWaitsForTheFirstToLetGo() throws Exception {
        Recorded steam = new Recorded();
        SteamLease<Recorded> lease = new SteamLease<>(() -> steam, 5_000, 2_000, System::currentTimeMillis);
        Recorded held = lease.acquire();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lease.release(held);
        });
        releaser.start();

        lease.acquire(); // returns once the other thread let go
        releaser.join();

        assertEquals(List.of("begin", "end", "begin"), steam.calls);
    }

    @Test
    void aNodeThatNeverLetsGoMakesTheNextOneFailWithAReason() {
        SteamLease<Recorded> lease = new SteamLease<>(Recorded::new, 50, 2_000, System::currentTimeMillis);
        lease.acquire();

        IllegalStateException e = assertThrows(IllegalStateException.class, lease::acquire);
        assertTrue(e.getMessage().contains("still in use"), e.getMessage());
    }

    @Test
    void aSteamThatCannotOpenIsTriedAgainByTheNextNode() {
        int[] tries = new int[1];
        Recorded steam = new Recorded();
        SteamLease<Recorded> lease = new SteamLease<>(() -> {
            if (++tries[0] == 1)
                throw new IllegalStateException("Steam didn't start");
            return steam;
        }, 1_000, 2_000, System::currentTimeMillis);

        assertThrows(IllegalStateException.class, lease::acquire);
        assertSame(steam, lease.acquire(), "Steam started meanwhile: the next node opens it");
    }

    @Test
    void releasingWhatWasNotLentIsAProgrammerError() {
        SteamLease<Recorded> lease = new SteamLease<>(Recorded::new, 1_000, 2_000, System::currentTimeMillis);
        assertThrows(IllegalStateException.class, () -> lease.release(new Recorded()));
    }
}
