package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.session.Loading;

/**
 * SOSGSGMP: the loading window (SOS-158): what the game is doing while the PCs freeze, and for how long so far. The size
 * and look of the game's own popup (its titled panel, as wide as its yes/no prompt), with no buttons; it is a mod window
 * because the game's prompt stops the game's time.
 */
final class MpLoadingWindow extends MpWindow {

	/** As wide as the game's yes/no prompt at its narrowest ({@code IPromtYesNO}). */
	private static final int WIDTH = 600;

	private final long openedMs;
	/** The seconds line, rewritten in place: the window draws every frame. */
	private final StringBuilder seconds = new StringBuilder(32);
	private long shownSeconds = -1;

	MpLoadingWindow(Loading.Window w) {
		super(title(w.kind()));
		this.openedMs = w.openedMs();
		String text = w.text();
		int lines = UI.FONT().M.getHeight(text, WIDTH);
		inner.add(new RENDEROBJ.RenderImp(WIDTH, lines) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(8, new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				UI.FONT().M.render(r, seconds, body.x1(), body.y1());
			}
		});
	}

	private static String title(Loading.Kind kind) {
		return switch (kind) {
			case WORLD_GENERATION -> "World generation";
			case WORLD_INITIALIZATION -> "World initialization";
			case BATTLE_START, BATTLE_END -> "Battle";
			case VIEW_START, VIEW_END -> "View";
		};
	}

	@Override
	void frame() {
		long s = (System.nanoTime() / 1_000_000 - openedMs) / 1000;
		if (s == shownSeconds)
			return;
		shownSeconds = s;
		seconds.setLength(0);
		seconds.append("Please wait: ").append(s).append(s == 1 ? " second" : " seconds");
	}
}
