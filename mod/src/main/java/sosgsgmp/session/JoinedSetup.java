package sosgsgmp.session;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.net.Message;
import sosgsgmp.net.Pieces;
import sosgsgmp.net.TcpNode;
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

    private final TcpNode node;
    private final MapWorld world;
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
    /** The engine's streams once this PC plays; null before. */
    private EngineLink link;
    private boolean closed;
    private String closedReason;
    private int changes;

    /** Takes over a started lobby's connection to the host, this PC's player id and the players. */
    JoinedSetup(TcpNode node, int localId, List<LobbyPlayer> players, MapWorld world, FactionWorld factionWorld) {
        this.node = node;
        this.world = world;
        this.factionWorld = factionWorld;
        this.players = List.copyOf(players);
        this.step = MapStep.joiner(localId);
    }

    @Override
    public void update() {
        TcpNode.Event e;
        while (!closed && step.pendingBuild() == 0 && !waitingToPlay() && (e = node.poll()) != null) {
            switch (e) {
                case TcpNode.Event.Connected c -> {
                    // Only the host's connection, made in the lobby.
                }
                case TcpNode.Event.Received r -> {
                    received(r.message());
                    // a piece of the engine's streams or a cursor changes nothing the panel shows
                    if (!(r.message() instanceof Message.Stream) && !(r.message() instanceof Message.Cursor))
                        changes++;
                }
                case TcpNode.Event.Disconnected d -> {
                    if (d.peer() == TcpNode.HOST)
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
            case Message.Cursor c -> {
                if (c.playerId() != localPlayerId())
                    cursors.set(c.playerId(), c.onMap(), c.x(), c.y());
            }
            case Message.Leave l -> close("The host closed the game.");
            case Message.Unsupported u -> close("The host runs another version of the mod (protocol " + u.version() + ").");
            default -> close("The host sent a message that doesn't belong in the setup.");
        }
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
        node.send(TcpNode.HOST, new Message.MapResult(step.genId(), step.hash(), step.status()));
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
        link = new EngineLink(false, factions.slotOf(localPlayerId()), port);
    }

    @Override
    public boolean playing() {
        return link != null;
    }

    @Override
    public void exchange() {
        if (closed)
            return;
        for (Message.Stream s : link.out())
            node.send(TcpNode.HOST, s);
    }

    @Override
    public void cursor(boolean onMap, int x, int y) {
        if (!closed && cursors.own(onMap, x, y, System.nanoTime()))
            node.send(TcpNode.HOST, new Message.Cursor(localPlayerId(), onMap, x, y));
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
        node.send(TcpNode.HOST, new Message.BuildReady(buildId));
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
    public void pick(FactionPick pick) {
        if (factions == null)
            throw new IllegalStateException("SOSGSGMP: picks before the map is confirmed");
        factions.ownPick(pick);
        node.send(TcpNode.HOST, new Message.Pick(pick));
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
        node.send(TcpNode.HOST, new Message.Loaded(factions.transfer(), hash));
        changes++;
    }

    @Override
    public void startSpot() {
        node.send(TcpNode.HOST, new Message.StartSpot(factions.transfer()));
        changes++;
    }

    @Override
    public void leave() {
        if (closed)
            return;
        node.send(TcpNode.HOST, new Message.Leave());
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
}
