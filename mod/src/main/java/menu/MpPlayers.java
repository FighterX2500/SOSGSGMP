package menu;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.trade.TradeManager;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.ArrayList;
import snake2d.util.sets.LIST;
import snake2d.util.sets.Tree;
import sosdet.Engine;
import sosgsgmp.game.PlayerDeals;
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
 * tariffs between players (SOS-77). Game thread, outside the step.
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
		VIEW.world().UI.factions.open(null); // vanilla's list, which also clears the side panels
		MpPlayerWindow w = new MpPlayerWindow(f);
		if (draft)
			w.draftForPreview();
		else if (diplomacy)
			w.diplomacy();
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
