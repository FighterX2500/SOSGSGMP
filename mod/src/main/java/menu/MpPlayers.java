package menu;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import init.sprite.UI.UI;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.sets.LIST;
import snake2d.util.sets.Tree;
import sosgsgmp.game.PlayerDeals;
import util.gui.misc.GBox;
import util.info.GFORMAT;
import util.text.Dic;
import view.main.VIEW;
import world.region.RD;

/**
 * SOSGSGMP: another player in vanilla's faction screens (spec §6; SOS-76). Hooks M2 to M5 call here where vanilla
 * handles only an NPC: the map click, the region's banner, the faction list and the hover box. Each does nothing for an
 * NPC or in single player, so vanilla's own code runs on. Game thread, outside the step.
 */
public final class MpPlayers {

	/** Where vanilla shows an NPC's stance ("Neutral"), another player's window and hover box say this (owner, 2026-10-27). */
	static final String PLAYER = "Player";

	private MpPlayers() {
	}

	/** Hooks M2 to M4: opens the faction window for another player, beside vanilla's faction list, as for an NPC. */
	public static void open(Faction f) {
		open(f, false);
	}

	/** Opens the window for another player; on its Diplomacy tab when {@code diplomacy}. Does nothing for anyone else. */
	static void open(Faction f, boolean diplomacy) {
		if (!PlayerDeals.otherPlayer(f))
			return;
		VIEW.world().UI.factions.open(null); // vanilla's list, which also clears the side panels
		MpPlayerWindow w = new MpPlayerWindow(f);
		if (diplomacy)
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
