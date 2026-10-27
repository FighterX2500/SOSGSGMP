package menu;

import java.util.ArrayList;
import java.util.List;

import game.faction.FACTIONS;
import game.faction.FCredits.CTYPE;
import init.trade.TR;
import init.trade.TRADE_TYPE;
import sosdet.txn.TransferLog;
import sosgsgmp.game.PlayerDeals;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the playtest log's goods and money lines (SOS-81). It runs between steps, a few times a second from
 * {@link MpLog}, and only reads the engine's record of transfers. Every PC records every player's transfers alike, so
 * the logs match line for line. With the lobby's full transfer log on, it writes every transfer, NPC trade included;
 * off, only those between players: deals, sales between players, the tariff cut and tribute.
 */
final class MpTransferLog {

	private static final CTYPE[] CTYPES = CTYPE.values();

	private final SetupSession setup;
	private final List<TransferLog.Transfer> read = new ArrayList<>();
	private long lastSeq = -1;

	MpTransferLog(SetupSession setup) {
		this.setup = setup;
	}

	/** Looks again; each new transfer is one line through {@code out}. */
	void look(MpLog out) {
		if (lastSeq < 0) { // the record holds what came before this log; it starts from here
			lastSeq = PlayerDeals.lastTransfer();
			out.line("transfers logged: " + (setup.fullTransferLog() ? "every transfer" : "between players only"));
			return;
		}
		read.clear();
		long lost = PlayerDeals.transfers(lastSeq, read);
		lastSeq = PlayerDeals.lastTransfer();
		if (lost > 0)
			out.line("transfers: " + lost + " not logged, the record wrapped");
		for (TransferLog.Transfer t : read)
			if (setup.fullTransferLog() || betweenPlayers(t))
				out.line(line(t));
	}

	private boolean betweenPlayers(TransferLog.Transfer t) {
		switch (t.kind()) {
		case BOUGHT:
		case SOLD:
			return player(t.other()) || t.reason() == TRADE_TYPE.diplomacy.index;
		case DELIVERED:
			return t.reason() == TRADE_TYPE.diplomacy.index;
		case MONEY: // a plain trade credit is the tariff cut; market sales come as money for goods
			return t.reason() == CTYPE.DIPLOMACY.ordinal() || t.reason() == CTYPE.TRIBUTE.ordinal()
					|| t.reason() == CTYPE.TRADE.ordinal();
		case MONEY_FOR_GOODS:
			return t.reason() == CTYPE.DIPLOMACY.ordinal();
		default:
			throw new IllegalStateException("SOSGSGMP: unknown transfer kind " + t.kind());
		}
	}

	private String line(TransferLog.Transfer t) {
		StringBuilder b = new StringBuilder("transfer step ").append(t.step()).append(": ").append(name(t.human())).append(' ');
		switch (t.kind()) {
		case BOUGHT:
			b.append("bought ").append(goods(t)).append(" from ").append(name(t.other())).append(" for ").append(t.price())
					.append(" denari");
			break;
		case DELIVERED:
			b.append("received ").append(goods(t));
			break;
		case SOLD:
			b.append("sold ").append(goods(t)).append(" to ").append(name(t.other())).append(" for ").append(t.price())
					.append(" denari");
			break;
		case MONEY:
			b.append(t.amount() < 0 ? "paid " : "got ").append(Math.abs(Math.round(t.amount()))).append(" denari");
			break;
		case MONEY_FOR_GOODS:
			b.append(t.amount() < 0 ? "paid " : "got ").append(Math.abs(Math.round(t.amount()))).append(" denari for ")
					.append(t.price()).append(' ').append(TR.ALL().get(t.goods()).name);
			break;
		default:
			throw new IllegalStateException("SOSGSGMP: unknown transfer kind " + t.kind());
		}
		b.append(" (").append(reason(t)).append(", txn ").append(t.txnId()).append(')');
		return b.toString();
	}

	private static String goods(TransferLog.Transfer t) {
		return Math.round(t.amount()) + " " + TR.ALL().get(t.goods()).name;
	}

	private static String reason(TransferLog.Transfer t) {
		boolean credits = t.kind() == TransferLog.Kind.MONEY || t.kind() == TransferLog.Kind.MONEY_FOR_GOODS;
		return String.valueOf(credits ? CTYPES[t.reason()].name : TRADE_TYPE.all.get(t.reason()).name);
	}

	private boolean player(int slot) {
		for (LobbyPlayer p : setup.players())
			if (setup.factions().slotOf(p.id()) == slot)
				return true;
		return false;
	}

	private String name(int slot) {
		if (slot < 0)
			return "the market";
		for (LobbyPlayer p : setup.players())
			if (setup.factions().slotOf(p.id()) == slot)
				return p.name();
		return FACTIONS.getByIndex(slot).name.toString();
	}
}
