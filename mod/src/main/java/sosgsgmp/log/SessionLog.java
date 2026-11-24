package sosgsgmp.log;

/**
 * A session's logs on this PC (SOS-246, SOS-248), opened at Host or Join and carried by the lobby and the setup into
 * the game and through a hot-join's restart: the playtest log, and the recorder (null when its file couldn't be made).
 * The sessions only carry it; the menu's classes write and close it. Plain Java.
 */
public final class SessionLog {

    private final PlayLog text;
    private final Recorder recorder;

    public SessionLog(PlayLog text, Recorder recorder) {
        this.text = text;
        this.recorder = recorder;
    }

    public PlayLog text() {
        return text;
    }

    /** The recorder, or null. */
    public Recorder recorder() {
        return recorder;
    }

    /** Both close: nothing more is written. */
    public void close() {
        text.close();
        if (recorder != null)
            recorder.close();
    }
}
