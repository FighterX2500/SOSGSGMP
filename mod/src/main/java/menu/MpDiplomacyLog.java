package menu;

import java.util.ArrayList;
import java.util.List;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import sosdet.game.deal.DealBook;
import sosgsgmp.game.PlayerDeals;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the playtest log's diplomacy and trade lines (owner, 2026-10-27). It runs between steps, a few times a
 * second from {@link MpLog}, and only reads. It writes a line for each proposal when it opens and when it is answered,
 * for each change of a treaty or tariff between two players, and for each change of a player's region count or
 * overlord. The first look writes the state as it stands, so a log that starts at a resume has its baseline.
 */
final class MpDiplomacyLog {

	private final SetupSession setup;
	private final List<Integer> seenOpen = new ArrayList<>();
	private int lastAnswerId;
	private final List<String> pairs = new ArrayList<>();
	private final List<String> realms = new ArrayList<>();
	private boolean first = true;

	MpDiplomacyLog(SetupSession setup) {
		this.setup = setup;
	}

	/** Looks again; each new or changed fact is one line through {@code out}. */
	void look(MpLog out) {
		int[] slots = slots();
		proposals(out);
		answers(out);
		List<String> nowPairs = new ArrayList<>();
		for (int i = 0; i < slots.length; i++)
			for (int j = i + 1; j < slots.length; j++)
				nowPairs.add(pair(slots[i], slots[j]));
		List<String> nowRealms = new ArrayList<>();
		for (int s : slots)
			nowRealms.add(realm(s));
		if (first) {
			first = false;
			out.line("diplomacy now: " + String.join("; ", nowPairs) + " | " + String.join("; ", nowRealms));
		} else {
			changed(out, "treaty", pairs, nowPairs);
			changed(out, "realm", realms, nowRealms);
		}
		pairs.clear();
		pairs.addAll(nowPairs);
		realms.clear();
		realms.addAll(nowRealms);
	}

	private void proposals(MpLog out) {
		List<Integer> now = new ArrayList<>();
		for (DealBook.Proposal p : PlayerDeals.open()) {
			now.add(p.id());
			if (!seenOpen.contains(p.id()))
				out.line("proposal " + p.id() + " from " + name(p.from()) + " to " + name(p.to()) + ": " + terms(p));
		}
		seenOpen.clear();
		seenOpen.addAll(now);
	}

	private void answers(MpLog out) {
		int last = lastAnswerId;
		for (DealBook.Answer a : PlayerDeals.answers()) {
			if (a.id() <= lastAnswerId)
				continue;
			if (!first) // a resume's old answers are history, not news
				out.line("proposal " + a.id() + " from " + name(a.from()) + " to " + name(a.to()) + ": " + a.outcome());
			last = Math.max(last, a.id());
		}
		lastAnswerId = last;
	}

	private static String terms(DealBook.Proposal p) {
		DealBook.Terms t = p.terms();
		StringBuilder b = new StringBuilder();
		b.append(t.treaty == DealBook.NO_TREATY ? "no treaty" : MpDeals.treatyName(t.treaty));
		if (t.vassalage())
			b.append(", tribute ").append(t.tributePercent).append('%');
		b.append("; proposer gives ").append(MpDeals.gives(t.fromCredits, t.fromGoods()));
		if (t.fromRegions().length > 0)
			b.append(" and ").append(MpDeals.regions(t.fromRegions()));
		b.append("; receiver gives ").append(MpDeals.gives(t.toCredits, t.toGoods()));
		if (t.toRegions().length > 0)
			b.append(" and ").append(MpDeals.regions(t.toRegions()));
		b.append("; expires at second ").append(p.expires());
		return b.toString();
	}

	private String pair(int a, int b) {
		Faction fa = FACTIONS.getByIndex(a);
		Faction fb = FACTIONS.getByIndex(b);
		return name(a) + "-" + name(b) + " " + DIP.get(fa, fb).key() + ", tariffs " + PlayerDeals.tariff(a, b) + "%/"
				+ PlayerDeals.tariff(b, a) + "%";
	}

	private String realm(int slot) {
		Faction f = FACTIONS.getByIndex(slot);
		Faction lord = DIP.overlord(f);
		return name(slot) + " " + f.realm().regions() + " regions"
				+ (lord == null ? "" : ", vassal of " + name(lord.index()) + " at " + PlayerDeals.tributePercent(slot, lord.index()) + "%");
	}

	private static void changed(MpLog out, String what, List<String> before, List<String> now) {
		for (int i = 0; i < now.size(); i++)
			if (i >= before.size() || !now.get(i).equals(before.get(i)))
				out.line(what + ": " + now.get(i) + (i < before.size() ? " (was " + before.get(i) + ")" : ""));
	}

	private int[] slots() {
		List<Integer> l = new ArrayList<>();
		for (LobbyPlayer p : setup.players()) {
			int s = setup.factions().slotOf(p.id());
			if (s >= 0) // a player without a slot in this world
				l.add(s);
		}
		int[] s = new int[l.size()];
		for (int i = 0; i < s.length; i++)
			s[i] = l.get(i);
		return s;
	}

	private String name(int slot) {
		for (LobbyPlayer p : setup.players())
			if (setup.factions().slotOf(p.id()) == slot)
				return p.name();
		return "slot " + slot;
	}
}
