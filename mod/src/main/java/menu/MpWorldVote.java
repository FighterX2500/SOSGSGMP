package menu;

import snake2d.util.gui.GUI_BOX;
import snake2d.util.misc.ACTION;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.net.Message;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * SOSGSGMP: the host's vote for a new world during the setup (spec §5, owner 2026-11-26). The host presses Regenerate
 * on Place Capital or Place your city-state and confirms; every other player is asked Yes or No; more than half of all
 * players, the host counted as Yes, sends every PC back to the map step, where the host generates a new world. A tie or
 * 30 seconds without a majority keeps this one. The session counts the votes ({@code RestartVotes}, the battle restart's
 * machinery); this class asks, tells the chat, and counts the time on the host. Once per frame from the setup's screens.
 */
public final class MpWorldVote {

	private static final String HOVER = "Ask every player to go back to the map step for a new world. More than half must say"
			+ " Yes within 30 seconds; a tie keeps this world. Races and factions stay; every location and Ready are cleared.";

	private final SetupSession setup;
	/** The window asking this PC's player, while their answer is due; null otherwise. */
	private MpRestartAsk ask;

	public MpWorldVote(SetupSession setup) {
		this.setup = setup;
	}

	public void frame() {
		if (setup.closed())
			return;
		if (setup.isHost() && setup.restartVotes().open() != null)
			setup.checkRestart(null); // the time and the players who left
		Message.RestartVote v;
		while ((v = setup.restartVotes().take()) != null)
			heard(v);
		if (ask != null && ask.expired()) { // no answer in time counts as No
			ask.close();
			ask = null;
		}
	}

	/** The host's Regenerate: asks Confirm or Cancel first, then calls the vote. */
	public GButt.ButtPanel button() {
		return new GButt.ButtPanel("regenerate") {
			@Override
			public void hoverInfoGet(GUI_BOX text) {
				((GBox) text).text(HOVER);
			}

			@Override
			protected void clickA() {
				VIEW.inters().yesNo.activate("Propose a new world to every player? If the vote passes, everyone goes back to the"
						+ " map step and every location and Ready are cleared.",
						() -> setup.callRestart(Message.RestartCall.NEW_WORLD), ACTION.NOP, true);
			}

			@Override
			protected void renAction() {
				activeSet(!setup.closed() && setup.restartVotes().open() == null);
			}
		};
	}

	private void heard(Message.RestartVote v) {
		if (v.battle() != Message.RestartCall.NEW_WORLD)
			return; // no battle runs during the setup
		switch (v.state()) {
			case Message.RestartVote.OPEN -> {
				setup.chat().notice(name(v.callerId()) + " proposes a new world: back to the map step.");
				if (v.callerId() != setup.localPlayerId()) {
					closeAsk();
					ask = new MpRestartAsk("A new world?", v.vote(), name(v.callerId()) + " proposes a new world. Everyone goes back"
							+ " to the map step, where the host generates a new one; races and factions stay, every location and"
							+ " Ready are cleared. Go back?", this::answer);
					ask.open();
				}
			}
			case Message.RestartVote.PASSED -> {
				closeAsk();
				setup.chat().notice("The vote passed, " + v.yes() + " of " + v.voters() + ": back to the map step for a new world.");
			}
			case Message.RestartVote.FAILED -> {
				closeAsk();
				setup.chat().notice("No new world: " + v.yes() + " of " + v.voters() + " said Yes.");
			}
			case Message.RestartVote.CANCELLED -> {
				closeAsk();
				setup.chat().notice("The vote for a new world was called off. " + v.reason());
			}
			default -> setup.chat().notice("No vote: " + v.reason()); // REFUSED, for the caller only
		}
	}

	private void answer(int vote, boolean yes) {
		setup.answerRestart(vote, yes);
		closeAsk();
	}

	private void closeAsk() {
		if (ask != null) {
			ask.close();
			ask = null;
		}
	}

	private String name(int playerId) {
		for (LobbyPlayer p : setup.players())
			if (p.id() == playerId)
				return p.name();
		return "A player";
	}
}
