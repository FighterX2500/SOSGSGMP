package sosgsgmp.net;

/**
 * The Steam calls a {@link SteamNode} needs, over plain longs (Steam IDs): lobbies, P2P sessions and packets. The game
 * adapter implements it with the steamworks4j the game jar ships; tests use an in-memory Steam. One thread only: the
 * node's I/O thread creates the port, calls it and closes it, and the listener is called back inside
 * {@link #runCallbacks()} on that thread.
 */
public interface SteamPort {

    /** A friend in a Songs of Syx lobby: who and which lobby. */
    record FriendLobby(String friend, long lobby) {}

    /** What Steam reports, called back inside {@link #runCallbacks()}. */
    interface Listener {
        /** This PC's lobby is created ({@code error} null), or couldn't be. */
        void lobbyCreated(long lobby, String error);

        /** This PC entered a lobby ({@code error} null), or couldn't. */
        void lobbyEntered(long lobby, String error);

        /** Another user entered this PC's lobby. */
        void memberJoined(long lobby, long user);

        /** A user left this PC's lobby, disconnected or was removed. */
        void memberLeft(long lobby, long user);

        /** This PC was removed from a lobby it had entered. */
        void lobbyLost(long lobby);

        /** The user accepted a friend's invite to a lobby in Steam's overlay. */
        void joinRequested(long lobby);

        /** A user sent this PC its first packet: the session is open once accepted. */
        void sessionRequested(long user);

        /** The P2P session with a user failed. */
        void sessionFailed(long user, String reason);
    }

    void listener(Listener listener);

    /** This PC's Steam ID. */
    long self();

    /** Creates a lobby for friends with room for {@code maxMembers}; the answer comes as {@link Listener#lobbyCreated}. */
    void createLobby(int maxMembers);

    /** Enters a lobby; the answer comes as {@link Listener#lobbyEntered}. */
    void joinLobby(long lobby);

    void leaveLobby(long lobby);

    /** The lobby's owner, 0 if unknown. */
    long lobbyOwner(long lobby);

    /** Marks a lobby as this mod's, with its protocol version, so friends' games can tell it apart. */
    void lobbyData(long lobby, String key, String value);

    /** Friends playing Songs of Syx in a lobby, as Steam knows them now. */
    java.util.List<FriendLobby> friendLobbies();

    /** Opens Steam's overlay for inviting friends into a lobby. */
    void invite(long lobby);

    void acceptSession(long user);

    void closeSession(long user);

    /** Sends one packet reliably, in order; false if Steam refused it. */
    boolean send(long user, byte[] bytes);

    /** The next packet waiting, its sender in {@code from[0]}; null when none waits. */
    byte[] read(long[] from);

    void runCallbacks();

    /** A line for the connection's trace, when it keeps one. */
    default void note(String text) {
    }

    /** Leaves nothing open: the port's Steam interfaces are released. */
    void close();
}
