package sosgsgmp.session;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sosgsgmp.lobby.Lobby;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.net.Message;
import sosgsgmp.net.TcpNode;

/** A lobby hosted on this PC over direct IP. */
public final class HostedLobby implements LobbySession {

    private final Lobby lobby;
    private final TcpNode node;
    /** Connected peers that have joined, to their player ids. Peers still waiting to say hello aren't here. */
    private final Map<Integer, Integer> joined = new HashMap<>();
    private LobbyState state = LobbyState.OPEN;
    private String closedReason;
    private int changes;

    /** Opens a lobby listening on {@code port} (0: any free port); throws if the port can't be used. */
    public HostedLobby(LobbySettings settings, String hostName, int port) throws IOException {
        this.lobby = new Lobby(settings, hostName);
        this.node = TcpNode.listen(port);
    }

    /** The port players join on. */
    public int port() {
        return node.port();
    }

    @Override
    public void update() {
        TcpNode.Event e;
        while (state != LobbyState.CLOSED && (e = node.poll()) != null) {
            switch (e) {
                case TcpNode.Event.Connected c -> {
                    // The PC joins with its hello.
                }
                case TcpNode.Event.Received r -> received(r.peer(), r.message());
                case TcpNode.Event.Disconnected d -> removeJoined(d.peer());
            }
        }
    }

    private void received(int peer, Message m) {
        boolean isJoined = joined.containsKey(peer);
        switch (m) {
            case Message.Hello h when !isJoined -> {
                Refusal refusal = lobby.refusal();
                if (refusal != null) {
                    refuse(peer, refusal);
                    return;
                }
                int id = lobby.join(h.name());
                joined.put(peer, id);
                node.send(peer, new Message.Welcome(id, lobby.settings()));
                sendRoster();
            }
            case Message.Unsupported u -> refuse(peer, Refusal.WRONG_VERSION);
            case Message.Leave l when isJoined -> {
                node.disconnect(peer);
                removeJoined(peer);
            }
            default -> {
                // Anything else from a joining PC breaks the protocol: drop it.
                node.disconnect(peer);
                removeJoined(peer);
            }
        }
    }

    private void removeJoined(int peer) {
        Integer id = joined.remove(peer);
        if (id == null)
            return;
        lobby.leave(id);
        sendRoster();
    }

    private void refuse(int peer, Refusal refusal) {
        node.send(peer, new Message.Refused(refusal));
        node.disconnect(peer);
    }

    private void sendRoster() {
        changes++;
        Message roster = new Message.Roster(lobby.players());
        for (int peer : joined.keySet())
            node.send(peer, roster);
    }

    @Override
    public LobbyState state() {
        return state;
    }

    @Override
    public List<LobbyPlayer> players() {
        return lobby.players();
    }

    @Override
    public LobbySettings settings() {
        return lobby.settings();
    }

    @Override
    public int localPlayerId() {
        return lobby.players().get(0).id();
    }

    @Override
    public boolean isHost() {
        return true;
    }

    @Override
    public boolean canStart() {
        return state == LobbyState.OPEN && lobby.canStart();
    }

    @Override
    public void start() {
        if (!canStart())
            throw new IllegalStateException("SOSGSGMP: the lobby can't start now (state " + state + ", " + lobby.players().size() + " players)");
        lobby.start();
        state = LobbyState.STARTED;
        changes++;
        for (int peer : joined.keySet())
            node.send(peer, new Message.Start());
    }

    @Override
    public void leave() {
        if (state == LobbyState.CLOSED)
            return;
        for (int peer : joined.keySet())
            node.send(peer, new Message.Leave());
        node.close();
        state = LobbyState.CLOSED;
        closedReason = "You closed the lobby.";
        changes++;
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
