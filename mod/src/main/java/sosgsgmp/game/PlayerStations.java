package sosgsgmp.game;

import java.util.ArrayList;
import java.util.List;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.DipStance;
import snake2d.util.datatypes.COORDINATE;
import sosdet.Engine;
import world.entity.army.WArmy;
import world.entity.army.WArmyState;
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

    /**
     * Why this PC's player can't move {@code a} now, or null (owner, 2026-11-19): a stationed army moves once every
     * division is out of the host's city, in the camp.
     */
    public static String moveRefusal(WArmy a) {
        return a == null || !Engine.active() ? null : Engine.stationMoveRefusal(a);
    }

    /** Why {@code a} can't be attacked directly, or null (owner, 2026-11-19): a stationed army can't. */
    public static String attackRefusal(WArmy a) {
        return a == null || !Engine.active() ? null : Engine.stationAttackRefusal(a);
    }

    /** How many divisions with men of the stationed army {@code a} are in the host's city; 0 for one not stationed. */
    public static int inCity(WArmy a) {
        return Engine.active() ? Engine.stationDivisions(a, true) : 0;
    }

    /** How many divisions with men of the stationed army {@code a} are in the camp beside the city; 0 for one not stationed. */
    public static int inCamp(WArmy a) {
        return Engine.active() ? Engine.stationDivisions(a, false) : 0;
    }

    /**
     * The division popup's Leave for the camp for the city's division {@code div}: null when it isn't a stationed
     * division this PC's player commands, "" when it may leave now, or why not.
     */
    public static String campState(int div) {
        return Engine.active() ? Engine.stationCampState(div) : null;
    }

    /** The city's division {@code div} leaves for the camp. Check {@link #campState} first. */
    public static void camp(int div) {
        Engine.stationCamp(div);
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

    /** Why this PC's player can't give the host command of {@code a}'s soldiers in its city, or take it back, or null. */
    public static String grantRefusal(WArmy a) {
        return Engine.active() ? Engine.stationGrantRefusal(a) : "No multiplayer game runs.";
    }

    /** This PC's player gives the host command of {@code a}'s soldiers, or takes it back (owner, 2026-11-19). */
    public static void grant(WArmy a, boolean granted) {
        Engine.stationGrant(a, granted);
    }

    /** Whether the host commands the soldiers of {@code a}, stationed at its capital; false for an army not stationed. */
    public static boolean granted(WArmy a) {
        if (a == null || !Engine.active())
            return false;
        for (int i = 0; i < Engine.stationedCount(); i++)
            if (Engine.stationedArmy(i) == a)
                return Engine.stationGranted(i);
        return false;
    }

    /**
     * Whether this PC's player and the player in {@code host} have an agreement that lets one station an army at the
     * other's capital, as the engine's rule reads it: allies, colleagues (a pact), overlord or vassal, not at war.
     */
    public static boolean partner(int host) {
        if (!Engine.active() || host == FACTIONS.player().index() || !PlayerDeals.otherPlayer(FACTIONS.getByIndex(host)))
            return false;
        Faction me = FACTIONS.player();
        Faction h = FACTIONS.getByIndex(host);
        DipStance s = DIP.get(me, h);
        return (s == DIP.ALLY() || s == DIP.PACT() || s == DIP.VASSAL() || s == DIP.OVERLORD()) && !DIP.WAR().is(me, h);
    }

    /** The player whose capital {@code reg} is, or -1. */
    public static int capitalOf(Region reg) {
        Faction f = reg == null ? null : reg.faction();
        return f != null && f.capitolRegion() == reg && PlayerDeals.otherPlayer(f) ? f.index() : -1;
    }

    /** The tile beside {@code capital} where {@code a} can stand, as vanilla's siege picks it; null when none is reached. */
    public static COORDINATE tileBeside(WArmy a, Region capital) {
        return a.besigeTile(capital);
    }

    /** This PC's player's move order for {@code a}: a world command, as the map's own. */
    public static void march(WArmy a, int tx, int ty) {
        a.setDestination(tx, ty);
    }

    /** Whether {@code a} stands still, as stationing needs. */
    public static boolean standsStill(WArmy a) {
        return a.state() == WArmyState.fortified || a.state() == WArmyState.fortifying;
    }

    /** Whether {@code a} stands within reach of the capital of the player in {@code host}. */
    public static boolean inReach(WArmy a, int host) {
        Region cap = FACTIONS.getByIndex(host).capitolRegion();
        return cap != null && Math.max(Math.abs(a.ctx() - cap.cx()), Math.abs(a.cty() - cap.cy()))
                <= sosdet.game.station.StationBook.REACH_TILES;
    }

    /** Whether {@code a} is still this PC's player's army in the world. */
    public static boolean mine(WArmy a) {
        return a != null && a.added() && a.faction() == FACTIONS.player();
    }
}
