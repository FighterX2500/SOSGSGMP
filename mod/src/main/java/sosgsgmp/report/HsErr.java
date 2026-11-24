package sosgsgmp.report;

import java.nio.charset.StandardCharsets;

/**
 * The JVM's fatal error file, {@code hs_err_pid<pid>.log} (SOS-246), made fit to leave the PC: its environment section
 * (the lines between {@code Environment Variables:} and the next blank line) is taken out, since it may hold user names,
 * paths and keys. The rest stays as it was, byte for byte. Plain Java.
 */
public final class HsErr {

    /** The files' names, as the JVM writes them. */
    public static final String GLOB = "hs_err_pid*.log";

    private static final String SECTION = "Environment Variables:";
    static final String REMOVED = "(removed before this file left the PC)";

    private HsErr() {
    }

    /** {@code file}'s bytes without the environment section's lines; a file without one comes back unchanged. */
    public static byte[] redact(byte[] file) {
        // ISO-8859-1 maps every byte to one char and back: whatever the file's own encoding, nothing else changes
        String text = new String(file, StandardCharsets.ISO_8859_1);
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
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
