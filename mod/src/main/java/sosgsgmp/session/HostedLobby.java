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
import sosgsgmp.net.Node;
import sosgsgmp.net.TcpNode;
import sosgsgmp.setup.FactionWorld;
import sosgsgmp.setup.MapWorld;

/** A lobby hosted on this PC, over direct IP or Steam. */
public final class HostedLobby implements LobbySession {

    private final Lobby lobby;
    private final Node node;
    /** Connected peers that have joined, to their player ids. Peers still waiting to say hello aren't here. */
    private final Map<Integer, Integer> joined = new HashMap<>();
    private LobbyState state = LobbyState.OPEN;
    private boolean handedOver;
    private String closedReason;
    private final Chat chat = new Chat();
    private int changes;

    /**
     * Opens a lobby listening on {@code port} (0: any free port); throws if the port can't be used. {@code hostKey}: this
     * PC's player key, who it is in a campaign.
     */
    public HostedLobby(LobbySettings settings, String hostName, long hostKey, int port) throws IOException {
        this(settings, hostName, hostKey, TcpNode.listen(port));
    }

    /** Opens a lobby on a node that already hosts (a Steam lobby this PC owns). */
    public HostedLobby(LobbySettings settings, String hostName, long hostKey, Node node) {
        this.lobby = new Lobby(settings, hostName, hostKey);
        this.node = node;
    }

    /** The port players join on over direct IP; -1 over Steam. */
    public int port() {
        return node instanceof TcpNode t ? t.port() : -1;
    }

    @Override
    public void update() {
        Node.Event e;
        while (state != LobbyState.CLOSED && !handedOver && (e = node.poll()) != null) {
            switch (e) {
                case Node.Event.Connected c -> {
                    // The PC joins with its hello.
                }
                case Node.Event.Received r -> received(r.peer(), r.message());
                case Node.Event.Disconnected d -> removeJoined(d.peer());
            }
        }
    }

    @Override
    public Chat chat() {
        return chat;
    }

    @Override
    public void say(String text) {
        String out = chat.outgoing(text, System.nanoTime());
        if (out == null)
            return;
        chat.heard(localPlayerId(), lobby.players(), out);
        relay(new Message.Chat(localPlayerId(), out));
    }

    /** Every joined PC gets it, its sender too: the host's order is every PC's. */
    private void relay(Message.Chat c) {
        for (int peer : joined.keySet())
            node.send(peer, c);
    }

    private void received(int peer, Message m) {
        boolean isJoined = joined.containsKey(peer);
        switch (m) {
            case Message.Hello h when !isJoined -> {
                Refusal refusal = lobby.refusal(h.playerKey());
                if (refusal != null) {
                    refuse(peer, refusal);
                    return;
                }
                int id = lobby.join(h.name(), h.playerKey());
                joined.put(peer, id);
                node.send(peer, new Message.Welcome(id, lobby.settings()));
                sendRoster();
            }
            case Message.Chat c when isJoined -> {
                int id = joined.get(peer);
                chat.heard(id, lobby.players(), c.text());
                relay(new Message.Chat(id, Chat.clean(c.text())));
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
        if (state == LobbyState.CLOSED || handedOver)
            return;
        for (int peer : joined.keySet())
            node.send(peer, new Message.Leave());
        node.close();
        state = LobbyState.CLOSED;
        closedReason = "You closed the lobby.";
        changes++;
    }

    @Override
    public SetupSession handOver(MapWorld world, FactionWorld factions) {
        if (state != LobbyState.STARTED || handedOver)
            throw new IllegalStateException("SOSGSGMP: only a started lobby hands over, once (state " + state + ", handed over " + handedOver + ")");
        handedOver = true;
        return new HostedSetup(node, joined, lobby.players(), lobby.keys(), world, factions, lobby.settings(), chat);
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
