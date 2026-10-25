package sosgsgmp.session;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;

import sosgsgmp.campaign.SaveOrder;

/**
 * The host's view of one campaign save (SOS-64): which PCs have reported, with their world hashes or why they couldn't
 * save. Complete once every player has reported; the outcome says what went wrong, or nothing.
 */
final class SaveRound {

    final SaveOrder order;
    private final int hostId;
    /** Each player's report in player order: its hash, or null until it reports. */
    private final Map<Integer, Long> hashes = new LinkedHashMap<>();
    private final Map<Integer, String> problems = new LinkedHashMap<>();

    SaveRound(SaveOrder order, int hostId, Collection<Integer> playerIds) {
        this.order = order;
        this.hostId = hostId;
        for (int id : playerIds)
            hashes.put(id, null);
    }

    /** Player {@code id} saved with world hash {@code hash}, or couldn't ({@code problem}). False if unknown or repeated. */
    boolean report(int id, long hash, String problem) {
        if (!hashes.containsKey(id) || hashes.get(id) != null || problems.containsKey(id))
            return false;
        if (problem != null)
            problems.put(id, problem);
        else
            hashes.put(id, hash);
        return true;
    }

    boolean complete() {
        for (Map.Entry<Integer, Long> e : hashes.entrySet())
            if (e.getValue() == null && !problems.containsKey(e.getKey()))
                return false;
        return true;
    }

    /** Once complete: empty when every PC saved the same world, else one line per PC that didn't. */
    String outcome(IntFunction<String> names) {
        StringBuilder b = new StringBuilder();
        Long reference = hashes.get(hostId);
        for (Map.Entry<Integer, Long> e : hashes.entrySet()) {
            String line = null;
            if (problems.containsKey(e.getKey()))
                line = names.apply(e.getKey()) + " couldn't save: " + problems.get(e.getKey());
            else if (reference != null && !e.getValue().equals(reference))
                line = names.apply(e.getKey()) + "'s world differs from the host's";
            if (line != null)
                b.append(b.length() == 0 ? "" : "\n").append(line);
        }
        return b.toString();
    }
}
