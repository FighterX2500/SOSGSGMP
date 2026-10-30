package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: a city view's message to this PC's player (spec §10, SOS-82): why a view didn't open or ended, or that the
 * host is opening its view. A window, not vanilla's full-screen prompt: that one takes the game's time while it shows,
 * so this PC would stop stepping and, through the host's pace, every PC with it.
 */
final class MpViewNotice extends MpWindow {

	private static final int WIDTH = 380;

	/** A notice; with {@code ok} the player closes it, without it the caller does. */
	MpViewNotice(String text, boolean ok) {
		this("City view", text, ok);
	}

	/** A notice under another title: the assault's (SOS-92), which shares this window's layout. */
	MpViewNotice(String title, String text, boolean ok) {
		super(title);
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(text, WIDTH)) { // as high as the text wraps: 3 lines left the buttons over it (G82A)
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		if (ok)
			inner.addDown(8, new GButt.ButtPanel("OK") {
				@Override
				protected void clickA() {
					close();
				}
			});
	}

	@Override
	void frame() {
	}
}
