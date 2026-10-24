package sosgsgmp.lobby;

/** Where a lobby session stands, as the lobby screen shows it. */
public enum LobbyState {
    /** A joining PC is connecting to the host, or waiting for its welcome. */
    CONNECTING,
    /** Players can join and leave. */
    OPEN,
    /** The host pressed Start; nobody can join any more. */
    STARTED,
    /** The session is over: left, refused, host gone or connection lost. */
    CLOSED
}
