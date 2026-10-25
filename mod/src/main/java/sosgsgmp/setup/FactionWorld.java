package sosgsgmp.setup;

import java.util.List;

/**
 * The game's side of the factions step, as the setup sessions use it: the game adapter implements it, tests fake it.
 * Game thread only.
 */
public interface FactionWorld {

    /**
     * Why a capital can't be at world tile {@code x, y} with the other players' at {@code otherX, otherY}: the engine's
     * rule (the terrain, and 6 tiles from the others), or null when it can.
     */
    String locationProblem(int x, int y, int[] otherX, int[] otherY);

    /**
     * Host: builds the world on the confirmed map around {@code humans} (slot order: the host's first), with the lobby's
     * difficulty, and returns the save every PC loads, this one too. Throws {@link IllegalStateException} when the game
     * can't build or save it.
     */
    byte[] buildWorld(List<FactionPick> humans, String difficulty);
}
