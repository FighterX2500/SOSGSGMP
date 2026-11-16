package sosgsgmp.game;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;

import init.constant.C;
import init.sprite.UI.UI;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.misc.ACTION;
import view.interrupter.IPromtScreen;
import view.main.VIEW;

/**
 * SOSGSGMP: the text box of vanilla's full-screen prompt, {@code IPromtScreen}'s private {@code text}, sized to the
 * mod's text before the prompt opens ({@link PromptLayout}): {@code activate} then centres the box and puts the buttons
 * under it, and every line is drawn. The prompt is read from the game loaded now on each call, never kept. Game thread
 * only, never per frame.
 */
public final class PromptBox {

    private PromptBox() {
    }

    /**
     * Sizes the current prompt's box for {@code text}, to be called just before the prompt's {@code activate}. Returns
     * the prompt's deactivate action, which gives the box vanilla's height back when the prompt closes, so that vanilla's
     * own prompts look as before.
     */
    public static ACTION fit(CharSequence text) {
        RENDEROBJ.RenderImp box = box(VIEW.inters().fullScreen);
        box.body().setHeight(PromptLayout.boxHeight(UI.FONT().H2.getHeight(text, box.body().width()), C.HEIGHT()));
        return () -> box.body().setHeight(PromptLayout.VANILLA_HEIGHT);
    }

    private static RENDEROBJ.RenderImp box(IPromtScreen prompt) {
        try {
            return (RENDEROBJ.RenderImp) Handle.TEXT.get(prompt);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSGSGMP: can't read " + Handle.TEXT + " (Songs of Syx V71)", e);
        }
    }

    /** Resolved on first use, once: a holder class, so nothing is looked up before the game's own classes have loaded. */
    private static final class Handle {
        static final Field TEXT = field(IPromtScreen.class, "text");

        private static Field field(Class<?> c, String name) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException | SecurityException | InaccessibleObjectException e) {
                throw new IllegalStateException("SOSGSGMP: can't reach " + c.getName() + "." + name + " (Songs of Syx V71)", e);
            }
        }
    }
}
