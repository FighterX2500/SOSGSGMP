package sosgsgmp.net;

import java.util.ArrayList;
import java.util.List;

/**
 * Test tooling (SOS-175): the network conditions a test asks this PC's connections to suffer, from the
 * {@value #VARIABLE} environment variable. Never set in normal play; with it unset nothing changes.
 *
 * <p>The spec is comma-separated {@code key=value} pairs:
 * <ul>
 * <li>{@code delay}: milliseconds added to this PC's round trip, half on each way ({@link ConditionedNode});</li>
 * <li>{@code jitter}: up to this many milliseconds more or less on each message, in order, as a reliable stream
 * bunches;</li>
 * <li>{@code loss}: percent of messages that wait for a resend, holding up the ones behind them;</li>
 * <li>{@code seed}: the random draws' seed, so a run can be repeated;</li>
 * <li>{@code steamlag}, {@code steamloss}, {@code steamreorder}: Steam's own fake network settings, packet by packet
 * under its reliable layer (milliseconds added to the round trip, percent lost each way, percent reordered).</li>
 * </ul>
 */
public record NetConditions(int delayMs, int jitterMs, double lossPercent, long seed, int steamLagMs,
                            double steamLossPercent, double steamReorderPercent) {

    public static final String VARIABLE = "SOSGSGMP_NETEM";
    /** The longest outage a test may ask for (owner, 2026-11-08). */
    public static final int MAX_OUTAGE_SECONDS = 60;

    public NetConditions {
        if (delayMs < 0 || delayMs > 10_000)
            throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " delay " + delayMs + " ms, not 0 to 10000");
        if (jitterMs < 0 || jitterMs > 10_000)
            throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " jitter " + jitterMs + " ms, not 0 to 10000");
        if (lossPercent < 0 || lossPercent > 50)
            throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " loss " + lossPercent + " %, not 0 to 50");
        if (steamLagMs < 0 || steamLagMs > 10_000)
            throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " steamlag " + steamLagMs + " ms, not 0 to 10000");
        if (steamLossPercent < 0 || steamLossPercent > 50)
            throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " steamloss " + steamLossPercent + " %, not 0 to 50");
        if (steamReorderPercent < 0 || steamReorderPercent > 50)
            throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " steamreorder " + steamReorderPercent + " %, not 0 to 50");
    }

    /** The conditions in the environment, or null when the variable is unset or empty. */
    public static NetConditions fromEnvironment() {
        String v = System.getenv(VARIABLE);
        return v == null || v.isBlank() ? null : parse(v);
    }

    /** Parses a spec; fails naming the pair it can't read. An empty spec is no conditions, with an outage still possible. */
    public static NetConditions parse(String spec) {
        int delay = 0, jitter = 0, steamLag = 0;
        double loss = 0, steamLoss = 0, steamReorder = 0;
        long seed = 1;
        for (String pair : spec.split(",")) {
            String p = pair.trim();
            if (p.isEmpty())
                continue;
            int eq = p.indexOf('=');
            if (eq < 0)
                throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " '" + p + "' is no key=value pair");
            String k = p.substring(0, eq).trim(), v = p.substring(eq + 1).trim();
            try {
                switch (k) {
                    case "delay" -> delay = Integer.parseInt(v);
                    case "jitter" -> jitter = Integer.parseInt(v);
                    case "loss" -> loss = Double.parseDouble(v);
                    case "seed" -> seed = Long.parseLong(v);
                    case "steamlag" -> steamLag = Integer.parseInt(v);
                    case "steamloss" -> steamLoss = Double.parseDouble(v);
                    case "steamreorder" -> steamReorder = Double.parseDouble(v);
                    default -> throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " has an unknown key '" + k + "'");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("SOSGSGMP: " + VARIABLE + " '" + p + "' has no number", e);
            }
        }
        return new NetConditions(delay, jitter, loss, seed, steamLag, steamLoss, steamReorder);
    }

    /** Whether Steam's own fake network settings are asked for. */
    public boolean steam() {
        return steamLagMs > 0 || steamLossPercent > 0 || steamReorderPercent > 0;
    }

    /** For the playtest log. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        parts.add("delay " + delayMs + " ms");
        parts.add("jitter " + jitterMs + " ms");
        parts.add("loss " + lossPercent + " %");
        if (steam())
            parts.add("Steam lag " + steamLagMs + " ms, loss " + steamLossPercent + " %, reorder " + steamReorderPercent + " %");
        return String.join(", ", parts);
    }
}
