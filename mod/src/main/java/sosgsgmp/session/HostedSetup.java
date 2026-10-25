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
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStatus;
import sosgsgmp.setup.MapStep;
import sosgsgmp.setup.MapWorld;

/** The setup hosted on this PC: the host decides the builds and generations and collects every PC's result. */
public final class HostedSetup implements SetupSession {

    private final TcpNode node;
    private final MapWorld world;
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
    private boolean closed;
    private String closedReason;
    private int changes;

    /** Takes over a started lobby's connection, its joined peers (peer to player id) and its players, the host first. */
    HostedSetup(TcpNode node, Map<Integer, Integer> joined, List<LobbyPlayer> players, MapWorld world) {
        this.node = node;
        this.world = world;
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
        players.removeIf(p -> p.id() == id);
        step.remove(id);
        sendAll(new Message.Roster(players));
        changed();
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
        changed();
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
