package sosgsgmp.game;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;

import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import view.interrupter.InterManager;
import view.interrupter.Interrupter;
import view.main.VIEW;
import view.world.WorldView;
import view.world.panel.UIMinimapW;

/**
 * SOSGSGMP: the world minimaps' private members (SOS-123, SOS-124), one handle per member. The setup's {@code IMinimap}
 * and the world view's {@code UIMinimap} each keep a {@link UIMinimapW} in a field {@code map}; the world view keeps its
 * minimap only in its interrupters. Game thread only.
 */
public final class MinimapAdapter {

    private static final String PLAY_MINIMAP = "view.world.panel.UIMinimap";
    /** The setup minimap's bar: the background, race, visuals, titles, terrain, plus and minus. */
    private static final int BAR_SIZE = 7;
    private static final int FIRST_SETUP_BUTTON = 1;
    private static final int LAST_SETUP_BUTTON = 4;

    private final Field interrupters = field(InterManager.class, "inters");
    private WorldView view;
    private Object playing;
    private Field expanded;
    private RECTANGLE playingMap;

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | SecurityException | InaccessibleObjectException e) {
            throw new IllegalStateException("SOSGSGMP: can't reach " + c.getName() + "." + name + " (Songs of Syx V71)", e);
        }
    }

    private static Object read(Field f, Object o) {
        try {
            return f.get(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSGSGMP: can't read " + f + " (Songs of Syx V71)", e);
        }
    }

    /** Spec §5: the setup's choices come from the lobby, so its minimap's bar keeps only plus and minus. */
    public void zoomButtonsOnly(Interrupter setupMinimap) {
        GuiSection bar = (GuiSection) read(field(setupMinimap.getClass(), "buttons"), setupMinimap);
        if (bar.elements().size() != BAR_SIZE)
            throw new IllegalStateException("SOSGSGMP: the setup minimap's bar has " + bar.elements().size() + " items, not "
                    + BAR_SIZE + " (Songs of Syx V71)");
        for (int i = FIRST_SETUP_BUTTON; i <= LAST_SETUP_BUTTON; i++) {
            RENDEROBJ button = bar.elements().get(i);
            ((CLICKABLE) button).visableSet(false);
        }
    }

    /** The map's body on the screen, for the setup's minimap. */
    public RECTANGLE setupMap(Interrupter setupMinimap) {
        return map(setupMinimap);
    }

    private static RECTANGLE map(Object minimap) {
        return ((UIMinimapW) read(field(minimap.getClass(), "map"), minimap)).body();
    }

    /** The world view's minimap body on the screen. The view changes with a load. */
    public RECTANGLE playingMap() {
        find();
        return playingMap;
    }

    /** Whether the world view's minimap is open, not folded away by its arrow. */
    public boolean playingMapOpen() {
        find();
        try {
            return expanded.getBoolean(playing);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SOSGSGMP: can't read " + expanded + " (Songs of Syx V71)", e);
        }
    }

    private void find() {
        WorldView now = VIEW.world();
        if (now == view)
            return;
        for (Object o : (Iterable<?>) read(interrupters, now.uiManager)) {
            if (o.getClass().getName().equals(PLAY_MINIMAP)) {
                view = now;
                playing = o;
                expanded = field(o.getClass(), "expanded");
                playingMap = map(o);
                return;
            }
        }
        throw new IllegalStateException("SOSGSGMP: the world view has no " + PLAY_MINIMAP + " (Songs of Syx V71)");
    }
}
