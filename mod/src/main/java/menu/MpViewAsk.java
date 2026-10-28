package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: the owner's popup when another player asks to view their city (spec §10 Asking, SOS-82): Allow or Refuse.
 * No answer within 30 seconds counts as Refuse ({@link MpViews}). It never pauses the game.
 */
final class MpViewAsk extends MpWindow {

	private static final int WIDTH = 360;

	/** The asking player's slot. */
	final int viewer;

	MpViewAsk(MpViews views, int viewer, String name) {
		super("View request");
		this.viewer = viewer;
		String text = name + " asks to view your city. They would see it live until they close the view; nothing in it"
				+ " can be changed. No answer in 30 seconds refuses.";
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(text, WIDTH)) { // as high as the text wraps: 3 lines left the buttons over it (G82A)
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(8, new GButt.ButtPanel("Allow") {
			@Override
			protected void clickA() {
				views.answer(viewer, true);
			}
		});
		inner.addRightC(8, new GButt.ButtPanel("Refuse") {
			@Override
			protected void clickA() {
				views.answer(viewer, false);
			}
		});
	}

	@Override
	void frame() {
	}
}
