package menu;

import sosgsgmp.game.SteamworksPort;
import sosgsgmp.net.SteamNode;

/**
 * SOSGSGMP: a friend's Steam invite accepted while the main menu shows (SOS-103). Before, Steam started only on the
 * Join through Steam screen, so an invite accepted in Steam's overlay or friends list from the main menu went unheard
 * and was lost. While the main menu draws, this keeps a browsing Steam node open; an accepted invite joins that lobby,
 * as the Join through Steam screen does. Steam runs once in the process, so the node is handed to that screen when it
 * opens, and stopped before a Steam host starts. Once the menu stops drawing (a game started), the node closes itself.
 * If Steam isn't available the menu works as before, without it. Game thread only.
 */
final class MpInviteWatch {

    /** The menu hasn't drawn for this long: it is gone, and the node closes. */
    private static final long AWAY_NANOS = 2_000_000_000L;

    private SteamNode node;
    /** Steam wasn't available: no new tries in this menu. */
    private boolean failed;
    private volatile long seen;

    /** Every frame the main menu draws: the lobby of an invite the player accepted, or 0. */
    long frame() {
        long now = System.nanoTime();
        long before = seen;
        seen = now;
        if (node != null && now - before >= AWAY_NANOS) {
            node.close(); // it closed itself while the menu was gone; a fresh one starts
            node = null;
        }
        if (failed)
            return 0;
        if (node == null) {
            node = SteamNode.browse(SteamworksPort::new);
            node.closeWhenUnwanted(() -> System.nanoTime() - seen < AWAY_NANOS);
            return 0;
        }
        if (node.failure() != null) {
            failed = true;
            node.close();
            node = null;
            return 0;
        }
        return node.invited();
    }

    /** Hands the browsing node over (the Join through Steam screen, or a join from an invite), or null. */
    SteamNode take() {
        SteamNode n = node;
        node = null;
        if (n != null)
            n.closeWhenUnwanted(null);
        return n;
    }

    /** Before a Steam host starts: Steam runs once in the process. */
    void stop() {
        if (node != null)
            node.close();
        node = null;
    }
}
