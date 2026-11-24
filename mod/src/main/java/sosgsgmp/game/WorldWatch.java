package sosgsgmp.game;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import game.GAME;
import game.event.engine.Event;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.diplomacy.DipStance;
import game.faction.npc.FactionNPC;
import game.faction.royalty.NPCCourt;
import game.faction.royalty.Royalty;
import game.time.TIME;
import game.values.GCOUNTS;
import settlement.stats.STATS;
import settlement.stats.colls.StatsPopulation;
import sosgsgmp.log.EventQueue;
import sosgsgmp.log.NewEntries;
import view.main.VIEW;
import view.ui.message.Message;
import world.WORLD;
import world.battle.BattleListener;
import world.entity.army.WArmy;
import world.log.LogEntry;
import world.log.WorldLog;
import world.map.regions.Region;
import world.region.RD;

/**
 * SOSGSGMP: what the playtest log reads from the game itself (SOS-247): the world's events as the game's own listeners
 * hear them, the world's news, scripted events, this player's messages and its city's day. Game thread only.
 *
 * <p>The listeners run inside the step, on every PC alike. Each one only puts its arguments' indexes and the step into
 * a preallocated {@link EventQueue} and returns: no game state read beyond its arguments, no draw, no allocation, no
 * I/O, and never who the player is (the world part runs each human's half with that human as the player). The game
 * clears its listener lists when it makes a game ({@code GameDisposable}), so {@link #install()} makes them again
 * whenever the loaded world is another. Everything else here runs between steps.
 */
public final class WorldWatch {

    /** A line for the log: its step (or -1), the game day (or -1) and its text. */
    public interface Out {
        /**
         * Meant to be the same on every PC: at {@code step}, or on {@code day} when the step is unknown (-1);
         * {@code confirmed} when a twopc run showed this kind the same on both PCs (batch-15.md: stances and news).
         */
        void same(long step, int day, boolean confirmed, String text);

        /** This PC's own. */
        void local(String text);
    }

    private static final int STANCE = 1, OWNER = 2, FACTION_ADDED = 3, FACTION_REMOVED = 4, BATTLE = 5, SIEGE = 6,
            SUCCESSION = 7;
    /** Records the queue keeps between two frames: a frame of many steps at speed with a war's worth of changes. */
    private static final int QUEUE = 4096;

    private final EventQueue queue = new EventQueue(QUEUE);
    private final LongSupplier step;
    private final Field messagesAll = field(view.ui.message.Messages.class, "all");
    private final Field messageTitle = field(Message.class, "title");
    private final NewEntries<LogEntry> news = new NewEntries<>(e -> e.daySinceStart() + " " + e.message);
    private final NewEntries<Message> messages = new NewEntries<>(this::title);
    /** The loaded world the listeners are in; another one means a load made the game again. */
    private WorldLog world;
    private long lostSaid;
    private Event event;
    private Object eventHandler;
    private int day = -1;
    private int[] counts;

    /** {@code step}: the engine's step, or -1 outside a session; called inside the step, so it must not allocate. */
    public WorldWatch(LongSupplier step) {
        this.step = step;
    }

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | SecurityException | InaccessibleObjectException e) {
            throw new IllegalStateException("SOSGSGMP: can't reach " + c.getName() + "." + name + " (Songs of Syx V71)", e);
        }
    }

    /** Every frame, between steps: the listeners, made again for a world that a load made anew. */
    public void install() {
        WorldLog w = WORLD.LOG();
        if (w == world)
            return;
        world = w;
        listen();
    }

    private void listen() {
        new DIP.DipActivityListener() {
            @Override
            public void change(Faction faction, Faction other, DipStance old, DipStance nn) {
                queue.put(step.getAsLong(), STANCE, index(faction), index(other), old == null ? -1 : old.index(),
                        nn == null ? -1 : nn.index());
            }
        };
        new RD.RDOwnerChanger() {
            @Override
            public void change(Region reg, Faction oldOwner, Faction newOwner) {
                queue.put(step.getAsLong(), OWNER, reg.index(), index(oldOwner), index(newOwner), 0);
            }
        };
        new Faction.FactionActivityListener() {
            @Override
            public void remove(FactionNPC f) {
                queue.put(step.getAsLong(), FACTION_REMOVED, index(f), 0, 0, 0);
            }

            @Override
            public void add(FactionNPC f) {
                queue.put(step.getAsLong(), FACTION_ADDED, index(f), 0, 0, 0);
            }
        };
        new BattleListener() {
            @Override
            public void battle(Faction a, boolean victory, int losses, int kills, Faction against) {
                queue.put(step.getAsLong(), BATTLE, index(a), index(against), kills * 2 + (victory ? 1 : 0), losses);
            }

            @Override
            public void battle(WArmy a, boolean victory, int losses, int kills, Faction against) {
                // the faction's line above says it for the whole side
            }

            @Override
            public void siege(WArmy attacker, Region reg) {
                // the faction's line below says it
            }

            @Override
            public void siege(Faction attacker, Region reg) {
                queue.put(step.getAsLong(), SIEGE, index(attacker), reg.index(), 0, 0);
            }
        };
        new NPCCourt.RoyaltyEventListener() {
            @Override
            public void change(int successionI, Royalty old, Royalty nn) {
                Royalty r = nn != null ? nn : old;
                queue.put(step.getAsLong(), SUCCESSION, r == null ? -1 : index(r.court.faction), successionI, nn == null ? 0 : 1, 0);
            }
        };
    }

    private static int index(Faction f) {
        return f == null ? -1 : f.index();
    }

    /** Every frame, between steps: what the listeners heard since, as lines. */
    public void drain(Out out) {
        while (queue.take())
            out.same(queue.step(), -1, queue.kind() == STANCE, text());
        if (queue.lost() != lostSaid) {
            out.local("world events: " + (queue.lost() - lostSaid) + " lost, the queue was full");
            lostSaid = queue.lost();
        }
    }

    private String text() {
        int a = queue.a(), b = queue.b(), c = queue.c(), d = queue.d();
        return switch (queue.kind()) {
            case STANCE -> "stance: " + faction(a) + " and " + faction(b) + ", " + stance(c) + " to " + stance(d);
            case OWNER -> b == c && b >= 0 ? "capital: " + faction(b) + " to " + region(a)
                    : "region " + region(a) + ": " + faction(b) + " to " + faction(c);
            case FACTION_ADDED -> "faction born: " + faction(a);
            case FACTION_REMOVED -> "faction gone: " + faction(a);
            case BATTLE -> "battle: " + faction(a) + ((c & 1) == 1 ? " won" : " lost") + " against " + faction(b) + ", losses " + d
                    + ", kills " + (c >> 1);
            case SIEGE -> "siege: " + faction(a) + " takes " + region(b);
            case SUCCESSION -> "succession: " + faction(a) + ", place " + b + (c == 1 ? " filled" : " emptied");
            default -> throw new IllegalStateException("SOSGSGMP: no world event kind " + queue.kind());
        };
    }

    private static String faction(int i) {
        return i < 0 ? "nobody" : FACTIONS.getByIndex(i).name + " (" + i + ")";
    }

    private static String region(int i) {
        return WORLD.REGIONS().getByIndex(i).info.name() + " (" + i + ")";
    }

    private static String stance(int i) {
        for (DipStance s : new DipStance[] { DIP.WAR(), DIP.NEUTRAL(), DIP.TRADE(), DIP.PACT(), DIP.ALLY(), DIP.VASSAL(), DIP.OVERLORD() })
            if (s.index() == i)
                return s.key().toLowerCase(java.util.Locale.ROOT);
        return "stance " + i;
    }

    /**
     * A few times a second, between steps: the world's news and the scripted events since the last look, keyed by the
     * game day (the step they happened at is past knowing), and this player's new messages.
     */
    public void poll(Out out) {
        WorldLog w = WORLD.LOG();
        int first = news.firstNew(w, w.all().size(), w.all()::get);
        for (int i = first; i < w.all().size(); i++) {
            LogEntry e = w.all().get(i);
            out.same(-1, e.daySinceStart(), true, "news: " + e.message);
        }
        Object handler = GAME.EVENT();
        Event now = GAME.EVENT().current();
        if (handler != eventHandler) {
            eventHandler = handler; // a load: what runs now was running before it
            event = now;
        } else if (now != event) {
            int today = TIME.days().bitsSinceStart();
            if (event != null)
                out.same(-1, today, false, "scripted event ends: " + event.key);
            if (now != null)
                out.same(-1, today, false, "scripted event starts: " + now.key);
            event = now;
        }
        newMessages(out);
    }

    private void newMessages(Out out) {
        view.ui.message.Messages m = VIEW.messages();
        snake2d.util.sets.ArrayList<?> all = (snake2d.util.sets.ArrayList<?>) read(messagesAll, m);
        int first = messages.firstNew(all, all.size(), i -> (Message) all.get(i));
        for (int i = first; i < all.size(); i++)
            out.local("message: " + title((Message) all.get(i)));
    }

    private String title(Message m) {
        return String.valueOf(read(messageTitle, m));
    }

    private static Object read(Field f, Object o) {
        try {
            return f.get(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSGSGMP: can't read " + f + " (Songs of Syx V71)", e);
        }
    }

    /**
     * Between steps, a few times a second: on a new game day, this player's city's day before it, for crashes and
     * freezes: deaths and arrivals by cause, and the riots, accidents, executions and invasions the game counted.
     */
    public void cityDay(Out out) {
        int today = TIME.days().bitsSinceStart();
        GCOUNTS g = GAME.count();
        GCOUNTS.SAccumilator[] kept = { g.RIOTS, g.ACCIDENTS, g.EXECUTIONS, g.INVASIONS, g.INVASIONS_WON, g.INVASIONS_LOST };
        int[] now = new int[kept.length];
        for (int i = 0; i < kept.length; i++)
            now[i] = kept[i].current();
        if (day < 0 || today < day || counts == null) { // the first look, or a load: the counts start here
            day = today;
            counts = now;
            return;
        }
        if (today == day)
            return;
        List<String> parts = new ArrayList<>();
        StatsPopulation.StatsDeath pop = STATS.POP().COUNT;
        StringBuilder deaths = new StringBuilder();
        for (StatsPopulation.StatsDeath.PopData p : pop.leaves())
            add(deaths, p.info().name, p.statistics(null).total().get(1));
        if (deaths.length() > 0)
            parts.add("deaths and leavers: " + deaths);
        StringBuilder arrivals = new StringBuilder();
        for (StatsPopulation.StatsDeath.PopData p : pop.enters())
            add(arrivals, p.info().name, p.statistics(null).total().get(1));
        if (arrivals.length() > 0)
            parts.add("arrivals: " + arrivals);
        StringBuilder counted = new StringBuilder();
        for (int i = 0; i < kept.length; i++)
            add(counted, kept[i].name, now[i] - counts[i]);
        if (counted.length() > 0)
            parts.add(counted.toString());
        out.local("city day " + day + ": " + (parts.isEmpty() ? "nothing counted" : String.join("; ", parts)));
        day = today;
        counts = now;
    }

    private static void add(StringBuilder b, CharSequence name, int n) {
        if (n != 0)
            b.append(b.length() == 0 ? "" : ", ").append(name).append(' ').append(n);
    }
}
