package sosgsgmp.lobby;

/**
 * What the host picks when creating a lobby (spec §5, lobby step 2).
 *
 * @param difficulty key of a vanilla difficulty mode (a file in the game's player/mode folder)
 * @param maxPlayers player limit, 2 to 16 (spec §3)
 */
public record LobbySettings(String difficulty, int maxPlayers) {

    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 16;

    public LobbySettings {
        if (difficulty == null || difficulty.isEmpty())
            throw new IllegalArgumentException("SOSGSGMP: lobby has no difficulty");
        if (maxPlayers < MIN_PLAYERS || maxPlayers > MAX_PLAYERS)
            throw new IllegalArgumentException("SOSGSGMP: player limit " + maxPlayers + " is outside " + MIN_PLAYERS + ".." + MAX_PLAYERS);
    }
}
