package menu;

import snake2d.util.gui.GUI_BOX;
import sosdet.BattleKey;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.net.Message;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GBox;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: a battle's restart by vote (spec §8, SOS-231). A player calls it with the battle's own Restart or the panel's
 * Restart battle; the host asks every player with a window, Yes or No, and counts. Passed, the host's engine restarts
 * the battle on every PC that runs it, at one step. The chat tells every player each step. Once per frame from
 * {@link MpPlay}, between steps.
 */
final class MpRestartVote {

	private final SetupSession setup;
	private final EnginePlay engine;
	private final MpNotices notices;
	private final MpLog log;
	/** The window asking this PC's player, while their answer is due; null otherwise. */
	private MpRestartAsk ask;

	MpRestartVote(SetupSession setup, EnginePlay engine, MpNotices notices, MpLog log) {
		this.setup = setup;
		this.engine = engine;
		this.notices = notices;
		this.log = log;
	}

	void frame() {
		if (engine.takeRestartAsked())
			call(setup, engine);
		if (setup.isHost() && setup.restartVotes().open() != null) // a battle that ended calls its vote off
			setup.checkRestart(engine.restartRefusal(setup.restartVotes().open().battle()));
		Message.RestartVote v;
		while ((v = setup.restartVotes().take()) != null)
			heard(v);
		if (ask != null && ask.expired()) { // no answer in time counts as No: the host's result closes it, or this
			ask.close();
			ask = null;
		}
	}

	/** This PC's player asks for a vote on the battle they see. */
	static void call(SetupSession setup, EnginePlay engine) {
		long battle = engine.battleHere();
		if (battle == BattleKey.NONE) {
			setup.chat().notice("There is no battle here to restart.");
			return;
		}
		setup.callRestart(battle);
	}

	/** Restart battle, beside Leave on the multiplayer panel: shown while this PC's player sees a battle. */
	static GButt.ButtPanel button(SetupSession setup, EnginePlay engine) {
		return new GButt.ButtPanel("Restart battle") {
			@Override
			public void hoverInfoGet(GUI_BOX text) {
				((GBox) text).text("Ask every player to restart this battle from its start. More than half must say Yes within 30"
						+ " seconds.");
			}

			@Override
			protected void clickA() {
				call(setup, engine);
			}
		};
	}

	private void heard(Message.RestartVote v) {
		String what = name(v.battle());
		String caller = notices.name(slotOf(v.callerId()));
		switch (v.state()) {
			case Message.RestartVote.OPEN -> {
				log.line("restart vote " + v.vote() + ": " + caller + " calls it for " + what + " (" + v.voters() + " players vote)");
				setup.chat().notice(caller + " calls a vote to restart " + what + ".");
				if (v.callerId() != setup.localPlayerId()) {
					closeAsk();
					ask = new MpRestartAsk(this, v.vote(), caller + " calls a vote to restart " + what + ". Restart it?");
					ask.open();
				}
			}
			case Message.RestartVote.PASSED -> {
				ended(v);
				setup.chat().notice("The vote passed, " + v.yes() + " of " + v.voters() + ": " + what + " restarts.");
				if (setup.isHost())
					restart(v.battle(), what);
			}
			case Message.RestartVote.FAILED -> {
				ended(v);
				setup.chat().notice("The vote to restart " + what + " failed: " + v.yes() + " of " + v.voters() + " said Yes.");
			}
			case Message.RestartVote.CANCELLED -> {
				ended(v);
				setup.chat().notice("The vote to restart " + what + " was called off. " + v.reason());
			}
			default -> setup.chat().notice("No restart vote: " + v.reason()); // REFUSED, for the caller only
		}
	}

	private void ended(Message.RestartVote v) {
		log.line("restart vote " + v.vote() + " over: state " + v.state() + ", " + v.yes() + " of " + v.voters() + " for it"
				+ (v.reason().isEmpty() ? "" : ", " + v.reason()));
		closeAsk();
	}

	/** Host: the vote passed; the engine's log restarts the battle at one step on every PC that runs it. */
	private void restart(long battle, String what) {
		String why = engine.restartRefusal(battle);
		if (why != null) {
			log.line("restart vote passed, but " + what + " can't restart: " + why);
			setup.chat().notice(what + " can't restart now. " + why);
			return;
		}
		engine.restartBattle(battle);
		log.line("restart of " + what + " logged at step " + engine.step());
	}

	/** This PC's player answered in the window. */
	void answer(int vote, boolean yes) {
		setup.answerRestart(vote, yes);
		log.line("restart vote " + vote + ": answered " + (yes ? "Yes" : "No"));
		closeAsk();
	}

	private void closeAsk() {
		if (ask != null) {
			ask.close();
			ask = null;
		}
	}

	/** The battle {@code key} in words: whose, and against whom. */
	private String name(long key) {
		BattleKey k = BattleKey.of(key);
		return notices.battle(k.kind, k.human);
	}

	private int slotOf(int playerId) {
		return setup.factions().slotOf(playerId);
	}
}
