package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: the host's popup when a PC asks to join the running game (hot-join, SOS-190): Accept or Refuse. It never
 * pauses the game; the PC waits for the answer.
 */
final class MpJoinAsk extends MpWindow {

	private static final int WIDTH = 380;

	MpJoinAsk(MpHotJoin hotJoin, String name) {
		super("Join request");
		String text = name + " asks to join the game with a city-state of their own. They pick a spot on a copy of the"
				+ " world while everyone plays on; then every game saves and reloads for a moment.";
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(text, WIDTH)) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(8, new GButt.ButtPanel("Accept") {
			@Override
			protected void clickA() {
				hotJoin.answer(true);
			}
		});
		inner.addRightC(8, new GButt.ButtPanel("Refuse") {
			@Override
			protected void clickA() {
				hotJoin.answer(false);
			}
		});
	}

	@Override
	void frame() {
	}
}
