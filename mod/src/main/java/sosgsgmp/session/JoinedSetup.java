package sosgsgmp.session;

import java.util.ArrayList;
import java.util.List;

import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.net.Message;
import sosgsgmp.net.Pieces;
import sosgsgmp.net.Node;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;
import sosgsgmp.setup.FactionWorld;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStatus;
import sosgsgmp.setup.MapStep;
import sosgsgmp.setup.MapWorld;

/**
 * The setup on another PC, joined: this PC follows the host's builds and generations in the order they arrive, and
 * loads each generation's terrain as the host sends it: vanilla's generator would make another map here
 * (setup-map-step.md §7).
 */
public final class JoinedSetup implements SetupSession {

    private final Node node;
    private final MapWorld world;
    /** The host's lobby switch for the playtest log (SOS-81). */
    private final boolean fullTransferLog;
    private final FactionWorld factionWorld;
    private final MapStep step;
    private final Pieces.Receiver terrain = new Pieces.Receiver();
    private List<LobbyPlayer> players;
    /** The factions step from the host's confirm on; null before. */
    private FactionStep factions;
    private final Pieces.Receiver worldParts = new Pieces.Receiver();
    /** The host's world once all its pieces are here, until this PC takes it to load; null otherwise. */
    private byte[] save;
    private final Cursors cursors = new Cursors();
    /** Players whose PC left the running game, as the host tells it, and the hold for them (SOS-65). */
    private final Departures departures = new Departures();
    /** The engine's streams once this PC plays; null before. */
    private EngineLink link;
    /** City viewing's requests once this PC plays; null before (SOS-82). */
    private ViewRequests views;
    private final Pieces.Receiver resyncParts = new Pieces.Receiver();
    /** The host's latest world resync once all of it is here, until this PC takes it to load; null otherwise. */
    private WorldResync resync;
    private Campaign campaign;
    /** The host's latest save order, until this PC takes it. */
    private SaveOrder saveOrder;
    private int doneNumber;
    private String doneOutcome;
    private boolean closed;
    private String closedReason;
    private int changes;

    /**
     * Takes over a started lobby's connection to the host, this PC's player id and the players. A resume ({@code resume}
     * not null) starts at the world-loaded step: the host's slot map follows, and every PC loads its own save (SOS-64).
     */
    JoinedSetup(Node node, int localId, List<LobbyPlayer> players, MapWorld world, FactionWorld factionWorld,
            LobbySettings.Resume resume, boolean fullTransferLog) {
        this.node = node;
        this.fullTransferLog = fullTransferLog;
        this.world = world;
        this.factionWorld = factionWorld;
        this.players = List.copyOf(players);
        this.step = MapStep.joiner(localId);
        if (resume != null) {
            campaign = resume.campaign();
            factions = FactionStep.joiner(localId);
        }
    }

    @Override
    public void update() {
        Node.Event e;
        while (!closed && step.pendingBuild() == 0 && !waitingToPlay() && (e = node.poll()) != null) {
            switch (e) {
                case Node.Event.Connected c -> {
                    // Only the host's connection, made in the lobby.
                }
                case Node.Event.Received r -> {
                    received(r.message());
                    // a piece of the engine's streams, a cursor or a view's city or hashes changes nothing the panel shows
                    if (!(r.message() instanceof Message.Stream) && !(r.message() instanceof Message.Cursor)
                            && !(r.message() instanceof Message.ViewCity) && !(r.message() instanceof Message.ViewHashes))
                        changes++;
                }
                case Node.Event.Disconnected d -> {
                    if (d.peer() == Node.HOST)
                        close("Lost the connection to the host. " + d.reason());
                }
            }
        }
    }

    private void received(Message m) {
        switch (m) {
            case Message.MapBuild b -> step.onBuild(b.buildId(), b.size());
            case Message.MapGenerate g -> {
                // A generation of a build this PC doesn't have is answered at once; otherwise its terrain follows.
                if (step.onGenerate(g.genId(), g.buildId(), g.mapType(), g.latitude(), g.seed()) && step.pendingGeneration() == null)
                    sendResult();
            }
            case Message.MapTerrain t -> {
                byte[] bytes = terrain.accept(t.genId(), t.index(), t.count(), t.bytes());
                MapOptions pending = step.pendingGeneration();
                if (bytes == null || pending == null || t.genId() != step.genId())
                    return;
                load(pending, bytes);
                sendResult();
            }
            case Message.MapConfirm c -> {
                if (step.onConfirm(c.genId(), c.hash())) {
                    world.lock();
                    factions = FactionStep.joiner(step.localPlayerId());
                }
            }
            case Message.Picks p when factions != null -> factions.onPicks(p.picks());
            case Message.SlotMap s when factions != null -> {
                if (!factions.onSlotMap(s.transfer(), s.slots()) && !factions.initialized())
                    close("The host built a world without a place for you.");
            }
            case Message.WorldPart w when factions != null && factions.initialized() -> {
                byte[] all = worldParts.accept(w.transfer(), w.index(), w.count(), w.bytes());
                if (all != null && w.transfer() == factions.transfer())
                    save = all;
            }
            case Message.WorldPlayers p when factions != null -> factions.onProgress(p.transfer(), p.players());
            case Message.Go g when factions != null -> factions.onGo(g.transfer());
            case Message.MapPlayers p -> step.onPlayers(p.buildId(), p.genId(), p.players());
            case Message.Roster r -> {
                players = r.players();
                cursors.retain(players);
            }
            case Message.Stream s when link != null -> {
                String problem = link.problem(s);
                if (problem != null)
                    close("The host sent a broken game stream: " + problem);
                else
                    link.in(s);
            }
            case Message.Resync r when link != null -> {
                byte[] all = resyncParts.accept(r.transfer(), r.index(), r.count(), r.bytes());
                if (all == null)
                    return;
                try {
                    resync = WorldResync.unpack(r.step(), all);
                } catch (IllegalArgumentException e) {
                    close("The host sent a broken world resync: " + e.getMessage());
                }
            }
            case Message.ViewAsk v when views != null -> view(v);
            case Message.ViewAnswer v when views != null -> view(v);
            case Message.ViewCity v when views != null -> view(v);
            case Message.ViewHashes v when views != null -> view(v);
            case Message.SaveAt s when link != null -> {
                saveOrder = s.order();
                campaign = s.order().campaign();
            }
            case Message.SaveDone d when link != null -> {
                doneNumber = d.number();
                doneOutcome = d.outcome();
            }
            case Message.Cursor c -> {
                if (c.playerId() != localPlayerId())
                    cursors.set(c.playerId(), c.onMap(), c.x(), c.y());
            }
            case Message.PlayerGone g when link != null -> gone(g.playerId(), g.holdStep());
            case Message.GoneChoice c when link != null -> {
                Departures.Choice choice = c.continued() ? Departures.Choice.CONTINUE : Departures.Choice.RETURN;
                if (departures.chosen(c.holdStep(), choice) && choice == Departures.Choice.RETURN)
                    close("The host chose to return to the last save. Resume the campaign from Load Saved Lobby.");
            }
            case Message.Leave l -> close("The host closed the game.");
            case Message.Unsupported u -> close("The host runs another version of the mod (protocol " + u.version() + ").");
            default -> close("The host sent a message that doesn't belong in the setup.");
        }
    }

    /** A view message from the host (SOS-82). */
    private void view(Message m) {
        String problem = views.received(factions.slotOf(hostId()), m);
        if (problem != null)
            close("The host sent a view message that doesn't fit: " + problem);
    }

    /** The host says player {@code id}'s PC left: this PC holds at {@code hold} until the host chooses (spec §11). */
    private void gone(int id, long hold) {
        if (id == localPlayerId() || id == hostId()) {
            close("The host said a player who is still here left (player " + id + ").");
            return;
        }
        String name = "A player";
        for (LobbyPlayer p : players)
            if (p.id() == id)
                name = p.name();
        if (!departures.heard(new Departures.Gone(id, name, factions.slotOf(id)), hold))
            return;
        List<LobbyPlayer> left = new ArrayList<>(players);
        left.removeIf(p -> p.id() == id);
        players = List.copyOf(left);
        cursors.retain(players);
    }

    @Override
    public Departures departures() {
        return departures;
    }

    @Override
    public void chooseGone(Departures.Choice choice) {
        throw new IllegalStateException("SOSGSGMP: only the host chooses after a player left");
    }

    private void load(MapOptions options, byte[] bytes) {
        long hash;
        try {
            hash = world.load(options, bytes);
        } catch (IllegalArgumentException e) {
            // The adapter logged why; the host sees this PC's error and can't confirm.
            step.generated(step.genId(), 0, MapStatus.BAD_TERRAIN);
            return;
        }
        step.generated(step.genId(), hash, MapStatus.OK);
    }

    private void sendResult() {
        node.send(Node.HOST, new Message.MapResult(step.genId(), step.hash(), step.status()));
    }

    private void close(String reason) {
        node.close();
        closed = true;
        closedReason = reason;
        changes++;
    }

    private boolean waitingToPlay() {
        return factions != null && factions.started() && link == null;
    }

    @Override
    public void play(EnginePort port) {
        if (factions == null || !factions.started() || link != null)
            throw new IllegalStateException("SOSGSGMP: play before the start, or twice");
        link = new EngineLink(false, factions.slotOf(localPlayerId()), factions.slotOf(hostId()), port);
        views = new ViewRequests(false, factions.slotOf(localPlayerId()), factions.slotOf(hostId()),
                (slot, m) -> node.send(Node.HOST, m), System::nanoTime);
    }

    @Override
    public boolean playing() {
        return link != null;
    }

    @Override
    public ViewRequests views() {
        return views;
    }

    @Override
    public void exchange() {
        if (closed)
            return;
        for (Message.Stream s : link.out())
            node.send(Node.HOST, s);
    }

    @Override
    public void resync(WorldResync r) {
        throw new IllegalStateException("SOSGSGMP: only the host resyncs the world");
    }

    @Override
    public WorldResync takeResync() {
        WorldResync r = resync;
        resync = null;
        return r;
    }

    @Override
    public void cursor(boolean onMap, int x, int y) {
        if (!closed && cursors.own(onMap, x, y, System.nanoTime()))
            node.send(Node.HOST, new Message.Cursor(localPlayerId(), onMap, x, y));
    }

    @Override
    public Cursors cursors() {
        return cursors;
    }

    @Override
    public boolean isHost() {
        return false;
    }

    @Override
    public int localPlayerId() {
        return step.localPlayerId();
    }

    @Override
    public List<LobbyPlayer> players() {
        return players;
    }

    @Override
    public MapStep step() {
        return step;
    }

    @Override
    public void build(int size) {
        throw new IllegalStateException("SOSGSGMP: only the host starts a build");
    }

    @Override
    public void built() {
        int buildId = step.pendingBuild();
        step.built(buildId);
        node.send(Node.HOST, new Message.BuildReady(buildId));
        changes++;
    }

    @Override
    public void generate(MapOptions options) {
        throw new IllegalStateException("SOSGSGMP: only the host generates a map");
    }

    @Override
    public MapOptions choices() {
        return step.options();
    }

    @Override
    public void confirm() {
        throw new IllegalStateException("SOSGSGMP: only the host confirms the map");
    }

    @Override
    public FactionStep factions() {
        return factions;
    }

    @Override
    public boolean fullTransferLog() {
        return fullTransferLog;
    }

    @Override
    public void pick(FactionPick pick) {
        if (factions == null)
            throw new IllegalStateException("SOSGSGMP: picks before the map is confirmed");
        factions.ownPick(pick);
        node.send(Node.HOST, new Message.Pick(pick));
        changes++;
    }

    @Override
    public String locationProblem(int x, int y) {
        if (factions == null)
            throw new IllegalStateException("SOSGSGMP: a location before the map is confirmed");
        return factions.locationProblem(x, y, factionWorld::locationProblem);
    }

    @Override
    public void initialize() {
        throw new IllegalStateException("SOSGSGMP: only the host initializes the world");
    }

    @Override
    public byte[] takeWorld() {
        byte[] s = save;
        save = null;
        return s;
    }

    @Override
    public void worldLoaded(long hash) {
        factions.loaded(hash);
        node.send(Node.HOST, new Message.Loaded(factions.transfer(), hash));
        changes++;
    }

    @Override
    public void startSpot() {
        node.send(Node.HOST, new Message.StartSpot(factions.transfer()));
        changes++;
    }

    @Override
    public void leave() {
        if (closed)
            return;
        node.send(Node.HOST, new Message.Leave());
        close("You left the game.");
    }

    @Override
    public int changes() {
        return changes;
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public String closedReason() {
        return closedReason;
    }

    @Override
    public String network() {
        return node.describe();
    }

    /** The host's player id: the roster's host. */
    private int hostId() {
        for (LobbyPlayer p : players)
            if (p.host())
                return p.id();
        throw new IllegalStateException("SOSGSGMP: the players have no host: " + players);
    }

    @Override
    public Campaign campaign() {
        return campaign;
    }

    @Override
    public void orderSave(SaveOrder order) {
        throw new IllegalStateException("SOSGSGMP: only the host orders a campaign save");
    }

    @Override
    public SaveOrder takeSaveOrder() {
        SaveOrder o = saveOrder;
        saveOrder = null;
        return o;
    }

    @Override
    public void askQuit() {
        if (!closed)
            node.send(Node.HOST, new Message.QuitAsk());
    }

    /** None: only the host takes a player's ask to quit. */
    @Override
    public int takeQuitAsk() {
        return -1;
    }

    @Override
    public void saved(int number, long hash, String problem) {
        if (!closed)
            node.send(Node.HOST, new Message.Saved(number, hash, problem == null ? "" : problem));
    }

    @Override
    public String takeSaveDone(int number) {
        if (doneOutcome == null || doneNumber != number)
            return null;
        String o = doneOutcome;
        doneOutcome = null;
        return o;
    }

    /** None: every network problem of a joining PC closes its session, and {@link #closedReason()} says why. */
    @Override
    public String takeNote() {
        return null;
    }
}
