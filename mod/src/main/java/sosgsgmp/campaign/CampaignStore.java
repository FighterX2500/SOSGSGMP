package sosgsgmp.campaign;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * This PC's campaign records (SOS-64): one file per save, {@code <campaign id>-<number>.txt}, in one folder. A record
 * that can't be read is left out of the list with the reason kept in {@link #problems()}, so one broken file doesn't
 * hide the others. Plain Java; the game's save files are the caller's.
 */
public final class CampaignStore {

    private static final String ENDING = ".txt";

    private final Path dir;
    private final List<String> problems = new ArrayList<>();

    public CampaignStore(Path dir) {
        this.dir = dir;
    }

    /** Every record that reads, newest first. */
    public List<CampaignRecord> all() {
        problems.clear();
        List<CampaignRecord> all = new ArrayList<>();
        if (!Files.isDirectory(dir))
            return all;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!f.getFileName().toString().endsWith(ENDING))
                    continue;
                try {
                    all.add(CampaignRecord.fromText(Files.readString(f, StandardCharsets.UTF_8)));
                } catch (IOException | IllegalArgumentException e) {
                    problems.add(f.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't list the campaign records in " + dir, e);
        }
        all.sort(Comparator.comparingLong(CampaignRecord::time).reversed().thenComparing(CampaignRecord::number,
                Comparator.reverseOrder()));
        return all;
    }

    /** Why records were left out of the last {@link #all()}, one line each. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** The record of save {@code number} of campaign {@code campaignId}, or null. */
    public CampaignRecord find(long campaignId, int number) {
        for (CampaignRecord r : all())
            if (r.campaign().id() == campaignId && r.number() == number)
                return r;
        return null;
    }

    /** The number the host gives its next save of {@code campaignId}: one past the highest this PC has. */
    public int nextNumber(long campaignId) {
        int max = 0;
        for (CampaignRecord r : all())
            if (r.campaign().id() == campaignId)
                max = Math.max(max, r.number());
        return max + 1;
    }

    public void write(CampaignRecord r) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file(r.campaign().id(), r.number()), r.toText(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: can't write campaign record " + r.number() + " in " + dir, e);
        }
    }

    /**
     * Removes the records of {@code campaignId}'s autosaves past the newest {@code keep} and returns them, for the
     * caller to delete their game saves. Saves of kind SAVE are never removed.
     */
    public List<CampaignRecord> rotate(long campaignId, int keep) {
        List<CampaignRecord> gone = new ArrayList<>();
        int kept = 0;
        for (CampaignRecord r : all()) {
            if (r.campaign().id() != campaignId || r.kind() != CampaignRecord.Kind.AUTO)
                continue;
            if (kept < keep) {
                kept++;
                continue;
            }
            try {
                Files.deleteIfExists(file(campaignId, r.number()));
            } catch (IOException e) {
                throw new UncheckedIOException("SOSGSGMP: can't delete campaign record " + r.number() + " in " + dir, e);
            }
            gone.add(r);
        }
        return gone;
    }

    private Path file(long campaignId, int number) {
        return dir.resolve(Long.toHexString(campaignId) + "-" + number + ENDING);
    }
}
