package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.EnginePlay;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: an ally's battle starts within reach of this player's army (spec §8, SOS-118): Join or Stay out. No answer
 * in 30 seconds stays out. A window, not vanilla's full-screen prompt: this player's own game goes on behind it.
 */
final class MpAllyJoin extends MpWindow {

	private static final int WIDTH = 380;
	private static final String TEXT = "An ally's battle is starting within reach of your army. Join it, or stay out. "
			+ "With no answer in 30 seconds your army stays out.";

	MpAllyJoin(EnginePlay engine) {
		super("Join the battle?");
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(TEXT, WIDTH)) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, TEXT, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(8, new GButt.ButtPanel("Join") {
			@Override
			protected void clickA() {
				engine.allyAnswer(true);
			}
		});
		inner.addRightC(8, new GButt.ButtPanel("Stay out") {
			@Override
			protected void clickA() {
				engine.allyAnswer(false);
			}
		});
	}

	@Override
	void frame() {
	}
}
