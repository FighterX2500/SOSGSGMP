package sosgsgmp.lobby;

import sosgsgmp.campaign.Campaign;

/**
 * What the host picks when creating a lobby (spec §5, lobby step 2).
 *
 * @param difficulty key of a vanilla difficulty mode (a file in the game's player/mode folder)
 * @param maxPlayers player limit, 2 to 16 (spec §3)
 * @param resume the campaign save the lobby resumes (spec §11, SOS-64), or null for a new game
 */
public record LobbySettings(String difficulty, int maxPlayers, Resume resume) {

    /** A campaign save to resume: only its players can join, and every PC loads its own save of that number. */
    public record Resume(Campaign campaign, int number) {
        public Resume {
            if (number < 1)
                throw new IllegalArgumentException("SOSGSGMP: resume of campaign save " + number);
        }
    }

    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 16;

    public LobbySettings {
        if (difficulty == null || difficulty.isEmpty())
            throw new IllegalArgumentException("SOSGSGMP: lobby has no difficulty");
        if (maxPlayers < MIN_PLAYERS || maxPlayers > MAX_PLAYERS)
            throw new IllegalArgumentException("SOSGSGMP: player limit " + maxPlayers + " is outside " + MIN_PLAYERS + ".." + MAX_PLAYERS);
        if (resume != null && resume.campaign().players().size() != maxPlayers)
            throw new IllegalArgumentException("SOSGSGMP: a resume of " + resume.campaign().players().size()
                    + " players in a lobby of " + maxPlayers);
    }

    /** A new game's lobby. */
    public LobbySettings(String difficulty, int maxPlayers) {
        this(difficulty, maxPlayers, null);
    }

    /** The lobby resuming {@code resume}: the campaign's difficulty and players. */
    public static LobbySettings resuming(Resume resume) {
        return new LobbySettings(resume.campaign().difficulty(), resume.campaign().players().size(), resume);
    }
}
