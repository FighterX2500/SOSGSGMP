package menu;

import game.faction.FACTIONS;
import game.faction.diplomacy.DIP;
import init.sprite.UI.UI;
import init.trade.TR;
import init.trade.TRADABLE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosdet.game.deal.DealBook;
import util.colors.GCOLOR;
import util.gui.misc.GButt;
import util.gui.misc.GText;

/**
 * SOSGSGMP: this PC's player builds a deal for another player (spec §6, §7 direct contracts; SOS-72): one treaty term,
 * the money and one kind of goods each side gives, then Propose. War isn't offered between players until they can fight
 * (owner, 2026-10-26). The engine's refusal shows below the terms and greys out Propose; it checks only this player's
 * own side, the other side's when the deal applies.
 */
final class MpDealOffer extends MpWindow {

	private static final int WIDTH = 460;
	private static final int[] TREATIES = {DealBook.NO_TREATY, DealBook.PEACE, DealBook.TRADE, DealBook.PACT,
			DealBook.ALLY, DealBook.CANCEL_ALL};
	private static final int[] MONEY_STEPS = {-1000, -100, 100, 1000};
	private static final int[] GOODS_STEPS = {-100, -10, 10, 100};
	/** The terms are checked again this often (frames, about a second). */
	private static final int RECHECK_FRAMES = 60;
	private int frames;

	private final MpDeals deals;
	private final int slot;
	private int treaty = DealBook.NO_TREATY;
	private int give, ask;
	/** Each side's goods: a tradable's index, -1 for none, and the amount. */
	private int giveGoods = -1, giveAmount, askGoods = -1, askAmount;
	private String refusal;
	private final GText now = new GText(UI.FONT().M, 128);
	private final GText giveText = new GText(UI.FONT().M, 128);
	private final GText askText = new GText(UI.FONT().M, 128);
	private final GText why = new GText(UI.FONT().M, 256);

	MpDealOffer(MpDeals deals, int slot) {
		super("Deal with " + deals.name(slot));
		this.deals = deals;
		this.slot = slot;
		int lineH = UI.FONT().M.height() + 2;
		inner.add(text(now, lineH, false), 0, 0);
		GuiSection treaties = new GuiSection();
		for (int t : TREATIES) {
			treaties.addRightC(4, new GButt.ButtPanel(MpDeals.treatyName(t)) {
				@Override
				protected void clickA() {
					treaty = t;
					changed();
				}

				@Override
				protected void renAction() {
					selectedSet(treaty == t);
				}
			});
		}
		inner.addDown(8, treaties);
		inner.addDown(8, text(giveText, lineH * 2, false));
		inner.addDown(4, steps(true));
		inner.addDown(8, text(askText, lineH * 2, false));
		inner.addDown(4, steps(false));
		inner.addDown(8, text(why, lineH * 2, true));
		GuiSection end = new GuiSection();
		end.addRightC(0, new GButt.ButtPanel("Propose") {
			@Override
			protected void clickA() {
				if (refusal != null)
					return;
				deals.engine().dealPropose(slot, terms());
				close();
			}

			@Override
			protected void renAction() {
				activeSet(refusal == null);
			}
		});
		end.addRightC(8, new GButt.ButtPanel("Close") {
			@Override
			protected void clickA() {
				close();
			}
		});
		inner.addDown(8, end);
		changed();
	}

	private static RENDEROBJ text(GText t, int height, boolean bad) {
		return new RENDEROBJ.RenderImp(WIDTH, height) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				(bad ? GCOLOR.T().IBAD : COLOR.WHITE100).bind();
				UI.FONT().M.render(r, t, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		};
	}

	/** One side's buttons: its money in steps, its goods (the next kind), and their amount in steps. */
	private GuiSection steps(boolean mine) {
		GuiSection s = new GuiSection();
		for (int step : MONEY_STEPS) {
			s.addRightC(4, new GButt.ButtPanel((step > 0 ? "+" : "") + step) {
				@Override
				protected void clickA() {
					if (mine)
						give = Math.max(0, give + step);
					else
						ask = Math.max(0, ask + step);
					changed();
				}
			});
		}
		s.addRightC(12, new GButt.ButtPanel("goods") {
			@Override
			protected void clickA() {
				if (mine) {
					giveGoods = nextGoods(giveGoods, true);
					giveAmount = giveGoods < 0 ? 0 : Math.max(giveAmount, 10);
				} else {
					askGoods = nextGoods(askGoods, false);
					askAmount = askGoods < 0 ? 0 : Math.max(askAmount, 10);
				}
				changed();
			}
		});
		for (int step : GOODS_STEPS) {
			s.addRightC(4, new GButt.ButtPanel((step > 0 ? "+" : "") + step) {
				@Override
				protected void clickA() {
					if (mine && giveGoods >= 0)
						giveAmount = Math.max(1, giveAmount + step);
					else if (!mine && askGoods >= 0)
						askAmount = Math.max(1, askAmount + step);
					changed();
				}
			});
		}
		return s;
	}

	/**
	 * The next kind of goods after {@code from}, then none: for this player's side, only goods they have to trade; for
	 * the other side, any (their stock is on their PC).
	 */
	private static int nextGoods(int from, boolean mine) {
		for (int i = from + 1; i < TR.ALL().size(); i++) {
			TRADABLE t = TR.ALL().get(i);
			if (!mine || FACTIONS.player().res().getAvailable(t) > 0)
				return i;
		}
		return -1;
	}

	private DealBook.Terms terms() {
		return new DealBook.Terms(treaty, give, ask, giveGoods < 0 ? new int[0] : new int[] {giveGoods, giveAmount},
				askGoods < 0 ? new int[0] : new int[] {askGoods, askAmount});
	}

	/** After every click: the texts, and the engine's refusal of the terms as they are now. */
	private void changed() {
		DealBook.Terms t = terms();
		now.clear().add("Now: ").add(DIP.get(FACTIONS.player(), FACTIONS.getByIndex(slot)).name).add(". Offer: ")
				.add(MpDeals.treatyName(treaty)).add(".");
		giveText.clear().add("You give ").add(MpDeals.gives(give, t.fromGoods())).add(".");
		askText.clear().add(deals.name(slot)).add(" gives ").add(MpDeals.gives(ask, t.toGoods())).add(".");
		refusal = deals.engine().dealRefusal(slot, t);
		why.clear().add(refusal == null ? "" : refusal);
	}

	@Override
	void frame() {
		if (++frames >= RECHECK_FRAMES) { // this player's treasury and stock move while the window is open
			frames = 0;
			changed();
		}
	}
}
