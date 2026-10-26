package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosdet.game.deal.DealBook;
import util.colors.GCOLOR;
import util.gui.misc.GButt;
import util.gui.misc.GText;

/**
 * SOSGSGMP: a proposal from another player, on this PC only (spec §6, §12; SOS-72): its terms, Accept and Decline.
 * Accept is greyed out, with the reason, while this player can't give their side. It closes when the proposal does
 * (answered, expired, or its proposer left).
 */
final class MpDealPopup extends MpWindow {

	private static final int WIDTH = 420;

	private final MpDeals deals;
	private final int id;
	private final GText text = new GText(UI.FONT().M, 512);
	private final GText why = new GText(UI.FONT().M, 256);
	private boolean canAccept;

	MpDealPopup(MpDeals deals, DealBook.Proposal p) {
		super("Deal from " + deals.name(p.from()));
		this.deals = deals;
		this.id = p.id();
		text.add(deals.describe(p));
		int lineH = UI.FONT().M.height();
		inner.add(new RENDEROBJ.RenderImp(WIDTH, lineH * 5) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(4, new RENDEROBJ.RenderImp(WIDTH, lineH * 2) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				GCOLOR.T().IBAD.bind();
				UI.FONT().M.render(r, why, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		});
		inner.addDown(8, new GButt.ButtPanel("Accept") {
			@Override
			protected void clickA() {
				if (canAccept)
					answer(true);
			}

			@Override
			protected void renAction() {
				activeSet(canAccept);
			}
		});
		inner.addRightC(8, new GButt.ButtPanel("Decline") {
			@Override
			protected void clickA() {
				answer(false);
			}
		});
		refresh();
	}

	/** From {@link MpDeals#refresh}: closes once the proposal is gone, and rechecks Accept. */
	void refresh() {
		if (deals.proposal(id) == null) {
			close();
			return;
		}
		String r = deals.engine().dealAcceptRefusal(id);
		canAccept = r == null;
		why.clear().add(r == null ? "" : r);
	}

	private void answer(boolean accept) {
		if (deals.proposal(id) != null)
			deals.engine().dealAnswer(id, accept);
		close();
	}

	@Override
	void frame() {
	}
}
