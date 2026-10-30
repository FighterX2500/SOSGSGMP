package sosgsgmp.session;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
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
}
