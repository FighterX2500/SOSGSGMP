package menu;

import init.sprite.UI.UI;
import snake2d.MButt;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;

/**
 * SOSGSGMP (owner, 2026-11-19): a message in a small window, with no buttons: a click on it closes it. The game goes on
 * behind it. Hook M15's refused move of a stationed army opens one.
 */
final class MpMessage extends MpWindow {

	private static final int WIDTH = 380;

	MpMessage(String title, CharSequence text) {
		super(title);
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(text, WIDTH)) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
	}

	@Override
	protected void mouseClick(MButt button) {
		close();
	}

	@Override
	void frame() {
	}
}
