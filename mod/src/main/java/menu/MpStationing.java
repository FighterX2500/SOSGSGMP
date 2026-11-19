package menu;

import game.faction.FACTIONS;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import sosgsgmp.game.PlayerStations;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.info.GFORMAT;
import world.army.AD;
import world.entity.army.WArmy;

/**
 * SOSGSGMP: the Armies tab of another player's window (spec §9; SOS-233, ADR 0011). Above, this PC's player's armies
 * near that player's capital, each with Station or Call back, and once stationed Give command or Take command back
 * (owner, 2026-11-19: the host then commands its soldiers in their city); below, that player's armies stationed at this
 * PC's player's capital, each with Send away and Supply. The engine keeps every stationed army as world state; the buttons
 * send its commands. Each row looks its army up as it renders, with no allocation.
 */
final class MpStationing extends GuiSection {

	private static final int ROWS = 6;
	private static final int LABEL_WIDTH = 260;
	private static final String MINE = "Your armies near their capital";
	private static final String THEIRS = "Their armies stationed at your capital";
	private static final String STATION_D = "Station this army at their capital. It stays a field army beside their city, eats"
			+ " its own supplies and defends their capital with them. Moving it ends the stationing.";
	private static final String SUPPLY_D = "Fill this army's supplies from your own stockpiles, as much as they hold.";
	private static final String SEND_D = "Send this army away: it stays where it stands, stationed no more.";
	private static final String GIVE_D = "Let them command this army's soldiers in their city. You still command them while"
			+ " you view the city.";
	private static final String TAKE_D = "Take back the command of this army's soldiers in their city: only you command them,"
			+ " while you view the city.";

	private final int slot;

	MpStationing(int slot, int width) {
		this.slot = slot;
		body().setWidth(width).setHeight(1);
		addRelBody(8, DIR.S, title(MINE));
		for (int k = 0; k < ROWS; k++)
			addRelBody(4, DIR.S, mine(k));
		addRelBody(16, DIR.S, title(THEIRS));
		for (int k = 0; k < ROWS; k++)
			addRelBody(4, DIR.S, theirs(k));
	}

	private static snake2d.util.gui.renderable.RENDEROBJ title(String t) {
		return new GStat() {
			@Override
			public void update(GText text) {
				text.add(t);
				text.lablify();
			}
		}.r(DIR.W);
	}

	/**
	 * A stationed army with divisions both in the host's city and in the camp (owner, 2026-11-19): Station takes Call
	 * back's place, to bring the camp's divisions back in; Call back waits until every division is out.
	 */
	private static boolean mixed(WArmy a) {
		return PlayerStations.stationed(a) && PlayerStations.inCity(a) > 0 && PlayerStations.inCamp(a) > 0;
	}

	private GuiSection mine(int k) {
		GuiSection row = new GuiSection();
		row.addRightC(0, label(true, k));
		row.addRightC(8, new GButt.ButtPanel("Station") {
			@Override
			protected void clickA() {
				WArmy a = PlayerStations.mineNear(slot, k);
				if (a != null && PlayerStations.stationRefusal(a, slot) == null)
					PlayerStations.station(a, slot);
			}

			@Override
			protected void renAction() {
				WArmy a = PlayerStations.mineNear(slot, k);
				visableSet(a != null && (!PlayerStations.stationed(a) || mixed(a)));
				activeSet(a != null && PlayerStations.stationRefusal(a, slot) == null);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				explain(text, "Station", STATION_D, PlayerStations.stationRefusal(PlayerStations.mineNear(slot, k), slot));
			}
		});
		row.add(new GButt.ButtPanel("Call back") { // in Station's place, while the army is stationed
			@Override
			protected void clickA() {
				WArmy a = PlayerStations.mineNear(slot, k);
				if (a != null && PlayerStations.endRefusal(a) == null)
					PlayerStations.end(a);
			}

			@Override
			protected void renAction() {
				WArmy a = PlayerStations.mineNear(slot, k);
				visableSet(a != null && PlayerStations.stationed(a) && !mixed(a));
				activeSet(a != null && PlayerStations.endRefusal(a) == null);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				explain(text, "Call back", "End this army's stationing: it stays where it stands.",
						PlayerStations.endRefusal(PlayerStations.mineNear(slot, k)));
			}
		}, row.getLastX1(), row.getLastY1());
		row.addRightC(8, new GButt.ButtPanel("Give command") {
			@Override
			protected void clickA() {
				grant(k, true);
			}

			@Override
			protected void renAction() {
				WArmy a = PlayerStations.mineNear(slot, k);
				visableSet(a != null && PlayerStations.stationed(a) && !PlayerStations.granted(a));
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				explain(text, "Give command", GIVE_D, PlayerStations.grantRefusal(PlayerStations.mineNear(slot, k)));
			}
		});
		row.add(new GButt.ButtPanel("Take command back") { // in Give command's place, while they command it
			@Override
			protected void clickA() {
				grant(k, false);
			}

			@Override
			protected void renAction() {
				WArmy a = PlayerStations.mineNear(slot, k);
				visableSet(a != null && PlayerStations.granted(a));
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				explain(text, "Take command back", TAKE_D, PlayerStations.grantRefusal(PlayerStations.mineNear(slot, k)));
			}
		}, row.getLastX1(), row.getLastY1());
		return row;
	}

	private void grant(int k, boolean granted) {
		WArmy a = PlayerStations.mineNear(slot, k);
		if (a != null && PlayerStations.grantRefusal(a) == null && PlayerStations.granted(a) != granted)
			PlayerStations.grant(a, granted);
	}

	private GuiSection theirs(int k) {
		GuiSection row = new GuiSection();
		row.addRightC(0, label(false, k));
		row.addRightC(8, new GButt.ButtPanel("Send away") {
			@Override
			protected void clickA() {
				WArmy a = theirArmy(k);
				if (a != null && PlayerStations.endRefusal(a) == null)
					PlayerStations.end(a);
			}

			@Override
			protected void renAction() {
				visableSet(theirArmy(k) != null);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				explain(text, "Send away", SEND_D, PlayerStations.endRefusal(theirArmy(k)));
			}
		});
		row.addRightC(8, new GButt.ButtPanel("Supply") {
			@Override
			protected void clickA() {
				WArmy a = theirArmy(k);
				if (a != null && PlayerStations.supplyRefusal(a) == null)
					PlayerStations.supply(a);
			}

			@Override
			protected void renAction() {
				WArmy a = theirArmy(k);
				visableSet(a != null);
				activeSet(a != null && PlayerStations.supplyRefusal(a) == null);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				explain(text, "Supply", SUPPLY_D, PlayerStations.supplyRefusal(theirArmy(k)));
			}
		});
		return row;
	}

	private WArmy theirArmy(int k) {
		return PlayerStations.stationedAt(slot, FACTIONS.player().index(), k);
	}

	/** The army's name, men and supplies, in a row's left part; empty when the row has no army. */
	/**
	 * An army's line, in three short lines so it fits beside its buttons (owner, 2026-11-19): its name, its men and
	 * supplies, and who commands its soldiers in the city when the host does.
	 */
	private GuiSection label(boolean mine, int k) {
		GStat name = new GStat() {
			@Override
			public void update(GText text) {
				WArmy a = mine ? PlayerStations.mineNear(slot, k) : theirArmy(k);
				if (a != null)
					text.add(a.name);
			}
		};
		GStat men = new GStat() {
			@Override
			public void update(GText text) {
				WArmy a = mine ? PlayerStations.mineNear(slot, k) : theirArmy(k);
				if (a == null)
					return;
				GFORMAT.i(text, AD.men(null).get(a));
				text.s().add("men,").s();
				GFORMAT.perc(text, AD.supplies().health(a));
				text.s().add("supplied");
			}
		};
		GStat command = new GStat() {
			@Override
			public void update(GText text) {
				WArmy a = mine ? PlayerStations.mineNear(slot, k) : theirArmy(k);
				if (a != null && PlayerStations.granted(a))
					text.add(mine ? "they command it" : "you command it");
			}
		};
		GuiSection l = new GuiSection();
		l.add(name.r(DIR.W));
		l.addDown(0, men.r(DIR.W));
		l.addDown(0, command.r(DIR.W));
		l.body().setWidth(LABEL_WIDTH);
		return l;
	}

	private static void explain(GUI_BOX text, String title, String what, String refusal) {
		GBox b = (GBox) text;
		b.title(title);
		b.text(what);
		if (refusal != null) {
			b.NL(4);
			b.error(refusal);
		}
	}
}
