package sosgsgmp.game;

/**
 * SOSGSGMP: the height of the text box of vanilla's full-screen prompt ({@code IPromtScreen}) for a text of the mod's,
 * so that every line is drawn: vanilla's box is 100 high and draws only the lines that start inside it. Plain logic;
 * {@link PromptBox} applies it.
 */
public final class PromptLayout {

    /** Vanilla's box height. */
    public static final int VANILLA_HEIGHT = 100;
    /** Under the box: vanilla's gap to the buttons ({@code C.SCALE*10}) and a row of the mod's text buttons (H2 + 8). */
    static final int BELOW = 40 + 32;

    private PromptLayout() {
    }

    /**
     * The box for a text {@code textHeight} high on a screen {@code screenHeight} high: at least vanilla's, and at most
     * what keeps the centred box and the buttons under it on the screen (the lines below that are cut then).
     */
    public static int boxHeight(int textHeight, int screenHeight) {
        return Math.max(VANILLA_HEIGHT, Math.min(textHeight, screenHeight - 2 * BELOW));
    }
}
