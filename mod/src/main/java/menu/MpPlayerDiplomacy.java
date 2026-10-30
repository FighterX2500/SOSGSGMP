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
import snake2d.util.gui.clickable.CLICKABLE;
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
import world.WORLD;
import world.map.regions.Region;

/**
 * SOSGSGMP: the faction window's Diplomacy tab for another player (spec §6; SOS-76), laid out as vanilla's: its terms
 * in its order, Offer and Demand with regions, resources and money (and how much each side has), vanilla's list of the
 * deal's parts beside them, then why it can't go yet and Propose (SOS-100). Protector and Vassal carry the tribute rate
 * set below them (SOS-73). The terms not built between players are greyed out with the reason; Cancel All Agreements
 * applies at once. A proposal from this player shows below, with Accept and Decline. Never pauses the game (spec §4).
 */
final class MpPlayerDiplomacy extends GuiSection {

	private static final int BW = 345;
	private static final int BH = 34;
	private static final int LIST_W = 340;
	/** The engine's checks are asked again this often (frames, about half a second), and after every click. */
	private static final int RECHECK_FRAMES = 30;
	private static final int[] NONE = new int[0];
	private static final String TITLE = "What do you wish to offer?";
	private static final String YOU_GET = "You get";
	private static final String TAKE_OUT = "Click to take it out of the deal.";
	private static final String THEIR_MONEY = "Their treasury is on their PC: whether they have the money is checked when the deal applies.";
	/** The deal list's rows, as vanilla's: their height, and the most there can be. */
	private static final int ROW_H = 32;
	private static final int ENTRIES = 256;
	private static final int E_HEADER = 0, E_TERM = 1, E_CREDITS = 2, E_GOOD = 3, E_REGION = 4;
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
	/** Counts the deal's changes: the region picker's rows ask the engine again after each. */
	private int checks;

	/** The oldest open proposal from this player to this PC's, or -1; and its terms as words. */
	private int incoming = -1;
	private final GText incomingText = new GText(UI.FONT().M, 512);
	private String acceptWhy;
	private boolean waiting;
	private final String theyGet;
	/** Vanilla's terms by {@link Term} ordinal: their names, descriptions and icons for the deal's list. */
	private final DealBool[] vanillaBools = new DealBool[Term.values().length];
	/** The deal's list as drafted now (see {@link #fill}): each row's kind, its argument, and whose part it is. */
	private final int[] eKind = new int[ENTRIES];
	private final int[] eArg = new int[ENTRIES];
	private final boolean[] eMine = new boolean[ENTRIES];
	private int entries;

	MpPlayerDiplomacy(Faction f, int width, int height) {
		this.f = f;
		this.slot = f.index();
		this.theyGet = f.name + " gets";
		body().setWidth(width);

		addRelBody(0, DIR.S, new GText(UI.FONT().M, TITLE).lablifySub());

		// vanilla's bottom row: why the deal can't go yet on the left, its button on the right (SOS-100)
		GuiSection op = new GuiSection();
		op.add(new RENDEROBJ.RenderImp(LIST_W + BW - 128, UI.FONT().M.height() * 2) { // room for the widest button, inside the tab
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				if (dealWhy == null)
					return;
				GCOLOR.T().IBAD.bind();
				UI.FONT().M.render(r, dealWhy, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		GuiSection buttons = new GuiSection();
		buttons.addRightC(0, new GButt.ButtPanel("Propose") {
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
		buttons.add(new GButt.ButtPanel("Cancel All Agreements") { // in Propose's place, while Cancel All is chosen
			@Override
			protected void clickA() {
				if (dealWhy != null || terms.chosen() != Term.CANCEL_ALL)
					return;
				PlayerDeals.cancel(slot);
				clearDeal();
			}

			@Override
			protected void renAction() {
				visableSet(terms.chosen() == Term.CANCEL_ALL);
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
		buttons.add(new GButt.ButtPanel("Declare War") { // in Propose's place, while War is chosen (SOS-83)
			@Override
			protected void clickA() {
				if (dealWhy != null || terms.chosen() != Term.WAR)
					return;
				PlayerDeals.declareWar(slot);
				clearDeal();
			}

			@Override
			protected void renAction() {
				visableSet(terms.chosen() == Term.WAR);
				activeSet(dealWhy == null);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				GBox b = (GBox) text;
				b.text("Applies at once: " + f.name + " doesn't answer it. When your armies meet, both of you fight the battle in real time.");
				if (dealWhy != null)
					b.error(dealWhy);
			}
		}, 0, 0);
		op.addRightC(16, buttons);
		GuiSection in = incoming(width); // below, so the terms start under the title as vanilla's do

		int h = height - body().height() - op.body().height() - in.body().height() - 24;
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
		addRelBody(8, DIR.S, in);
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
		else if (terms.chosen() == Term.WAR)
			dealWhy = PlayerDeals.warRefusal(slot);
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
		if (t == Term.WAR)
			return PlayerDeals.warRefusal(slot);
		if (PlayerTerms.kind(t) == Kind.AT_ONCE)
			return PlayerDeals.cancelRefusal(slot);
		return PlayerDeals.refusal(slot, new DealBook.Terms(PlayerTerms.treaty(t), 0, 0, NONE, NONE));
	}

	/** A Trade with money and a good each way, for the autopilot's GUI preview to show the deal's list (SOS-100). */
	void draftForPreview() {
		clearDeal();
		terms.toggle(Term.TRADE);
		give.set(1000);
		ask.set(500);
		terms.goods(true, 0, 20);
		terms.goods(false, 1, 10);
		changed();
	}

	private void changed() {
		frames = RECHECK_FRAMES;
		checks++;
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
		String treaty = t.treaty == DealBook.PROTECTOR ? ": they become your overlord, with a tribute of " + t.tributePercent + "% a year"
				: t.treaty == DealBook.VASSAL ? ": they become your vassal, with a tribute of " + t.tributePercent + "% a year"
				: t.treaty == DealBook.NO_TREATY ? "" : ": " + MpDeals.treatyName(t.treaty);
		return f.name + " proposes" + treaty + ". "
				+ f.name + " gives " + MpDeals.gives(t.fromCredits, t.fromGoods()) + regionsText(t.fromRegions()) + "; you give "
				+ MpDeals.gives(t.toCredits, t.toGoods()) + regionsText(t.toRegions()) + ". It expires unanswered after 30 days.";
	}

	private static String regionsText(int[] regions) {
		return regions.length == 0 ? "" : ", and the regions " + MpDeals.regions(regions);
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
			vanillaBools[i] = bools[i];
			rows.add(new Bool(all[i], bools[i]));
			if (all[i] == Term.TRADE)
				rows.add(tariff()); // between Trade and Become Colleagues (owner, 2026-10-27)
			if (all[i] == Term.VASSAL)
				rows.add(tribute()); // under Protector and Vassal, which it belongs to (SOS-73)
		}
		rows.add(header("Offer"));
		rows.add(regions(true));
		rows.add(resources(true));
		rows.add(new GInputInt(give, true, true) {
			{
				body().setHeight(BH);
				addRelBody(4, DIR.W, UI.icons().s.money);
				addRelBody(8, DIR.E, available(true));
				body().setWidth(BW);
			}
		});
		rows.add(header("Demand"));
		rows.add(regions(false));
		rows.add(resources(false));
		rows.add(new GInputInt(ask, true, true) {
			{
				body().setHeight(BH);
				addRelBody(4, DIR.W, UI.icons().s.money);
				addRelBody(8, DIR.E, available(false));
				body().setWidth(BW);
			}
		});
		return new GScrollRows(rows, height).view();
	}

	/**
	 * The money this PC's player has, next to the amount as vanilla shows an NPC's (SOS-100). The other player's money is
	 * on their PC, not known here: "?", checked when the deal applies.
	 */
	private GStat available(boolean mine) {
		return new GStat() {
			@Override
			public void update(GText text) {
				text.add('(');
				if (mine)
					GFORMAT.iBig(text, (long) FACTIONS.player().credits().credits());
				else
					text.add('?');
				text.add(')');
			}

			@Override
			public void hoverInfoGet(GBox b) {
				if (!mine)
					b.text(THEIR_MONEY);
			}
		};
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
			if (term == Term.CANCEL_ALL || term == Term.WAR)
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

	/**
	 * One side's regions (SOS-73): a popup listing the giver's regions other than the capital, each chosen or not. A
	 * region the engine's rule refuses next is greyed out with the reason: as vanilla, each must border the receiver's
	 * land or another region chosen with it.
	 */
	private RENDEROBJ regions(boolean mine) {
		GButt.ButtPanel b = new GButt.ButtPanel(Dic.¤¤Regions) {
			@Override
			protected void clickA() {
				VIEW.inters().popup.show(regionPicker(mine), this);
			}
		};
		b.icon(UI.icons().s.world);
		b.body().setDim(BW, BH);
		return b;
	}

	/** Built when opened: the giver's regions change as the game goes on. */
	private GuiSection regionPicker(boolean mine) {
		Faction giver = mine ? FACTIONS.player() : f;
		int giverSlot = giver.index();
		int receiverSlot = mine ? slot : FACTIONS.player().index();
		LinkedList<RENDEROBJ> rows = new LinkedList<>();
		for (int i = 0; i < giver.realm().regions(); i++) {
			Region reg = giver.realm().region(i);
			if (reg.capitol())
				continue;
			int ri = reg.index();
			GButt.ButtPanel row = new GButt.ButtPanel(reg.info.name()) {
				private String why;
				// the rule walks the map: asked again after a change, or every RECHECK_FRAMES, not every frame
				private int seen = -1;
				private int age;

				@Override
				protected void renAction() {
					boolean chosen = terms.hasRegion(mine, ri);
					if (seen != checks || ++age >= RECHECK_FRAMES) {
						seen = checks;
						age = 0;
						why = chosen ? null : PlayerDeals.regionRefusal(giverSlot, receiverSlot, ri, terms.regions(mine));
					}
					selectedSet(chosen);
					activeSet(chosen || why == null);
				}

				@Override
				protected void clickA() {
					if (why != null && !terms.hasRegion(mine, ri))
						return;
					terms.toggleRegion(mine, ri);
					changed();
				}

				@Override
				public void hoverInfoGet(GUI_BOX text) {
					if (why != null)
						((GBox) text).error(why);
				}
			};
			row.body().setDim(BW, BH);
			rows.add(row);
		}
		GuiSection pop = new GuiSection();
		if (rows.isEmpty()) {
			pop.add(new GText(UI.FONT().M, giver.name + " has no regions to give but the capital.").normalify2(), 0, 0);
			return pop;
		}
		pop.add(new GScrollRows(rows, Math.min(rows.size(), 10) * BH).view());
		return pop;
	}

	/** The vassal's yearly tribute (SOS-73), set in the deal: 0% to 100% of its worth, 10% until set. Counts only with Protector or Vassal. */
	private RENDEROBJ tribute() {
		INT.IntImp rate = new INT.IntImp(0, 100) {
			@Override
			public int get() {
				return terms.tribute();
			}

			@Override
			public void set(int t) {
				terms.tribute(Math.max(0, Math.min(100, t)));
				changed();
			}
		};
		GuiSection pop = new GuiSection();
		pop.add(new GText(UI.FONT().H2, "The vassal's yearly tribute").lablify(), 0, 0);
		pop.addDown(8, new GSliderInt(rate, 300, true, false));
		pop.addRightC(16, new GStat() {
			@Override
			public void update(GText text) {
				text.add(terms.tribute()).add('%');
			}
		});
		GButt.ButtPanel b = new GButt.ButtPanel("Tribute") {
			private final GText shown = new GText(UI.FONT().M, 8);

			@Override
			protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
				super.render(r, ds, terms.vassalage(), false, isHovered);
				shown.clear().add(terms.tribute()).add('%');
				shown.renderCY(r, body().x2() - 48, body().cY());
			}

			@Override
			protected void clickA() {
				if (terms.vassalage())
					VIEW.inters().popup.show(pop, this);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				GBox g = (GBox) text;
				g.title("Tribute");
				g.text("What the vassal pays its overlord each year, in percent of its worth. Choose Protector or Vassal first.");
			}
		};
		b.icon(UI.icons().s.money);
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
	/** Vanilla's list of the deal's parts (UIDealList): the term, then what you get, then what they get; a click takes one out. */
	private RENDEROBJ summary(int height) {
		GuiSection s = new GuiSection() {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				fill();
				super.render(r, ds);
			}
		};
		int n = Math.min(ENTRIES, height / ROW_H);
		for (int i = 0; i < n; i++)
			s.addDown(0, new Row(i));
		return s;
	}

	/** The list's rows for the deal as drafted now. Each frame, with no allocation. */
	private void fill() {
		entries = 0;
		Term t = terms.chosen();
		if (t != null)
			entry(E_TERM, t.ordinal(), false);
		party(false);
		party(true);
	}

	/** What the other player gives (not {@code mine}: "You get") or this PC's player gives, under its header if any. */
	private void party(boolean mine) {
		int start = entries;
		entry(E_HEADER, 0, mine);
		if (terms.credits(mine) > 0)
			entry(E_CREDITS, 0, mine);
		for (int i = 0; i < terms.regionCount(mine); i++)
			entry(E_REGION, terms.region(mine, i), mine);
		for (int i = 0; i < TR.ALL().size(); i++)
			if (terms.goods(mine, i) > 0)
				entry(E_GOOD, i, mine);
		if (entries == start + 1)
			entries = start;
	}

	private void entry(int kind, int arg, boolean mine) {
		if (entries >= ENTRIES)
			return;
		eKind[entries] = kind;
		eArg[entries] = arg;
		eMine[entries] = mine;
		entries++;
	}

	/** One row of the deal's list, drawn as vanilla's. */
	private final class Row extends CLICKABLE.ClickableAbs {

		private final int index;
		private final GText header = new GText(UI.FONT().H2, 48).lablify();
		private final GText name = new GText(UI.FONT().H2, 48).normalify();

		Row(int index) {
			super(LIST_W, ROW_H);
			this.index = index;
		}

		@Override
		protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
			if (index >= entries)
				return;
			int arg = eArg[index];
			boolean mine = eMine[index];
			if (eKind[index] == E_HEADER) {
				header.clear().add(mine ? theyGet : YOU_GET);
				header.adjustWidth();
				header.renderCXY2(r, body.cX(), body.y2() - 4);
				return;
			}
			GButt.ButtPanel.renderBG(r, isActive, isSelected, isHovered, body);
			GButt.ButtPanel.renderFrame(r, body);
			name.clear();
			SPRITE icon;
			switch (eKind[index]) {
			case E_TERM:
				icon = vanillaBools[arg].icon;
				name.add(vanillaBools[arg].info.name);
				if (terms.vassalage())
					name.s().add(terms.tribute()).add('%');
				break;
			case E_CREDITS:
				icon = UI.icons().s.money;
				name.add(terms.credits(mine));
				break;
			case E_GOOD:
				icon = TR.ALL().get(arg).icon();
				name.add(terms.goods(mine, arg));
				break;
			default:
				Region reg = WORLD.REGIONS().getByIndex(arg);
				icon = reg.faction() != null ? reg.faction().banner().MEDIUM : UI.icons().s.world;
				name.add(reg.info.name());
			}
			icon.renderCY(r, body.x1() + 8, body.cY());
			name.renderCY(r, body.x1() + 40, body.cY());
		}

		@Override
		protected void clickA() {
			if (index >= entries)
				return;
			int arg = eArg[index];
			boolean mine = eMine[index];
			switch (eKind[index]) {
			case E_TERM:
				terms.toggle(terms.chosen());
				break;
			case E_CREDITS:
				(mine ? give : ask).set(0);
				break;
			case E_GOOD:
				terms.goods(mine, arg, 0);
				break;
			case E_REGION:
				terms.toggleRegion(mine, arg);
				break;
			default:
				return;
			}
			changed();
			super.clickA();
		}

		@Override
		public void hoverInfoGet(GUI_BOX text) {
			if (index >= entries || eKind[index] == E_HEADER)
				return;
			GBox b = (GBox) text;
			int arg = eArg[index];
			switch (eKind[index]) {
			case E_TERM:
				b.title(vanillaBools[arg].info.name);
				b.text(vanillaBools[arg].info.desc);
				break;
			case E_CREDITS:
				b.title(Dic.¤¤Currs);
				break;
			case E_GOOD:
				b.title(TR.ALL().get(arg).names);
				break;
			default:
				VIEW.world().UI.regions.hover(WORLD.REGIONS().getByIndex(arg), b);
			}
			b.NL();
			b.text(TAKE_OUT);
		}
	}
}
