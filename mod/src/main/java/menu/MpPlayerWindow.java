package menu;

import java.util.LinkedList;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import init.constant.C;
import init.sprite.UI.Icon;
import init.sprite.UI.UI;
import init.trade.TR;
import init.trade.TRADABLE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.Hoverable.HOVERABLE;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.SPRITE;
import sosdet.game.deal.TradeTally;
import sosgsgmp.game.PlayerDeals;
import util.colors.GCOLOR;
import util.data.GETTER.GETTER_IMP;
import util.gui.common.UIPickerArmy;
import util.gui.common.UIPickerRegion;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GScrollRows;
import util.info.GFORMAT;
import util.text.Dic;
import view.interrupter.ISidePanel;
import view.main.VIEW;
import world.WORLD;
import world.army.AD;
import world.entity.army.WArmy;
import world.map.regions.Region;
import world.region.RD;

/**
 * SOSGSGMP: another player's faction window, beside vanilla's faction list as an NPC's is (spec §6; SOS-76). Vanilla's
 * window works only on an NPC, so this one is built from the same parts for a player: the banner header, which says
 * "Player" where vanilla shows the stance, and the tabs Realm, Goods, Boosts, Diplomacy and Armies (stationing, SOS-233). There is no Court: a player
 * has no court. A new window is made at each open; vanilla's list clears the old one.
 */
final class MpPlayerWindow extends ISidePanel {

	/** Beside vanilla's faction list, whose rows are {@code C.SG*264} wide, in the side panels' width. */
	private static final int WIDTH = C.MIN_WIDTH - C.SG * 264 - 48;
	private static final int TAB_WIDTH = 140;
	private static final String BUY_D = "Buy: what you pay for one item from them on the market, their export price and the toll, as your Import depot lists them. Your tariff on them comes back to your treasury.";
	private static final String SELL_D = "Sell: what reaches you for one item sold to them on the market, your export price less their tariff on you, as your Export depot's hover shows it. They pay the toll.";
	private static final String NOT_SOLD = "Their Export depot doesn't take it: they don't sell it.";
	private static final String NOT_BOUGHT = "Their Import depot doesn't take it: they don't buy it.";
	private static final String NOT_EXPORTED = "Your Export depot doesn't take it: you don't sell it.";
	private static final String EXPORTED = "Exported";
	private static final String IMPORTED = "Imported";
	private static final String[] PERIODS = { "This year", "Last year", "All time" }; // TradeTally's periods, in order
	private static final String TRADED_D = "Exported: what you sold them on the market; imported: what you bought from them. Items @ the average price in denari, tolls and tariffs settled. Deals don't count.";
	private static final String NO_BOOSTS = "Another player's boosts come from their city, which runs on their PC: not shown yet.";

	private final CLICKABLE.ClickSwitch sw;
	private final MpPlayerDiplomacy dip;
	private final CLICKABLE goods;

	MpPlayerWindow(Faction f) {
		GETTER_IMP<Faction> g = new GETTER_IMP<>(f);
		section = new GuiSection() {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				if (PlayerDeals.otherPlayer(f)) // a player removed since stays as an empty panel, as vanilla's does
					super.render(r, ds);
			}
		};
		section.body().setWidth(WIDTH).setHeight(1);
		section.addRelBody(0, DIR.S, header(f));
		section.addRelBody(8, DIR.S, viewButton(f.index()));

		int tabH = new GButt.ButtPanel(Dic.¤¤Realm).body().height();
		int hi = HEIGHT - section.body().height() - tabH - 8 - 16 - 24;
		CLICKABLE realm = realm(g, hi);
		sw = new CLICKABLE.ClickSwitch(realm);
		sw.setD(DIR.N);
		dip = new MpPlayerDiplomacy(f, WIDTH, hi);
		goods = goods(f, hi);
		GuiSection tabs = new GuiSection();
		tabs.addRightC(0, tab(Dic.¤¤Realm, realm));
		tabs.addRightC(0, tab(Dic.¤¤goods, goods));
		tabs.addRightC(0, new GButt.ButtPanel(Dic.¤¤Boosts) {
			@Override
			protected void renAction() {
				activeSet(false);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				((GBox) text).error(NO_BOOSTS);
			}
		}.setDim(TAB_WIDTH, tabH));
		tabs.addRightC(0, tab(Dic.¤¤Diplomacy, dip));
		tabs.addRightC(0, tab(Dic.¤¤Armies, new MpStationing(f.index(), WIDTH))); // stationing (SOS-233)
		section.addRelBody(8, DIR.S, tabs);
		section.addRelBody(0, DIR.S, new RENDEROBJ.RenderImp(WIDTH - 128, 16) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				GCOLOR.UI().border(r, body().x1(), body().x2(), body().y1() + 5, body().y1() + 8);
			}
		});
		section.addRelBody(0, DIR.S, sw);
		section.body().setWidth(WIDTH).setHeight(HEIGHT);
	}

	/**
	 * View city (spec §10, SOS-82): asks the player to let this one view their city live. Greyed, with the reason, when
	 * a view can't be asked for now; "Asked..." until the answer comes.
	 */
	/** On the Diplomacy tab with a deal drafted, for the autopilot's GUI preview (SOS-100). */
	void draftForPreview() {
		diplomacy();
		dip.draftForPreview();
	}

	/** On the Goods tab, for the autopilot's GUI preview (SOS-135). */
	void goodsForPreview() {
		sw.set(goods);
	}

	private static CLICKABLE viewButton(int slot) {
		SPRITE view = UI.FONT().H2.getText("View city");
		SPRITE watch = UI.FONT().H2.getText("Watch battle");
		SPRITE asked = UI.FONT().H2.getText("Asked...");
		return new GButt.ButtPanel(view) {
			@Override
			protected void clickA() {
				MpViews v = MpViews.current();
				if (v != null)
					v.ask(slot);
			}

			@Override
			protected void renAction() {
				MpViews v = MpViews.current();
				activeSet(v != null && v.canAsk(slot));
				replaceLabel(v != null && v.asked(slot) ? asked : v != null && v.watches(slot) ? watch : view, DIR.C);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				MpViews v = MpViews.current();
				String refusal = v == null ? "The game isn't running." : v.askRefusal(slot);
				GBox b = (GBox) text;
				if (v != null && v.watches(slot)) {
					b.title("Watch battle");
					b.text("Join this player's battle against NPCs and watch it live. While you watch, your own city waits"
							+ " where it stopped.");
				} else {
					b.title("View city");
					b.text("Ask to watch this player's city live. They allow or refuse; while you watch, your own city waits"
							+ " where it stopped.");
				}
				if (refusal != null) {
					b.NL(4);
					b.error(refusal);
				}
			}
		}.setDim(TAB_WIDTH);
	}

	/** Shows the Diplomacy tab, where another player's proposal is answered. */
	void diplomacy() {
		sw.set(dip);
	}

	private CLICKABLE tab(CharSequence name, CLICKABLE content) {
		GButt.ButtPanel b = new GButt.ButtPanel(name) {
			@Override
			protected void clickA() {
				sw.set(content);
			}

			@Override
			protected void renAction() {
				selectedSet(sw.current() == content);
			}
		};
		b.body.setWidth(TAB_WIDTH);
		return b;
	}

	/** Vanilla's banner header: the name between the two banners, "Player" below it, then the player's figures. */
	private static GuiSection header(Faction f) {
		GuiSection s = new GuiSection();
		s.body().setWidth(WIDTH);
		s.addRelBody(6, DIR.S, new GStat(new GText(UI.FONT().H1, 32)) {
			@Override
			public void update(GText text) {
				text.add(f.name);
				text.lablify();
			}
		}.r(DIR.N));
		s.addRelBody(4, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				text.add(MpPlayers.PLAYER);
			}

			@Override
			public void hoverInfoGet(GBox b) {
				DIP.get(FACTIONS.player(), f).hover(b);
			}
		}.r(DIR.N));
		for (int side = 0; side < 2; side++) {
			RENDEROBJ banner = new RENDEROBJ.RenderImp(Icon.L * 2) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					f.banner().HUGE.render(r, body());
				}
			};
			if (side == 0)
				banner.body().moveX1(s.body().x1()).moveCY(s.body().cY());
			else
				banner.body().moveX2(s.body().x2()).moveCY(s.body().cY());
			s.add(banner);
		}
		GuiSection facts = new GuiSection();
		facts.addRightC(0, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, RD.DIST().distance(f));
			}
		}.hh(UI.icons().s.wheel).hoverTitleSet(Dic.¤¤Distance));
		facts.addRightC(48, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, (int) FACTIONS.WORTH().faction(f));
			}
		}.hh(UI.icons().s.money).hoverTitleSet(Dic.¤¤NetWorth));
		facts.addRightC(48, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, RD.RACES().population.faction().get(f));
			}
		}.hh(UI.icons().s.human).hoverTitleSet(Dic.¤¤Population));
		s.addRelBody(8, DIR.S, facts);
		return s;
	}

	/** Vanilla's Realm tab: soldiers, conscripts and the armies on the left; area and the regions on the right. */
	private static CLICKABLE realm(GETTER_IMP<Faction> f, int height) {
		GuiSection all = new GuiSection();
		GuiSection s = new GuiSection();
		s.addRelBody(8, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, AD.men(null).faction(f.get()));
			}
		}.hh(Dic.¤¤Soldiers));
		s.addRelBody(8, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, AD.conscripts().total(null).get(f.get()));
			}
		}.hh(Dic.¤¤Conscripts));
		s.addRelBody(8, DIR.S, new UIPickerArmy(f, height - s.body().height() - 16) {
			@Override
			protected boolean canBePicked(WArmy a) {
				return !WORLD.FOW().is(a.ctx(), a.cty());
			}

			@Override
			protected void pick(WArmy a) {
				if (!WORLD.FOW().is(a.ctx(), a.cty())) {
					VIEW.world().activate();
					VIEW.UI().manager.close();
					VIEW.world().window.centererTile.set(a.ctx(), a.cty());
				}
			}

			@Override
			public void hover(GUI_BOX text, WArmy a) {
				if (!WORLD.FOW().is(a.ctx(), a.cty()))
					super.hover(text, a);
			}
		});
		all.add(s);
		all.addRelBody(16, DIR.E, new RENDEROBJ.RenderImp(1, height) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				GCOLOR.UI().border().render(r, body);
			}
		});
		s = new GuiSection();
		s.addRelBody(8, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				int am = 0;
				for (Region r : f.get().realm().all())
					am += r.info.area();
				GFORMAT.i(text, am);
			}
		}.hh(Dic.¤¤Area));
		s.addRelBody(8, DIR.S, new UIPickerRegion(f, height - 16) {
			@Override
			protected void toggle(Region reg) {
				VIEW.world().activate();
				VIEW.UI().manager.close();
				VIEW.world().window.centererTile.set(reg.cx(), reg.cy());
				VIEW.world().UI.regions.open(reg);
			}
		});
		all.addRelBody(16, DIR.E, s);
		return all;
	}

	/**
	 * The Goods tab: what the player last reported having of each good, the market's price between the two players
	 * (SOS-230): Buy, what one item from them costs, and Sell, what one item sold to them brings, each as the depots price
	 * it, with its parts in the figure's hover; and what this player sold them and bought from them on the market since
	 * the start, with each year's figures and average price in the row's hover (SOS-135). Vanilla's Rate, Purchasing
	 * Power and traded bar describe an NPC's own market, so they're gone.
	 */
	private static CLICKABLE goods(Faction f, int height) {
		int slot = f.index();
		int rowW = WIDTH - 64;
		int stored = rowW * 7 / 20;
		int buy = rowW / 2;
		int sell = rowW * 3 / 5;
		int exp = rowW * 7 / 10;
		int imp = rowW * 17 / 20;
		LinkedList<RENDEROBJ> rows = new LinkedList<>();
		rows.add(new RENDEROBJ.RenderImp(rowW, UI.FONT().H2.height() + UI.FONT().M.height() + 8) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				GCOLOR.T().H1.bind();
				UI.FONT().H2.render(r, Dic.¤¤Resource, body.x1() + 40, body.y1());
				UI.FONT().H2.render(r, Dic.¤¤Stored, body.x1() + stored, body.y1());
				// Price centred over its two subcolumns, from Buy's left edge to Sell's right one (batch 13 item 14)
				int span = sell + UI.FONT().M.width(Dic.¤¤Sell) - buy;
				UI.FONT().H2.render(r, Dic.¤¤Price, body.x1() + buy + Math.max(0, (span - UI.FONT().H2.width(Dic.¤¤Price)) / 2),
						body.y1());
				UI.FONT().H2.render(r, EXPORTED, body.x1() + exp, body.y1());
				UI.FONT().H2.render(r, IMPORTED, body.x1() + imp, body.y1());
				GCOLOR.T().H2.bind();
				UI.FONT().M.render(r, Dic.¤¤Buy, body.x1() + buy, body.y1() + UI.FONT().H2.height());
				UI.FONT().M.render(r, Dic.¤¤Sell, body.x1() + sell, body.y1() + UI.FONT().H2.height());
				COLOR.unbind();
			}
		});
		for (TRADABLE t : TR.ALL()) {
			GuiSection row = new GuiSection() {
				private final GText amount = new GText(UI.FONT().M, 16);
				private final GText exported = new GText(UI.FONT().M, 16);
				private final GText imported = new GText(UI.FONT().M, 16);

				{
					body().setDim(rowW, Icon.M + 8);
				}

				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					t.icon().render(r, body().x1() + 8, body().y1() + 4);
					COLOR.WHITE100.bind();
					UI.FONT().M.render(r, t.name, body().x1() + 40, body().y1() + 4);
					GFORMAT.i(amount.clear(), PlayerDeals.stock(slot, t.index()));
					amount.render(r, body().x1() + stored, body().y1() + 4);
					int me = FACTIONS.player().index();
					GFORMAT.i(exported.clear(), PlayerDeals.traded(me, slot, t.index(), TradeTally.ALL_TIME, false));
					exported.render(r, body().x1() + exp, body().y1() + 4);
					GFORMAT.i(imported.clear(), PlayerDeals.traded(slot, me, t.index(), TradeTally.ALL_TIME, false));
					imported.render(r, body().x1() + imp, body().y1() + 4);
					COLOR.unbind();
					super.render(r, ds); // the Buy and Sell figures
				}

				@Override
				protected void hoverInfoSelf(GUI_BOX text) {
					GBox b = (GBox) text;
					b.title(t.name);
					int me = FACTIONS.player().index();
					b.tab(5);
					b.textLL(EXPORTED);
					b.tab(9);
					b.textLL(IMPORTED);
					b.NL();
					for (int p = 0; p < PERIODS.length; p++) {
						b.text(PERIODS[p]);
						traded(b, 5, me, slot, t, p);
						traded(b, 9, slot, me, t, p);
						b.NL();
					}
					b.NL(4);
					b.text(TRADED_D);
				}
			};
			row.add(new PriceCell(f, t, true, sell - buy), row.body().x1() + buy, row.body().y1());
			row.add(new PriceCell(f, t, false, exp - sell), row.body().x1() + sell, row.body().y1());
			rows.add(row);
		}
		GuiSection s = new GuiSection();
		s.add(new GScrollRows(rows, height).view());
		return s;
	}

	/**
	 * A Goods tab row's Buy ({@code buying}) or Sell figure (SOS-230): blank while the depot doesn't list the other
	 * player for the goods, dark while the price is at or over the buyer's highest import price, as the depot's row is.
	 */
	private static final class PriceCell extends HOVERABLE.HoverableAbs {

		private final Faction f;
		private final TRADABLE t;
		private final boolean buying;
		private final GText text = new GText(UI.FONT().M, 16);

		PriceCell(Faction f, TRADABLE t, boolean buying, int width) {
			super(width, Icon.M + 8);
			this.f = f;
			this.t = t;
			this.buying = buying;
		}

		@Override
		protected void render(SPRITE_RENDERER r, float ds, boolean isHovered) {
			int v = figure(f, t, buying);
			if (v < 0)
				return;
			GFORMAT.i(text.clear(), v);
			if (MpPlayers.depotOverCap(f, t, buying))
				text.color(GCOLOR.T().INACTIVE);
			text.render(r, body.x1(), body.y1() + 4);
		}

		@Override
		public void hoverInfoGet(GUI_BOX text) {
			priceHover((GBox) text, f, t, buying);
		}
	}

	/** The Goods tab's Buy ({@code buying}) or Sell for one item of {@code t} between this player and {@code f}; -1 for none. */
	private static int figure(Faction f, TRADABLE t, boolean buying) {
		if (!buying)
			return MpPlayers.depotReceived(f, t);
		int paid = MpPlayers.depotPrice(f, t, true);
		return paid > 0 ? paid : -1;
	}

	/**
	 * The hover of a Goods tab row's Buy ({@code buying}) or Sell figure (SOS-230): the price in its parts, as the depot's
	 * row shows it, or why there is none. Also the autopilot's GUI preview.
	 */
	static void priceHover(GBox b, Faction f, TRADABLE t, boolean buying) {
		b.title(t.name);
		b.text(buying ? BUY_D : SELL_D);
		b.NL(8);
		if (figure(f, t, buying) < 0)
			b.error(buying ? NOT_SOLD : !MpPlayers.exports(t) ? NOT_EXPORTED : NOT_BOUGHT);
		else
			MpPlayers.depotHover(b, f, t, buying);
	}

	/** In the Goods tab's hover, at {@code tab}: the items {@code seller} sold {@code buyer} in {@code period}, and at what average price. */
	private static void traded(GBox b, int tab, int seller, int buyer, TRADABLE t, int period) {
		long items = PlayerDeals.traded(seller, buyer, t.index(), period, false);
		b.tab(tab);
		b.add(GFORMAT.i(b.text(), items));
		if (items > 0)
			b.add(b.text().add(" @ ").add(Math.round((double) PlayerDeals.traded(seller, buyer, t.index(), period, true) / items)));
	}
}
