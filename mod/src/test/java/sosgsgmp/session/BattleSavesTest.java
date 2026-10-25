package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class BattleSavesTest {

    private static byte[] save(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++)
            b[i] = (byte) (i * 31 + seed);
        return b;
    }

    @Test
    void aSaveInAnyPiecesComesOutWholeOnceForItsStep() {
        byte[] s = save(200_000, 7);
        byte[] stream = BattleSaves.frame(1200, s);
        BattleSaves saves = new BattleSaves();
        for (int from = 0; from < stream.length; from += 60_000)
            saves.in(2, Arrays.copyOfRange(stream, from, Math.min(stream.length, from + 60_000)));
        assertNull(saves.take(1199), "another battle's step");
        assertArrayEquals(s, saves.take(1200));
        assertNull(saves.take(1200), "taken once");
    }

    @Test
    void theBattlePcLoadsItsOwnAndTheNewestBattleWins() {
        BattleSaves saves = new BattleSaves();
        saves.own(900, save(10, 1));
        byte[] later = save(20, 2);
        byte[] both = new byte[0];
        for (byte[] r : new byte[][] {BattleSaves.frame(600, save(5, 3)), BattleSaves.frame(1500, later)}) {
            byte[] b = Arrays.copyOf(both, both.length + r.length);
            System.arraycopy(r, 0, b, both.length, r.length);
            both = b;
        }
        saves.in(1, both);
        assertNull(saves.take(900), "an older battle's save is gone");
        assertArrayEquals(later, saves.take(1500));
    }

    @Test
    void aBrokenRecordIsRefused() {
        BattleSaves saves = new BattleSaves();
        byte[] bad = BattleSaves.frame(30, save(4, 0));
        bad[8] = (byte) 0x7F; // a length far over any save
        assertThrows(IllegalArgumentException.class, () -> saves.in(1, bad));
        assertThrows(IllegalArgumentException.class, () -> BattleSaves.frame(-1, save(4, 0)));
    }
}
