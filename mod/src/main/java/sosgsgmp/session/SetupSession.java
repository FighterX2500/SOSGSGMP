package sosgsgmp.session;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStep;

/**
 * The setup after Start Lobby, as this PC sees it, hosted here ({@link HostedSetup}) or joined ({@link JoinedSetup}).
 * It takes over the started lobby's connection and players (spec §5 Map; setup-map-step.md §3). Game thread only:
 * {@link #update()} applies what arrived from the network, and a joining PC loads the host's maps inside it.
 */
public interface SetupSession {

    /**
     * Applies the network events that arrived since the last call. Call once per frame. It stops while a build is
     * pending ({@link MapStep#pendingBuild()}): this PC rebuilds its game first, then reports {@link #built()}.
     */
    void update();

    boolean isHost();

    int localPlayerId();

    /** Everyone still in the setup, the host first. */
    List<LobbyPlayer> players();

    /** The map step's state, for reading; it changes only through this session. */
    MapStep step();

    /** Host: starts a new build at {@code size}; every PC, this one included, rebuilds its game. */
    void build(int size);

    /** This PC's game for the pending build is up and shows the map step. */
    void built();

    /**
     * Host: generates with these options on this PC and sends the map to every PC. At a size other than the current
     * build's, every PC rebuilds its game first and the generation follows. Check {@link MapStep#canGenerate()} first.
     */
    void generate(MapOptions options);

    /**
     * The options of the latest Generate: the host's choices, kept across a rebuild, or on a joining PC the host's
     * latest generation. Null before the first.
     */
    MapOptions choices();

    /** Host: locks the map on every PC. Check {@link MapStep#canConfirm()} first. */
    void confirm();

    /** Leaves the setup (the host closes it for everyone) and frees the connection. */
    void leave();

    /** Counts changes to the step or the players, so the panel redraws its text only when this moves. */
    int changes();

    boolean closed();

    /** Why the session closed; null while it isn't closed. */
    String closedReason();
}
