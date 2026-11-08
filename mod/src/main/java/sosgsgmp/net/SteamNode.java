package sosgsgmp.net;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Connections over Steam P2P (SOS-60, spec §13: App ID 1162750): the host owns a Steam lobby for friends; joining PCs
 * enter it and talk to its owner. Peers are lobby members: the host numbers them from 1 as they enter; a joining PC's
 * host is {@link #HOST}. A P2P session is accepted only from a member, and a member who leaves the lobby is a
 * disconnect; for a joining PC, the owner leaving is losing the host. A member who only lost its connection to Steam
 * stays a peer (SOS-177), and a joining PC put out of the lobby for it enters it again.
 *
 * <p>A peer's messages outlive its session (SOS-178): each goes as a {@link Message.Sequenced} and is kept until
 * acknowledged ({@link Resend}). A failed session, or a message Steam refuses, breaks the line instead of dropping the
 * peer: this PC sends {@link Message.Resume} every {@link #RESUME_MS}, which opens a new session, and each side sends
 * again what the other lacks. A message past a gap (one a failed session lost) asks for the rest the same way.
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
    /** How often a broken line asks its peer to resume (SOS-178), and a joining PC put out of the lobby tries it again. */
    static final long RESUME_MS = 2_000;

    private sealed interface Command {
        record Send(int peer, byte[] bytes) implements Command {}
        record Drop(int peer) implements Command {}
        record Invite() implements Command {}
        record Join(long lobby) implements Command {}
        record Cut() implements Command {}
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

    private final Liveness liveness = new Liveness();
    /** When the I/O thread next looks for heartbeats due. */
    private long beatAt;
    private final Resend resend = new Resend();
    /** Broken lines (SOS-178): when each next asks its peer to resume. */
    private final Map<Integer, Long> broken = new HashMap<>();
    /** A host's broken lines the sessions dropped: once the PC comes back and has what was queued, they end. */
    private final Set<Integer> dropWhenSent = new HashSet<>();
    /** A joining PC put out of the lobby as it lost Steam: when it tries to enter again; 0 while it is in. */
    private long rejoinAt;

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
                beat();
                resume();
                rejoin();
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
                    if (user == null || dropWhenSent.contains(s.peer()))
                        break;
                    byte[] wire = resend.wrap(s.peer(), s.bytes());
                    if (!broken.containsKey(s.peer()) && !port.send(user, wire))
                        breakLine(s.peer(), "Steam refused a message to this player.");
                }
                case Command.Cut x -> {
                    for (Map.Entry<Integer, Long> e : users.entrySet()) {
                        port.closeSession(e.getValue());
                        breakLine(e.getKey(), "Test: the connection was cut (SOS-178).");
                    }
                }
                case Command.Join j -> enter(j.lobby());
                case Command.Invite i -> {
                    if (lobby != 0)
                        port.invite(lobby);
                }
                case Command.Drop d -> {
                    if (hosting && broken.containsKey(d.peer()) && users.containsKey(d.peer())) {
                        dropWhenSent.add(d.peer()); // it gets what was queued for it if it comes back
                        break;
                    }
                    Long user = users.remove(d.peer());
                    forget(d.peer());
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
            long now = Liveness.now();
            liveness.heard(peer, now);
            Message m;
            try {
                m = Protocol.decode(bytes);
            } catch (IOException e) {
                drop(peer, "It sent a broken message: " + e.getMessage());
                continue;
            }
            switch (m) {
                case Message.Heartbeat h -> {
                    liveness.beat(peer, h, now);
                    resend.acked(peer, h.received());
                }
                case Message.Resume r -> {
                    port.send(from[0], Protocol.encode(new Message.Resumed(resend.received(peer))));
                    sendAgain(peer, r.received());
                }
                case Message.Resumed r -> sendAgain(peer, r.received());
                case Message.Sequenced q -> {
                    switch (resend.in(peer, q.seq())) {
                        case NEXT -> events.add(new Event.Received(peer, q.message()));
                        case REPEAT -> {
                        }
                        case GAP -> askAgain(peer, now); // a failed session lost what came before it
                    }
                }
                default -> events.add(new Event.Received(peer, m)); // another version's, which the sessions refuse
            }
        }
    }

    /**
     * {@code peer} had our first {@code received} (SOS-178): the line works again, and what it lacks goes again; a
     * line the sessions dropped then ends.
     */
    private void sendAgain(int peer, long received) {
        Long user = users.get(peer);
        if (user == null)
            return;
        List<byte[]> again;
        try {
            again = resend.after(peer, received);
        } catch (IllegalArgumentException e) {
            drop(peer, "It sent a broken message: " + e.getMessage());
            return;
        }
        if (broken.remove(peer) != null)
            port.note((hosting ? "Player " + peer : "The host") + " is back; " + again.size() + " messages go again.");
        for (byte[] wire : again)
            if (!port.send(user, wire)) {
                breakLine(peer, "Steam refused a message to this player.");
                return;
            }
        if (dropWhenSent.contains(peer)) { // its session stays, so Steam still delivers what was just sent
            users.remove(peer);
            peers.remove(user);
            forget(peer);
        }
    }

    /** A message past a gap: asks {@code peer} for the rest, at most once per {@link #RESUME_MS}. */
    private void askAgain(int peer, long now) {
        Long due = broken.get(peer);
        if (due != null && now < due)
            return;
        broken.put(peer, now + RESUME_MS);
        Long user = users.get(peer);
        if (user != null)
            port.send(user, Protocol.encode(new Message.Resume(0, resend.received(peer))));
    }

    /**
     * {@code peer}'s session failed, or Steam refused a message (SOS-178): the peer stays, nothing more goes until it
     * resumes, and this PC asks it to every {@link #RESUME_MS}.
     */
    private void breakLine(int peer, String reason) {
        if (!users.containsKey(peer) || broken.containsKey(peer))
            return;
        broken.put(peer, Liveness.now());
        port.note((hosting ? "Player " + peer : "The host") + ": " + reason + " Waiting for it to come back.");
        events.add(new Event.Disconnected(-1, "SOSGSGMP: the connection to peer " + peer + " broke (" + reason
                + "); waiting for it to come back"));
    }

    /** Each broken line asks its peer to resume when due, unless a test outage is under way. */
    private void resume() {
        long now = Liveness.now();
        if (broken.isEmpty() || liveness.silenced(now))
            return;
        for (Map.Entry<Integer, Long> e : broken.entrySet()) {
            Long user = users.get(e.getKey());
            if (user == null || now < e.getValue())
                continue;
            e.setValue(now + RESUME_MS);
            port.send(user, Protocol.encode(new Message.Resume(0, resend.received(e.getKey()))));
        }
    }

    /** A joining PC put out of the lobby as it lost Steam tries to enter it again; failing changes nothing. */
    private void rejoin() {
        if (rejoinAt == 0 || System.currentTimeMillis() < rejoinAt)
            return;
        rejoinAt = System.currentTimeMillis() + RESUME_MS;
        port.joinLobby(lobby);
    }

    /** A heartbeat to every peer whose beat is due (SOS-177). */
    private void beat() {
        long now = Liveness.now();
        if (now < beatAt)
            return;
        beatAt = now + 100;
        for (int peer : users.keySet().toArray(new Integer[0])) {
            if (broken.containsKey(peer))
                continue;
            Message.Heartbeat h = liveness.due(peer, now, resend.received(peer));
            if (h != null && !port.send(users.get(peer), Protocol.encode(h)))
                breakLine(peer, "Steam refused a message to this player.");
        }
    }

    /** Forgets a peer and closes its session, with an event for the game thread. */
    private void drop(int peer, String reason) {
        Long user = users.remove(peer);
        if (user == null)
            return;
        forget(peer);
        peers.remove(user);
        port.closeSession(user);
        events.add(new Event.Disconnected(peer, reason));
    }

    /** Everything this node keeps for a peer that is gone, but its Steam ID. */
    private void forget(int peer) {
        liveness.remove(peer);
        resend.remove(peer);
        broken.remove(peer);
        dropWhenSent.remove(peer);
    }

    private void added(int peer, long user) {
        resend.add(peer);
        liveness.add(peer, Liveness.now());
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
            if (rejoinAt != 0) { // back in after losing Steam: the host is a peer still
                if (error == null) {
                    rejoinAt = 0;
                    port.note("This PC is back in the Steam lobby.");
                }
                return;
            }
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
        public void memberLeft(long id, long user, boolean lostSteam) {
            Integer peer = peers.get(user);
            if (id != lobby || peer == null)
                return;
            if (lostSteam) { // a blip: its session decides, failing only past Steam's timeout (sessionFailed)
                port.note((hosting ? "Player " + peer : "The host") + " lost its connection to Steam; waiting for its messages.");
                return;
            }
            drop(peer, hosting ? "Left the Steam lobby." : "The host left the Steam lobby.");
        }

        @Override
        public void lobbyLost(long id, boolean lostSteam) {
            if (hosting || id != lobby)
                return;
            if (lostSteam) {
                port.note("This PC lost its connection to Steam; waiting for the host's messages.");
                rejoinAt = System.currentTimeMillis() + RESUME_MS;
                return;
            }
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
                breakLine(peer, "The Steam connection failed: " + reason + ".");
        }
    }
}
