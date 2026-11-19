package menu;

import java.util.ArrayList;
import java.util.List;

import game.faction.FACTIONS;
import game.faction.Faction;
import init.trade.TR;
import snake2d.LOG;
import sosdet.game.deal.DealBook;
import sosgsgmp.game.PlayerDeals;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.DealInbox;
import sosgsgmp.session.SetupSession;
import world.WORLD;
import world.map.regions.Region;

/**
 * SOSGSGMP: deals between players in the running game (spec §6 Diplomacy, §12 popups; SOS-72, SOS-76). The engine
 * keeps the proposals and answers as world state; this PC sends each proposal made to its player to the message folder
 * as vanilla's deal message ({@link MpDealMessage}, owner 2026-11-19), where it is answered, and keeps the latest answer
 * to its own proposals for the session panel. From the panel's refresh, outside the step.
 */
final class MpDeals {

	private final SetupSession setup;
	private final DealInbox inbox;
	private final int me;
	private String lastAnswer = "";

	MpDeals(SetupSession setup) {
		this.setup = setup;
		this.me = setup.factions().slotOf(setup.localPlayerId());
		this.inbox = new DealInbox(me);
	}

	/**
	 * While playing, every refresh of the session panel: news of answers, and the next proposal's message. The message
	 * goes into the city this PC runs, so it waits while {@code home} is false: a view or a battle has another game
	 * loaded.
	 */
	void refresh(boolean home) {
		List<DealInbox.Entry> answers = new ArrayList<>();
		for (DealBook.Answer a : PlayerDeals.answers())
			answers.add(new DealInbox.Entry(a.id(), a.from(), a.to()));
		for (DealInbox.Entry e : inbox.newAnswers(answers))
			answered(e.id());
		if (!home)
			return;
		List<DealInbox.Entry> open = new ArrayList<>();
		for (DealBook.Proposal p : PlayerDeals.open())
			open.add(new DealInbox.Entry(p.id(), p.from(), p.to()));
		DealInbox.Entry next = inbox.nextProposal(open, answers);
		if (next == null)
			return;
		DealBook.Proposal p = PlayerDeals.proposal(next.id());
		if (p == null)
			return;
		inbox.shown(next.id());
		Faction from = FACTIONS.getByIndex(next.from());
		DealBook.Terms t = p.terms();
		new MpDealMessage(p.id(), next.from(), from.name.toString(), name(next.from()),
				lines(t, t.fromCredits, t.fromGoods(), t.fromRegions()), lines(t, t.toCredits, t.toGoods(), t.toRegions())).send();
	}

	/** One side's part of a deal, a line each, as vanilla's deal list: the treaty, the money, each good, each region. */
	private static String[] lines(DealBook.Terms t, int credits, int[] goods, int[] regions) {
		List<String> l = new ArrayList<>();
		if (t.treaty != DealBook.NO_TREATY)
			l.add(treatyLine(t));
		if (credits > 0)
			l.add(credits + " denari");
		for (int i = 0; i < goods.length; i += 2)
			l.add(goods[i + 1] + " " + TR.ALL().get(goods[i]).name);
		for (int r : regions)
			l.add("Region " + regions(new int[] { r }));
		if (l.isEmpty())
			l.add("Nothing");
		return l.toArray(new String[0]);
	}

	/** The treaty, as the receiver reads it. */
	private static String treatyLine(DealBook.Terms t) {
		return switch (t.treaty) {
			case DealBook.PROTECTOR -> "We become your overlord, with a tribute of " + t.tributePercent + "% a year";
			case DealBook.VASSAL -> "We become your vassal, with a tribute of " + t.tributePercent + "% a year";
			default -> {
				String n = treatyName(t.treaty);
				yield Character.toUpperCase(n.charAt(0)) + n.substring(1);
			}
		};
	}

	private void answered(int id) {
		for (DealBook.Answer a : PlayerDeals.answers()) {
			if (a.id() != id)
				continue;
			String who = name(a.to());
			lastAnswer = switch (a.outcome()) {
				case ACCEPTED -> who + " accepted your deal.";
				case DECLINED -> who + " declined your deal.";
				case EXPIRED -> "Your deal to " + who + " expired unanswered.";
				case REFUSED -> "Your deal to " + who + " no longer held when they accepted it, so nothing changed.";
				case DROPPED -> "Your deal to " + who + " was dropped: they left the game.";
			};
			LOG.ln("SOSGSGMP: deal " + id + ": " + lastAnswer);
			setup.chat().notice(lastAnswer);
		}
	}

	/** The latest answer to this player's own proposals, as a sentence; empty before the first. */
	String lastAnswer() {
		return lastAnswer;
	}

	/** The name of the player in {@code slot}. */
	String name(int slot) {
		for (LobbyPlayer p : setup.players())
			if (setup.factions().slotOf(p.id()) == slot)
				return p.name();
		return "slot " + slot;
	}

	static String treatyName(int treaty) {
		return switch (treaty) {
			case DealBook.NO_TREATY -> "no treaty";
			case DealBook.PEACE -> "peace";
			case DealBook.TRADE -> "trade";
			case DealBook.PACT -> "pact";
			case DealBook.ALLY -> "alliance";
			case DealBook.CANCEL_ALL -> "cancel all treaties";
			case DealBook.PROTECTOR -> "the proposer becomes overlord";
			case DealBook.VASSAL -> "the proposer becomes vassal";
			default -> throw new IllegalArgumentException("SOSGSGMP: a treaty term of " + treaty);
		};
	}

	/** The regions one side gives, as words: "Aria and Bel", or "no regions" (SOS-73). */
	static String regions(int[] regions) {
		if (regions.length == 0)
			return "no regions";
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < regions.length; i++) {
			if (i > 0)
				b.append(i == regions.length - 1 ? " and " : ", ");
			Region r = WORLD.REGIONS().getByIndex(regions[i]);
			b.append(r == null ? "region " + regions[i] : r.info.name());
		}
		return b.toString();
	}

	/** What one side gives, as words: "100 denari and 50 Stone", or "nothing". */
	static String gives(int credits, int[] goods) {
		StringBuilder b = new StringBuilder();
		if (credits > 0)
			b.append(credits).append(" denari");
		for (int i = 0; i < goods.length; i += 2) {
			if (b.length() > 0)
				b.append(" and ");
			b.append(goods[i + 1]).append(' ').append(TR.ALL().get(goods[i]).name);
		}
		return b.length() == 0 ? "nothing" : b.toString();
	}
}
