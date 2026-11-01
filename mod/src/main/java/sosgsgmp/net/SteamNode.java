package sosgsgmp.net;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Connections over Steam P2P (SOS-60, spec §13: App ID 1162750): the host owns a Steam lobby for friends; joining PCs
 * enter it and talk to its owner. Peers are lobby members: the host numbers them from 1 as they enter; a joining PC's
 * host is {@link #HOST}. A P2P session is accepted only from a member, and a member who leaves the lobby is a
 * disconnect; for a joining PC, the owner leaving is losing the host.
 *
 * <p>One I/O thread (AGENTS.md, approved exception) creates the {@link SteamPort}, makes every Steam call, runs Steam's
 * callbacks and reads packets; it only decodes messages and queues {@link Event}s. Each message is one reliable packet
 * of {@link Protocol#encode}'s bytes ({@link Protocol#MAX_MESSAGE} is well under Steam's 1 MB reliable limit).
 */
public final class SteamNode implements Node {

    /** The lobby data key that marks a lobby as this mod's; its value is the protocol version. */
    public static final String LOBBY_KEY = "sosgsgmp";

    private static final long LOOP_MS = 5;
    private static final long CLOSE_FLUSH_MS = 1_000;
    private static final long FRIENDS_MS = 2_000;
    /** How long a joining PC waits for Steam's lobby, and then for the host's first message (SOS-105). */
    private static final long JOIN_MS = 30_000;
    /** How often members' sessions are accepted again: Steam's newer messages forget an accept made before the first packet. */
    private static final long ACCEPT_MS = 250;

    private sealed interface Command {
        record Send(int peer, byte[] bytes) implements Command {}
        record Drop(int peer) implements Command {}
        record Invite() implements Command {}
        record Join(long lobby) implements Command {}
    }

    private final boolean hosting;
    private final Queue<Event> events = new ConcurrentLinkedQueue<>();
    private final Queue<Command> commands = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private volatile boolean closing;
    /** The lobby once created or entered; 0 before. */
    private volatile long lobby;
    /** Why the lobby couldn't be created or entered; null otherwise. */
    private volatile String failure;
    /** While browsing: friends' lobbies, refreshed every {@link #FRIENDS_MS}. */
    private volatile List<SteamPort.FriendLobby> friendLobbies = List.of();
    /** While browsing: a lobby whose invite the user accepted in Steam's overlay; 0 if none. */
    private volatile long invited;
    /** While set and false, the node closes itself (SOS-103: the main menu's invite watch, once the menu is gone). */
    private volatile BooleanSupplier wanted;

    // Owned by the I/O thread.
    private SteamPort port;
    private final Map<Integer, Long> users = new HashMap<>();
    private final Map<Long, Integer> peers = new HashMap<>();
    private int nextPeer = 1;
    private long owner;
    /** A joining node has asked to enter a lobby. */
    private boolean entering;
    /** Until when a joining node waits for the lobby, then for the host's first message; 0 when it doesn't wait. */
    private long joinDeadline;
    private final long joinMs;

    /** Written once on the I/O thread: only that thread may call the port. */
    private volatile String persona;

    /** Hosts: creates a lobby for up to {@code maxMembers} friends. Joining PCs appear as they enter it. */
    public static SteamNode host(Supplier<SteamPort> steam, int maxMembers) {
        return new SteamNode(steam, true, maxMembers, 0, JOIN_MS);
    }

    /** Joins {@code lobby}: success arrives as {@link Event.Connected} for {@link #HOST}, failure as {@link Event.Disconnected}. */
    public static SteamNode join(Supplier<SteamPort> steam, long lobby) {
        return join(steam, lobby, JOIN_MS);
    }

    static SteamNode join(Supplier<SteamPort> steam, long lobby, long joinMs) {
        return new SteamNode(steam, false, 0, lobby, joinMs);
    }

    /** Starts Steam without a lobby yet: friends' lobbies and accepted invites appear; {@link #join(long)} picks one. */
    public static SteamNode browse(Supplier<SteamPort> steam) {
        return new SteamNode(steam, false, 0, 0, JOIN_MS);
    }

    private SteamNode(Supplier<SteamPort> steam, boolean hosting, int maxMembers, long joinLobby, long joinMs) {
        this.hosting = hosting;
        this.joinMs = joinMs;
        thread = new Thread(() -> run(steam, maxMembers, joinLobby), "SOSGSGMP steam");
        thread.setDaemon(true);
        thread.start();
    }

    /** This PC's name on Steam, null until Steam has started. */
    public String personaName() {
        return persona;
    }

    /** The lobby, once created or entered; 0 before (the invite dialog needs it). */
    public long lobby() {
        return lobby;
    }

    /** Why the lobby couldn't be created or entered, or null. */
    public String failure() {
        return failure;
    }

    /** While browsing: friends playing Songs of Syx in a lobby. */
    public List<SteamPort.FriendLobby> friendLobbies() {
        return friendLobbies;
    }

    /** While browsing: the lobby of an invite the user accepted in Steam's overlay, or 0. */
    public long invited() {
        return invited;
    }

    /**
     * The node closes itself, on its I/O thread, as soon as {@code wanted} is false; null keeps it open (SOS-103). For
     * a node the game thread may stop calling, such as the main menu's invite watch once a game starts.
     */
    public void closeWhenUnwanted(BooleanSupplier wanted) {
        this.wanted = wanted;
    }

    /** A browsing node enters {@code lobby}; the outcome arrives as for {@link #join(Supplier, long)}. Once only. */
    public void join(long lobby) {
        commands.add(new Command.Join(lobby));
    }

    /** Opens Steam's invite dialog for this lobby (a host's, once created). */
    public void invite() {
        commands.add(new Command.Invite());
    }

    @Override
    public void send(int peer, Message m) {
        commands.add(new Command.Send(peer, Protocol.encode(m)));
    }

    @Override
    public void disconnect(int peer) {
        commands.add(new Command.Drop(peer));
    }

    @Override
    public Event poll() {
        return events.poll();
    }

    @Override
    public String describe() {
        return "Steam P2P, " + (hosting ? "hosting" : "joined") + " lobby " + lobby;
    }

    @Override
    public void close() {
        closing = true;
        try {
            thread.join(CLOSE_FLUSH_MS + 1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ I/O thread

    private void run(Supplier<SteamPort> steam, int maxMembers, long joinLobby) {
        try {
            port = steam.get();
            persona = port.personaName();
        } catch (RuntimeException | LinkageError e) {
            // Steam not running, the game not owned, the native libraries missing: the screen shows why.
            fail("Steam isn't available: " + e);
            return;
        }
        // The I/O thread's top-level boundary: whatever breaks it ends this node, with the reason on the screen.
        try {
            port.listener(new Callbacks());
            if (hosting)
                port.createLobby(maxMembers);
            else if (joinLobby != 0)
                enter(joinLobby);
            long friendsAt = 0;
            long acceptAt = 0;
            while (!closing && unwantedNot()) {
                runCommands();
                port.runCallbacks();
                if (System.currentTimeMillis() >= acceptAt) {
                    for (long user : peers.keySet())
                        port.acceptSession(user);
                    acceptAt = System.currentTimeMillis() + ACCEPT_MS;
                }
                readPackets();
                joinTimeout();
                if (!hosting && !entering && System.currentTimeMillis() >= friendsAt) {
                    friendLobbies = List.copyOf(port.friendLobbies());
                    friendsAt = System.currentTimeMillis() + FRIENDS_MS;
                }
                Thread.sleep(LOOP_MS);
            }
            runCommands(); // what was queued before close goes out
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | LinkageError e) {
            fail("Steam stopped working: " + e);
            for (int peer : users.keySet().toArray(new Integer[0]))
                drop(peer, "Steam stopped working: " + e);
        } finally {
            for (long user : peers.keySet())
                port.closeSession(user);
            if (lobby != 0)
                port.leaveLobby(lobby);
            port.close();
        }
    }

    private boolean unwantedNot() {
        BooleanSupplier w = wanted;
        return w == null || w.getAsBoolean();
    }

    private void enter(long id) {
        if (entering || hosting)
            return;
        entering = true;
        joinDeadline = System.currentTimeMillis() + joinMs;
        port.joinLobby(id);
    }

    /** A join Steam never answers ends with a reason instead of waiting for ever (SOS-105). */
    private void joinTimeout() {
        if (joinDeadline == 0 || System.currentTimeMillis() < joinDeadline)
            return;
        joinDeadline = 0;
        String reason = lobby == 0
                ? "Steam didn't let this PC into the lobby within " + joinMs / 1000 + " seconds. Check that you are friends and"
                        + " that the host's lobby is still open, then try again."
                : "The host's PC didn't answer over Steam within " + joinMs / 1000 + " seconds. Check that both players installed"
                        + " the same build, or connect by IP address instead.";
        port.note(reason);
        fail(reason);
    }

    private void fail(String reason) {
        failure = reason;
        events.add(new Event.Disconnected(hosting ? -1 : HOST, reason));
    }

    private void runCommands() {
        Command c;
        while ((c = commands.poll()) != null) {
            switch (c) {
                case Command.Send s -> {
                    Long user = users.get(s.peer());
                    if (user != null && !port.send(user, s.bytes()))
                        drop(s.peer(), "Steam refused a message to this player.");
                }
                case Command.Join j -> enter(j.lobby());
                case Command.Invite i -> {
                    if (lobby != 0)
                        port.invite(lobby);
                }
                case Command.Drop d -> {
                    Long user = users.remove(d.peer());
                    if (user != null) {
                        peers.remove(user);
                        port.closeSession(user);
                    }
                }
            }
        }
    }

    private void readPackets() {
        long[] from = new long[1];
        byte[] bytes;
        while ((bytes = port.read(from)) != null) {
            Integer peer = peers.get(from[0]);
            if (peer == null)
                continue; // not a member of this lobby: dropped
            joinDeadline = 0;
            try {
                events.add(new Event.Received(peer, Protocol.decode(bytes)));
            } catch (IOException e) {
                drop(peer, "It sent a broken message: " + e.getMessage());
            }
        }
    }

    /** Forgets a peer and closes its session, with an event for the game thread. */
    private void drop(int peer, String reason) {
        Long user = users.remove(peer);
        if (user == null)
            return;
        peers.remove(user);
        port.closeSession(user);
        events.add(new Event.Disconnected(peer, reason));
    }

    private void added(int peer, long user) {
        users.put(peer, user);
        peers.put(user, peer);
        events.add(new Event.Connected(peer));
    }

    private final class Callbacks implements SteamPort.Listener {

        @Override
        public void lobbyCreated(long id, String error) {
            if (error != null) {
                fail("Steam couldn't create the lobby: " + error);
                return;
            }
            lobby = id;
            port.lobbyData(id, LOBBY_KEY, Integer.toString(Protocol.VERSION));
        }

        @Override
        public void lobbyEntered(long id, String error) {
            if (hosting)
                return; // the host's own lobby, entered as it was created
            joinDeadline = 0;
            if (error != null) {
                fail("Couldn't enter the Steam lobby: " + error);
                return;
            }
            lobby = id;
            joinDeadline = System.currentTimeMillis() + joinMs;
            owner = port.lobbyOwner(id);
            if (owner == 0 || owner == port.self()) {
                joinDeadline = 0;
                fail("The Steam lobby has no host.");
                return;
            }
            added(HOST, owner);
        }

        @Override
        public void memberJoined(long id, long user) {
            if (!hosting || id != lobby || user == port.self() || peers.containsKey(user))
                return;
            port.acceptSession(user); // its hello may come before Steam reports the session request
            added(nextPeer++, user);
        }

        @Override
        public void memberLeft(long id, long user) {
            Integer peer = peers.get(user);
            if (id != lobby || peer == null)
                return;
            drop(peer, hosting ? "Left the Steam lobby." : "The host left the Steam lobby.");
        }

        @Override
        public void lobbyLost(long id) {
            if (!hosting && id == lobby)
                drop(HOST, "Removed from the Steam lobby.");
        }

        @Override
        public void joinRequested(long id) {
            if (!hosting && !entering)
                invited = id;
        }

        @Override
        public void sessionRequested(long user) {
            if (peers.containsKey(user))
                port.acceptSession(user); // only lobby members: anyone else's packets go unread
        }

        @Override
        public void sessionFailed(long user, String reason) {
            Integer peer = peers.get(user);
            if (peer != null)
                drop(peer, "The Steam connection failed: " + reason);
        }
    }
}
