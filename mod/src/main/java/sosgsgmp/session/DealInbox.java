package sosgsgmp.session;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which deals between players this PC still has to show (SOS-72, spec §6 and §12): the proposals made to this PC's
 * player, one popup at a time, and the answers to this player's own proposals, each once. The engine keeps the deals as
 * world state; this only remembers what this PC has shown. An answer already there at the first look, after a load or
 * a resume, isn't news; a proposal still open then pops up again, since it can still be answered.
 */
public final class DealInbox {

    /** A proposal or an answer: its id and the two players' slots. */
    public record Entry(int id, int from, int to) {
    }

    private final int me;
    private final Set<Integer> shownProposals = new HashSet<>();
    private final Set<Integer> seenAnswers = new HashSet<>();
    private boolean looked;

    /** {@code me}: this PC's player's slot. */
    public DealInbox(int me) {
        this.me = me;
    }

    /**
     * The oldest proposal to this player in {@code open} (oldest first) not shown yet, or null. It stays unshown until
     * {@link #shown}.
     */
    public Entry nextProposal(List<Entry> open, List<Entry> answers) {
        firstLook(answers);
        for (Entry e : open) {
            if (e.to() == me && !shownProposals.contains(e.id())) {
                return e;
            }
        }
        return null;
    }

    /** The popup for proposal {@code id} was shown: it isn't offered again. */
    public void shown(int id) {
        shownProposals.add(id);
    }

    /** The answers to this player's own proposals not seen yet, oldest first; each is returned once. */
    public List<Entry> newAnswers(List<Entry> answers) {
        firstLook(answers);
        List<Entry> out = new ArrayList<>();
        for (Entry e : answers) {
            if (e.from() == me && seenAnswers.add(e.id())) {
                out.add(e);
            }
        }
        return out;
    }

    private void firstLook(List<Entry> answers) {
        if (looked) {
            return;
        }
        looked = true;
        for (Entry e : answers) {
            seenAnswers.add(e.id());
        }
    }
}
