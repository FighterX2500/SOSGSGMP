package sosgsgmp.log;

/**
 * What the game's listeners saw inside the step (SOS-247), for the playtest log to read between steps: a preallocated
 * queue of small records (a step, a kind and four ints). {@link #put} only writes primitives into arrays: no allocation,
 * no I/O, nothing read but its arguments, so a listener running inside the step can call it. A full queue drops the
 * newest record and counts it, so the order of what is kept stays the game's. Plain Java; game thread only.
 */
public final class EventQueue {

    private final long[] steps;
    private final int[] kinds;
    private final int[] a, b, c, d;
    private int head, size;
    private long lost;
    /** The slot {@link #take()} read last. */
    private int taken = -1;

    public EventQueue(int capacity) {
        if (capacity < 1)
            throw new IllegalArgumentException("SOSGSGMP: an event queue of " + capacity + " records");
        steps = new long[capacity];
        kinds = new int[capacity];
        a = new int[capacity];
        b = new int[capacity];
        c = new int[capacity];
        d = new int[capacity];
    }

    /** Queues a record; false, and counted in {@link #lost()}, when the queue is full. */
    public boolean put(long step, int kind, int a, int b, int c, int d) {
        if (size == steps.length) {
            lost++;
            return false;
        }
        int i = (head + size) % steps.length;
        steps[i] = step;
        kinds[i] = kind;
        this.a[i] = a;
        this.b[i] = b;
        this.c[i] = c;
        this.d[i] = d;
        size++;
        return true;
    }

    /** Takes the oldest record, whose fields the getters then return; false when the queue is empty. */
    public boolean take() {
        if (size == 0)
            return false;
        taken = head;
        head = (head + 1) % steps.length;
        size--;
        return true;
    }

    public long step() {
        return steps[slot()];
    }

    public int kind() {
        return kinds[slot()];
    }

    public int a() {
        return a[slot()];
    }

    public int b() {
        return b[slot()];
    }

    public int c() {
        return c[slot()];
    }

    public int d() {
        return d[slot()];
    }

    private int slot() {
        if (taken < 0)
            throw new IllegalStateException("SOSGSGMP: no record taken from the event queue");
        return taken;
    }

    /** Records waiting. */
    public int size() {
        return size;
    }

    /** Records dropped because the queue was full, since it was made. */
    public long lost() {
        return lost;
    }
}
