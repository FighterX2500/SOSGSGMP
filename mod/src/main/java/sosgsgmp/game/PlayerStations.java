package sosgsgmp.game;

import java.util.ArrayList;
import java.util.List;

import game.faction.FACTIONS;
import game.faction.Faction;
import sosdet.Engine;
import world.entity.army.WArmy;
import world.map.regions.Region;

/**
 * SOSGSGMP: armies stationed at another player's capital (spec §9; SOS-233, ADR 0011). The engine keeps them as world
 * state, so this holds none: the player window's Armies tab and the autopilot call here. Game thread, outside the step.
 */
public final class PlayerStations {

    /** A stationed army: the army (null when gone since the world's last check), its player and its host. */
    public record Stationed(WArmy army, int owner, int host) {
    }

    /** How far from a capital, in tiles along either axis, the window lists this player's armies: a little past the reach. */
    public static final int NEAR = 5;

    private PlayerStations() {
    }

    /** Every stationed army, in the engine's order; empty without a game. */
    public static List<Stationed> all() {
        List<Stationed> out = new ArrayList<>();
        if (!Engine.active())
            return out;
        for (int i = 0; i < Engine.stationedCount(); i++)
            out.add(new Stationed(Engine.stationedArmy(i), Engine.stationedOwner(i), Engine.stationedHost(i)));
        return out;
    }

    /** The armies of the player in {@code owner} stationed at the capital of the player in {@code host}. */
    public static List<Stationed> between(int owner, int host) {
        List<Stationed> out = new ArrayList<>();
        for (Stationed s : all())
            if (s.owner() == owner && s.host() == host && s.army() != null)
                out.add(s);
        return out;
    }

    /**
     * Whether an army of the player in {@code owner} stationed at the capital of the player in {@code host} fights an
     * invasion of it by {@code invader}, as an invasion notice names it (SOS-234): its soldiers then come into the host's
     * city, and its player commands them from a view of it.
     */
    public static boolean fights(int owner, int host, int invader) {
        return Engine.active() && Engine.stationFights(owner, host, invader);
    }

    /**
     * The player whose stationed soldiers division {@code div} of the city on screen holds, or -1 for one of the city's
     * own (SOS-234).
     */
    public static int divOwner(int div) {
        return Engine.active() ? Engine.stationedDivOwner(div) : -1;
    }

    /**
     * The {@code k}th army of the player in {@code owner} stationed at the capital of the player in {@code host}, or
     * null; for the window's rows, every frame: no allocation.
     */
    public static WArmy stationedAt(int owner, int host, int k) {
        if (!Engine.active())
            return null;
        for (int i = 0; i < Engine.stationedCount(); i++) {
            WArmy a = Engine.stationedOwner(i) == owner && Engine.stationedHost(i) == host ? Engine.stationedArmy(i) : null;
            if (a != null && k-- == 0)
                return a;
        }
        return null;
    }

    /**
     * The {@code k}th army of this PC's player within {@link #NEAR} tiles of the capital of the player in {@code host},
     * or null; every frame: no allocation.
     */
    public static WArmy mineNear(int host, int k) {
        Faction h = FACTIONS.getByIndex(host);
        Region cap = h == null ? null : h.capitolRegion();
        if (cap == null || !Engine.active())
            return null;
        for (int i = 0; i < FACTIONS.player().armies().all().size(); i++) {
            WArmy a = FACTIONS.player().armies().all().get(i);
            if (Math.max(Math.abs(a.ctx() - cap.cx()), Math.abs(a.cty() - cap.cy())) <= NEAR && k-- == 0)
                return a;
        }
        return null;
    }

    /** True when {@code a} is stationed anywhere. */
    public static boolean stationed(WArmy a) {
        if (a == null || !Engine.active())
            return false;
        for (int i = 0; i < Engine.stationedCount(); i++)
            if (Engine.stationedArmy(i) == a)
                return true;
        return false;
    }

    /** Why this PC's player can't station {@code a} at the capital of the player in {@code host}, or null. */
    public static String stationRefusal(WArmy a, int host) {
        return Engine.active() ? Engine.stationRefusal(a, host) : "No multiplayer game runs.";
    }

    public static void station(WArmy a, int host) {
        Engine.station(a, host);
    }

    /** Why this PC's player can't send {@code a} away (the host) or call it back (its player), or null. */
    public static String endRefusal(WArmy a) {
        return Engine.active() ? Engine.stationEndRefusal(a) : "No multiplayer game runs.";
    }

    public static void end(WArmy a) {
        Engine.stationEnd(a);
    }

    /** Why this PC's player can't supply {@code a} from its own city, or null: the host may. */
    public static String supplyRefusal(WArmy a) {
        return Engine.active() ? Engine.stationSupplyRefusal(a) : "No multiplayer game runs.";
    }

    public static void supply(WArmy a) {
        Engine.stationSupply(a);
    }
}
