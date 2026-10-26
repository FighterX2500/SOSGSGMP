package menu;

import java.util.ArrayList;
import java.util.List;

import init.trade.TR;
import snake2d.LOG;
import sosdet.game.deal.DealBook;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.DealInbox;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: deals between players in the running game (spec §6 Diplomacy, §12 popups; SOS-72). The engine keeps the
 * proposals and answers as world state; this PC shows a popup for each proposal made to its player
 * ({@link MpDealPopup}), opens the offer window for another player ({@link MpDealOffer}), and keeps the latest answer to
 * its own proposals for the session panel. From the panel's refresh, outside the step.
 */
final class MpDeals {

	private final SetupSession setup;
	private final EnginePlay engine;
	private final DealInbox inbox;
	private final int me;
	private MpDealPopup popup;
	private String lastAnswer = "";

	MpDeals(SetupSession setup, EnginePlay engine) {
		this.setup = setup;
		this.engine = engine;
		this.me = setup.factions().slotOf(setup.localPlayerId());
		this.inbox = new DealInbox(me);
	}

	/** While playing, every refresh of the session panel: news of answers, and the next proposal's popup. */
	void refresh() {
		List<DealInbox.Entry> answers = new ArrayList<>();
		for (DealBook.Answer a : engine.dealAnswers())
			answers.add(new DealInbox.Entry(a.id(), a.from(), a.to()));
		for (DealInbox.Entry e : inbox.newAnswers(answers))
			answered(e.id());
		if (popup != null && popup.isActivated()) {
			popup.refresh();
			return;
		}
		List<DealInbox.Entry> open = new ArrayList<>();
		for (DealBook.Proposal p : engine.deals())
			open.add(new DealInbox.Entry(p.id(), p.from(), p.to()));
		DealInbox.Entry next = inbox.nextProposal(open, answers);
		if (next == null)
			return;
		inbox.shown(next.id());
		popup = new MpDealPopup(this, proposal(next.id()));
		popup.open();
	}

	private void answered(int id) {
		for (DealBook.Answer a : engine.dealAnswers()) {
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
		}
	}

	/** The latest answer to this player's own proposals, as a sentence; empty before the first. */
	String lastAnswer() {
		return lastAnswer;
	}

	/** Opens the offer window for the player in {@code slot}. */
	void offer(int slot) {
		new MpDealOffer(this, slot).open();
	}

	EnginePlay engine() {
		return engine;
	}

	/** The open proposal {@code id}, or null when it closed. */
	DealBook.Proposal proposal(int id) {
		for (DealBook.Proposal p : engine.deals())
			if (p.id() == id)
				return p;
		return null;
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
			default -> throw new IllegalArgumentException("SOSGSGMP: a treaty term of " + treaty);
		};
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

	/** A proposal as this PC's player reads it. */
	String describe(DealBook.Proposal p) {
		DealBook.Terms t = p.terms();
		String from = name(p.from());
		return from + " proposes a deal" + (t.treaty == DealBook.NO_TREATY ? "" : ": " + treatyName(t.treaty)) + ".\n"
				+ from + " gives " + gives(t.fromCredits, t.fromGoods()) + ".\nYou give " + gives(t.toCredits, t.toGoods())
				+ ".\nIt expires unanswered after 30 days.";
	}
}
