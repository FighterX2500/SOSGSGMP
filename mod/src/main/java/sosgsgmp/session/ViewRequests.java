package sosgsgmp.session;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import sosgsgmp.net.Message;
import sosgsgmp.net.Protocol;

/**
 * City viewing's requests and transfers on one PC (spec §10, SOS-82; docs/plan/city-viewing.md §1, §2). Plain Java,
 * one per PC once it plays; the game side asks the engine and shows the windows.
 *
 * <p>The viewer's PC asks; the host checks the ask against the engine and passes it to the owner's PC, whose player
 * allows or refuses it (no answer in {@link #ANSWER_NANOS} counts as Refuse). On Allow the host begins the view in the
 * engine and tells the viewer. The owner's city and its view hashes then go to the host, which passes them to that
 * viewer's PC only. The viewer's Close, or a limit its PC hits, goes to the host, which ends the view in the engine.
 *
 * <p>The host is the router: every message goes through it, and it acts for itself as a viewer or an owner without the
 * network. Slots are the PCs' numbers. Game thread only.
 */
public final class ViewRequests {

    /** An ask with no answer this long counts as Refuse (spec §10 Asking). */
    public static final long ANSWER_NANOS = 30_000_000_000L;
    /** The reason the owner refused: its player chose Refuse. */
    public static final String REFUSED = "refused";
    /** The reason the owner refused: its player didn't answer in time. */
    public static final String NO_ANSWER = "no answer";
    /** The reason the host refused: the owner's player left the game. */
    public static final String LEFT = "left the game";
    /** Why a view ends when its viewer closes it: the engine's {@code View.CLOSED}. */
    public static final int CLOSED = 0;

    /** Sends a message to the PC in a slot: from the host to any PC, from a joined PC to the host only. */
    interface Net {
        void send(int slot, Message m);
    }

    /** An ask this PC's player answers: the player in slot {@code viewer} asked at {@code at} (the clock's nanoseconds). */
    public record Ask(int viewer, long at) {}

    /** A view between the player in slot {@code viewer} and the city of the player in slot {@code owner}. */
    public record Pair(int viewer, int owner) {}

    /** Host: the view of the player in slot {@code viewer} ends, for {@code why} (the engine's {@code View} codes). */
    public record End(int viewer, int why) {}

    private static final byte[] NONE = new byte[0];

    private final boolean host;
    private final int local;
    private final int hostSlot;
    private final Net net;
    private final LongSupplier clock;

    // ---------------------------------------------------------------- this PC as the viewer

    /** The owner this PC's player asked, until the answer comes; -1 otherwise. */
    private int asked = -1;
    /** The latest answer to this PC's ask, until taken. */
    private Message.ViewAnswer answer;
    /** The city coming in: its view's freeze step, its bytes and how many are here; -1 while none comes. */
    private long cityStep = -1;
    private byte[] city;
    private int cityHave;
    /** A whole city, until taken: its step and bytes. */
    private long doneStep = -1;
    private byte[] done;
    private final ByteArrayOutputStream hashes = new ByteArrayOutputStream();

    // ---------------------------------------------------------------- this PC as the owner

    private final List<Ask> asks = new ArrayList<>();

    // ---------------------------------------------------------------- the host

    private final ArrayDeque<Pair> checks = new ArrayDeque<>();
    private final ArrayDeque<Pair> begins = new ArrayDeque<>();
    private final ArrayDeque<End> ends = new ArrayDeque<>();
    /** Asks passed on or being checked, by viewer: the owner asked. */
    private final Map<Integer, Integer> pending = new HashMap<>();
    /** Views begun, by viewer: the owner, whose city and hashes go to that viewer. */
    private final Map<Integer, Integer> open = new HashMap<>();

    /** This PC, in slot {@code local}; the host is in slot {@code hostSlot}. */
    ViewRequests(boolean host, int local, int hostSlot, Net net, LongSupplier clock) {
        if (host != (local == hostSlot))
            throw new IllegalArgumentException("SOSGSGMP: view requests for slot " + local + (host ? " as the host" : "") + ", host slot " + hostSlot);
        this.host = host;
        this.local = local;
        this.hostSlot = hostSlot;
        this.net = net;
        this.clock = clock;
    }

    /** This PC's slot. */
    public int local() {
        return local;
    }

    // ---------------------------------------------------------------- the viewer's side

    /** This PC's player asks to view the city of the player in slot {@code owner}. One ask at a time. */
    public void ask(int owner) {
        if (asked >= 0)
            throw new IllegalStateException("SOSGSGMP: a view of slot " + owner + " asked while slot " + asked + "'s is unanswered");
        if (owner == local)
            throw new IllegalArgumentException("SOSGSGMP: slot " + local + " asked to view its own city");
        asked = owner;
        toHost(new Message.ViewAsk(local, owner));
    }

    /** The owner this PC's player asked and is waiting for; -1 when none. */
    public int asked() {
        return asked;
    }

    /** The latest answer to this PC's ask, once; null when none came since. */
    public Message.ViewAnswer takeAnswer() {
        Message.ViewAnswer a = answer;
        answer = null;
        return a;
    }

    /** This PC's view ends, for {@code why}: its player closed it, or its PC hit a limit or lost step. */
    public void close(int why) {
        if (why < 0 || why > Protocol.MAX_VIEW_CLOSE)
            throw new IllegalArgumentException("SOSGSGMP: a view closed for reason " + why);
        toHost(new Message.ViewClose(local, why));
    }

    /** The owner's city for the view freezing at {@code step}, once all of it is here; returned once, else null. */
    public byte[] takeCity(long step) {
        if (done == null || doneStep != step)
            return null;
        byte[] c = done;
        done = null;
        doneStep = -1;
        return c;
    }

    /** The owner's view hashes that came in since the last call; empty when none. */
    public byte[] takeHashes() {
        if (hashes.size() == 0)
            return NONE;
        byte[] h = hashes.toByteArray();
        hashes.reset();
        return h;
    }

    // ---------------------------------------------------------------- the owner's side

    /** The oldest ask this PC's player hasn't answered, or null. */
    public Ask ask() {
        return asks.isEmpty() ? null : asks.get(0);
    }

    /** This PC's player allows or refuses the ask of the player in slot {@code viewer}. Nothing if there is none. */
    public void answer(int viewer, boolean allow) {
        answer(viewer, allow, allow ? "" : REFUSED);
    }

    /** Every ask unanswered for {@link #ANSWER_NANOS} is refused. Returns the viewers refused so. */
    public List<Integer> expire() {
        if (asks.isEmpty())
            return List.of(); // every frame: nothing made when there is nothing to expire
        List<Integer> refused = new ArrayList<>();
        long now = clock.getAsLong();
        for (Ask a : new ArrayList<>(asks))
            if (now - a.at() >= ANSWER_NANOS) {
                answer(a.viewer(), false, NO_ANSWER);
                refused.add(a.viewer());
            }
        return refused;
    }

    private void answer(int viewer, boolean allow, String reason) {
        if (!asks.removeIf(a -> a.viewer() == viewer))
            return;
        toHost(new Message.ViewAnswer(viewer, local, allow, reason));
    }

    /** Owner: its city for the view of the player in slot {@code viewer}, freezing at {@code step}, in pieces. */
    public void city(int viewer, long step, byte[] bytes) {
        if (bytes.length > Protocol.MAX_VIEW_CITY)
            throw new IllegalArgumentException("SOSGSGMP: a city of " + bytes.length + " bytes for a view");
        int offset = 0;
        do {
            int to = Math.min(bytes.length, offset + Protocol.MAX_STREAM_PIECE);
            toHost(new Message.ViewCity(viewer, step, bytes.length, offset, Arrays.copyOfRange(bytes, offset, to)));
            offset = to;
        } while (offset < bytes.length);
    }

    /** Owner: more view hashes for the player in slot {@code viewer}, in pieces. Nothing for none. */
    public void hashes(int viewer, byte[] bytes) {
        for (int from = 0; from < bytes.length; from += Protocol.MAX_STREAM_PIECE)
            toHost(new Message.ViewHashes(viewer, Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + Protocol.MAX_STREAM_PIECE))));
    }

    // ---------------------------------------------------------------- the host's side

    /** Host: an ask to check against the engine, once; then {@link #checked}. Null when none waits. */
    public Pair takeCheck() {
        return checks.poll();
    }

    /** Host: the ask checked: refused with {@code refusal}, or (null) passed to the owner. */
    public void checked(Pair p, String refusal) {
        if (refusal != null) {
            pending.remove(p.viewer());
            deliver(p.viewer(), new Message.ViewAnswer(p.viewer(), p.owner(), false, refusal));
            return;
        }
        deliver(p.owner(), new Message.ViewAsk(p.viewer(), p.owner()));
    }

    /** Host: a view its owner allowed, to begin in the engine, once; then {@link #begun}. Null when none waits. */
    public Pair takeBegin() {
        return begins.poll();
    }

    /** Host: the allowed view began in the engine, or couldn't ({@code refusal}); the viewer hears either. */
    public void begun(Pair p, String refusal) {
        pending.remove(p.viewer());
        if (refusal == null)
            open.put(p.viewer(), p.owner());
        deliver(p.viewer(), new Message.ViewAnswer(p.viewer(), p.owner(), refusal == null, refusal == null ? "" : refusal));
    }

    /** Host: a view to end in the engine, once. Null when none waits. */
    public End takeEnd() {
        return ends.poll();
    }

    /**
     * Host: the player in {@code slot} left the game. An ask of or to them ends, the viewer hearing why, and a view
     * they are in ends.
     */
    public void gone(int slot) {
        for (Map.Entry<Integer, Integer> e : new ArrayList<>(pending.entrySet())) {
            if (e.getKey() == slot) {
                pending.remove(slot);
            } else if (e.getValue() == slot) {
                pending.remove(e.getKey());
                deliver(e.getKey(), new Message.ViewAnswer(e.getKey(), slot, false, LEFT));
            }
        }
        checks.removeIf(p -> p.viewer() == slot || p.owner() == slot);
        begins.removeIf(p -> p.viewer() == slot || p.owner() == slot);
        for (Map.Entry<Integer, Integer> e : new ArrayList<>(open.entrySet()))
            if (e.getKey() == slot || e.getValue() == slot) {
                open.remove(e.getKey());
                ends.add(new End(e.getKey(), CLOSED));
            }
    }

    // ---------------------------------------------------------------- messages

    /**
     * A view message from the PC in slot {@code from} (on a joined PC, always the host). Returns why it breaks the
     * protocol, or null. A message that came too late (an answer to an ask already over) changes nothing.
     */
    public String received(int from, Message m) {
        if (!host && from != hostSlot)
            return "a view message from slot " + from + ", not the host";
        return host ? route(from, m) : endpoint(m);
    }

    private void toHost(Message m) {
        if (host)
            route(local, m);
        else
            net.send(hostSlot, m);
    }

    /** Host: to the PC in {@code slot}, or to this PC's own side. */
    private void deliver(int slot, Message m) {
        if (slot == local)
            endpoint(m);
        else
            net.send(slot, m);
    }

    private String route(int from, Message m) {
        switch (m) {
            case Message.ViewAsk a -> {
                if (a.viewer() != from)
                    return "slot " + from + " asked for slot " + a.viewer();
                if (a.owner() == a.viewer())
                    return "slot " + from + " asked to view its own city";
                if (pending.containsKey(a.viewer()) || pending.containsValue(a.owner())) {
                    deliver(a.viewer(), new Message.ViewAnswer(a.viewer(), a.owner(), false, "Another request is waiting for an answer."));
                    return null;
                }
                pending.put(a.viewer(), a.owner());
                checks.add(new Pair(a.viewer(), a.owner()));
            }
            case Message.ViewAnswer a -> {
                if (a.owner() != from)
                    return "slot " + from + " answered for slot " + a.owner();
                Integer owner = pending.get(a.viewer());
                if (owner == null || owner != a.owner() || begins.contains(new Pair(a.viewer(), a.owner())))
                    return null; // too late: the ask is over
                if (a.allowed()) {
                    begins.add(new Pair(a.viewer(), a.owner()));
                } else {
                    pending.remove(a.viewer());
                    deliver(a.viewer(), a);
                }
            }
            case Message.ViewClose c -> {
                if (c.viewer() != from)
                    return "slot " + from + " closed slot " + c.viewer() + "'s view";
                ends.add(new End(c.viewer(), c.why()));
            }
            case Message.ViewCity c -> {
                Integer owner = open.get(c.viewer());
                if (owner == null || owner != from)
                    return "slot " + from + " sent a city for slot " + c.viewer() + ", which doesn't view it";
                deliver(c.viewer(), c);
            }
            case Message.ViewHashes h -> {
                Integer owner = open.get(h.viewer());
                if (owner == null || owner != from)
                    return "slot " + from + " sent view hashes for slot " + h.viewer() + ", which doesn't view it";
                deliver(h.viewer(), h);
            }
            default -> throw new IllegalArgumentException("SOSGSGMP: not a view message: " + m);
        }
        return null;
    }

    /** This PC as the viewer or the owner. */
    private String endpoint(Message m) {
        switch (m) {
            case Message.ViewAsk a -> {
                if (a.owner() != local)
                    return "an ask for slot " + a.owner() + "'s city reached slot " + local;
                asks.removeIf(x -> x.viewer() == a.viewer());
                asks.add(new Ask(a.viewer(), clock.getAsLong()));
            }
            case Message.ViewAnswer a -> {
                if (a.viewer() != local)
                    return "an answer for slot " + a.viewer() + " reached slot " + local;
                if (asked != a.owner())
                    return null; // an ask this PC no longer waits for
                asked = -1;
                answer = a;
            }
            case Message.ViewCity c -> {
                if (c.viewer() != local)
                    return "a city for slot " + c.viewer() + " reached slot " + local;
                return cityPiece(c);
            }
            case Message.ViewHashes h -> {
                if (h.viewer() != local)
                    return "view hashes for slot " + h.viewer() + " reached slot " + local;
                hashes.writeBytes(h.bytes());
            }
            default -> {
                return m.getClass().getSimpleName() + " doesn't go to a viewer or an owner";
            }
        }
        return null;
    }

    private String cityPiece(Message.ViewCity c) {
        if (c.step() != cityStep) {
            if (c.offset() != 0)
                return "a city piece at " + c.offset() + " of a view's city that didn't start";
            cityStep = c.step();
            city = new byte[c.total()];
            cityHave = 0;
        }
        if (city == null || c.offset() != cityHave || c.total() != city.length)
            return "a city piece at " + c.offset() + " of " + c.total() + " bytes, with " + cityHave + " here";
        byte[] b = c.bytes();
        System.arraycopy(b, 0, city, cityHave, b.length);
        cityHave += b.length;
        if (cityHave == city.length) {
            done = city;
            doneStep = cityStep;
            city = null;
        }
        return null;
    }
}
