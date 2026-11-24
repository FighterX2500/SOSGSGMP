package sosgsgmp.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EventQueueTest {

    @Test
    void recordsComeOutInOrderWithTheirFields() {
        EventQueue q = new EventQueue(4);
        q.put(10, 1, 2, 3, 4, 5);
        q.put(11, 6, 7, 8, 9, -1);
        assertEquals(2, q.size());
        assertTrue(q.take());
        assertEquals(10, q.step());
        assertEquals(1, q.kind());
        assertEquals(2, q.a());
        assertEquals(3, q.b());
        assertEquals(4, q.c());
        assertEquals(5, q.d());
        assertTrue(q.take());
        assertEquals(11, q.step());
        assertEquals(-1, q.d());
        assertFalse(q.take());
    }

    @Test
    void aFullQueueDropsTheNewestAndCountsThem() {
        EventQueue q = new EventQueue(2);
        assertTrue(q.put(1, 1, 0, 0, 0, 0));
        assertTrue(q.put(2, 1, 0, 0, 0, 0));
        assertFalse(q.put(3, 1, 0, 0, 0, 0));
        assertFalse(q.put(4, 1, 0, 0, 0, 0));
        assertEquals(2, q.lost());
        q.take();
        assertEquals(1, q.step());
        assertTrue(q.put(5, 1, 0, 0, 0, 0)); // room again, and the order wraps
        q.take();
        assertEquals(2, q.step());
        q.take();
        assertEquals(5, q.step());
        assertFalse(q.take());
    }

    @Test
    void aGetterBeforeAnyTakeIsAProgrammingError() {
        EventQueue q = new EventQueue(1);
        assertThrows(IllegalStateException.class, q::step);
        assertThrows(IllegalArgumentException.class, () -> new EventQueue(0));
    }
}
