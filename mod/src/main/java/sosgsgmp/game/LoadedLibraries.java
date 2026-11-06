package sosgsgmp.game;

/**
 * SOSGSGMP: where a native library this process loaded lives on Linux (SOS-157), from the {@code /proc/self/maps}
 * listing. Plain Java, so it is tested without a Linux PC.
 */
final class LoadedLibraries {

    private LoadedLibraries() {
    }

    /**
     * The path of the first mapping of {@code file} in {@code maps}, or null when it isn't mapped. A line is the
     * address, permissions, offset, device and inode, then the path, which may hold spaces; nothing before the path
     * holds a slash.
     */
    static String path(String maps, String file) {
        String end = "/" + file;
        for (String line : maps.split("\n")) {
            int at = line.indexOf('/');
            if (at < 0)
                continue;
            String path = line.substring(at).trim();
            if (path.endsWith(end))
                return path;
        }
        return null;
    }
}
