package sosgsgmp.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 *
 * <p>A peer's line outlives its socket (SOS-178): every message goes as a {@link Message.Sequenced} and is kept until
 * acknowledged ({@link Resend}). When a socket breaks (an error, not the other side closing it), a joining PC connects
 * again every {@link #RECONNECT_MS} with its token, the host gives it its line back, and each side sends again what the
 * other lacks. The game sees only a silent peer meanwhile. A new connection first says who it is with
 * {@link Message.Resume}; one that doesn't (another version) gets a line that can't come back.
 */
public final class TcpNode implements Node {

    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long CLOSE_FLUSH_MS = 1_000;
    /** How long a new connection has to say who it is, and a joining PC's host to answer it (SOS-178). */
    private static final long HANDSHAKE_MS = 10_000;
    /** How often a joining PC tries its host again while its line is broken (SOS-178). */
    static final long RECONNECT_MS = 2_000;

    private sealed interface Command {
        record Send(int peer, byte[] bytes) implements Command {}
        record Drop(int peer) implements Command {}
        record Cut() implements Command {}
    }

    private final Selector selector;
    private final ServerSocketChannel server;
    private final InetSocketAddress connectTo;
    private final Queue<Event> events = new ConcurrentLinkedQueue<>();
    private final Queue<Command> commands = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean closing;
    private final Liveness liveness = new Liveness();
    /** A joining PC's connection token: who it is when it connects again (SOS-178); never 0. */
    private final long token;

    // Owned by the I/O thread.
    private final Map<Integer, Line> lines = new HashMap<>();
    private final List<Conn> conns = new ArrayList<>();
    private final Resend resend = new Resend();
    private SelectionKey serverKey;
    private int nextPeer = HOST;
    /** A joining PC whose line is broken: when it next tries its host; 0 while it doesn't. */
    private long reconnectAt;
    /** A joining PC told its sessions it reached its host. */
    private boolean connectedOnce;

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
        long t = 0;
        if (connectTo != null) {
            SecureRandom random = new SecureRandom();
            while (t == 0)
                t = random.nextLong();
        }
        this.token = t;
        if (server != null)
            serverKey = server.register(selector, SelectionKey.OP_ACCEPT);
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

    @Override
    public void send(int peer, Message m) {
        commands.add(new Command.Send(peer, Protocol.encode(m)));
        selector.wakeup();
    }

    /** {@code bytes}, one message's, as they go on the wire: their length, then them. */
    private static ByteBuffer frame(byte[] bytes) {
        ByteBuffer frame = ByteBuffer.allocate(4 + bytes.length);
        frame.putInt(bytes.length).put(bytes).flip();
        return frame;
    }

    @Override
    public void disconnect(int peer) {
        commands.add(new Command.Drop(peer));
        selector.wakeup();
    }

    @Override
    public Event poll() {
        return events.poll();
    }

    @Override
    public String describe() {
        return server != null ? "direct IP, listening on port " + server.socket().getLocalPort()
                : "direct IP to " + connectTo.getHostString() + ":" + connectTo.getPort();
    }

    @Override
    public long silentMs(int peer) {
        return liveness.silentMs(peer, Liveness.now());
    }

    @Override
    public int rttMs(int peer) {
        return liveness.rttMs(peer);
    }

    @Override
    public void silence(int seconds) {
        liveness.silence(Liveness.now() + seconds * 1000L);
    }

    @Override
    public void cutAll() {
        commands.add(new Command.Cut());
        selector.wakeup();
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

    /** A peer as the sessions know it: its number, its streams, and the socket that carries it now, if any. */
    private static final class Line {
        final int id;
        /** The joining PC's token; 0: a PC that didn't say (another version), whose line can't come back. */
        final long token;
        /** The socket now; null while the line is broken. */
        Conn conn;
        /** The handshake on {@link #conn} is done: messages go. */
        boolean resumed;
        /** A handshake was done once: a broken socket is waited for, not a disconnect. */
        boolean known;
        /** The sessions dropped it: once what is queued for it went, it ends. */
        boolean dropWhenSent;

        Line(int id, long token) {
            this.id = id;
            this.token = token;
        }
    }

    /** One socket. */
    private static final class Conn {
        final SocketChannel channel;
        final ByteBuffer in = ByteBuffer.allocate(4 + Protocol.MAX_FRAME);
        final ArrayDeque<ByteBuffer> out = new ArrayDeque<>();
        SelectionKey key;
        boolean connected;
        boolean closeWhenSent;
        /** Until when it may connect, or say who it is, or (a joining PC's) wait for its host's answer; 0: no limit. */
        long deadline;
        /** Its line; null for a host's new socket that hasn't said who it is. */
        Line line;

        Conn(SocketChannel channel) {
            this.channel = channel;
        }
    }

    /** Top-level boundary of the I/O thread: a failure of the selector itself ends the node and reports it. */
    private void run() {
        try {
            if (connectTo != null)
                startConnect();
            while (!closing) {
                long now = Liveness.now();
                if (serverKey != null) // a test outage: new connections wait in the backlog (SOS-178)
                    serverKey.interestOps(liveness.silenced(now) ? 0 : SelectionKey.OP_ACCEPT);
                selector.select(100);
                runCommands();
                beat();
                for (SelectionKey key : selector.selectedKeys())
                    handle(key);
                selector.selectedKeys().clear();
                checkDeadlines();
                reconnect();
            }
            runCommands();
            flush();
        } catch (IOException | RuntimeException e) {
            for (Line l : new ArrayList<>(lines.values()))
                endLine(l, "SOSGSGMP: network thread failed: " + e);
        } finally {
            for (Conn c : new ArrayList<>(conns))
                closeConn(c, false);
            lines.clear();
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

    /** A joining PC connects to its host: the first time, or again once its line broke. */
    private void startConnect() {
        Line l = lines.get(HOST);
        if (l == null) {
            l = new Line(HOST, token);
            lines.put(HOST, l);
            resend.add(HOST);
        }
        Conn c = null;
        try {
            c = new Conn(SocketChannel.open());
            conns.add(c);
            c.line = l;
            l.conn = c;
            c.channel.configureBlocking(false);
            c.channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            InetSocketAddress address = new InetSocketAddress(connectTo.getHostString(), connectTo.getPort());
            if (address.isUnresolved())
                throw new IOException("unknown host " + connectTo.getHostString());
            c.deadline = Liveness.now() + CONNECT_TIMEOUT_MS;
            boolean done = c.channel.connect(address);
            c.key = c.channel.register(selector, done ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT, c);
            if (done)
                connected(c);
        } catch (IOException e) {
            String reason = "Could not connect to " + connectTo.getHostString() + ":" + connectTo.getPort() + ": " + e.getMessage();
            if (c != null)
                broken(c, reason);
            else
                endLine(l, "Could not open a connection: " + e.getMessage());
        }
    }

    /** A joining PC's socket is up: it says who it is first (SOS-178); the first time, the sessions hear of it. */
    private void connected(Conn c) {
        c.connected = true;
        c.deadline = Liveness.now() + HANDSHAKE_MS;
        Line l = c.line;
        c.out.add(frame(Protocol.encode(new Message.Resume(token, resend.received(l.id)))));
        updateInterest(c);
        if (!connectedOnce) {
            connectedOnce = true;
            liveness.add(l.id, Liveness.now());
            events.add(new Event.Connected(l.id));
        }
    }

    private void checkDeadlines() {
        long now = Liveness.now();
        for (Conn c : new ArrayList<>(conns)) {
            if (c.deadline == 0 || now <= c.deadline)
                continue;
            if (c.line == null)
                closeConn(c, false); // a host's new socket that never said who it is
            else if (!c.connected)
                broken(c, "No answer from " + connectTo.getHostString() + ":" + connectTo.getPort() + " within " + CONNECT_TIMEOUT_MS / 1000 + " s.");
            else
                broken(c, "The host at " + connectTo.getHostString() + ":" + connectTo.getPort() + " didn't answer within "
                        + HANDSHAKE_MS / 1000 + " s. Check that both players installed the same build.");
        }
    }

    /** A joining PC whose line is broken tries its host again, unless a test outage is under way. */
    private void reconnect() {
        if (connectTo == null || reconnectAt == 0)
            return;
        long now = Liveness.now();
        Line l = lines.get(HOST);
        if (l == null || l.conn != null) {
            reconnectAt = 0;
            return;
        }
        if (now < reconnectAt || liveness.silenced(now))
            return;
        reconnectAt = now + RECONNECT_MS;
        startConnect();
    }

    private void runCommands() {
        Command c;
        while ((c = commands.poll()) != null) {
            switch (c) {
                case Command.Send s -> {
                    Line l = lines.get(s.peer());
                    if (l == null || l.dropWhenSent)
                        break;
                    byte[] wire = resend.wrap(l.id, s.bytes());
                    if (l.resumed && l.conn != null) {
                        l.conn.out.add(frame(wire));
                        updateInterest(l.conn);
                    }
                }
                case Command.Drop d -> {
                    Line l = lines.get(d.peer());
                    if (l == null)
                        break;
                    l.dropWhenSent = true;
                    if (l.resumed && l.conn != null) {
                        l.conn.closeWhenSent = true;
                        if (l.conn.out.isEmpty())
                            endLine(l, null);
                        else
                            updateInterest(l.conn);
                    } else if (server == null || !l.known) {
                        endLine(l, null); // a joining PC gives up on its host; a line that can't come back ends
                    } // else a host keeps what it queued for a broken line: the PC gets it if it comes back
                }
                case Command.Cut x -> {
                    for (Conn conn : new ArrayList<>(conns)) {
                        Line l = conn.line;
                        closeConn(conn, true);
                        if (l != null && l.conn == conn)
                            broken(conn, "Test: the connection was cut (SOS-178).");
                    }
                }
            }
        }
    }

    /** A heartbeat to every peer whose beat is due (SOS-177), with what this PC received from it (SOS-178). */
    private void beat() {
        long now = Liveness.now();
        for (Line l : lines.values()) {
            if (l.conn == null || !l.resumed || l.dropWhenSent)
                continue;
            Message.Heartbeat h = liveness.due(l.id, now, resend.received(l.id));
            if (h != null) {
                l.conn.out.add(frame(Protocol.encode(h)));
                updateInterest(l.conn);
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
        Conn c = (Conn) key.attachment();
        try {
            if (key.isConnectable()) {
                c.channel.finishConnect();
                connected(c);
            }
            if (key.isValid() && key.isReadable())
                read(c);
            if (key.isValid() && key.isWritable())
                write(c);
        } catch (IOException e) {
            broken(c, c.connected ? "Connection lost: " + e.getMessage()
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
            Conn c = new Conn(ch);
            c.connected = true;
            c.deadline = Liveness.now() + HANDSHAKE_MS;
            conns.add(c);
            c.key = ch.register(selector, SelectionKey.OP_READ, c);
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

    private void read(Conn c) throws IOException {
        int read = c.channel.read(c.in);
        if (read < 0) {
            closedByOtherSide(c);
            return;
        }
        if (read > 0 && c.line != null && c.line.conn == c)
            liveness.heard(c.line.id, Liveness.now()); // a big message counts while its bytes arrive
        c.in.flip();
        while (c.in.remaining() >= 4) {
            int length = c.in.getInt(c.in.position());
            if (length <= 0 || length > Protocol.MAX_FRAME) {
                malformed(c, "Malformed data: a message of " + length + " bytes.");
                return;
            }
            if (c.in.remaining() < 4 + length)
                break;
            c.in.getInt();
            byte[] bytes = new byte[length];
            c.in.get(bytes);
            Message m;
            try {
                m = Protocol.decode(bytes);
            } catch (IOException e) {
                malformed(c, "Malformed data: " + e.getMessage());
                return;
            }
            if (!receive(c, m))
                return;
        }
        c.in.compact();
    }

    /** One message from {@code c}; false once {@code c} is closed. */
    private boolean receive(Conn c, Message m) {
        Line l = c.line;
        if (l == null)
            return identify(c, m);
        if (l.conn != c) {
            closeConn(c, false); // a socket another took over from
            return false;
        }
        long now = Liveness.now();
        switch (m) {
            case Message.Heartbeat h -> {
                liveness.beat(l.id, h, now);
                resend.acked(l.id, h.received());
            }
            case Message.Resumed r -> {
                if (r.received() < 0) {
                    endLine(l, "The host no longer knows this PC's connection: it started again, or removed this player.");
                    return false;
                }
                c.deadline = 0;
                l.resumed = true;
                l.known = true;
                liveness.heard(l.id, now);
                return sendAgain(c, r.received());
            }
            case Message.Resume r -> { // an ask for what is missing on a live socket
                c.out.add(frame(Protocol.encode(new Message.Resumed(resend.received(l.id)))));
                return sendAgain(c, r.received());
            }
            case Message.Sequenced q -> {
                switch (resend.in(l.id, q.seq())) {
                    case NEXT -> events.add(new Event.Received(l.id, q.message()));
                    case REPEAT -> {
                    }
                    case GAP -> { // a socket loses nothing: a broken peer; connecting again sorts it out
                        broken(c, "Message " + q.seq() + " came after " + resend.received(l.id) + ".");
                        return false;
                    }
                }
            }
            default -> events.add(new Event.Received(l.id, m)); // another version's, which the sessions refuse
        }
        return true;
    }

    /** Sends again on {@code c} what its peer lacks after its first {@code received}; false if that broke the line. */
    private boolean sendAgain(Conn c, long received) {
        Line l = c.line;
        List<byte[]> again;
        try {
            again = resend.after(l.id, received);
        } catch (IllegalArgumentException e) {
            endLine(l, "Malformed data: " + e.getMessage());
            return false;
        }
        for (byte[] wire : again)
            c.out.add(frame(wire));
        if (l.dropWhenSent)
            c.closeWhenSent = true;
        updateInterest(c);
        return true;
    }

    /**
     * A host's new socket says who it is (SOS-178): a known token takes its line back, an unknown one starts a new
     * line, and anything else (another version's hello) gets a line that can't come back. False once {@code c} is
     * closed.
     */
    private boolean identify(Conn c, Message m) {
        c.deadline = 0;
        if (!(m instanceof Message.Resume r)) {
            Line l = newLine(0);
            attach(l, c);
            events.add(new Event.Connected(l.id));
            return receive(c, m);
        }
        Line l = r.token() == 0 ? null : byToken(r.token());
        if (l == null && r.token() != 0 && r.received() == 0) {
            l = newLine(r.token());
            attach(l, c);
            l.known = true;
            c.out.add(frame(Protocol.encode(new Message.Resumed(0))));
            updateInterest(c);
            events.add(new Event.Connected(l.id));
            return true;
        }
        if (l == null) {
            c.out.add(frame(Protocol.encode(new Message.Resumed(-1))));
            c.closeWhenSent = true;
            updateInterest(c);
            return true;
        }
        if (l.conn != null)
            closeConn(l.conn, false); // half open: the PC's new socket wins
        attach(l, c);
        liveness.heard(l.id, Liveness.now());
        c.out.add(frame(Protocol.encode(new Message.Resumed(resend.received(l.id)))));
        events.add(new Event.Disconnected(-1, "SOSGSGMP: peer " + l.id + " connected again; its messages resume"));
        return sendAgain(c, r.received());
    }

    private Line newLine(long token) {
        Line l = new Line(nextPeer++, token);
        lines.put(l.id, l);
        resend.add(l.id);
        liveness.add(l.id, Liveness.now());
        return l;
    }

    private static void attach(Line l, Conn c) {
        l.conn = c;
        l.resumed = true;
        c.line = l;
    }

    private Line byToken(long t) {
        for (Line l : lines.values())
            if (l.token == t)
                return l;
        return null;
    }

    private void write(Conn c) throws IOException {
        while (!c.out.isEmpty()) {
            ByteBuffer b = c.out.peek();
            c.channel.write(b);
            if (b.hasRemaining())
                break;
            c.out.poll();
        }
        if (c.out.isEmpty() && c.closeWhenSent) {
            Line l = c.line;
            closeConn(c, false);
            if (l != null && l.conn == c)
                endLine(l, null);
        } else {
            updateInterest(c);
        }
    }

    private void updateInterest(Conn c) {
        if (c.key == null || !c.key.isValid() || !c.connected)
            return;
        c.key.interestOps(SelectionKey.OP_READ | (c.out.isEmpty() ? 0 : SelectionKey.OP_WRITE));
    }

    /** At close: send what is queued, for up to {@link #CLOSE_FLUSH_MS}. */
    private void flush() throws IOException {
        long deadline = System.currentTimeMillis() + CLOSE_FLUSH_MS;
        while (System.currentTimeMillis() < deadline) {
            boolean pending = false;
            for (Conn c : new ArrayList<>(conns)) {
                if (!c.connected || c.out.isEmpty())
                    continue;
                try {
                    write(c);
                } catch (IOException e) {
                    closeConn(c, false);
                }
                pending |= conns.contains(c) && !c.out.isEmpty();
            }
            if (!pending)
                return;
            selector.select(10);
            selector.selectedKeys().clear();
        }
    }

    /** The other side closed {@code c} itself: it left (a quit or a crash), unlike a broken socket. */
    private void closedByOtherSide(Conn c) {
        Line l = c.line;
        closeConn(c, false);
        if (l != null && l.conn == c)
            endLine(l, l.dropWhenSent ? null : "The other side closed the connection.");
    }

    /** {@code c} sent something that breaks the protocol: its line ends. */
    private void malformed(Conn c, String reason) {
        Line l = c.line;
        closeConn(c, false);
        if (l != null && l.conn == c)
            endLine(l, reason);
    }

    /**
     * {@code c} broke (an error, a timeout, a test cut): a line that had its handshake waits for its PC to come back (a
     * joining PC tries its host again); one that never did ends, with {@code reason} for the sessions.
     */
    private void broken(Conn c, String reason) {
        Line l = c.line;
        boolean working = l != null && l.conn == c && l.resumed;
        closeConn(c, false);
        if (l == null || l.conn != c)
            return;
        l.conn = null;
        l.resumed = false;
        if (!l.known || closing || (l.dropWhenSent && server == null)) {
            endLine(l, l.dropWhenSent ? null : reason);
            return;
        }
        if (server == null)
            reconnectAt = Liveness.now() + (working ? 0 : RECONNECT_MS);
        events.add(new Event.Disconnected(-1, "SOSGSGMP: the connection to peer " + l.id + " broke (" + reason
                + "); waiting for it to come back"));
    }

    /** {@code l} is gone for good; {@code reason}, if any, goes to the sessions as its disconnect. */
    private void endLine(Line l, String reason) {
        lines.remove(l.id);
        resend.remove(l.id);
        liveness.remove(l.id);
        if (l.conn != null) {
            Conn c = l.conn;
            l.conn = null;
            closeConn(c, false);
        }
        if (reason != null)
            events.add(new Event.Disconnected(l.id, reason));
    }

    /** Closes a socket; {@code reset}: at once, as a broken line does (a test cut). */
    private void closeConn(Conn c, boolean reset) {
        if (!conns.remove(c))
            return;
        if (c.key != null)
            c.key.cancel();
        try {
            if (reset && c.channel.isConnected())
                c.channel.setOption(StandardSocketOptions.SO_LINGER, 0);
            c.channel.close();
        } catch (IOException e) {
            // The socket is gone either way; the reason the caller reports already says why.
            events.add(new Event.Disconnected(-1, "SOSGSGMP: closing a connection failed: " + e));
        }
    }
}
