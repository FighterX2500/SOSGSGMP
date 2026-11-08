package sosgsgmp.net;

/**
 * One PC's connections to the others, whatever carries them: direct IP ({@link TcpNode}) or Steam P2P
 * ({@link SteamNode}). The sessions only send messages to peers and poll what happened; each node does its network
 * work on its own I/O thread (AGENTS.md "State and Lifecycle", approved exception), which only decodes incoming messages
 * and queues them as {@link Event}s for the game thread.
 */
public interface Node extends AutoCloseable {

    /** What happened on the network, in order, for the game thread. */
    sealed interface Event {
        /** A PC connected to this host, or this joining PC reached its host. */
        record Connected(int peer) implements Event {}
        record Received(int peer, Message message) implements Event {}
        /**
         * The other side closed, the connection broke or never came up, or it sent something malformed.
         * Peer -1: a problem not tied to one connection (a failed accept), reported for the log only.
         */
        record Disconnected(int peer, String reason) implements Event {}
    }

    /** The peer id a joining PC's host has. */
    int HOST = 0;

    /** Queues a message for a peer. Unknown or gone peers are skipped. */
    void send(int peer, Message m);

    /** Closes a peer's connection once the messages queued for it are sent. No event follows. */
    void disconnect(int peer);

    /** The next event, or null if there is none. Game thread. */
    Event poll();

    /** What carries the connections, for the playtest log: the transport and its address or lobby. */
    String describe();

    /**
     * How long {@code peer}'s connection has carried nothing, heartbeats included (SOS-177): seconds of it mean that
     * PC or its line went silent while the connection stayed open. 0 for a peer this node doesn't know. Any thread.
     */
    long silentMs(int peer);

    /** {@code peer}'s round trip from the heartbeats, in ms, or -1 before the first. Any thread. */
    int rttMs(int peer);

    /** Test outages only (SOS-175): for {@code seconds}, no heartbeat goes and none counts, as if the line were down. */
    void silence(int seconds);

    /**
     * Test cuts only (SOS-175, SOS-178): every connection this PC has breaks as a lost internet breaks it; each side
     * waits for it to come back, as for a real break.
     */
    void cutAll();

    /** Sends what is queued (briefly), then closes every connection. No events after this. */
    @Override
    void close();
}
