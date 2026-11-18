package sosgsgmp.session;

import java.util.ArrayDeque;
import java.util.List;

import sosgsgmp.net.Message;

/**
 * Votes to restart a battle (spec §8, SOS-231). A vote goes through the host, like a pause: any player calls it, the
 * host opens it for every player in the session and counts the answers ({@link RestartTally}), one vote at a time, and
 * every PC hears the opening and the result in the host's order. The restart itself goes through the engine's log, which
 * the host's game side writes when a vote passes. Plain Java; the sessions feed it, the game side takes each message.
 */
public final class RestartVotes {

    private final ArrayDeque<Message.RestartVote> heard = new ArrayDeque<>();
    /** Host: the open vote, or null. */
    private RestartTally open;
    /** Host: the last vote's number. */
    private int last;
    /** Every PC: the vote open as this PC last heard, or 0. */
    private int heardOpen;

    void heard(Message.RestartVote v) {
        heard.add(v);
        if (v.state() == Message.RestartVote.OPEN)
            heardOpen = v.vote();
        else if (v.vote() == heardOpen)
            heardOpen = 0;
    }

    /** The vote open as this PC last heard, the one its player answers; 0 for none. */
    public int heardOpen() {
        return heardOpen;
    }

    /** The next opening or result in the host's order, or null; each is handed out once. */
    public Message.RestartVote take() {
        return heard.poll();
    }

    /** Host: the open vote, or null. */
    public RestartTally open() {
        return open;
    }

    /**
     * Host: player {@code caller} calls a vote to restart {@code battle}, with {@code voters} every player in the session
     * now. The opening for every PC, or the refusal for the caller only while another vote is open.
     */
    Message.RestartVote call(int caller, long battle, List<Integer> voters, long nowMs) {
        if (open != null)
            return refusal(caller, battle, "Another restart vote is open.");
        open = new RestartTally(++last, caller, battle, voters, nowMs);
        return announce(Message.RestartVote.OPEN, "");
    }

    private static Message.RestartVote refusal(int caller, long battle, String reason) {
        return new Message.RestartVote(0, caller, battle, Message.RestartVote.REFUSED, 0, 0, reason);
    }

    /** Host: player {@code id} answers vote {@code vote}; the result for every PC once it is decided, else null. */
    Message.RestartVote answer(int id, int vote, boolean yes, List<Integer> present, long nowMs) {
        if (open == null || open.vote() != vote || !open.answer(id, yes))
            return null; // a late or repeated answer changes nothing
        return check(present, nowMs);
    }

    /**
     * Host, every frame: the result for every PC once the vote is decided (its time up, or a voter gone with
     * {@code present} the players still in the session), else null.
     */
    Message.RestartVote check(List<Integer> present, long nowMs) {
        if (open == null)
            return null;
        open.keepOnly(present);
        return switch (open.outcome(nowMs)) {
            case OPEN -> null;
            case PASSED -> close(Message.RestartVote.PASSED, "");
            case FAILED -> close(Message.RestartVote.FAILED, "");
        };
    }

    /** Host: the open vote's battle can't restart any more ({@code reason}); the result for every PC, or null with none open. */
    Message.RestartVote cancel(String reason) {
        return open == null ? null : close(Message.RestartVote.CANCELLED, reason);
    }

    private Message.RestartVote close(int state, String reason) {
        Message.RestartVote v = announce(state, reason);
        open = null;
        return v;
    }

    private Message.RestartVote announce(int state, String reason) {
        return new Message.RestartVote(open.vote(), open.caller(), open.battle(), state, open.yes(), open.voters(), reason);
    }
}
