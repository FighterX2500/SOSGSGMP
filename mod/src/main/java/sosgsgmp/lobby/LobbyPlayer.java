package sosgsgmp.lobby;

/** One player in a lobby. The id is handed out by the host and is only meaningful inside this lobby. */
public record LobbyPlayer(int id, String name, boolean host) {

    public LobbyPlayer {
        if (id < 0)
            throw new IllegalArgumentException("SOSGSGMP: player id " + id + " is negative");
        if (name == null || name.isEmpty())
            throw new IllegalArgumentException("SOSGSGMP: player " + id + " has no name");
    }
}
