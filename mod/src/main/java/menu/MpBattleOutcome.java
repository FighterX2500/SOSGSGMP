package menu;

import game.faction.FACTIONS;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosdet.BattleOutcome;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: the loser's window after a battle between two players (spec §8; SOS-83, decision 3a): information only. The
 * winner chooses captives and spoils in vanilla's result prompt; the loser reads here what was lost.
 */
final class MpBattleOutcome extends MpWindow {

	private static final int WIDTH = 360;

	MpBattleOutcome(BattleOutcome o) {
		super("Defeat");
		String text = (o.retreat ? "Your army retreated from " : "Your army was beaten by ") + FACTIONS.getByIndex(o.winner).name
				+ ". You lost " + o.loserLosses + " men; they lost " + o.winnerLosses + ". They choose the captives and spoils.";
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.height() * 4) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
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
