package sosgsgmp.session;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;

/**
 * A lobby as this PC sees it, hosted here ({@link HostedLobby}) or joined ({@link JoinedLobby}).
 * Game thread only: {@link #update()} applies what arrived from the network.
 */
public interface LobbySession {

    /** Applies the network events that arrived since the last call. Call once per frame. */
    void update();

    LobbyState state();

    /** Everyone in the lobby, the host first; empty until a joining PC is welcomed. */
    List<LobbyPlayer> players();

    /** The host's settings; null until a joining PC is welcomed. */
    LobbySettings settings();

    /** This PC's player id; -1 until a joining PC is welcomed. */
    int localPlayerId();

    boolean isHost();

    /** Whether Start Lobby is active on this PC: the host's, with 2 or more players, not yet started. */
    boolean canStart();

    /** The host starts the lobby. Check {@link #canStart()} first. */
    void start();

    /** Leaves the lobby (the host closes it for everyone) and frees the connection. */
    void leave();

    /** Counts changes to the state, players or settings, so a screen redraws its text only when this moves. */
    int changes();

    /** Why the session closed, for the lobby screen; null while it isn't closed. */
    String closedReason();
}
