package sosgsgmp.game;

import java.nio.ByteBuffer;
import java.nio.file.Paths;
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
import com.codedisaster.steamworks.SteamAuth;
import com.codedisaster.steamworks.SteamAuthTicket;

import snake2d.LOG;
import sosgsgmp.log.PlayLog;
import sosgsgmp.net.Protocol;
import sosgsgmp.net.SteamPort;

/**
 * {@link SteamPort} over the steamworks4j 1.9.0 the game jar ships (SOS-60). The game process never starts Steam
 * itself (its achievements run in a separate JVM), so the port loads the libraries and initializes the Steam API when
 * it is created and shuts it down when closed. Steam finds the App ID (1162750) in the {@code SteamAppId} environment
 * variable, which Steam sets for a game it launched; a game started outside Steam needs it set too.
 *
 * <p>Everything here runs on the {@link sosgsgmp.net.SteamNode}'s I/O thread, callbacks included.
 */
public final class SteamworksPort implements SteamPort {

    /** Songs of Syx on Steam (spec §13). */
    public static final int APP_ID = 1162750;

    private static final int CHANNEL = 0;

    /** {@code SOSGSGMP_STEAM_NET=p2p}: the deprecated P2P packets instead of Steam's newer messages (SOS-105). */
    private static final String NET_VARIABLE = "SOSGSGMP_STEAM_NET", P2P = "p2p";
    private static final long STATUS_MS = 1_000;

    private Listener listener;
    /** Steam's newer messages; null when the old P2P packets are chosen. */
    private final SteamMessages messages;
    private final SteamUser user;
    private final SteamMatchmaking matchmaking;
    private final SteamNetworking networking;
    private final SteamFriends friends;
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

    /** Starts the Steam API; throws (with the reason for the screen) if Steam isn't running or won't start. */
    public SteamworksPort() {
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

    /** Opens the trace, once: the main menu's invite watch runs a port on every start and has no lobby to trace. */
    private void traceLobby(String text) {
        if (trace == null) {
            trace = PlayLog.open(Paths.get(System.getenv("APPDATA"), "songsofsyx", "sosgsgmp-logs"), "steam", Clock.systemDefaultZone());
            trace("Steam connection, protocol " + Protocol.VERSION + ", this user " + self() + ", transport "
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
    public List<FriendLobby> friendLobbies() {
        List<FriendLobby> found = new ArrayList<>();
        int n = friends.getFriendCount(SteamFriends.FriendFlags.Immediate);
        for (int i = 0; i < n; i++) {
            SteamID friend = friends.getFriendByIndex(i, SteamFriends.FriendFlags.Immediate);
            // a game ID's low 24 bits are the App ID
            if (!friends.getFriendGamePlayed(friend, game) || (game.getGameID() & 0xFFFFFF) != APP_ID)
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

    @Override
    public void close() {
        if (trace != null)
            trace.close();
        networking.dispose();
        matchmaking.dispose();
        friends.dispose();
        user.dispose();
        SteamAPI.shutdown();
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
                listener.memberLeft(handle(lobby), handle(changed));
        }

        @Override
        public void onLobbyKicked(SteamID lobby, SteamID admin, boolean kickedDueToDisconnect) {
            listener.lobbyLost(handle(lobby));
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
