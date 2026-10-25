package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WorldResyncTest {

    @Test
    void theStateAndTheSaveComeBackApart() {
        WorldResync r = WorldResync.unpack(42, new WorldResync(42, new byte[] {1, 2}, new byte[] {3, 4, 5}).pack());
        assertEquals(42, r.step());
        assertArrayEquals(new byte[] {1, 2}, r.state());
        assertArrayEquals(new byte[] {3, 4, 5}, r.save());
    }

    @Test
    void bytesThatArentAResyncThrow() {
        assertThrows(IllegalArgumentException.class, () -> WorldResync.unpack(1, new byte[] {0, 0}), "too short");
        assertThrows(IllegalArgumentException.class, () -> WorldResync.unpack(1, new byte[] {0, 0, 0, 0, 9}), "no state");
        assertThrows(IllegalArgumentException.class, () -> WorldResync.unpack(1, new byte[] {0, 0, 0, 1, 9}), "no save");
        assertThrows(IllegalArgumentException.class, () -> WorldResync.unpack(1, new byte[] {0, 0, 0, 9, 9, 9}), "state past the end");
        assertThrows(IllegalArgumentException.class, () -> new WorldResync(-1, new byte[] {1}, new byte[] {1}));
    }
}
