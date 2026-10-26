package menu;

import game.faction.Faction;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.PlayerDeals;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: a proposal from another player, on this PC only (spec §6, §12; SOS-76): a short notice, as vanilla's message
 * of an NPC's request. Inspect opens the proposer's faction window on its Diplomacy tab, where the proposal is answered.
 * It closes once the proposal does (answered, expired, or its proposer left).
 */
final class MpDealNotice extends MpWindow {

	private static final int WIDTH = 360;

	private final int id;

	MpDealNotice(int id, Faction from) {
		super("Deal from " + from.name);
		this.id = id;
		String text = from.name + " proposes a deal. It expires unanswered after 30 days.";
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.height() * 2) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(8, new GButt.ButtPanel("Inspect") {
			@Override
			protected void clickA() {
				MpPlayers.open(from, true);
				close();
			}
		});
		inner.addRightC(8, new GButt.ButtPanel("Later") {
			@Override
			protected void clickA() {
				close();
			}
		});
	}

	/** From {@link MpDeals#refresh}: closes once the proposal is gone. */
	void refresh() {
		if (PlayerDeals.proposal(id) == null)
			close();
	}

	@Override
	void frame() {
	}
}
