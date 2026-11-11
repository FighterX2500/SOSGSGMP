package sosgsgmp.lobby;

import sosgsgmp.campaign.Campaign;

/**
 * What the host picks when creating a lobby (spec §5, lobby step 2).
 *
 * @param difficulty key of a vanilla difficulty mode (a file in the game's player/mode folder)
 * @param maxPlayers player limit, 2 to 16 (spec §3)
 * @param resume the campaign save the lobby resumes (spec §11, SOS-64), or null for a new game
 * @param fullTransferLog every PC's playtest log lists every goods and money transfer, NPC trade included; off, only
 *        those between players (SOS-81)
 * @param livingWorld the world is built around the host alone, and every other player settles a city-state carved
 *        into it on the real political map (spec §5 World, SOS-187); off, the classic setup. A resume plays its saved
 *        world, so it is off there
 */
public record LobbySettings(String difficulty, int maxPlayers, Resume resume, boolean fullTransferLog, boolean livingWorld) {

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
        if (resume != null && livingWorld)
            throw new IllegalArgumentException("SOSGSGMP: a resume builds no world, living or classic");
    }

    /** A new game's lobby, with the full transfer log on. */
    public LobbySettings(String difficulty, int maxPlayers) {
        this(difficulty, maxPlayers, null, true, false);
    }

    /** The lobby resuming {@code resume}: the campaign's difficulty and players, with the full transfer log on. */
    public static LobbySettings resuming(Resume resume) {
        return new LobbySettings(resume.campaign().difficulty(), resume.campaign().players().size(), resume, true, false);
    }

    /** These settings with the full transfer log on or off. */
    public LobbySettings withFullTransferLog(boolean on) {
        return new LobbySettings(difficulty, maxPlayers, resume, on, livingWorld);
    }

    /** These settings with the living world on or off (SOS-187). */
    public LobbySettings withLivingWorld(boolean on) {
        return new LobbySettings(difficulty, maxPlayers, resume, fullTransferLog, on);
    }
}
