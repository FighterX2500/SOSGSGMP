package sosgsgmp.session;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;

/**
 * The players' cursors (spec §5 "Other players' cursors", §12): where each other player points, in pixels of the map it
 * is on, and when this PC's own goes out. A cursor is on a place: the world map, or a city by its human's slot, as the
 * owner and its viewers see the same city (SOS-58); a PC draws only the cursors on the place it shows. A cursor is only shown, never an action, so it isn't shared state: no tick,
 * and a lost or late one only moves a marker. The other players' are kept by player id, at most
 * {@link LobbySettings#MAX_PLAYERS}; reads are by index, so the renderer allocates nothing.
 */
public final class Cursors {

    /** At most this often a moving cursor goes out: 10 times a second. */
    static final long SEND_NANOS = 100_000_000L;

    /** No place: the cursor is on no map. */
    public static final int OFF = -1;
    /** The world map, in the game and in the setup. */
    public static final int WORLD = 0;

    private final int[] ids = new int[LobbySettings.MAX_PLAYERS];
    private final int[] places = new int[LobbySettings.MAX_PLAYERS];
    private final int[] xs = new int[LobbySettings.MAX_PLAYERS];
    private final int[] ys = new int[LobbySettings.MAX_PLAYERS];
    private int count;

    private int sentPlace;
    private int sentX;
    private int sentY;
    private long sentAt;
    private boolean everSent;

    /** The place of a city: its human's slot (SOS-58). */
    public static int city(int slot) {
        if (slot < 0)
            throw new IllegalArgumentException("SOSGSGMP: a city's place for slot " + slot);
        return slot + 1;
    }

    /**
     * Whether this PC's cursor, now at pixel {@code x, y} of {@code place} (or {@link #OFF}), should go out now: when it
     * changed, and a moving one at most every {@link #SEND_NANOS}. Going to another place, or off, goes out at once.
     */
    boolean own(int place, int x, int y, long nanos) {
        if (place < OFF)
            throw new IllegalArgumentException("SOSGSGMP: cursor place " + place);
        if (everSent && place == sentPlace && (place == OFF || x == sentX && y == sentY))
            return false;
        if (everSent && place != OFF && place == sentPlace && nanos - sentAt < SEND_NANOS)
            return false;
        everSent = true;
        sentPlace = place;
        sentX = x;
        sentY = y;
        sentAt = nanos;
        return true;
    }

    /** Another player's cursor moved to pixel {@code x, y} of {@code place}, or left every map ({@link #OFF}). */
    void set(int playerId, int place, int x, int y) {
        int i = indexOf(playerId);
        if (place == OFF) {
            if (i >= 0)
                removeAt(i);
            return;
        }
        if (i < 0) {
            if (count == ids.length)
                throw new IllegalStateException("SOSGSGMP: more than " + ids.length + " cursors (player " + playerId + ")");
            i = count++;
            ids[i] = playerId;
        }
        places[i] = place;
        xs[i] = x;
        ys[i] = y;
    }

    /** The player left: their cursor goes. */
    void removePlayer(int playerId) {
        int i = indexOf(playerId);
        if (i >= 0)
            removeAt(i);
    }

    /** Only these players are left: the others' cursors go. */
    void retain(List<LobbyPlayer> players) {
        for (int i = count - 1; i >= 0; i--) {
            boolean in = false;
            for (LobbyPlayer p : players)
                in |= p.id() == ids[i];
            if (!in)
                removeAt(i);
        }
    }

    /** How many other players' cursors are on a map. */
    public int count() {
        return count;
    }

    public int playerId(int i) {
        return ids[check(i)];
    }

    public int place(int i) {
        return places[check(i)];
    }

    public int x(int i) {
        return xs[check(i)];
    }

    public int y(int i) {
        return ys[check(i)];
    }

    private int check(int i) {
        if (i < 0 || i >= count)
            throw new IndexOutOfBoundsException("SOSGSGMP: cursor " + i + " of " + count);
        return i;
    }

    private int indexOf(int playerId) {
        for (int i = 0; i < count; i++)
            if (ids[i] == playerId)
                return i;
        return -1;
    }

    private void removeAt(int i) {
        count--;
        ids[i] = ids[count];
        places[i] = places[count];
        xs[i] = xs[count];
        ys[i] = ys[count];
    }
}
