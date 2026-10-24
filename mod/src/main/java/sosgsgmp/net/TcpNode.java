package sosgsgmp.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Direct-IP connections over TCP: a host listens, joining PCs connect to it.
 *
 * <p>One I/O thread per node (AGENTS.md "State and Lifecycle", approved exception) does all socket work. It only
 * decodes incoming messages and puts them on a queue as {@link Event}s; the game thread drains them with
 * {@link #poll()} and makes every state change itself. Sending only queues the bytes for the I/O thread.
 *
 * <p>On the wire each message is a 4-byte length followed by the bytes of {@link Protocol#encode}.
 */
public final class TcpNode implements AutoCloseable {

    /** What happened on the network, in order, for the game thread. */
    public sealed interface Event {
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
    public static final int HOST = 0;

    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long CLOSE_FLUSH_MS = 1_000;

    private sealed interface Command {
        record Send(int peer, ByteBuffer bytes) implements Command {}
        record Drop(int peer) implements Command {}
    }

    private final Selector selector;
    private final ServerSocketChannel server;
    private final InetSocketAddress connectTo;
    private final Queue<Event> events = new ConcurrentLinkedQueue<>();
    private final Queue<Command> commands = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean closing;

    // Owned by the I/O thread.
    private final Map<Integer, Peer> peers = new HashMap<>();
    private int nextPeer = HOST;

    /** Listens on {@code port} (0: any free port) on every local address. Fails at once if the port is taken. */
    public static TcpNode listen(int port) throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open();
        try {
            server.bind(new InetSocketAddress(port));
            server.configureBlocking(false);
            return new TcpNode(server, null);
        } catch (IOException e) {
            server.close();
            throw e;
        }
    }

    /**
     * Connects to a host. Name lookup and connecting happen on the I/O thread: success arrives as
     * {@link Event.Connected} for peer {@link #HOST}, failure as {@link Event.Disconnected}.
     */
    public static TcpNode connect(String host, int port) throws IOException {
        return new TcpNode(null, InetSocketAddress.createUnresolved(host, port));
    }

    private TcpNode(ServerSocketChannel server, InetSocketAddress connectTo) throws IOException {
        this.server = server;
        this.connectTo = connectTo;
        this.selector = Selector.open();
        if (server != null)
            server.register(selector, SelectionKey.OP_ACCEPT);
        thread = new Thread(this::run, "SOSGSGMP net");
        thread.setDaemon(true);
        thread.start();
    }

    /** The port this host listens on. */
    public int port() {
        if (server == null)
            throw new IllegalStateException("SOSGSGMP: a joining PC doesn't listen");
        return server.socket().getLocalPort();
    }

    /** Queues a message for a peer. Unknown or gone peers are skipped. */
    public void send(int peer, Message m) {
        byte[] payload = Protocol.encode(m);
        ByteBuffer frame = ByteBuffer.allocate(4 + payload.length);
        frame.putInt(payload.length).put(payload).flip();
        commands.add(new Command.Send(peer, frame));
        selector.wakeup();
    }

    /** Closes a peer's connection once the messages queued for it are sent. No event follows. */
    public void disconnect(int peer) {
        commands.add(new Command.Drop(peer));
        selector.wakeup();
    }

    /** The next event, or null if there is none. Game thread. */
    public Event poll() {
        return events.poll();
    }

    /** Sends what is queued (for up to a second), then closes every connection. No events after this. */
    @Override
    public void close() {
        closing = true;
        selector.wakeup();
        try {
            thread.join(CLOSE_FLUSH_MS + 1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ I/O thread

    private static final class Peer {
        final int id;
        final SocketChannel channel;
        final ByteBuffer in = ByteBuffer.allocate(4 + Protocol.MAX_MESSAGE);
        final ArrayDeque<ByteBuffer> out = new ArrayDeque<>();
        SelectionKey key;
        boolean connected;
        boolean dropWhenSent;
        long connectDeadline;

        Peer(int id, SocketChannel channel) {
            this.id = id;
            this.channel = channel;
        }
    }

    /** Top-level boundary of the I/O thread: a failure of the selector itself ends the node and reports it. */
    private void run() {
        try {
            if (connectTo != null)
                startConnect();
            while (!closing) {
                selector.select(100);
                runCommands();
                for (SelectionKey key : selector.selectedKeys())
                    handle(key);
                selector.selectedKeys().clear();
                checkConnectTimeout();
            }
            runCommands();
            flush();
        } catch (IOException | RuntimeException e) {
            for (Peer p : new ArrayList<>(peers.values()))
                drop(p, "SOSGSGMP: network thread failed: " + e);
        } finally {
            for (Peer p : new ArrayList<>(peers.values()))
                closeQuietly(p);
            peers.clear();
            try {
                if (server != null)
                    server.close();
                selector.close();
            } catch (IOException e) {
                // Closing at shutdown: nothing is left to tell, and the game thread no longer listens.
                events.add(new Event.Disconnected(-1, "SOSGSGMP: closing the network failed: " + e));
            }
        }
    }

    private void startConnect() {
        SocketChannel ch = null;
        Peer p = null;
        try {
            ch = SocketChannel.open();
            p = new Peer(nextPeer++, ch);
            peers.put(p.id, p);
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            InetSocketAddress address = new InetSocketAddress(connectTo.getHostString(), connectTo.getPort());
            if (address.isUnresolved())
                throw new IOException("unknown host " + connectTo.getHostString());
            p.connectDeadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
            boolean done = ch.connect(address);
            p.key = ch.register(selector, done ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT, p);
            if (done)
                connected(p);
        } catch (IOException e) {
            if (p != null)
                drop(p, "Could not connect to " + connectTo.getHostString() + ":" + connectTo.getPort() + ": " + e.getMessage());
            else
                events.add(new Event.Disconnected(HOST, "Could not open a connection: " + e.getMessage()));
        }
    }

    private void connected(Peer p) {
        p.connected = true;
        events.add(new Event.Connected(p.id));
    }

    private void checkConnectTimeout() {
        long now = System.currentTimeMillis();
        for (Peer p : new ArrayList<>(peers.values()))
            if (!p.connected && now > p.connectDeadline)
                drop(p, "No answer from " + connectTo.getHostString() + ":" + connectTo.getPort() + " within " + CONNECT_TIMEOUT_MS / 1000 + " s.");
    }

    private void runCommands() {
        Command c;
        while ((c = commands.poll()) != null) {
            switch (c) {
                case Command.Send s -> {
                    Peer p = peers.get(s.peer());
                    if (p != null && !p.dropWhenSent) {
                        p.out.add(s.bytes());
                        updateInterest(p);
                    }
                }
                case Command.Drop d -> {
                    Peer p = peers.get(d.peer());
                    if (p != null) {
                        p.dropWhenSent = true;
                        if (p.out.isEmpty())
                            closeQuietly(p);
                        else
                            updateInterest(p);
                    }
                }
            }
        }
    }

    private void handle(SelectionKey key) {
        if (!key.isValid())
            return;
        if (key.channel() == server) {
            accept();
            return;
        }
        Peer p = (Peer) key.attachment();
        try {
            if (key.isConnectable()) {
                p.channel.finishConnect();
                connected(p);
                updateInterest(p);
            }
            if (key.isValid() && key.isReadable())
                read(p);
            if (key.isValid() && key.isWritable())
                write(p);
        } catch (IOException e) {
            drop(p, p.connected ? "Connection lost: " + e.getMessage()
                    : "Could not connect to " + connectTo.getHostString() + ":" + connectTo.getPort() + ": " + e.getMessage());
        }
    }

    private void accept() {
        SocketChannel ch = null;
        try {
            ch = server.accept();
            if (ch == null)
                return;
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            Peer p = new Peer(nextPeer++, ch);
            peers.put(p.id, p);
            p.key = ch.register(selector, SelectionKey.OP_READ, p);
            connected(p);
        } catch (IOException e) {
            // One failed accept doesn't stop the host; the joining PC sees its connection fail.
            events.add(new Event.Disconnected(-1, "SOSGSGMP: accepting a connection failed: " + e));
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException e2) {
                    events.add(new Event.Disconnected(-1, "SOSGSGMP: closing a failed connection failed: " + e2));
                }
            }
        }
    }

    private void read(Peer p) throws IOException {
        if (p.channel.read(p.in) < 0) {
            drop(p, "The other side closed the connection.");
            return;
        }
        p.in.flip();
        while (p.in.remaining() >= 4) {
            int length = p.in.getInt(p.in.position());
            if (length <= 0 || length > Protocol.MAX_MESSAGE) {
                drop(p, "Malformed data: a message of " + length + " bytes.");
                return;
            }
            if (p.in.remaining() < 4 + length)
                break;
            p.in.getInt();
            byte[] bytes = new byte[length];
            p.in.get(bytes);
            Message m;
            try {
                m = Protocol.decode(bytes);
            } catch (IOException e) {
                drop(p, "Malformed data: " + e.getMessage());
                return;
            }
            events.add(new Event.Received(p.id, m));
        }
        p.in.compact();
    }

    private void write(Peer p) throws IOException {
        while (!p.out.isEmpty()) {
            ByteBuffer b = p.out.peek();
            p.channel.write(b);
            if (b.hasRemaining())
                break;
            p.out.poll();
        }
        if (p.out.isEmpty() && p.dropWhenSent)
            closeQuietly(p);
        else
            updateInterest(p);
    }

    private void updateInterest(Peer p) {
        if (p.key == null || !p.key.isValid() || !p.connected)
            return;
        p.key.interestOps(SelectionKey.OP_READ | (p.out.isEmpty() ? 0 : SelectionKey.OP_WRITE));
    }

    /** At close: send what is queued, for up to {@link #CLOSE_FLUSH_MS}. */
    private void flush() throws IOException {
        long deadline = System.currentTimeMillis() + CLOSE_FLUSH_MS;
        while (System.currentTimeMillis() < deadline) {
            boolean pending = false;
            for (Peer p : new ArrayList<>(peers.values())) {
                if (!p.connected || p.out.isEmpty())
                    continue;
                try {
                    write(p);
                } catch (IOException e) {
                    closeQuietly(p);
                }
                pending |= !p.out.isEmpty();
            }
            if (!pending)
                return;
            selector.select(10);
            selector.selectedKeys().clear();
        }
    }

    private void drop(Peer p, String reason) {
        closeQuietly(p);
        events.add(new Event.Disconnected(p.id, reason));
    }

    private void closeQuietly(Peer p) {
        peers.remove(p.id);
        if (p.key != null)
            p.key.cancel();
        try {
            p.channel.close();
        } catch (IOException e) {
            // The connection is gone either way; the reason the caller reports already says why.
            events.add(new Event.Disconnected(p.id, "SOSGSGMP: closing a connection failed: " + e));
        }
    }
}
