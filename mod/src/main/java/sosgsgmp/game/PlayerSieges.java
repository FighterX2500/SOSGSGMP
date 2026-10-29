package sosgsgmp.game;

import game.faction.FACTIONS;
import sosdet.Engine;
import world.map.regions.Region;

/**
 * SOSGSGMP: the engine's sieges of this PC's player's capital (spec §9; SOS-92). The engine keeps each siege as world
 * state, so this holds none: the session panel and the siege notice call here. Game thread, outside the step.
 */
public final class PlayerSieges {

    private PlayerSieges() {
    }

    /** The game second the siege of this PC's player's capital began, or -1 while it isn't besieged. */
    public static int besiegedSince() {
        return Engine.active() ? Engine.besiegedSince(FACTIONS.player().index()) : -1;
    }

    /** The game days left before this PC's player's capital may be assaulted, 0 once the wait passed, -1 unbesieged. */
    public static int assaultInDays() {
        return Engine.active() ? Engine.assaultInDays(FACTIONS.player().index()) : -1;
    }

    /** True when {@code reg} is this PC's player's capital and it is besieged. */
    public static boolean besiegedCapital(Region reg) {
        return reg != null && reg == FACTIONS.player().capitolRegion() && besiegedSince() >= 0;
    }

    /** Why this PC's player can't sally out of their capital now, as a sentence, or null. */
    public static String sallyRefusal() {
        return Engine.active() ? Engine.sallyRefusal() : "No multiplayer game runs.";
    }

    /** This PC's player sallies out: the siege asks them at its step, as at its start. Check {@link #sallyRefusal} first. */
    public static void sallyOut() {
        Engine.sallyOut();
    }
}
