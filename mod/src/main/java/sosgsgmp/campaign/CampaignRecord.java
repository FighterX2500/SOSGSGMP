package sosgsgmp.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One campaign save as this PC holds it (SOS-64): the campaign, the save's number and kind (the same on every PC), and
 * this PC's own part: its slot, its vanilla save's file name and its world hash at the save's step. Stored as
 * {@code key=value} lines, the format version first; a format this mod doesn't know is refused, never guessed.
 */
public record CampaignRecord(Campaign campaign, int number, Kind kind, long time, int slot, String file, long worldHash) {

    /** Raise whenever the text's keys or their meaning change; {@link #fromText} reads every version still around. */
    public static final int FORMAT = 1;

    /** Save: the host's button, kept until deleted. Auto: an autosave, rotated. */
    public enum Kind {
        SAVE, AUTO
    }

    public CampaignRecord {
        if (number < 1)
            throw new IllegalArgumentException("SOSGSGMP: campaign save number " + number);
        if (file == null || file.isEmpty() || file.contains("/") || file.contains("\\"))
            throw new IllegalArgumentException("SOSGSGMP: campaign save file '" + file + "'");
        boolean known = false;
        for (Campaign.Player p : campaign.players())
            known |= p.slot() == slot;
        if (!known)
            throw new IllegalArgumentException("SOSGSGMP: a record of slot " + slot + ", which campaign " + campaign.name() + " doesn't have");
    }

    /** This record's player: the one in its slot. */
    public Campaign.Player player() {
        for (Campaign.Player p : campaign.players())
            if (p.slot() == slot)
                return p;
        throw new IllegalStateException("SOSGSGMP: no player in slot " + slot);
    }

    public String toText() {
        StringBuilder b = new StringBuilder();
        line(b, "format", FORMAT);
        line(b, "campaign", Long.toHexString(campaign.id()));
        line(b, "name", campaign.name());
        line(b, "seed", campaign.seed());
        line(b, "difficulty", campaign.difficulty());
        line(b, "players", campaign.players().size());
        for (int i = 0; i < campaign.players().size(); i++) {
            Campaign.Player p = campaign.players().get(i);
            line(b, "player." + i + ".key", Long.toHexString(p.key()));
            line(b, "player." + i + ".name", p.name());
            line(b, "player." + i + ".slot", p.slot());
        }
        line(b, "number", number);
        line(b, "kind", kind.name());
        line(b, "time", time);
        line(b, "slot", slot);
        line(b, "file", file);
        line(b, "worldHash", Long.toHexString(worldHash));
        return b.toString();
    }

    private static void line(StringBuilder b, String key, Object value) {
        String v = String.valueOf(value);
        if (v.contains("\n") || v.contains("\r"))
            throw new IllegalArgumentException("SOSGSGMP: campaign record value for " + key + " has a line break");
        b.append(key).append('=').append(v).append('\n');
    }

    /** Reads a record; throws {@link IllegalArgumentException} naming what is wrong. */
    public static CampaignRecord fromText(String text) {
        Map<String, String> v = new LinkedHashMap<>();
        for (String l : text.split("\r?\n")) {
            if (l.isEmpty())
                continue;
            int eq = l.indexOf('=');
            if (eq <= 0)
                throw new IllegalArgumentException("SOSGSGMP: campaign record line without a key: '" + l + "'");
            v.put(l.substring(0, eq), l.substring(eq + 1));
        }
        int format = integer(v, "format");
        if (format != FORMAT)
            throw new IllegalArgumentException("SOSGSGMP: campaign record of format " + format + "; this mod reads " + FORMAT);
        int n = integer(v, "players");
        List<Campaign.Player> players = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            players.add(new Campaign.Player(hex(v, "player." + i + ".key"), text(v, "player." + i + ".name"),
                    integer(v, "player." + i + ".slot")));
        Campaign c = new Campaign(hex(v, "campaign"), text(v, "name"), integer(v, "seed"), text(v, "difficulty"), players);
        Kind kind;
        try {
            kind = Kind.valueOf(text(v, "kind"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("SOSGSGMP: campaign record of kind '" + v.get("kind") + "'", e);
        }
        return new CampaignRecord(c, integer(v, "number"), kind, longValue(v, "time"), integer(v, "slot"), text(v, "file"),
                hex(v, "worldHash"));
    }

    private static String text(Map<String, String> v, String key) {
        String s = v.get(key);
        if (s == null)
            throw new IllegalArgumentException("SOSGSGMP: campaign record without " + key);
        return s;
    }

    private static int integer(Map<String, String> v, String key) {
        try {
            return Integer.parseInt(text(v, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SOSGSGMP: campaign record's " + key + " isn't a number: " + v.get(key), e);
        }
    }

    private static long longValue(Map<String, String> v, String key) {
        try {
            return Long.parseLong(text(v, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SOSGSGMP: campaign record's " + key + " isn't a number: " + v.get(key), e);
        }
    }

    private static long hex(Map<String, String> v, String key) {
        try {
            return Long.parseUnsignedLong(text(v, key), 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SOSGSGMP: campaign record's " + key + " isn't hexadecimal: " + v.get(key), e);
        }
    }
}
