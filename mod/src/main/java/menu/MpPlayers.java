package menu;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.trade.TradeManager;
import init.sprite.UI.UI;
import init.trade.TRADABLE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.ArrayList;
import snake2d.util.sets.LIST;
import snake2d.util.sets.Tree;
import sosdet.Engine;
import sosgsgmp.game.PlayerDeals;
import sosgsgmp.trade.DepotPrice;
import util.gui.misc.GBox;
import util.gui.misc.GText;
import util.info.GFORMAT;
import util.text.Dic;
import view.main.VIEW;
import world.region.RD;

/**
 * SOSGSGMP: another player in vanilla's faction screens (spec §6; SOS-76). Hooks M2 to M5 call here where vanilla
 * handles only an NPC: the map click, the region's banner, the faction list and the hover box. Each does nothing for an
 * NPC or in single player, so vanilla's own code runs on. Hook M7 adds players to Economy & Trade's partners, with the
 * tariffs between players (SOS-77), and hook M12 adds them to the depots' partner lists (SOS-136), with their price in
 * its parts (SOS-138). Game thread, outside the step.
 */
public final class MpPlayers {

	/** Where vanilla shows an NPC's stance ("Neutral"), another player's window and hover box say this (owner, 2026-10-27). */
	static final String PLAYER = "Player";

	private MpPlayers() {
	}

	/** Hook M10: a multiplayer game runs, so no Resettle: it would abandon a player's city (SOS-123). */
	public static boolean inGame() {
		return Engine.active();
	}

	/** Hooks M2 to M4: opens the faction window for another player, beside vanilla's faction list, as for an NPC. */
	public static void open(Faction f) {
		open(f, false);
	}

	/** Opens the window for another player; on its Diplomacy tab when {@code diplomacy}. Does nothing for anyone else. */
	static void open(Faction f, boolean diplomacy) {
		open(f, diplomacy, false);
	}

	/** As {@link #open(Faction, boolean)}; with {@code draft}, a deal drafted for the autopilot's GUI preview (SOS-100). */
	static void open(Faction f, boolean diplomacy, boolean draft) {
		if (!PlayerDeals.otherPlayer(f))
			return;
		MpPlayerWindow w = window(f);
		if (draft)
			w.draftForPreview();
		else if (diplomacy)
			w.diplomacy();
		show(w, f);
	}

	/** Opens the window for another player on its Goods tab, for the autopilot's GUI preview (SOS-135). */
	static void openGoodsForPreview(Faction f) {
		if (!PlayerDeals.otherPlayer(f))
			return;
		MpPlayerWindow w = window(f);
		w.goodsForPreview();
		show(w, f);
	}

	private static MpPlayerWindow window(Faction f) {
		VIEW.world().UI.factions.open(null); // vanilla's list, which also clears the side panels
		return new MpPlayerWindow(f);
	}

	private static void show(MpPlayerWindow w, Faction f) {
		VIEW.world().panels.add(w, false);
		VIEW.world().window.centererTile.set(f.cx(), f.cy());
	}

	/**
	 * Hook M4, each frame the faction list draws: adds the other players to its sort. No allocation: it runs per frame.
	 */
	public static void addOthers(Tree<Faction> sorter) {
		LIST<Faction> all = FACTIONS.active();
		for (int i = 0; i < all.size(); i++) {
			Faction f = all.get(i);
			if (PlayerDeals.otherPlayer(f))
				sorter.add(f);
		}
	}

	/** Hook M7, each frame Economy & Trade's partner list draws: adds the other players to it. No allocation. */
	public static void addOthers(ArrayList<Faction> list) {
		LIST<Faction> all = FACTIONS.active();
		for (int i = 0; i < all.size(); i++) {
			Faction f = all.get(i);
			if (PlayerDeals.otherPlayer(f))
				list.add(f);
		}
	}

	/** Hook M12: opens another player's window on its Diplomacy tab, from a depot's partner list. */
	public static void openDiplomacy(Faction f) {
		open(f, true);
	}

	/**
	 * Hook M12, each frame a depot's partner list draws: adds to {@code out} after its first {@code n} the other players
	 * this player's trade reaches, and returns the new count. No allocation.
	 */
	public static int addTraders(Faction[] out, int n) {
		LIST<Faction> all = FACTIONS.active();
		for (int i = 0; i < all.size(); i++) {
			Faction f = all.get(i);
			if (PlayerDeals.otherPlayer(f) && PlayerDeals.tradeReaches(f.index()))
				out[n++] = f;
		}
		return n;
	}

	/**
	 * Hook M12: the market's price for one item of {@code t} between this player and another, in a depot's partner list:
	 * the seller's lowest export price and the toll, as the market prices a sale between players. In the Import depot
	 * ({@code buying}) the seller is {@code f}, and 0 unless {@code f}'s Export depot takes it; in the Export depot it is
	 * this player, and 0 unless {@code f}'s Import depot takes it (SOS-139). A price at or over the buyer's highest import
	 * price is still listed, darkened ({@link #depotOverCap}, SOS-148). 0 before {@code f}'s first report. No allocation:
	 * the list reads it each frame.
	 */
	public static int depotPrice(Faction f, TRADABLE t, boolean buying) {
		int slot = f.index();
		if (PlayerDeals.depot(slot, t.index(), buying, false) <= 0)
			return 0; // their depot doesn't take it: they don't trade it with anyone
		if (buying) {
			int asked = PlayerDeals.price(slot, t.index(), true);
			return asked > 0 ? DepotPrice.paid(asked, depotToll(f, FACTIONS.player())) : 0;
		}
		return DepotPrice.paid(t.ps().priceCapsI.get(), depotToll(f, f));
	}

	/**
	 * Hook M12: whether another player's row in a depot is darkened: its price, the toll included, is at or over the
	 * buyer's highest import price, so the sale doesn't happen (SOS-148). In the Import depot ({@code buying}) the buyer
	 * is this player, in the Export depot {@code f}. False for an NPC. No allocation: the list reads it each frame.
	 */
	public static boolean depotOverCap(Faction f, TRADABLE t, boolean buying) {
		if (!PlayerDeals.otherPlayer(f))
			return false;
		int paid = depotPrice(f, t, buying);
		return paid > 0 && !DepotPrice.buys(paid, buyerCap(f, t, buying));
	}

	/** The buyer's highest import price in a depot's row: this player's own in the Import depot, else {@code f}'s reported one. */
	private static int buyerCap(Faction f, TRADABLE t, boolean buying) {
		return buying ? t.pb().priceCapsI.get() : PlayerDeals.price(f.index(), t.index(), false);
	}

	/**
	 * Hook M12, the hover of another player's row in a depot: the price in its parts, and what their depot holds or has
	 * room for (SOS-138, SOS-139). In the Import depot ({@code buying}) they sell to this player; in the Export depot
	 * this player sells to them.
	 */
	public static void depotHover(GBox b, Faction f, TRADABLE t, boolean buying) {
		int me = FACTIONS.player().index();
		int asked = buying ? PlayerDeals.price(f.index(), t.index(), true) : t.ps().priceCapsI.get();
		int percent = buying ? PlayerDeals.tariff(me, f.index()) : PlayerDeals.tariff(f.index(), me);
		DepotPrice p = new DepotPrice(asked, depotToll(f, buying ? FACTIONS.player() : f), percent);
		row(b, buying ? "Their export price" : "Your export price", p.asked);
		row(b, "Toll, to the caravan", p.toll);
		row(b, buying ? "You pay" : "They pay", p.paid());
		b.textLL(buying ? "Your tariff, back to you" : "Their tariff");
		b.tab(HOVER_TAB);
		b.add(GFORMAT.i(b.text(), -p.tariff()));
		b.add(b.text().add(' ').add('(').add(percent).add('%').add(')'));
		b.NL();
		row(b, buying ? "It costs you" : "Reaches you", buying ? p.cost() : p.received());
		b.NL(4);
		int cap = buyerCap(f, t, buying);
		row(b, buying ? "Your highest import price" : "Their highest import price", cap);
		if (buying) {
			row(b, "Your import price to buy, at least", DepotPrice.lowestCap(p.paid()));
		} else if (DepotPrice.highestAsked(cap, p.toll) > 0) {
			row(b, "Your export price to sell, at most", DepotPrice.highestAsked(cap, p.toll));
		} else {
			b.textLL("No export price sells to them: the toll alone reaches their highest import price.");
			b.NL();
		}
		if (!DepotPrice.buys(p.paid(), cap)) {
			b.add(b.text().errorify().add(buying ? "Their price is over what you are willing to pay."
					: "Your price is over what they are willing to pay."));
			b.NL();
		}
		b.NL(4);
		row(b, buying ? "In their Export depot" : "Room in their Import depot",
				PlayerDeals.depot(f.index(), t.index(), buying, true));
		b.NL(8);
	}

	/** The depot hover's value column: past its longest label, "Your import price to buy, at least" (SOS-153). */
	private static final int HOVER_TAB = 9;

	private static void row(GBox b, CharSequence label, int value) {
		b.textLL(label);
		b.tab(HOVER_TAB);
		b.add(GFORMAT.i(b.text(), value));
		b.NL();
	}

	/** The toll per item between this player and {@code f} when {@code buyer} buys: their roads cut it (SOS-138). */
	private static int depotToll(Faction f, Faction buyer) {
		return DepotPrice.toll(RD.DIST().distance(f), TradeManager.tollPerTile, PlayerDeals.tollDivisor(buyer.index()));
	}

	/**
	 * Hooks M4 and M7: whether this player reaches {@code f}, which those lists sort by and darken the rest. An NPC by
	 * vanilla's rule; another player by the rule their trade walks, across a player's land at peace too (SOS-134).
	 */
	public static boolean reachable(Faction f) {
		return PlayerDeals.otherPlayer(f) ? PlayerDeals.tradeReaches(f.index()) : RD.DIST().reachable(f);
	}

	/** Vanilla's toll per item between this player and {@code f}: the capital-to-capital distance, less the roads' boost. */
	public static double toll(Faction f) {
		return TradeManager.toll(FACTIONS.player(), f, RD.DIST().distance(f));
	}

	/** Hook M7's row: another player's tariff on what this player sells them, where an NPC's row shows its own. */
	public static void theirTariff(GText t, Faction f) {
		t.add(PlayerDeals.tariff(f.index(), FACTIONS.player().index())).add('%');
	}

	/**
	 * Hook M7's hover for another player: both tariffs and what this player's tariff brought in. False for an NPC, whose
	 * box vanilla fills.
	 */
	public static boolean tradeHover(GBox b, Faction f) {
		if (!PlayerDeals.otherPlayer(f))
			return false;
		int me = FACTIONS.player().index();
		b.add(UI.icons().s.wheel);
		b.textLL(Dic.¤¤Toll);
		b.tab(7);
		b.add(GFORMAT.f(b.text(), toll(f)));
		b.NL();
		b.add(UI.icons().s.money);
		b.textLL("Your tariff on them");
		b.tab(7);
		b.add(b.text().add(PlayerDeals.tariff(me, f.index())).add('%'));
		b.NL();
		b.add(UI.icons().s.angry);
		b.textLL("Their tariff on you");
		b.tab(7);
		b.add(b.text().add(PlayerDeals.tariff(f.index(), me)).add('%'));
		b.NL(4);
		b.textLL("Tariff revenue, this year");
		b.tab(7);
		b.add(GFORMAT.i(b.text(), PlayerDeals.tariffRevenue(me, f.index(), false)));
		b.NL();
		b.textLL("Last year");
		b.tab(7);
		b.add(GFORMAT.i(b.text(), PlayerDeals.tariffRevenue(me, f.index(), true)));
		b.NL();
		return true;
	}

	/**
	 * Hook M7, above Economy & Trade's partner list: this year's revenue from this player's tariffs on other players. It
	 * draws nothing in single player or before the session runs; the line is reserved, as the list is built before the
	 * game loads.
	 */
	public static RENDEROBJ tariffTotal(int width) {
		GText t = new GText(UI.FONT().S, 24);
		return new RENDEROBJ.RenderImp(width, UI.FONT().S.height() + 4) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				if (!PlayerDeals.inSession())
					return;
				UI.icons().s.money.render(r, body.x1(), body.y1());
				GFORMAT.i(t.clear(), PlayerDeals.tariffRevenue(FACTIONS.player().index(), -1, false));
				t.render(r, body.x1() + 20, body.y1());
			}
		};
	}

	/**
	 * Hook M5: fills the hover box for another player and returns true; false for anyone else. Vanilla's box for an NPC
	 * without what an NPC's court has: no king, opinion, trust, power or emissaries.
	 */
	public static boolean hover(GUI_BOX box, Faction f) {
		if (!PlayerDeals.otherPlayer(f))
			return false;
		GBox b = (GBox) box;
		b.title(f.name);
		b.add(f.banner().BIG);
		b.textL(PLAYER);
		b.NL(8);
		b.add(DIP.get(FACTIONS.player(), f).icon);
		b.textLL(Dic.¤¤DiplomyStance);
		b.tab(6);
		b.text(DIP.get(FACTIONS.player(), f).name);
		b.NL();
		b.add(UI.icons().s.wheel);
		b.textLL(Dic.¤¤Distance);
		b.tab(6);
		b.add(GFORMAT.i(b.text(), RD.DIST().distance(f)));
		b.NL();
		b.add(UI.icons().s.money);
		b.textLL(Dic.¤¤NetWorth);
		b.tab(6);
		b.add(GFORMAT.i(b.text(), (int) FACTIONS.WORTH().faction(f)));
		b.NL();
		b.add(UI.icons().s.human);
		b.textLL(Dic.¤¤Population);
		b.tab(6);
		b.add(GFORMAT.i(b.text(), RD.RACES().population.faction().get(f)));
		b.NL();
		if (DIP.WAR().all(f).size() > 0) {
			b.add(UI.icons().s.sword);
			b.textLL(Dic.¤¤Enemies);
			b.tab(6);
			for (Faction e : DIP.WAR().all(f))
				b.add(e.banner().MEDIUM);
			b.NL();
		}
		return true;
	}
}
