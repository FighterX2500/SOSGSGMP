package sosgsgmp.game;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamMatchmaking;
import com.codedisaster.steamworks.SteamMatchmakingCallback;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamNetworking;
import com.codedisaster.steamworks.SteamNetworkingCallback;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUserCallback;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;
import com.codedisaster.steamworks.SteamAuth;
import com.codedisaster.steamworks.SteamAuthTicket;

import snake2d.LOG;
import sosgsgmp.log.PlayLog;
import sosgsgmp.net.Protocol;
import sosgsgmp.net.SteamLease;
import sosgsgmp.net.SteamPort;

/**
 * {@link SteamPort} over the steamworks4j 1.9.0 the game jar ships (SOS-60). The game process never starts Steam
 * itself (its achievements run in a separate JVM), so the port loads the libraries and initializes the Steam API the
 * first time a node needs it, and never shuts it down (SOS-237, ADR 0012): shutting it down while it still delivered a
 * closed session's news crashed the game. The process has one port, which {@link #lease()} lends to one node at a
 * time; {@link #close()} gives it back. Steam finds the App ID (1162750) in the {@code SteamAppId} environment
 * variable, which Steam sets for a game it launched; a game started outside Steam needs it set too. A test may set
 * Valve's Spacewar (480) instead, so a second account that doesn't own the game can join; the port works under the
 * App ID Steam started with.
 *
 * <p>Everything here runs on the I/O thread of the {@link sosgsgmp.net.SteamNode} holding the port, callbacks included.
 */
public final class SteamworksPort implements SteamPort, SteamLease.Steam {

    /**
     * The process's one Steam (ADR 0012): mutable static state by design, since the Steam API itself is one per
     * process; a game session started again in the same JVM borrows the same port.
     */
    private static final SteamLease<SteamworksPort> LEASE = new SteamLease<>(SteamworksPort::new, 5_000, 2_000,
            System::currentTimeMillis);

    /** What Steam reports while no node listens: dropped. */
    private static final Listener NONE = new Listener() {
        @Override
        public void lobbyCreated(long lobby, String error) {
        }

        @Override
        public void lobbyEntered(long lobby, String error) {
        }

        @Override
        public void memberJoined(long lobby, long user) {
        }

        @Override
        public void memberLeft(long lobby, long user, boolean lostSteam) {
        }

        @Override
        public void lobbyLost(long lobby, boolean lostSteam) {
        }

        @Override
        public void joinRequested(long lobby) {
        }

        @Override
        public void sessionRequested(long user) {
        }

        @Override
        public void sessionFailed(long user, String reason) {
        }
    };

    private static final int CHANNEL = 0;

    /** {@code SOSGSGMP_STEAM_NET=p2p}: the deprecated P2P packets instead of Steam's newer messages (SOS-105). */
    private static final String NET_VARIABLE = "SOSGSGMP_STEAM_NET", P2P = "p2p";
    private static final long STATUS_MS = 1_000;

    private Listener listener = NONE;
    /** Steam's newer messages; null when the old P2P packets are chosen. */
    private final SteamMessages messages;
    private final SteamUser user;
    private final SteamMatchmaking matchmaking;
    private final SteamNetworking networking;
    private final SteamFriends friends;
    private final SteamUtils utils;
    /** The App ID Steam started with: Songs of Syx's (spec §13), or a test's. */
    private final int appId;
    private final ByteBuffer out = ByteBuffer.allocateDirect(Protocol.MAX_MESSAGE + 4);
    private ByteBuffer in = ByteBuffer.allocateDirect(64 * 1024);
    private final int[] size = new int[1];
    // made after the libraries load: SteamID's class setup calls into them
    private final SteamID sender;
    private final SteamFriends.FriendGameInfo game;
    /** Every step of a lobby's connection, for {@code send-logs}: a join that hangs writes no session log. Null until a lobby is asked for. */
    private PlayLog trace;
    private final Map<Long, String> sessions = new HashMap<>();
    private final Set<Long> sentTo = new HashSet<>(), readFrom = new HashSet<>(), acceptedFrom = new HashSet<>();
    private long statusAt;

    /**
     * On a node's I/O thread: the process's Steam port, started the first time; throws (with the reason for the screen)
     * if Steam isn't running or won't start, or the last node still holds it. {@link #close()} gives it back.
     */
    public static SteamPort lease() {
        return LEASE.acquire();
    }

    /** Starts the Steam API, once per process ({@link #LEASE}). */
    private SteamworksPort() {
        try {
            SteamAPI.loadLibraries();
            if (!SteamAPI.init())
                throw new IllegalStateException("Steam didn't start: is it running, and do you own Songs of Syx?");
        } catch (SteamException e) {
            throw new IllegalStateException("Steam's libraries didn't load: " + e.getMessage(), e);
        }
        sender = new SteamID();
        game = new SteamFriends.FriendGameInfo();
        user = new SteamUser(new UserCallbacks());
        matchmaking = new SteamMatchmaking(new MatchmakingCallbacks());
        networking = new SteamNetworking(new NetworkingCallbacks());
        friends = new SteamFriends(new FriendsCallbacks());
        utils = new SteamUtils(new UtilsCallbacks());
        appId = utils.getAppID();
        networking.allowP2PPacketRelay(true);
        messages = P2P.equals(System.getenv(NET_VARIABLE)) ? null : new SteamMessages(() -> listener, this::trace);
        LOG.ln("SOSGSGMP: Steam transport: " + (messages != null ? "ISteamNetworkingMessages (SOS-105)" : "ISteamNetworking P2P packets"));
    }


    private void trace(String text) {
        if (trace != null)
            trace.line(text);
    }

    @Override
    public void note(String text) {
        trace(text);
    }

    /** Opens the trace, once per node: the main menu's invite watch has no lobby to trace. */
    private void traceLobby(String text) {
        if (trace == null) {
            trace = PlayLog.open(DataFolders.layout().logs(), "steam", Clock.systemDefaultZone());
            trace("Steam connection, protocol " + Protocol.VERSION + ", app " + appId + ", this user " + self() + ", transport "
                    + (messages != null ? "messages" : "P2P packets"));
        }
        trace(text);
    }

    /** Each session's state as it changes, while a lobby is traced. */
    private void traceSessions() {
        if (trace == null || messages == null || System.currentTimeMillis() < statusAt)
            return;
        statusAt = System.currentTimeMillis() + STATUS_MS;
        for (Map.Entry<Long, String> e : sessions.entrySet()) {
            String now = messages.status(e.getKey());
            if (!now.equals(e.getValue())) {
                e.setValue(now);
                trace("session with " + e.getKey() + ": " + now);
            }
        }
    }

    private static SteamID id(long handle) {
        return SteamID.createFromNativeHandle(handle);
    }

    private static long handle(SteamID id) {
        return id == null ? 0 : SteamNativeHandle.getNativeHandle(id);
    }

    @Override
    public void listener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public String personaName() {
        return friends.getPersonaName();
    }

    @Override
    public long self() {
        return handle(user.getSteamID());
    }

    @Override
    public void createLobby(int maxMembers) {
        traceLobby("creating a lobby for " + maxMembers);
        matchmaking.createLobby(SteamMatchmaking.LobbyType.FriendsOnly, maxMembers);
    }

    @Override
    public void joinLobby(long lobby) {
        traceLobby("joining lobby " + lobby);
        matchmaking.joinLobby(id(lobby));
    }

    @Override
    public void leaveLobby(long lobby) {
        matchmaking.leaveLobby(id(lobby));
    }

    @Override
    public long lobbyOwner(long lobby) {
        return handle(matchmaking.getLobbyOwner(id(lobby)));
    }

    @Override
    public void lobbyData(long lobby, String key, String value) {
        matchmaking.setLobbyData(id(lobby), key, value);
    }

    @Override
    public String lobbyData(long lobby, String key) {
        String v = matchmaking.getLobbyData(id(lobby), key);
        return v == null ? "" : v;
    }

    @Override
    public List<FriendLobby> friendLobbies() {
        List<FriendLobby> found = new ArrayList<>();
        int n = friends.getFriendCount(SteamFriends.FriendFlags.Immediate);
        for (int i = 0; i < n; i++) {
            SteamID friend = friends.getFriendByIndex(i, SteamFriends.FriendFlags.Immediate);
            // a game ID's low 24 bits are the App ID
            if (!friends.getFriendGamePlayed(friend, game) || (game.getGameID() & 0xFFFFFF) != appId)
                continue;
            SteamID lobby = game.getSteamIDLobby();
            if (lobby != null && lobby.isValid())
                found.add(new FriendLobby(friends.getFriendPersonaName(friend), handle(lobby)));
        }
        return found;
    }

    @Override
    public void invite(long lobby) {
        friends.activateGameOverlayInviteDialog(id(lobby));
    }

    @Override
    public void acceptSession(long user) {
        if (messages != null) {
            boolean accepted = messages.accept(user);
            String was = sessions.put(user, sessions.getOrDefault(user, ""));
            if (was == null)
                trace("accepting " + user + ": " + accepted);
            else if (accepted && acceptedFrom.add(user)) // once, the moment a session exists to accept
                trace("accepted " + user);
            return;
        }
        networking.acceptP2PSessionWithUser(id(user));
    }

    @Override
    public void closeSession(long user) {
        trace("closing the session with " + user);
        if (messages != null) {
            messages.close(user);
            return;
        }
        networking.closeP2PSessionWithUser(id(user));
    }

    @Override
    public boolean send(long user, byte[] bytes) {
        if (messages != null) {
            boolean sent = messages.send(user, bytes);
            sessions.putIfAbsent(user, "");
            if (!sent)
                trace("Steam refused " + bytes.length + " bytes to " + user + ": result " + messages.sendResult());
            else if (sentTo.add(user))
                trace("first message to " + user + ": " + bytes.length + " bytes");
            return sent;
        }
        out.clear();
        out.put(bytes);
        out.flip();
        try {
            return networking.sendP2PPacket(id(user), out, SteamNetworking.P2PSend.Reliable, CHANNEL);
        } catch (SteamException e) {
            return false;
        }
    }

    @Override
    public byte[] read(long[] from) {
        if (messages != null) {
            byte[] bytes = messages.read(from);
            if (bytes != null && readFrom.add(from[0]))
                trace("first message from " + from[0] + ": " + bytes.length + " bytes");
            return bytes;
        }
        if (!networking.isP2PPacketAvailable(CHANNEL, size))
            return null;
        if (in.capacity() < size[0])
            in = ByteBuffer.allocateDirect(size[0]);
        in.clear();
        in.limit(size[0]);
        try {
            int n = networking.readP2PPacket(sender, in, CHANNEL);
            byte[] bytes = new byte[n];
            in.get(in.position(), bytes, 0, n);
            from[0] = handle(sender);
            return bytes;
        } catch (SteamException e) {
            throw new IllegalStateException("SOSGSGMP: reading a Steam packet failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void runCallbacks() {
        SteamAPI.runCallbacks();
        traceSessions();
    }

    /** The node is done: the port goes back to the process, and Steam stays initialized (SOS-237). */
    @Override
    public void close() {
        LEASE.release(this);
    }

    @Override
    public void begin(boolean dropStale) {
        listener = NONE;
        if (!dropStale)
            return;
        SteamAPI.runCallbacks(); // to no listener: an invite accepted long ago joins nothing now
        long[] from = new long[1];
        while (read(from) != null) {
            // a closed session's messages
        }
    }

    @Override
    public void end() {
        listener = NONE;
        if (trace != null)
            trace.close();
        trace = null;
        sessions.clear();
        sentTo.clear();
        readFrom.clear();
        acceptedFrom.clear();
        statusAt = 0;
    }

    // ------------------------------------------------------------ callbacks

    private final class MatchmakingCallbacks implements SteamMatchmakingCallback {
        @Override
        public void onLobbyCreated(SteamResult result, SteamID lobby) {
            trace("lobby created: " + result + ", lobby " + handle(lobby));
            listener.lobbyCreated(handle(lobby), result == SteamResult.OK ? null : result.name());
        }

        @Override
        public void onLobbyEnter(SteamID lobby, int chatPermissions, boolean blocked,
                SteamMatchmaking.ChatRoomEnterResponse response) {
            trace("lobby entered: " + response + ", lobby " + handle(lobby) + ", owner " + lobbyOwner(handle(lobby)));
            listener.lobbyEntered(handle(lobby), response == SteamMatchmaking.ChatRoomEnterResponse.Success ? null
                    : response.name());
        }

        @Override
        public void onLobbyChatUpdate(SteamID lobby, SteamID changed, SteamID makingChange,
                SteamMatchmaking.ChatMemberStateChange change) {
            trace("lobby member " + handle(changed) + ": " + change);
            if (change == SteamMatchmaking.ChatMemberStateChange.Entered)
                listener.memberJoined(handle(lobby), handle(changed));
            else
                listener.memberLeft(handle(lobby), handle(changed),
                        change == SteamMatchmaking.ChatMemberStateChange.Disconnected);
        }

        @Override
        public void onLobbyKicked(SteamID lobby, SteamID admin, boolean kickedDueToDisconnect) {
            trace("lobby lost: " + handle(lobby) + (kickedDueToDisconnect ? ", this PC lost Steam" : ""));
            listener.lobbyLost(handle(lobby), kickedDueToDisconnect);
        }

        @Override
        public void onFavoritesListChanged(int ip, int queryPort, int connPort, int appID, int flags, boolean add,
                int accountID) {
        }

        @Override
        public void onLobbyInvite(SteamID user, SteamID lobby, long gameID) {
        }

        @Override
        public void onLobbyDataUpdate(SteamID lobby, SteamID member, boolean success) {
        }

        @Override
        public void onLobbyChatMessage(SteamID lobby, SteamID user, SteamMatchmaking.ChatEntryType type, int chatID) {
        }

        @Override
        public void onLobbyGameCreated(SteamID lobby, SteamID server, int ip, short port) {
        }

        @Override
        public void onLobbyMatchList(int lobbiesMatching) {
        }

        @Override
        public void onFavoritesListAccountsUpdated(SteamResult result) {
        }
    }

    private final class NetworkingCallbacks implements SteamNetworkingCallback {
        @Override
        public void onP2PSessionRequest(SteamID remote) {
            listener.sessionRequested(handle(remote));
        }

        @Override
        public void onP2PSessionConnectFail(SteamID remote, SteamNetworking.P2PSessionError error) {
            listener.sessionFailed(handle(remote), error.name());
        }
    }

    private static final class UtilsCallbacks implements SteamUtilsCallback {
        @Override
        public void onSteamShutdown() {
        }
    }

    private static final class UserCallbacks implements SteamUserCallback {
        @Override
        public void onAuthSessionTicket(SteamAuthTicket ticket, SteamResult result) {
        }

        @Override
        public void onValidateAuthTicket(SteamID steamID, SteamAuth.AuthSessionResponse response, SteamID owner) {
        }

        @Override
        public void onMicroTxnAuthorization(int appID, long orderID, boolean authorized) {
        }

        @Override
        public void onEncryptedAppTicket(SteamResult result) {
        }
    }

    private final class FriendsCallbacks implements SteamFriendsCallback {
        @Override
        public void onSetPersonaNameResponse(boolean success, boolean localSuccess, SteamResult result) {
        }

        @Override
        public void onPersonaStateChange(SteamID steamID, SteamFriends.PersonaChange change) {
        }

        @Override
        public void onGameOverlayActivated(boolean active) {
        }

        @Override
        public void onGameLobbyJoinRequested(SteamID lobby, SteamID friend) {
            listener.joinRequested(handle(lobby));
        }

        @Override
        public void onAvatarImageLoaded(SteamID steamID, int image, int width, int height) {
        }

        @Override
        public void onFriendRichPresenceUpdate(SteamID steamIDFriend, int appID) {
        }

        @Override
        public void onGameRichPresenceJoinRequested(SteamID steamIDFriend, String connect) {
        }

        @Override
        public void onGameServerChangeRequested(String server, String password) {
        }
    }
}
