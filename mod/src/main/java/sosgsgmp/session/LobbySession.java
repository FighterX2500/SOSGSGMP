package sosgsgmp.session;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.log.SessionLog;
import sosgsgmp.setup.FactionWorld;
import sosgsgmp.setup.MapWorld;

/**
 * A lobby as this PC sees it, hosted here ({@link HostedLobby}) or joined ({@link JoinedLobby}).
 * Game thread only: {@link #update()} applies what arrived from the network.
 */
public interface LobbySession {

    /** Applies the network events that arrived since the last call. Call once per frame. */
    void update();

    LobbyState state();

    /** A joining PC (hot-join, SOS-190): the host's game is under way, and the host is asked whether this PC joins. */
    default boolean running() {
        return false;
    }

    /** With {@link #running}: this PC's player is frozen in the campaign, and the host is asked whether they come back (SOS-195). */
    default boolean returning() {
        return false;
    }

    /** Everyone in the lobby, the host first; empty until a joining PC is welcomed. */
    List<LobbyPlayer> players();

    /** The host's settings; null until a joining PC is welcomed. */
    LobbySettings settings();

    /** This PC's player id; -1 until a joining PC is welcomed. */
    int localPlayerId();

    boolean isHost();

    /** Whether Start Lobby is active on this PC: the host's, with 2 or more players, not yet started. */
    boolean canStart();

    /** The host starts the lobby, removing a resume's missing players. Check {@link #canStart()} first. */
    default void start() {
        start(false);
    }

    /**
     * The host starts the lobby. {@code freezeMissing}: a resume's missing players are frozen at the start instead of
     * removed (spec §11 Resuming). Check {@link #canStart()} first.
     */
    void start(boolean freezeMissing);

    /** The session's chat on this PC (spec §12), for reading. */
    Chat chat();

    /** This PC's player says {@code text} (spec §12); it shows on every PC, this one too, in the host's order. */
    void say(String text);

    /** Leaves the lobby (the host closes it for everyone) and frees the connection. */
    void leave();

    /**
     * Once the lobby has started: hands the connection, the players and the chat over to the setup, which owns them
     * from then on. This session then neither updates nor closes anything.
     */
    SetupSession handOver(MapWorld world, FactionWorld factions);

    /** Counts changes to the state, players or settings, so a screen redraws its text only when this moves. */
    int changes();

    /** Why the session closed, for the lobby screen; null while it isn't closed. */
    String closedReason();

    /**
     * Something worth a line in the playtest log that the players and the state don't show, such as a PC refused,
     * oldest first and each once; null when there is none (SOS-246).
     */
    default String takeNote() {
        return null;
    }

    /**
     * Hands {@link #handOver} this playtest log and recorder for the setup to carry into the game (SOS-246), and records
     * the lobby's network traffic from now on (SOS-248). Call before the hand-over; the lobby only carries it.
     */
    void keepLog(SessionLog log);

    /** What carries the lobby's connections, for the playtest log. */
    String network();
}
