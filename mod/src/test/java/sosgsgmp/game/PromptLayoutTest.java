package sosgsgmp.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The full-screen prompt's text box: as high as the text, never under vanilla's, never off the screen. */
public class PromptLayoutTest {

    /** H2's line height. */
    private static final int LINE = 24;

    @Test
    void aShortTextKeepsVanillasBox() {
        assertEquals(100, PromptLayout.boxHeight(0, 768));
        assertEquals(100, PromptLayout.boxHeight(4 * LINE, 768));
    }

    @Test
    void aLongTextGetsABoxForEveryLine() {
        // the player-left window: 10 lines, and 15 with a removal refusal and a long name
        assertEquals(10 * LINE, PromptLayout.boxHeight(10 * LINE, 768));
        assertEquals(15 * LINE, PromptLayout.boxHeight(15 * LINE, 768));
    }

    @Test
    void theBoxAndTheButtonsUnderItStayOnTheScreen() {
        int box = PromptLayout.boxHeight(40 * LINE, 768);
        assertEquals(768 - 2 * PromptLayout.BELOW, box);
        int top = (768 - box) / 2;
        assertEquals(768, top + box + PromptLayout.BELOW); // the buttons' bottom edge is the screen's
    }
}
