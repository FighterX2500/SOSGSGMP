package sosgsgmp.session;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;

/**
 * The players' cursors on the world map (spec §5 "Other players' cursors"): where each other player points, in world
 * pixels, and when this PC's own goes out. A cursor is only shown, never an action, so it isn't shared state: no tick,
 * and a lost or late one only moves a marker. The other players' are kept by player id, at most
 * {@link LobbySettings#MAX_PLAYERS}; reads are by index, so the renderer allocates nothing.
 */
public final class Cursors {

    /** At most this often a moving cursor goes out: 10 times a second. */
    static final long SEND_NANOS = 100_000_000L;

    private final int[] ids = new int[LobbySettings.MAX_PLAYERS];
    private final int[] xs = new int[LobbySettings.MAX_PLAYERS];
    private final int[] ys = new int[LobbySettings.MAX_PLAYERS];
    private int count;

    private boolean sentOnMap;
    private int sentX;
    private int sentY;
    private long sentAt;
    private boolean everSent;

    /**
     * Whether this PC's cursor, now on the world map at pixel {@code x, y} (or off it), should go out now: when it
     * changed, and a moving one at most every {@link #SEND_NANOS}. Leaving the map goes out at once.
     */
    boolean own(boolean onMap, int x, int y, long nanos) {
        if (everSent && onMap == sentOnMap && (!onMap || x == sentX && y == sentY))
            return false;
        if (everSent && onMap && sentOnMap && nanos - sentAt < SEND_NANOS)
            return false;
        everSent = true;
        sentOnMap = onMap;
        sentX = x;
        sentY = y;
        sentAt = nanos;
        return true;
    }

    /** Another player's cursor moved to pixel {@code x, y}, or left the map. */
    void set(int playerId, boolean onMap, int x, int y) {
        int i = indexOf(playerId);
        if (!onMap) {
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

    /** How many other players' cursors are on the world map. */
    public int count() {
        return count;
    }

    public int playerId(int i) {
        return ids[check(i)];
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
        xs[i] = xs[count];
        ys[i] = ys[count];
    }
}
