package menu;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import init.trade.TRADABLE;
import settlement.trade.PBuyer;
import settlement.trade.PSeller;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.sets.LIST;
import sosgsgmp.game.DepotTexts;
import sosgsgmp.game.PlayerDeals;
import sosgsgmp.trade.DepotMessages;
import util.gui.misc.GBox;
import util.info.GFORMAT;
import util.text.Dic;

/**
 * SOSGSGMP hooks M12 and M13: the depots' messages, counting the players this player trades with (spec §7; SOS-210).
 * Vanilla's {@code PBuyer} and {@code PSeller} work them out from the NPCs alone; they are the engine's (patch 13),
 * so the windows that show them call here instead: the depots' problem box, their goods' alert icons and hovers, the
 * depot rooms' goods picker and the treasury's hover. In single player, or with no player partner, each gives
 * vanilla's own. Game thread, outside the step; no allocation, as the windows ask each frame.
 */
public final class MpDepotMessages {

	private MpDepotMessages() {
	}

	/** The Import depot's problem (red) for {@code t}, or null. */
	public static CharSequence importProblem(TRADABLE t) {
		PBuyer b = FACTIONS.player().buyer(t);
		CharSequence p = b.problem();
		switch (DepotMessages.importProblem(npcProblem(p), partners(), cheapestPlayer(t))) {
		case DepotMessages.NONE:
			return null;
		case DepotMessages.NO_TRADERS:
			return Dic.¤¤noTrade;
		default:
			return p;
		}
	}

	/** The Import depot's warning (yellow) for {@code t}, or null; read when {@link #importProblem} is null. */
	public static CharSequence importWarning(TRADABLE t) {
		PBuyer b = FACTIONS.player().buyer(t);
		CharSequence w = b.warning();
		int npc = FACTIONS.player().trade.pricesBuy.get(t);
		boolean npcWarning = DepotTexts.is(w, DepotTexts.credits()) || DepotTexts.is(w, DepotTexts.importPriceCap())
				|| DepotTexts.is(w, DepotTexts.treasury()) || w == null && (npc <= 0 || npc == Integer.MAX_VALUE);
		if (!npcWarning)
			return w; // the depot's own: none set to the goods, full, its level at nothing
		int player = partners() ? cheapestPlayer(t) : 0;
		switch (DepotMessages.importWarning(npc, player,
				FACTIONS.player().credits().getD(), b.priceCapsI.get(), b.minMoney.get())) {
		case DepotMessages.NONE:
			return null;
		case DepotMessages.CREDITS:
			return DepotTexts.credits();
		case DepotMessages.PRICE_CAP:
			return DepotTexts.importPriceCap();
		case DepotMessages.TREASURY:
			return DepotTexts.treasury();
		default:
			return w;
		}
	}

	/** The Export depot's problem (red) for {@code t}, or null. */
	public static CharSequence exportProblem(TRADABLE t) {
		CharSequence p = FACTIONS.player().seller(t).problem();
		boolean npcProblem = npcProblem(p) || DepotTexts.is(p, DepotTexts.noBuyer());
		switch (DepotMessages.exportProblem(npcProblem, partners(), playerTakes(t))) {
		case DepotMessages.NONE:
			return null;
		case DepotMessages.NO_BUYER:
			return DepotTexts.noBuyer();
		default:
			return p;
		}
	}

	/** The Export depot's warning (yellow) for {@code t}, or null; read when {@link #exportProblem} is null. */
	public static CharSequence exportWarning(TRADABLE t) {
		PSeller s = FACTIONS.player().seller(t);
		CharSequence w = s.warning();
		int npc = FACTIONS.player().trade.pricesSell.get(t);
		boolean npcWarning = DepotTexts.is(w, DepotTexts.exportPriceCap()) || w == null && npc <= 0;
		if (!npcWarning || !partners())
			return w; // the depot's own: no goods, full
		switch (DepotMessages.exportWarning(npc, playerTakes(t), playerBuys(t),
				s.priceCapsI.get())) {
		case DepotMessages.NONE:
			return null;
		case DepotMessages.PRICE_CAP:
			return DepotTexts.exportPriceCap();
		default:
			return w;
		}
	}

	/**
	 * The Import goods' hover, as vanilla's {@code PBuyer.hover} lays it out, with this message and the cheapest price
	 * a player's when it is; vanilla's own where no player changes them.
	 */
	public static void importHover(GUI_BOX box, TRADABLE t) {
		PBuyer b = FACTIONS.player().buyer(t);
		int player = partners() ? cheapestPlayer(t) : 0;
		if (player <= 0) {
			b.hover(box);
			return;
		}
		GBox g = (GBox) box;
		g.title(t.name);
		CharSequence p = importProblem(t);
		if (p != null) {
			g.error(p);
		} else {
			p = importWarning(t);
			if (p != null)
				g.add(g.text().warnify().add(p));
		}
		g.NL(4);
		b.hoverCapacity(g);
		g.NL(8);
		row(g, PBuyer.¤¤PriceCap, b.priceCapsI.get());
		row(g, PBuyer.¤¤TreasuryLim, b.minMoney.get());
		row(g, DepotTexts.bestPrice(), DepotMessages.cheapest(FACTIONS.player().trade.pricesBuy.get(t), player));
		b.hoverTradeValue(box);
	}

	/** The Export goods' hover, as vanilla's {@code PSeller.hover} lays it out, with this message; vanilla's own otherwise. */
	public static void exportHover(GUI_BOX box, TRADABLE t) {
		PSeller s = FACTIONS.player().seller(t);
		if (!partners() || !playerTakes(t)) {
			s.hover(box);
			return;
		}
		GBox g = (GBox) box;
		g.title(t.names);
		CharSequence p = exportProblem(t);
		if (p == null)
			p = exportWarning(t);
		if (p != null) {
			g.error(p);
			g.NL();
		}
		s.hoverCapacity(g);
		g.NL(8);
		row(g, PBuyer.¤¤PriceCap, s.priceCapsI.get());
		row(g, DepotTexts.bestPrice(), FACTIONS.player().trade.pricesSell.get(t));
		s.hoverTradeValue(box);
	}

	private static void row(GBox g, CharSequence label, int value) {
		g.textLL(label);
		g.tab(6);
		g.add(GFORMAT.i(g.text(), value));
		g.NL();
	}

	/** True for a problem the NPCs alone pose: no NPC neighbour or no NPC trade agreement or no NPC trader of the goods. */
	private static boolean npcProblem(CharSequence p) {
		return DepotTexts.is(p, Dic.¤¤noTrade) || DepotTexts.is(p, Dic.¤¤noTradePartners);
	}

	/** True when this player trades with another: a trade agreement and trade reach, as the market needs. */
	private static boolean partners() {
		LIST<Faction> all = FACTIONS.active();
		for (int i = 0; i < all.size(); i++) {
			if (partner(all.get(i)))
				return true;
		}
		return false;
	}

	private static boolean partner(Faction f) {
		return PlayerDeals.otherPlayer(f) && PlayerDeals.tradeReaches(f.index()) && DIP.get(FACTIONS.player(), f).trades;
	}

	/** The cheapest price, toll included, that a partner player sells one item of {@code t} at, as the Import depot lists it; 0 for none. */
	private static int cheapestPlayer(TRADABLE t) {
		int best = 0;
		LIST<Faction> all = FACTIONS.active();
		for (int i = 0; i < all.size(); i++) {
			Faction f = all.get(i);
			int p = partner(f) ? MpPlayers.depotPrice(f, t, true) : 0;
			if (p > 0 && (best == 0 || p < best))
				best = p;
		}
		return best;
	}

	/** True when a partner player's Import depot takes {@code t}: they would buy it at some price. */
	private static boolean playerTakes(TRADABLE t) {
		LIST<Faction> all = FACTIONS.active();
		for (int i = 0; i < all.size(); i++) {
			Faction f = all.get(i);
			if (partner(f) && MpPlayers.depotPrice(f, t, false) > 0)
				return true;
		}
		return false;
	}

	/** True when a partner player buys {@code t} at this player's export price cap, the toll included. */
	private static boolean playerBuys(TRADABLE t) {
		LIST<Faction> all = FACTIONS.active();
		for (int i = 0; i < all.size(); i++) {
			Faction f = all.get(i);
			if (partner(f) && MpPlayers.depotPrice(f, t, false) > 0 && !MpPlayers.depotOverCap(f, t, false))
				return true;
		}
		return false;
	}
}
