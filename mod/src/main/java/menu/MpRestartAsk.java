package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.text.Str;
import sosgsgmp.session.RestartTally;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: a player called a vote to restart a battle (spec §8, SOS-231): Yes or No. Every player is asked, those
 * outside the battle and spectators too; no answer in 30 seconds counts as No. A window, as the speed and ally prompts
 * are: this player's own game goes on behind it.
 */
final class MpRestartAsk extends MpWindow {

	private static final int WIDTH = 380;
	private static final String NOTE = "More than half of the players must say Yes; a tie fails. With no answer in 30 seconds"
			+ " your vote counts as No.";
	private final long until = System.nanoTime() + RestartTally.ANSWER_MS * 1_000_000L;
	private final Str left = new Str(32);

	MpRestartAsk(MpRestartVote votes, int vote, String question) {
		super("Restart the battle?");
		String text = question + " " + NOTE;
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(text, WIDTH)) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(4, new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				long seconds = Math.max(0, (until - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
				left.clear().add(seconds).add(" s left to answer.");
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, left, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		});
		inner.addDown(8, new GButt.ButtPanel("Yes") {
			@Override
			protected void clickA() {
				votes.answer(vote, true);
			}
		});
		inner.addRightC(8, new GButt.ButtPanel("No") {
			@Override
			protected void clickA() {
				votes.answer(vote, false);
			}
		});
	}

	/** True once its 30 seconds are up here: the host counts the vote without this player's answer. */
	boolean expired() {
		return System.nanoTime() - until >= 0;
	}

	@Override
	void frame() {
	}
}
