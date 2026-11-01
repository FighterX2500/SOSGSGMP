package sosgsgmp.session;

import java.util.ArrayList;
import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.setup.FactionStep;

/**
 * The session's chat on this PC (spec §12): the last {@link #MAX_LINES} lines, in the host's order, so every PC shows
 * the same ones. A chat line is only shown, never an action, so it isn't shared state: no tick, no hash, and it isn't
 * saved. It belongs to the session and passes from the lobby to the setup; the box that shows it is rebuilt after every
 * load, so the box's place on this PC is kept here too. Game thread only. The n-th player of the session has the n-th
 * colour, as on cursors.
 */
public final class Chat {

    /** A line's {@code playerId} when no player said it. */
    public static final int NOTICE = -1;
    /** The lines kept for the session. */
    public static final int MAX_LINES = 200;
    /** The longest message, in characters. */
    public static final int MAX_TEXT = 200;
    /** A PC sends at most this many messages in {@link #BURST_NANOS}. */
    static final int BURST = 5;
    static final long BURST_NANOS = 5_000_000_000L;

    /** One line; {@code colour} is the speaker's place in the session when it was said. */
    public record Line(int playerId, String name, int colour, String text) {}

    private static final String TOO_FAST = "You're sending too fast. Wait a moment.";

    private final List<Line> lines = new ArrayList<>();
    private final long[] sentAt = new long[BURST];
    private int sent;
    private int added;
    private int boxX = -1;
    private int boxY = -1;
    private boolean boxHidden;
    /** The lobby's players at the last refresh; null before the first. */
    private List<String> roster;

    /**
     * A message as it may be shown: stripped, with control characters, surrogates and U+FFFF dropped, at most
     * {@link #MAX_TEXT} characters. U+FFFF is one past the end of the game's font table, so drawing it would crash the PC.
     */
    public static String clean(String text) {
        StringBuilder b = new StringBuilder(Math.min(text.length(), MAX_TEXT));
        for (int i = 0; i < text.length() && b.length() < MAX_TEXT; i++) {
            char c = text.charAt(i);
            if (c >= ' ' && c != 0x7F && !Character.isISOControl(c) && !Character.isSurrogate(c) && c != 0xFFFF)
                b.append(c);
        }
        return b.toString().strip();
    }

    /**
     * What this PC's player sends for {@code text} at {@code nanos}, or null when nothing goes out: an empty message, or
     * over the limit of {@link #BURST} in {@link #BURST_NANOS}, which leaves a line saying so.
     */
    String outgoing(String text, long nanos) {
        String clean = clean(text);
        if (clean.isEmpty())
            return null;
        int slot = sent % BURST;
        if (sent >= BURST && nanos - sentAt[slot] < BURST_NANOS) {
            if (lines.isEmpty() || !lines.get(lines.size() - 1).text().equals(TOO_FAST))
                notice(TOO_FAST);
            return null;
        }
        sentAt[slot] = nanos;
        sent++;
        return clean;
    }

    /** Player {@code playerId} said {@code text}, cleaned here as it came from the network; a player not in {@code players} is ignored. */
    void heard(int playerId, List<LobbyPlayer> players, String text) {
        String clean = clean(text);
        if (clean.isEmpty())
            return;
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).id() == playerId) {
                add(new Line(playerId, players.get(i).name(), i, clean));
                return;
            }
        }
    }

    /** A line that no player said, on this PC only. */
    public void notice(String text) {
        add(new Line(NOTICE, "", 0, text));
    }

    /** One line for each player whose Ready differs between two tables of picks; a player who left has no line. */
    void readyChanged(List<FactionStep.PlayerPick> before, List<FactionStep.PlayerPick> now, List<LobbyPlayer> players) {
        for (LobbyPlayer p : players) {
            boolean was = ready(before, p.id());
            boolean is = ready(now, p.id());
            if (was != is)
                notice(p.name() + (is ? " is ready." : " is no longer ready."));
        }
    }

    private static boolean ready(List<FactionStep.PlayerPick> table, int id) {
        for (FactionStep.PlayerPick p : table)
            if (p.playerId() == id)
                return p.ready();
        return false;
    }

    /** The lobby's players now, at each refresh: who joined or left since the last call; the first with anyone in only remembers. */
    public void roster(List<LobbyPlayer> players) {
        List<String> now = new ArrayList<>();
        for (LobbyPlayer p : players)
            now.add(p.name());
        if (roster != null && !roster.isEmpty()) {
            for (String name : now)
                if (!roster.contains(name))
                    notice(name + " joined.");
            for (String name : roster)
                if (!now.contains(name))
                    notice(name + " left.");
        }
        roster = now;
    }

    private void add(Line line) {
        lines.add(line);
        if (lines.size() > MAX_LINES)
            lines.remove(0);
        added++;
    }

    public int size() {
        return lines.size();
    }

    public Line line(int index) {
        return lines.get(index);
    }

    /** Counts every line ever added, so the box rebuilds its rows only when this moves, and the log can count messages. */
    public int added() {
        return added;
    }

    /** The box's left edge on this PC; -1 until the player moves it. */
    public int boxX() {
        return boxX;
    }

    /** The box's top edge on this PC; -1 until the player moves it. */
    public int boxY() {
        return boxY;
    }

    public void boxAt(int x, int y) {
        boxX = x;
        boxY = y;
    }

    public boolean boxHidden() {
        return boxHidden;
    }

    public void boxHidden(boolean hidden) {
        boxHidden = hidden;
    }
}
