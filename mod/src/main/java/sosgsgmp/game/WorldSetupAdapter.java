package sosgsgmp.game;

import game.faction.FACTIONS;
import game.faction.player.Player;
import sosdet.Engine;
import sosgsgmp.setup.FactionPick;

/**
 * SOSGSGMP: the factions step's game calls (setup-factions-step.md §5): this PC's picks as the game holds them. Each PC
 * changes only its own {@code FACTIONS.player()} with vanilla's race buttons and Customize Faction section; this reads
 * them back for the host. Game thread only.
 */
public final class WorldSetupAdapter {

    /**
     * This PC's picks as the game holds them: race, faction name and banner, at world tile {@code x, y} (or
     * {@link FactionPick#NONE} twice). Null while the faction has no name: a pick needs one.
     */
    public FactionPick pick(int x, int y) {
        Player p = FACTIONS.player();
        String name = p.name.toString();
        if (name.isBlank())
            return null;
        return new FactionPick(p.race().key, name, Engine.bannerBytes(), x, y);
    }
}
