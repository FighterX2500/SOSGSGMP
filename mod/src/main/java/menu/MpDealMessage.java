package menu;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.player.Player;
import init.sprite.UI.UI;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.LinkedList;
import sosgsgmp.game.PlayerDeals;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GHeader;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GScrollRows;
import util.text.Dic;
import view.ui.message.MessageSection;

/**
 * SOSGSGMP (owner, 2026-11-19): a deal from another player, as vanilla's deal message ({@code UIDipMessDeal}): it opens
 * at once and stays in the message folder. At the top the proposer's banner, then their faction's name and "Player
 * <name>"; what each side gives; Accept and Decline while the proposal is open, then what became of it.
 * <p>
 * Vanilla keeps its messages in the city save through the game's own Java serialization, and loads them so that one
 * whose class is missing or changed is dropped, the save loading on (the owner approved this exception to AGENTS.md's
 * rule, 2026-11-19). So this class keeps plain values only and finds the live proposal by its id.
 */
final class MpDealMessage extends MessageSection {

	private static final long serialVersionUID = 1L;
	private static final int LIST_WIDTH = 400;
	private static final int LIST_HEIGHT = 250;
	private static final int ROW_HEIGHT = 32;
	private static final byte OPEN = 0;
	private static final byte ACCEPTED = 1;
	private static final byte DECLINED = -1;
	/** How often the window asks the engine about the proposal again: its buttons and line ask every frame. */
	private static final long CHECK_NANOS = 500_000_000L;

	private final int id;
	private final int from;
	private final String factionName;
	private final String playerName;
	private final String[] theyGive;
	private final String[] youGive;
	/** Each line's icon, as {@link MpDeals#icon} reads it; null in a message from before the icons. */
	private final int[] theyIcons;
	private final int[] youIcons;
	private byte answer = OPEN;
	private transient boolean checked;
	private transient long checkedAt;
	private transient CharSequence over;
	private transient String refusal;

	MpDealMessage(int id, int from, String factionName, String playerName, String[] theyGive, int[] theyIcons, String[] youGive,
			int[] youIcons) {
		super("Deal from " + factionName);
		this.id = id;
		this.from = from;
		this.factionName = factionName;
		this.playerName = playerName;
		this.theyGive = theyGive.clone();
		this.youGive = youGive.clone();
		this.theyIcons = theyIcons.clone();
		this.youIcons = youIcons.clone();
	}

	@Override
	protected void make(GuiSection section) {
		Faction f = FACTIONS.getByIndex(from);
		if (f instanceof Player)
			section.add(f.banner().HUGE, 0, 0);
		section.addRelBody(8, DIR.S, new GText(UI.FONT().H2, factionName).lablify());
		section.addRelBody(4, DIR.S, new GText(UI.FONT().M, "Player " + playerName).color(COLOR.WHITE85));
		section.addRelBody(16, DIR.S, list());
		section.addRelBody(16, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				CharSequence over = over();
				if (over != null)
					text.warnify().add(over);
			}
		}.r(DIR.N));
		GuiSection buttons = new GuiSection();
		buttons.addRightC(0, new GButt.ButtPanel(Dic.¤¤Accept) {
			@Override
			protected void renAction() {
				activeSet(over() == null && refusal() == null);
			}

			@Override
			protected void clickA() {
				checked = false;
				if (over() != null || refusal() != null)
					return;
				PlayerDeals.answer(id, true);
				answer = ACCEPTED;
				checked = false;
				close();
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				String why = over() == null ? refusal() : null;
				if (why != null)
					((GBox) text).error(why);
			}
		});
		buttons.addRightC(0, new GButt.ButtPanel(Dic.¤¤Decline) {
			@Override
			protected void renAction() {
				activeSet(over() == null);
			}

			@Override
			protected void clickA() {
				checked = false;
				if (over() != null)
					return;
				PlayerDeals.answer(id, false);
				answer = DECLINED;
				checked = false;
				close();
			}
		});
		section.addRelBody(8, DIR.S, buttons);
	}

	/** "We give you" and "You give us", as vanilla's deal list. */
	private RENDEROBJ list() {
		LinkedList<RENDEROBJ> rows = new LinkedList<>();
		rows.add(row(new GHeader("We give you")));
		for (int i = 0; i < theyGive.length; i++)
			rows.add(row(line(theyGive[i], theyIcons == null ? 0 : theyIcons[i])));
		rows.add(row(new GHeader("You give us")));
		for (int i = 0; i < youGive.length; i++)
			rows.add(row(line(youGive[i], youIcons == null ? 0 : youIcons[i])));
		return new GScrollRows(rows, LIST_HEIGHT).view();
	}

	/** A line of the list: its icon, as vanilla's deal list has, then its words. */
	private static RENDEROBJ line(String words, int icon) {
		GuiSection s = new GuiSection();
		snake2d.util.sprite.SPRITE sprite = MpDeals.icon(icon);
		if (sprite != null)
			s.add(sprite, 0, 0);
		s.addRightC(sprite == null ? 0 : 8, new GText(UI.FONT().M, words));
		return s;
	}

	private static RENDEROBJ row(RENDEROBJ o) {
		GuiSection s = new GuiSection();
		s.add(new RENDEROBJ.RenderDummy(LIST_WIDTH, ROW_HEIGHT), 0, 0);
		o.body().moveX1(s.body().x1()).moveCY(s.body().cY());
		s.add(o);
		return s;
	}

	/** Why the proposal can't be answered any more, or null while it is open. */
	private CharSequence over() {
		check();
		return over;
	}

	/** Why this player can't accept the open proposal now, or null. */
	private String refusal() {
		check();
		return refusal;
	}

	private void check() {
		long now = System.nanoTime();
		if (checked && now - checkedAt < CHECK_NANOS)
			return;
		checked = true;
		checkedAt = now;
		if (answer == ACCEPTED)
			over = "You have accepted this offer.";
		else if (answer == DECLINED)
			over = "You have declined this offer.";
		else if (PlayerDeals.proposal(id) == null)
			over = "This offer is no longer valid.";
		else
			over = null;
		refusal = over == null ? PlayerDeals.acceptRefusal(id) : null;
	}
}
