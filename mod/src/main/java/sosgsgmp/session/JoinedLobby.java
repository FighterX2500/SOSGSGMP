package sosgsgmp.session;

import java.io.IOException;
import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.net.Message;
import sosgsgmp.net.Node;
import sosgsgmp.net.TcpNode;
import sosgsgmp.setup.FactionWorld;
import sosgsgmp.setup.MapWorld;

/** A lobby on another PC, joined over direct IP or Steam. */
public final class JoinedLobby implements LobbySession {

    private final Node node;
    private final String name;
    private final long playerKey;
    private LobbyState state = LobbyState.CONNECTING;
    private boolean handedOver;
    private List<LobbyPlayer> players = List.of();
    private LobbySettings settings;
    private int localId = -1;
    private String closedReason;
    private int changes;

    /** Starts connecting to {@code host}:{@code port}; the outcome arrives through {@link #update()}. */
    public JoinedLobby(String host, int port, String name, long playerKey) throws IOException {
        this(TcpNode.connect(host, port), name, playerKey);
    }

    /** Joins through a node that is reaching its host (a Steam lobby being entered); hello follows its connect. */
    public JoinedLobby(Node node, String name, long playerKey) {
        this.name = name;
        this.playerKey = playerKey;
        this.node = node;
    }

    /** Stops at Start: what follows is the setup's, read by the session this one hands over to. */
    @Override
    public void update() {
        Node.Event e;
        while ((state == LobbyState.CONNECTING || state == LobbyState.OPEN) && (e = node.poll()) != null) {
            switch (e) {
                case Node.Event.Connected c -> node.send(Node.HOST, new Message.Hello(name, playerKey));
                case Node.Event.Received r -> {
                    received(r.message());
                    changes++;
                }
                case Node.Event.Disconnected d -> {
                    if (d.peer() == Node.HOST)
                        close(localId < 0 ? d.reason() : "Lost the connection to the host. " + d.reason());
                }
            }
        }
    }

    private void received(Message m) {
        switch (m) {
            case Message.Welcome w -> {
                localId = w.yourId();
                settings = w.settings();
                state = LobbyState.OPEN;
            }
            case Message.Roster r -> players = r.players();
            case Message.Refused f -> close(f.reason().text);
            case Message.Start s -> state = LobbyState.STARTED;
            case Message.Leave l -> close("The host closed the lobby.");
            case Message.Unsupported u -> close("The host runs another version of the mod (protocol " + u.version() + ").");
            case Message.Hello h -> close("The host sent a message only a joining PC sends.");
            default -> close("The host sent a setup message before it started the lobby.");
        }
    }

    private void close(String reason) {
        node.close();
        state = LobbyState.CLOSED;
        closedReason = reason;
        changes++;
    }

    @Override
    public LobbyState state() {
        return state;
    }

    @Override
    public List<LobbyPlayer> players() {
        return players;
    }

    @Override
    public LobbySettings settings() {
        return settings;
    }

    @Override
    public int localPlayerId() {
        return localId;
    }

    @Override
    public boolean isHost() {
        return false;
    }

    @Override
    public boolean canStart() {
        return false;
    }

    @Override
    public void start() {
        throw new IllegalStateException("SOSGSGMP: only the host starts the lobby");
    }

    @Override
    public void leave() {
        if (state == LobbyState.CLOSED || handedOver)
            return;
        node.send(Node.HOST, new Message.Leave());
        close("You left the lobby.");
    }

    @Override
    public SetupSession handOver(MapWorld world, FactionWorld factions) {
        if (state != LobbyState.STARTED || handedOver)
            throw new IllegalStateException("SOSGSGMP: only a started lobby hands over, once (state " + state + ", handed over " + handedOver + ")");
        handedOver = true;
        return new JoinedSetup(node, localId, players, world, factions);
    }

    @Override
    public int changes() {
        return changes;
    }

    @Override
    public String closedReason() {
        return closedReason;
    }
}
