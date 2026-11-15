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

    /**
     * A living world (SOS-187), on the world built for settling: why a city-state can't be carved with its centre at
     * world tile {@code x, y}, with the other players' capitals and spots at {@code otherX, otherY}: the engine's carving
     * rule, and 6 tiles from the others; null when it can.
     */
    String settleProblem(int x, int y, int[] otherX, int[] otherY);

    /**
     * Host, a living world: builds the world on the confirmed map with {@code host}'s faction (no location: the world
     * gets a stand-in capital for it, SOS-214), keeping room for {@code humans} players, and returns the save every PC
     * loads to settle on, this one too.
     */
    byte[] buildForSettling(FactionPick host, int humans);

    /**
     * Host, a living world, on the world built for settling as this PC loaded it: carves a city-state for every player
     * at their spot ({@code humans} in slot order, the host's first: their capital moves there from the stand-in, SOS-214),
     * gives every human the lobby's difficulty, and returns the save every PC loads to start, this one too.
     */
    byte[] settle(List<FactionPick> humans, String difficulty);
}
