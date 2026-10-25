package sosgsgmp.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.Refusal;
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

/** The setup hosted on this PC: the host decides the builds and generations and collects every PC's result. */
public final class HostedSetup implements SetupSession {

    private final TcpNode node;
    private final MapWorld world;
    private final FactionWorld factionWorld;
    /** The lobby's difficulty, set on the world the host builds. */
    private final String difficulty;
    /** Connected peers in the setup, to their player ids. */
    private final Map<Integer, Integer> joined;
    private final List<LobbyPlayer> players;
    private final MapStep step;
    /** The players' progress as last sent to the joining PCs. */
    private List<MapStep.PlayerStatus> sentStatuses = List.of();
    /** The options of the latest Generate; null before the first. */
    private MapOptions choices;
    /** A Generate at a new size: its options, generated once this PC's game is rebuilt at that size; else null. */
    private MapOptions waiting;
    /** The factions step from the confirm on; null before. */
    private FactionStep factions;
    /** The picks as last sent to the joining PCs. */
    private List<FactionStep.PlayerPick> sentPicks = List.of();
    /** Every player's progress after the build, as last sent to the joining PCs. */
    private List<FactionStep.PlayerProgress> sentProgress = List.of();
    /** The world this PC built, until it takes it to load; null otherwise. */
    private byte[] save;
    private final Cursors cursors = new Cursors();
    private boolean closed;
    private String closedReason;
    private int changes;

    /**
     * Takes over a started lobby's connection, its joined peers (peer to player id), its players (the host first) and
     * its difficulty.
     */
    HostedSetup(TcpNode node, Map<Integer, Integer> joined, List<LobbyPlayer> players, MapWorld world, FactionWorld factionWorld, String difficulty) {
        this.node = node;
        this.world = world;
        this.factionWorld = factionWorld;
        this.difficulty = difficulty;
        this.joined = new HashMap<>(joined);
        this.players = new ArrayList<>(players);
        this.step = MapStep.host(players.get(0).id(), joined.values());
    }

    @Override
    public void update() {
        TcpNode.Event e;
        while (!closed && (e = node.poll()) != null) {
            switch (e) {
                case TcpNode.Event.Connected c -> {
                    // A late PC: refused when it says hello.
                }
                case TcpNode.Event.Received r -> received(r.peer(), r.message());
                case TcpNode.Event.Disconnected d -> removeJoined(d.peer());
            }
        }
    }

    private void received(int peer, Message m) {
        Integer id = joined.get(peer);
        switch (m) {
            case Message.BuildReady b when id != null -> {
                if (step.onBuildReady(id, b.buildId()))
                    changed();
            }
            case Message.MapResult r when id != null -> {
                if (step.onResult(id, r.genId(), r.hash(), r.status()))
                    changed();
            }
            case Message.Pick p when id != null && factions != null -> {
                if (factions.onPick(id, p.pick()))
                    picksChanged();
            }
            case Message.Loaded l when id != null && factions != null -> {
                if (factions.onLoaded(id, l.transfer(), l.hash()))
                    progressChanged();
            }
            case Message.StartSpot s when id != null && factions != null -> {
                if (factions.onStartSpot(id, s.transfer())) {
                    progressChanged();
                    goWhenReady();
                }
            }
            case Message.Cursor c when id != null -> {
                cursors.set(id, c.onMap(), c.x(), c.y());
                Message relayed = new Message.Cursor(id, c.onMap(), c.x(), c.y());
                for (Map.Entry<Integer, Integer> p : joined.entrySet())
                    if (p.getKey() != peer)
                        node.send(p.getKey(), relayed);
            }
            case Message.Leave l when id != null -> {
                node.disconnect(peer);
                removeJoined(peer);
            }
            case Message.Hello h when id == null -> refuse(peer, Refusal.STARTED);
            case Message.Unsupported u when id == null -> refuse(peer, Refusal.WRONG_VERSION);
            default -> {
                // Anything else breaks the protocol: drop the PC.
                node.disconnect(peer);
                removeJoined(peer);
            }
        }
    }

    private void removeJoined(int peer) {
        Integer id = joined.remove(peer);
        if (id == null)
            return;
        if (factions != null && factions.initialized()) {
            // Their slot is in the world every PC loads: the game can't start without them.
            String name = name(id);
            leave();
            closedReason = name + " left after the world was built; the game can't start without them.";
            return;
        }
        players.removeIf(p -> p.id() == id);
        cursors.removePlayer(id);
        step.remove(id);
        sendAll(new Message.Roster(players));
        changed();
        if (factions != null && factions.remove(id))
            picksChanged();
    }

    private String name(int id) {
        for (LobbyPlayer p : players)
            if (p.id() == id)
                return p.name();
        return "A player";
    }

    /** A player's progress after the build changed: count it, and send it to the joining PCs when it differs. */
    private void progressChanged() {
        changes++;
        List<FactionStep.PlayerProgress> now = factions.progress();
        if (now.isEmpty() || now.equals(sentProgress))
            return;
        sentProgress = now;
        sendAll(new Message.WorldPlayers(factions.transfer(), now));
    }

    /** The table of picks changed: count it, and send it to the joining PCs when it differs from what was sent. */
    private void picksChanged() {
        changes++;
        List<FactionStep.PlayerPick> now = factions.table();
        if (now.isEmpty() || now.equals(sentPicks))
            return;
        sentPicks = now;
        sendAll(new Message.Picks(now));
    }

    /** A player's progress may have changed: count it, and tell the joining PCs if it did. */
    private void changed() {
        changes++;
        sendStatuses(false);
    }

    /** Sends every player's progress when it differs from what was sent last, or {@code always}. */
    private void sendStatuses(boolean always) {
        if (step.buildId() == 0)
            return;
        List<MapStep.PlayerStatus> now = step.statuses();
        if (!always && now.equals(sentStatuses))
            return;
        sentStatuses = now;
        sendAll(new Message.MapPlayers(step.buildId(), step.genId(), now));
    }

    private void refuse(int peer, Refusal refusal) {
        node.send(peer, new Message.Refused(refusal));
        node.disconnect(peer);
    }

    private void sendAll(Message m) {
        for (int peer : joined.keySet())
            node.send(peer, m);
    }

    @Override
    public void cursor(boolean onMap, int x, int y) {
        if (!closed && cursors.own(onMap, x, y, System.nanoTime()))
            sendAll(new Message.Cursor(localPlayerId(), onMap, x, y));
    }

    @Override
    public Cursors cursors() {
        return cursors;
    }

    @Override
    public boolean isHost() {
        return true;
    }

    @Override
    public int localPlayerId() {
        return step.localPlayerId();
    }

    @Override
    public List<LobbyPlayer> players() {
        return List.copyOf(players);
    }

    @Override
    public MapStep step() {
        return step;
    }

    @Override
    public void build(int size) {
        int buildId = step.build(size);
        sendAll(new Message.MapBuild(buildId, size));
        changes++;
        sendStatuses(true);
    }

    @Override
    public void built() {
        step.built(step.pendingBuild());
        changed();
        if (waiting != null) {
            MapOptions o = waiting;
            waiting = null;
            generate(o);
        }
    }

    /**
     * At the current size, generates here and sends the generation and its terrain to every joining PC. At another
     * size, every PC rebuilds its game first, and the generation runs when this PC's rebuild is done ({@link #built}).
     */
    @Override
    public void generate(MapOptions options) {
        if (!options.isRandom() && !MapOptions.presetsAllowed(options.size()))
            throw new IllegalArgumentException("SOSGSGMP: map type '" + options.mapType() + "' at size " + options.size() + "; presets only at " + MapOptions.DEFAULT_SIZE);
        choices = options;
        if (options.size() != step.size()) {
            waiting = options;
            build(options.size());
            return;
        }
        int genId = step.generate(options);
        sendAll(new Message.MapGenerate(genId, step.buildId(), options.mapType(), options.latitude(), options.seed()));
        MapWorld.Terrain terrain = world.generate(options);
        step.generated(genId, terrain.hash(), MapStatus.OK);
        List<byte[]> pieces = Pieces.split(terrain.bytes());
        for (int i = 0; i < pieces.size(); i++)
            sendAll(new Message.MapTerrain(genId, i, pieces.size(), pieces.get(i)));
        changes++;
        sendStatuses(true);
    }

    @Override
    public MapOptions choices() {
        return choices;
    }

    @Override
    public void confirm() {
        step.confirm();
        sendAll(new Message.MapConfirm(step.genId(), step.confirmedHash()));
        world.lock();
        List<Integer> joiners = new ArrayList<>();
        for (LobbyPlayer p : players.subList(1, players.size()))
            joiners.add(p.id());
        factions = FactionStep.host(step.localPlayerId(), joiners, factionWorld::locationProblem);
        changed();
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
        if (factions.onPick(step.localPlayerId(), pick))
            picksChanged();
    }

    @Override
    public String locationProblem(int x, int y) {
        if (factions == null)
            throw new IllegalStateException("SOSGSGMP: a location before the map is confirmed");
        return factions.locationProblem(x, y, factionWorld::locationProblem);
    }

    /**
     * Builds the world here (the game holds this thread for a few seconds; nothing arrives meanwhile), then locks the
     * picks and sends every joining PC the slots and the save in pieces. A build that fails locks nothing.
     */
    @Override
    public void initialize() {
        if (factions == null || !factions.canInitialize())
            throw new IllegalStateException("SOSGSGMP: the world can't be initialized now");
        byte[] built = factionWorld.buildWorld(factions.humans(), difficulty);
        int transfer = factions.initialize();
        sendAll(new Message.SlotMap(transfer, factions.slotMap()));
        List<byte[]> pieces = Pieces.split(built);
        for (int i = 0; i < pieces.size(); i++)
            sendAll(new Message.WorldPart(transfer, i, pieces.size(), pieces.get(i)));
        save = built;
        progressChanged();
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
        progressChanged();
    }

    @Override
    public void startSpot() {
        factions.startSpot();
        progressChanged();
        goWhenReady();
    }

    /** Every PC loaded the same world and placed its landing party: the game starts on every PC. */
    private void goWhenReady() {
        if (!factions.canGo())
            return;
        factions.go();
        sendAll(new Message.Go(factions.transfer()));
        changes++;
    }

    @Override
    public void leave() {
        if (closed)
            return;
        sendAll(new Message.Leave());
        node.close();
        closed = true;
        closedReason = "You closed the game.";
        changes++;
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
