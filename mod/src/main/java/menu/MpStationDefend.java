package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.gui.misc.GButt;

/**
 * SOSGSGMP (owner, 2026-11-19): the capital where this player's army is stationed is invaded. Watch views the city,
 * where they command their soldiers; Decline gives the host command of them. No answer in 30 seconds declines. A window,
 * not vanilla's full-screen prompt: the game goes on behind it.
 */
final class MpStationDefend extends MpWindow {

	private static final int WIDTH = 380;
	private static final long ANSWER_NANOS = 30_000_000_000L;

	private final MpStationFlow flow;
	private final int host;
	private final long until = System.nanoTime() + ANSWER_NANOS;
	private boolean answered;

	MpStationDefend(MpStationFlow flow, int host, String hostName) {
		super("Your troops are under attack");
		this.flow = flow;
		this.host = host;
		String text = hostName + "'s city is being invaded and you have troops stationed there. Watch the city to command"
				+ " them, or decline and " + hostName + " commands them. With no answer in 30 seconds, " + hostName
				+ " commands them.";
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(text, WIDTH)) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(8, new GButt.ButtPanel("Watch") {
			@Override
			protected void clickA() {
				answer(true);
			}
		});
		inner.addRightC(8, new GButt.ButtPanel("Decline") {
			@Override
			protected void clickA() {
				answer(false);
			}
		});
	}

	private void answer(boolean watch) {
		if (answered)
			return;
		answered = true;
		close();
		if (watch)
			flow.watch(host);
		else
			flow.decline(host);
	}

	/** Each frame: true once it is answered or timed out (declined). */
	boolean frameEnds() {
		if (!answered && System.nanoTime() >= until)
			answer(false);
		return answered;
	}

	@Override
	void frame() {
	}
}
