package sosgsgmp.report;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JVM's fatal error file, {@code hs_err_pid<pid>.log} (SOS-246), made fit to leave the PC. Only a file of this
 * game's JVM goes ({@link #isGame}): the temp folder holds every JVM's on the PC. Its environment section (the lines
 * between {@code Environment Variables:} and the next blank line) is taken out, and the user's name and home folder
 * are replaced everywhere else, since paths, the command line and the system section hold them. The rest stays as it
 * was, byte for byte. Plain Java.
 */
public final class HsErr {

    /** The files' names, as the JVM writes them. */
    public static final String GLOB = "hs_err_pid*.log";

    private static final String SECTION = "Environment Variables:";
    static final String REMOVED = "(removed before this file left the PC)";
    static final String USER = "<user>", HOME = "<home>";
    /** The game's JVM runs this class ({@code init/Main} starts it); its class path holds {@code SongsOfSyx.jar}. */
    private static final String GAME_CLASS = "init.MainProcess", GAME_JAR = "songsofsyx";

    private HsErr() {
    }

    /**
     * Whether the file is this game's: its {@code java_command:} or {@code Command Line:} line (the VM Arguments and
     * Summary sections of HotSpot's file) names the game's main class or its jar.
     */
    public static boolean isGame(byte[] file) {
        String text = new String(file, StandardCharsets.ISO_8859_1);
        for (String line : text.split("\n")) {
            String l = line.strip();
            if ((l.startsWith("java_command:") || l.startsWith("Command Line:"))
                    && (l.contains(GAME_CLASS) || l.toLowerCase(Locale.ROOT).contains(GAME_JAR)))
                return true;
        }
        return false;
    }

    /**
     * {@code file}'s bytes without the environment section's lines, and with the home folder {@code home} and the user
     * name {@code user} replaced (any case; the name only as a whole word); null or blank ones are left alone.
     */
    public static byte[] redact(byte[] file, String user, String home) {
        // ISO-8859-1 maps every byte to one char and back: whatever the file's own encoding, nothing else changes
        String text = withoutEnvironment(new String(file, StandardCharsets.ISO_8859_1));
        for (String h : forms(home)) {
            text = replace(text, Pattern.quote(h), HOME);
            text = replace(text, Pattern.quote(h.replace('\\', '/')), HOME);
        }
        for (String u : forms(user))
            text = replace(text, "(?<![A-Za-z0-9])" + Pattern.quote(u) + "(?![A-Za-z0-9])", USER);
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** {@code s} as the file may hold it, read byte for byte: written as UTF-8, or in the system's own encoding. */
    private static Set<String> forms(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null || s.isBlank())
            return out;
        for (Charset c : new Charset[] { StandardCharsets.UTF_8, Charset.defaultCharset() })
            out.add(new String(s.getBytes(c), StandardCharsets.ISO_8859_1));
        return out;
    }

    private static String replace(String text, String regex, String with) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(Matcher.quoteReplacement(with));
    }

    private static String withoutEnvironment(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inside = false;
        int at = 0;
        while (at < text.length()) {
            int end = text.indexOf('\n', at);
            end = end < 0 ? text.length() : end + 1;
            String line = text.substring(at, end);
            at = end;
            if (inside) {
                if (!line.isBlank())
                    continue;
                inside = false;
                out.append(REMOVED).append(line.endsWith("\r\n") ? "\r\n" : "\n");
            } else if (line.strip().equals(SECTION)) {
                inside = true;
            }
            out.append(line);
        }
        if (inside) // the file ended inside the section
            out.append(REMOVED).append('\n');
        return out.toString();
    }
}
