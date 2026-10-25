package sosgsgmp.session;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.net.Message;
import sosgsgmp.net.TcpNode;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStep;
import sosgsgmp.setup.MapWorld;

/**
 * The setup on another PC, joined: this PC follows the host's builds and generations in the order they arrive, and
 * generates every one of them, never only the latest (setup-map-step.md §7).
 */
public final class JoinedSetup implements SetupSession {

    private final TcpNode node;
    private final MapWorld world;
    private final MapStep step;
    private List<LobbyPlayer> players;
    private boolean closed;
    private String closedReason;
    private int changes;

    /** Takes over a started lobby's connection to the host, this PC's player id and the players. */
    JoinedSetup(TcpNode node, int localId, List<LobbyPlayer> players, MapWorld world) {
        this.node = node;
        this.world = world;
        this.players = List.copyOf(players);
        this.step = MapStep.joiner(localId);
    }

    @Override
    public void update() {
        TcpNode.Event e;
        while (!closed && step.pendingBuild() == 0 && (e = node.poll()) != null) {
            switch (e) {
                case TcpNode.Event.Connected c -> {
                    // Only the host's connection, made in the lobby.
                }
                case TcpNode.Event.Received r -> {
                    received(r.message());
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
                if (!step.onGenerate(g.genId(), g.buildId(), g.mapType(), g.latitude(), g.seed()))
                    return;
                MapOptions pending = step.pendingGeneration();
                if (pending != null)
                    LocalMap.generate(step, world, pending);
                node.send(TcpNode.HOST, new Message.MapResult(step.genId(), step.hash(), step.status()));
            }
            case Message.MapConfirm c -> {
                if (step.onConfirm(c.genId(), c.hash()))
                    world.lock();
            }
            case Message.MapPlayers p -> step.onPlayers(p.buildId(), p.genId(), p.players());
            case Message.Roster r -> players = r.players();
            case Message.Leave l -> close("The host closed the game.");
            case Message.Unsupported u -> close("The host runs another version of the mod (protocol " + u.version() + ").");
            default -> close("The host sent a message that doesn't belong in the setup.");
        }
    }

    private void close(String reason) {
        node.close();
        closed = true;
        closedReason = reason;
        changes++;
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
    public void confirm() {
        throw new IllegalStateException("SOSGSGMP: only the host confirms the map");
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
