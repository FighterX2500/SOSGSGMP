package menu;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import init.constant.C;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.misc.ACTION;
import snake2d.util.sprite.SPRITE;
import snake2d.util.sprite.text.Font;
import util.colors.GCOLOR;
import util.gui.misc.GButt;
import view.main.VIEW;
import view.menu.IMenu;

/**
 * SOSGSGMP: the escape menu's column in a multiplayer game (spec §11 Saving, SOS-79; docs/plan/escape-save.md):
 * Resume, Save Campaign, Options, Keys, Quit to menu and Exit, in vanilla's fonts, buttons and frame. Vanilla's
 * Quick-save, Save and Load can't be resumed in multiplayer, so they're gone. Hook M6 adds
 * {@code IMenu.multiplayer(Actions)}, which builds the column here from the menu's own strings and actions; the session
 * panel calls it after every load, so the menu keeps no reference to the session past its view.
 */
public final class MpEscapeMenu {

	/** What the column's buttons do: the session's side. */
	public interface Actions {

		/** Why Save Campaign can't run now; null when it can. */
		CharSequence saveRefusal();

		/** Saves the campaign on every PC. Called only while {@link #saveRefusal()} is null. */
		void save();

		/** Quit to menu, or Exit the game with {@code exitGame}: the campaign is saved first, and the session ends. */
		void quit(boolean exitGame);
	}

	/** The escape menu's own strings and actions, which hook M6 hands over: its column keeps vanilla's text and screens. */
	public record Vanilla(CharSequence resume, CharSequence options, CharSequence keys, CharSequence quitMenu, CharSequence exit,
			CharSequence title, ACTION close, ACTION openOptions, ACTION openKeys) {
	}

	private static final int REASON_WIDTH = 520;

	private MpEscapeMenu() {
	}

	/** Shows the multiplayer column in this view's escape menu, through hook M6, until the view is rebuilt. */
	static void install(Actions actions) {
		IMenu menu = VIEW.inters().menu;
		Method hook;
		try {
			hook = menu.getClass().getMethod("multiplayer", Actions.class);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException("SOSGSGMP: hook M6 (IMenu.multiplayer) is missing from the game's V71 escape menu; "
					+ "is the mod's script jar ahead of SongsOfSyx.jar?", e);
		}
		try {
			hook.invoke(menu, actions);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("SOSGSGMP: hook M6 (IMenu.multiplayer) isn't public", e);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException("SOSGSGMP: building the escape menu's multiplayer column failed", e.getCause());
		}
	}

	/** Hook M6: the column, built once per view. */
	public static GuiSection column(Actions actions, Vanilla vanilla) {
		Font big = UI.FONT().H1;
		Font small = UI.FONT().M;
		GuiSection main = new GuiSection();

		main.addDownC(10, button(big.getText(vanilla.resume()), vanilla.close()));
		main.addDownC(6, new GButt.Glow(big.getText("save campaign")) {
			@Override
			protected void renAction() {
				activeSet(actions.saveRefusal() == null);
			}

			@Override
			protected void clickA() {
				if (actions.saveRefusal() != null)
					return;
				actions.save();
				vanilla.close().exe();
			}
		});
		// vanilla's menu shows no hover boxes, so a greyed-out Save Campaign says why on the line below it
		main.addDownC(2, new RENDEROBJ.RenderImp(REASON_WIDTH, small.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				CharSequence reason = actions.saveRefusal();
				if (reason == null)
					return;
				GCOLOR.T().INACTIVE.bind();
				small.renderCX(r, body.cX(), body.y1(), reason);
				COLOR.unbind();
			}
		});
		main.addDownC(6, button(big.getText(vanilla.options()), vanilla.openOptions()));
		main.addDownC(6, button(big.getText(vanilla.keys()), vanilla.openKeys()));
		main.addDownC(6, button(big.getText(vanilla.quitMenu()), () -> {
			vanilla.close().exe();
			actions.quit(false);
		}));
		main.addDownC(6, button(big.getText(vanilla.exit()), () -> {
			vanilla.close().exe();
			actions.quit(true);
		}));

		main.body().centerIn(C.DIM());
		main.add(UI.decor().frame(main.body()));
		main.moveLastToBack();
		RENDEROBJ title = UI.decor().getDecored(vanilla.title());
		title.body().centerX(main);
		title.body().moveY2(main.body().y1());
		main.add(title);
		return main;
	}

	private static GButt button(SPRITE label, ACTION action) {
		GButt b = new GButt.Glow(label);
		b.clickActionSet(action);
		return b;
	}
}
