package sosgsgmp.net;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Test tooling (SOS-175): one direction of one connection under {@link NetConditions}, as a reliable stream behaves.
 * Items leave in the order they came: each waits its delay plus a jitter draw, and never leaves before the item ahead
 * of it, so jitter bunches them. A lost item waits for its resend, and the items behind it wait with it. During an
 * outage nothing leaves; at its end everything due leaves at once.
 *
 * <p>Plain logic with the clock passed in, for the tests; {@link ConditionedNode} owns one per peer and direction.
 */
final class DelayLine<T> {

    /** A lost message's resend comes after at least this long, as TCP's minimum retransmission time. */
    static final long MIN_RESEND_MS = 200;

    private record Entry<T>(T item, long due) {}

    private final long delayMs;
    private final long jitterMs;
    private final double loss;
    private final Random random;
    private final ArrayDeque<Entry<T>> queue = new ArrayDeque<>();
    private long lastDue;
    private long heldUntil;

    private long count, lost, addedTotal, addedMax;

    /** {@code delayMs}: this direction's share of the delay; {@code lossPercent} 0 to 100. */
    DelayLine(long delayMs, long jitterMs, double lossPercent, Random random) {
        if (delayMs < 0 || jitterMs < 0 || lossPercent < 0 || lossPercent > 100)
            throw new IllegalArgumentException("SOSGSGMP: delay line " + delayMs + " ms, jitter " + jitterMs + " ms, loss " + lossPercent + " %");
        this.delayMs = delayMs;
        this.jitterMs = jitterMs;
        this.loss = lossPercent / 100;
        this.random = random;
    }

    /** Queues an item that arrived at {@code nowMs}. */
    void add(T item, long nowMs) {
        long wait = delayMs;
        if (jitterMs > 0)
            wait = Math.max(0, wait + (long) ((random.nextDouble() * 2 - 1) * jitterMs));
        if (loss > 0 && random.nextDouble() < loss) {
            wait += Math.max(MIN_RESEND_MS, 4 * delayMs); // a resend after about two round trips
            lost++;
        }
        long due = Math.max(lastDue, nowMs + wait);
        lastDue = due;
        queue.add(new Entry<>(item, due));
        long added = due - nowMs;
        count++;
        addedTotal += added;
        addedMax = Math.max(addedMax, added);
    }

    /** The next item due at {@code nowMs}, or null while none is (or an outage holds the line). */
    T poll(long nowMs) {
        if (nowMs < heldUntil)
            return null;
        Entry<T> e = queue.peek();
        if (e == null || e.due() > nowMs)
            return null;
        queue.poll();
        return e.item();
    }

    /** Nothing leaves before {@code untilMs}: an outage. A later call can only lengthen it. */
    void holdUntil(long untilMs) {
        heldUntil = Math.max(heldUntil, untilMs);
    }

    boolean held(long nowMs) {
        return nowMs < heldUntil;
    }

    int size() {
        return queue.size();
    }

    long count() {
        return count;
    }

    long lost() {
        return lost;
    }

    /** The mean wait added per item, in ms, outages aside. */
    long meanAddedMs() {
        return count == 0 ? 0 : addedTotal / count;
    }

    long maxAddedMs() {
        return addedMax;
    }
}
