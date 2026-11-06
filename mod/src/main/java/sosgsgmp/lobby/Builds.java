package sosgsgmp.lobby;

/**
 * What a PC runs, which every PC of a game shares (SOS-128): the game's build and a hash of each mod's jar. The hash,
 * because the Steam Workshop updates the mods one by one and a mod's version doesn't change with each build. Then the
 * game data it plays with, every mod's included (SOS-159).
 */
public record Builds(String game, long engine, long mod, GameFiles files) {

    /**
     * Why a PC running {@code joiner} can't join this one; null if they match. Our own builds first: their refusals tell
     * the player to let Steam finish updating.
     */
    public Refusal mismatch(Builds joiner) {
        if (!game.equals(joiner.game))
            return Refusal.WRONG_GAME;
        if (engine != joiner.engine)
            return Refusal.WRONG_ENGINE;
        if (mod != joiner.mod)
            return Refusal.WRONG_MOD;
        return files.equals(joiner.files) ? null : Refusal.WRONG_FILES;
    }
}
