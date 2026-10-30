package sosgsgmp.session;

import java.util.List;

import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;
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

    /** The factions step once the map is confirmed, for reading; null before. It changes only through this session. */
    FactionStep factions();

    /** The lobby's switch: the playtest log lists every transfer, or only those between players (SOS-81). */
    boolean fullTransferLog();

    /**
     * This PC's latest picks, sent whenever they change: the host records them at once (checking the location as it
     * checks everyone's), a joining PC sends them to the host. Only once the map is confirmed.
     */
    void pick(FactionPick pick);

    /**
     * Why this PC's location can't be at world tile {@code x, y}: the engine's rule with the other players' locations
     * as this PC knows them; null when it can. Only once the map is confirmed.
     */
    String locationProblem(int x, int y);

    /**
     * Host: builds the world around every player's picks, locks them, and sends every PC its slot and the save; this
     * PC loads it too ({@link #takeWorld}). Check {@link FactionStep#canInitialize()} first.
     */
    void initialize();

    /** The host's world save, once it is all here, for this PC to load; returned once, null otherwise. */
    byte[] takeWorld();

    /** This PC loaded the host's world: its world hash at step 0, which every PC's must match. */
    void worldLoaded(long hash);

    /**
     * This PC's landing party is placed, after its load. When every PC's is, the host starts the game on every PC
     * ({@link FactionStep#started()}), at the same moment (spec §5.5).
     */
    void startSpot();

    /**
     * The game started on every PC ({@link FactionStep#started()}) and this PC's engine runs: from now its streams go
     * through {@code port} (game-over-network.md). Between the start and this call the session reads nothing more from
     * the network, so no piece arrives before the engine can take it.
     */
    void play(EnginePort port);

    /** Whether {@link #play} was called. */
    boolean playing();

    /** The game streams' bytes so far, before and after compression; all 0 before {@link #play} (SOS-96). */
    EngineLink.Traffic traffic();

    /** City viewing's requests and transfers on this PC (spec §10, SOS-82), from {@link #play} on; null before. */
    ViewRequests views();

    /** Sends what this PC's engine made since the last call. Once per frame, after {@link #play}. */
    void exchange();

    /**
     * This PC's cursor: at pixel {@code x, y} of {@code place} (the world map or a city, {@link Cursors}), or
     * {@link Cursors#OFF}. Call every frame; it goes out only when it changed, at most 10 times a second.
     */
    void cursor(int place, int x, int y);

    /** The other players' cursors, each on its place, for reading. */
    Cursors cursors();

    /**
     * Host, at a world desync, Resync chosen (spec §11 World desyncs): sends {@code resync} to every PC; this PC takes
     * it back from {@link #takeResync} too, as every PC loads it.
     */
    void resync(WorldResync resync);

    /** The host's latest world resync once all of it is here, for this PC to load at its step; returned once, else null. */
    WorldResync takeResync();

    /** Players whose PC left the running game, and the hold for them (spec §11 Disconnects, SOS-65); for reading. */
    Departures departures();

    /**
     * Host, while {@link Departures#waiting}: Continue without the players gone (call it once the engine removed them)
     * or Return to the last save, which ends the session on every PC. Every PC hears the choice.
     */
    void chooseGone(Departures.Choice choice);

    /** Leaves the setup (the host closes it for everyone) and frees the connection. */
    void leave();

    /** Counts changes to the step or the players, so the panel redraws its text only when this moves. */
    int changes();

    boolean closed();

    /** Why the session closed; null while it isn't closed. */
    String closedReason();

    /**
     * The campaign this session plays (spec §11, SOS-64): a resume's, or a new game's once the host initialized the
     * world; a joining PC of a new game learns it from the host's first save order. Null before.
     */
    Campaign campaign();

    /** Host: every PC, this one too, saves the campaign at the order's step; this PC takes it back with {@link #takeSaveOrder}. */
    void orderSave(SaveOrder order);

    /** The host's latest save order, once; null otherwise. */
    SaveOrder takeSaveOrder();

    /** A joining PC: its player chose Quit to menu or Exit; the host saves the campaign on every PC, then the session ends (SOS-79). */
    void askQuit();

    /** Host: the first player who asked to quit and wasn't taken yet, once; -1 when none. */
    int takeQuitAsk();

    /** This PC saved for order {@code number} with world hash {@code hash}, or couldn't: {@code problem}, null when it saved. */
    void saved(int number, long hash, String problem);

    /** Once every PC reported on order {@code number}: empty, or what went wrong; given once, null before. */
    String takeSaveDone(int number);

    /** What carries the session's connections, for the playtest log. */
    String network();

    /**
     * Something on the network worth a line in the playtest log that nothing else shows, such as why a PC's connection
     * dropped, oldest first and each once; null when there is none (SOS-63).
     */
    String takeNote();
}
