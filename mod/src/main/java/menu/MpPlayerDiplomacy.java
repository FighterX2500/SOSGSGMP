package menu;

import java.util.LinkedList;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.deal.Deal;
import game.faction.diplomacy.deal.DealBool;
import init.sprite.UI.UI;
import init.trade.TR;
import init.trade.TRADABLE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.SPRITE;
import sosdet.game.deal.DealBook;
import sosgsgmp.game.PlayerDeals;
import sosgsgmp.session.PlayerTerms;
import sosgsgmp.session.PlayerTerms.Kind;
import sosgsgmp.session.PlayerTerms.Term;
import sosgsgmp.session.TariffSteps;
import util.colors.GCOLOR;
import util.data.INT;
import util.data.INT_O.INT_OE;
import util.gui.common.UIPicker;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GInputInt;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.slider.GSliderInt;
import util.gui.table.GScrollRows;
import util.info.GFORMAT;
import util.text.Dic;
import view.main.VIEW;

/**
 * SOSGSGMP: the faction window's Diplomacy tab for another player (spec §6; SOS-76), laid out as vanilla's: its terms
 * in its order, Offer and Demand with resources and money, the deal's summary, then Propose. The terms not built
 * between players are greyed out with the reason; Cancel All Agreements applies at once. A proposal from this player
 * shows above, with Accept and Decline, as vanilla shows an NPC's request. Never pauses the game (spec §4).
 */
final class MpPlayerDiplomacy extends GuiSection {

	private static final int BW = 345;
	private static final int BH = 34;
	private static final int LIST_W = 340;
	/** The engine's checks are asked again this often (frames, about half a second), and after every click. */
	private static final int RECHECK_FRAMES = 30;
	private static final int[] NONE = new int[0];
	private static final String TITLE = "What do you wish to offer?";
	private static final String NOT_YET = "Not between players yet.";
	private static final String TARIFF_D = "Your tariff on what this player sells you. It's cut from what they get and paid into your treasury. From 0% to 80%; 20% until you set it. It applies at once.";
	private static final String WAITING = "Your proposal waits for their answer; it expires unanswered after 30 days.";

	private final Faction f;
	private final int slot;
	private final PlayerTerms terms = new PlayerTerms(TR.ALL().size());
	private final INT.IntImp give = new INT.IntImp(0, 0);
	private final INT.IntImp ask = new INT.IntImp(0, Integer.MAX_VALUE);
	/** Why each term is greyed out now, by {@link Term} ordinal; null where it can be chosen. */
	private final String[] termWhy = new String[Term.values().length];
	private String dealWhy;
	/** Why Set Tariff is greyed out now; null while it can be set (SOS-77). */
	private String tariffWhy;
	private int frames = RECHECK_FRAMES;

	/** The oldest open proposal from this player to this PC's, or -1; and its terms as words. */
	private int incoming = -1;
	private final GText incomingText = new GText(UI.FONT().M, 512);
	private String acceptWhy;
	private boolean waiting;
	private final GText scratch = new GText(UI.FONT().M, 32);
	private final String theyGive;

	MpPlayerDiplomacy(Faction f, int width, int height) {
		this.f = f;
		this.slot = f.index();
		this.theyGive = f.name + " gives";
		body().setWidth(width);

		addRelBody(0, DIR.S, new GText(UI.FONT().M, TITLE).lablifySub());
		addRelBody(4, DIR.S, incoming(width));

		GuiSection op = new GuiSection();
		op.addRightC(0, new GButt.ButtPanel("Propose") {
			@Override
			protected void clickA() {
				if (dealWhy != null || terms.atOnce())
					return;
				PlayerDeals.propose(slot, terms.terms());
				clearDeal();
			}

			@Override
			protected void renAction() {
				visableSet(!terms.atOnce());
				activeSet(dealWhy == null);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				if (dealWhy != null)
					((GBox) text).error(dealWhy);
			}
		});
		op.add(new GButt.ButtPanel("Cancel All Agreements") { // in Propose's place, while Cancel All is chosen
			@Override
			protected void clickA() {
				if (dealWhy != null || !terms.atOnce())
					return;
				PlayerDeals.cancel(slot);
				clearDeal();
			}

			@Override
			protected void renAction() {
				visableSet(terms.atOnce());
				activeSet(dealWhy == null);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				GBox b = (GBox) text;
				b.text("Applies at once: " + f.name + " doesn't answer it.");
				if (dealWhy != null)
					b.error(dealWhy);
			}
		}, 0, 0);
		op.addRightC(16, new RENDEROBJ.RenderImp(LIST_W + BW, UI.FONT().M.height() * 2) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				if (dealWhy == null)
					return;
				GCOLOR.T().IBAD.bind();
				UI.FONT().M.render(r, dealWhy, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		});

		int h = height - body().height() - op.body().height() - 16;
		GuiSection s = new GuiSection();
		s.add(config(h));
		s.addRelBody(16, DIR.E, new SPRITE.Imp(1, h) {
			@Override
			public void render(SPRITE_RENDERER r, int X1, int X2, int Y1, int Y2) {
				GCOLOR.UI().border().render(r, X1, X2, Y1, Y2);
			}
		});
		s.addRelBody(16, DIR.E, summary(h));
		addRelBody(8, DIR.S, s);
		addRelBody(8, DIR.S, op);
	}

	@Override
	public void render(SPRITE_RENDERER r, float ds) {
		give.max = (int) Math.min(Integer.MAX_VALUE, Math.max(0, FACTIONS.player().credits().credits()));
		if (give.get() > give.max)
			give.set(give.max);
		terms.credits(true, give.get());
		terms.credits(false, ask.get());
		if (++frames >= RECHECK_FRAMES)
			recheck();
		super.render(r, ds);
	}

	/** Asks the engine again what can be chosen and proposed, and looks for this player's proposals. */
	private void recheck() {
		frames = 0;
		Term[] all = Term.values();
		for (int i = 0; i < all.length; i++)
			termWhy[i] = why(all[i]);
		tariffWhy = PlayerDeals.tariffRefusal(slot, PlayerDeals.tariff(FACTIONS.player().index(), slot));
		String own = terms.refusal();
		if (own != null)
			dealWhy = own;
		else if (terms.atOnce())
			dealWhy = PlayerDeals.cancelRefusal(slot);
		else
			dealWhy = PlayerDeals.refusal(slot, terms.terms());
		int me = FACTIONS.player().index();
		int first = -1;
		waiting = false;
		for (DealBook.Proposal p : PlayerDeals.open()) {
			if (p.from() == slot && p.to() == me && first < 0)
				first = p.id();
			if (p.from() == me && p.to() == slot)
				waiting = true;
		}
		if (first != incoming) {
			incoming = first;
			incomingText.clear();
			if (first >= 0)
				incomingText.add(describe(PlayerDeals.proposal(first)));
		}
		acceptWhy = incoming < 0 ? null : PlayerDeals.acceptRefusal(incoming);
	}

	private String why(Term t) {
		if (PlayerTerms.kind(t) == Kind.NOT_YET)
			return PlayerTerms.notYet(t);
		if (PlayerTerms.kind(t) == Kind.AT_ONCE)
			return PlayerDeals.cancelRefusal(slot);
		return PlayerDeals.refusal(slot, new DealBook.Terms(PlayerTerms.treaty(t), 0, 0, NONE, NONE));
	}

	private void changed() {
		frames = RECHECK_FRAMES;
	}

	private void clearDeal() {
		terms.clear();
		give.set(0);
		ask.set(0);
		changed();
	}

	/** The other player's proposal, as words for this PC's player. */
	private String describe(DealBook.Proposal p) {
		DealBook.Terms t = p.terms();
		return f.name + " proposes" + (t.treaty == DealBook.NO_TREATY ? "" : ": " + MpDeals.treatyName(t.treaty)) + ". "
				+ f.name + " gives " + MpDeals.gives(t.fromCredits, t.fromGoods()) + "; you give "
				+ MpDeals.gives(t.toCredits, t.toGoods()) + ". It expires unanswered after 30 days.";
	}

	/** Above the terms: the other player's proposal with Accept and Decline, or that this player's own waits. */
	private GuiSection incoming(int width) {
		GuiSection s = new GuiSection();
		int lineH = UI.FONT().M.height();
		s.add(new RENDEROBJ.RenderImp(width - 260, lineH * 3) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				if (incoming >= 0) {
					COLOR.WHITE100.bind();
					UI.FONT().M.render(r, incomingText, body.x1(), body.y1(), body.width(), 1.0);
				} else if (waiting) {
					GCOLOR.T().H2.bind();
					UI.FONT().M.render(r, WAITING, body.x1(), body.y1(), body.width(), 1.0);
				}
				COLOR.unbind();
			}
		}, 0, 0);
		s.addRightC(8, new GButt.ButtPanel(Dic.¤¤Accept) {
			@Override
			protected void clickA() {
				if (incoming >= 0 && acceptWhy == null)
					PlayerDeals.answer(incoming, true);
				changed();
			}

			@Override
			protected void renAction() {
				visableSet(incoming >= 0);
				activeSet(acceptWhy == null);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				if (acceptWhy != null)
					((GBox) text).error(acceptWhy);
			}
		});
		s.addRightC(8, new GButt.ButtPanel(Dic.¤¤Decline) {
			@Override
			protected void clickA() {
				if (incoming >= 0)
					PlayerDeals.answer(incoming, false);
				changed();
			}

			@Override
			protected void renAction() {
				visableSet(incoming >= 0);
			}
		});
		return s;
	}

	/** Vanilla's deal config: the terms, then Offer and Demand, each with Regions, Resources and money. */
	private RENDEROBJ config(int height) {
		LinkedList<RENDEROBJ> rows = new LinkedList<>();
		Deal names = new Deal(); // vanilla's terms, for their names, descriptions and icons only
		DealBool[] bools = {names.bools.WAR, names.bools.PEACE, names.bools.CANCEL_ALL, names.bools.TRADE,
				names.bools.PACT, names.bools.ALLY, names.bools.VASSAL, names.bools.OVERLORD, names.bools.ABSORB};
		Term[] all = Term.values();
		for (int i = 0; i < all.length; i++) {
			rows.add(new Bool(all[i], bools[i]));
			if (all[i] == Term.TRADE)
				rows.add(tariff()); // between Trade and Become Colleagues (owner, 2026-10-27)
		}
		rows.add(header("Offer"));
		rows.add(regions());
		rows.add(resources(true));
		rows.add(new GInputInt(give, true, true) {
			{
				body().setHeight(BH);
				addRelBody(4, DIR.W, UI.icons().s.money);
				body().setWidth(BW);
			}
		});
		rows.add(header("Demand"));
		rows.add(regions());
		rows.add(resources(false));
		rows.add(new GInputInt(ask, true, true) {
			{
				body().setHeight(BH);
				addRelBody(4, DIR.W, UI.icons().s.money);
				body().setWidth(BW);
			}
		});
		return new GScrollRows(rows, height).view();
	}

	private static RENDEROBJ header(CharSequence t) {
		return new RENDEROBJ.RenderImp(BW, BH) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				GCOLOR.T().H1.bind();
				UI.FONT().H2.renderCX(r, body.cX(), body().y2() - UI.FONT().H2.height() - 4, t);
				COLOR.unbind();
			}
		};
	}

	/** A term's button, as vanilla's: greyed out with the reason while it can't be chosen. */
	private final class Bool extends GButt.ButtPanel {

		private final Term term;
		private final DealBool vanilla;

		Bool(Term term, DealBool vanilla) {
			super(vanilla.info.name);
			this.term = term;
			this.vanilla = vanilla;
			icon(vanilla.icon);
			body().setDim(BW, BH);
		}

		@Override
		protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
			isSelected = terms.chosen() == term;
			isActive = isSelected || termWhy[term.ordinal()] == null;
			super.render(r, ds, isActive, isSelected, isHovered);
		}

		@Override
		protected void clickA() {
			if (terms.chosen() == term || termWhy[term.ordinal()] == null) {
				terms.toggle(term);
				changed();
			}
			super.clickA();
		}

		@Override
		public void hoverInfoGet(GUI_BOX text) {
			GBox b = (GBox) text;
			vanilla.hover(b);
			if (term == Term.CANCEL_ALL)
				b.text("Between players it applies at once, without an answer.");
			String why = termWhy[term.ordinal()];
			if (why != null)
				b.error(why);
		}
	}

	/**
	 * Set Tariff (spec §7; SOS-77): this player's tariff on what the other sells them, 0% to 80%. It's cut from what the
	 * seller gets and paid into this player's treasury, and applies at once, with no answer. It needs a trade agreement.
	 */
	private RENDEROBJ tariff() {
		INT.IntImp step = new INT.IntImp(0, TariffSteps.LAST);
		GuiSection pop = new GuiSection();
		pop.add(new GText(UI.FONT().H2, "Your tariff on " + f.name).lablify(), 0, 0);
		pop.addDown(8, new GSliderInt(step, 300, true, false));
		pop.addRightC(16, new GStat() {
			@Override
			public void update(GText text) {
				text.add(TariffSteps.percent(step.get())).add('%');
			}
		});
		GuiSection end = new GuiSection();
		end.addRightC(0, new GButt.ButtPanel("Apply") {
			@Override
			protected void clickA() {
				int percent = TariffSteps.percent(step.get());
				if (PlayerDeals.tariffRefusal(slot, percent) == null)
					PlayerDeals.setTariff(slot, percent);
				VIEW.inters().popup.close();
				changed();
			}
		});
		end.addRightC(16, new GStat() {
			@Override
			public void update(GText text) {
				text.add("Theirs on you: ").add(PlayerDeals.tariff(slot, FACTIONS.player().index())).add('%');
			}
		});
		pop.addDown(8, end);
		GButt.ButtPanel b = new GButt.ButtPanel("Set Tariff") {
			private final GText rate = new GText(UI.FONT().M, 8);

			@Override
			protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
				super.render(r, ds, tariffWhy == null, false, isHovered);
				rate.clear().add(PlayerDeals.tariff(FACTIONS.player().index(), slot)).add('%');
				rate.renderCY(r, body().x2() - 48, body().cY());
			}

			@Override
			protected void clickA() {
				if (tariffWhy != null)
					return;
				step.set(TariffSteps.step(PlayerDeals.tariff(FACTIONS.player().index(), slot)));
				VIEW.inters().popup.show(pop, this);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				GBox b = (GBox) text;
				b.title("Set Tariff");
				b.text(TARIFF_D);
				b.NL(4);
				b.textLL("Theirs on you");
				b.tab(6);
				b.add(b.text().add(PlayerDeals.tariff(slot, FACTIONS.player().index())).add('%'));
				if (tariffWhy != null)
					b.error(tariffWhy);
			}
		};
		b.icon(UI.icons().s.money);
		b.body().setDim(BW, BH);
		return b;
	}

	/** Regions can't change hands between players yet (SOS-73): greyed out, not removed. */
	private static RENDEROBJ regions() {
		GButt.ButtPanel b = new GButt.ButtPanel(Dic.¤¤Regions) {
			@Override
			protected void renAction() {
				activeSet(false);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				((GBox) text).error(NOT_YET);
			}
		};
		b.icon(UI.icons().s.world);
		b.body().setDim(BW, BH);
		return b;
	}

	/**
	 * One side's goods, picked as in vanilla's popup. This PC's player can give what they have; the other player's side
	 * is capped by what they last reported having, and the engine checks it again when the deal applies.
	 */
	private RENDEROBJ resources(boolean mine) {
		INT_OE<TRADABLE> amounts = new INT_OE<TRADABLE>() {
			@Override
			public int get(TRADABLE t) {
				return terms.goods(mine, t.index());
			}

			@Override
			public int min(TRADABLE t) {
				return 0;
			}

			@Override
			public int max(TRADABLE t) {
				return mine ? FACTIONS.player().res().getAvailable(t) : PlayerDeals.stock(slot, t.index());
			}

			@Override
			public void set(TRADABLE t, int i) {
				terms.goods(mine, t.index(), Math.max(0, i));
				changed();
			}
		};
		GuiSection pop = new UIPicker<TRADABLE>(amounts, 16, TR.ALL());
		GButt.ButtPanel b = new GButt.ButtPanel(Dic.¤¤Resource) {
			@Override
			protected void clickA() {
				VIEW.inters().popup.show(pop, this);
			}
		};
		b.icon(UI.icons().s.storage);
		b.body().setDim(BW, BH);
		return b;
	}

	/** The deal as it stands, as vanilla's list beside the config: the term, what each side gives. */
	private RENDEROBJ summary(int height) {
		return new RENDEROBJ.RenderImp(LIST_W, height) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				int x = body.x1();
				int y = body.y1();
				int line = UI.FONT().M.height() + 4;
				Term t = terms.chosen();
				if (t != null) {
					COLOR.WHITE100.bind();
					UI.FONT().M.render(r, t == Term.CANCEL_ALL ? "Cancel All Agreements" : MpDeals.treatyName(PlayerTerms.treaty(t)), x, y);
					y += line * 2;
				}
				y = side(r, x, y, line, true, "You give");
				side(r, x, y + line, line, false, theyGive);
				COLOR.unbind();
			}
		};
	}

	private int side(SPRITE_RENDERER r, int x, int y, int line, boolean mine, CharSequence title) {
		GCOLOR.T().H1.bind();
		UI.FONT().H2.render(r, title, x, y);
		y += UI.FONT().H2.height() + 4;
		COLOR.WHITE100.bind();
		if (terms.credits(mine) > 0) {
			UI.icons().s.money.render(r, x, y);
			GFORMAT.i(scratch.clear(), terms.credits(mine));
			scratch.render(r, x + 28, y);
			y += line;
		}
		for (int i = 0; i < TR.ALL().size(); i++) {
			TRADABLE t = TR.ALL().get(i);
			int n = terms.goods(mine, i);
			if (n <= 0)
				continue;
			t.icon().render(r, x, y);
			GFORMAT.i(scratch.clear(), n);
			scratch.render(r, x + 28, y);
			UI.FONT().M.render(r, t.name, x + 96, y);
			y += line;
		}
		return y;
	}
}
