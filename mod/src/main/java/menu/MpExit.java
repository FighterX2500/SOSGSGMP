package menu;

import java.util.ArrayList;
import java.util.List;

import game.GAME;
import snake2d.CORE;
import snake2d.util.color.COLOR;
import snake2d.util.misc.ACTION;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * SOSGSGMP: the escape menu's Save Campaign, Quit to menu and Exit (spec §11 Saving, SOS-79; docs/plan/escape-save.md),
 * and every save that ends the session: a player's quit and a desync's Save and exit (SOS-68). A quit saves the
 * campaign on every PC first: the host orders the save, and a joining PC asks the host for it. While no save can start
 * (a battle), the player can leave without one instead. Once the save is done, the session ends on every PC; the player
 * who quit goes to the main menu or closes the game, the others see why it ended. Once per frame from {@link MpPlay},
 * between steps.
 *
 * <p>Leave and freeze (spec §11 Freezing a player, SOS-194): a joining player asks the host, the host saves the campaign
 * on every PC with them as its quitter, and once it is done their PC leaves from the save's step while the others play
 * on; {@link MpGone} freezes their human on the host.
 */
final class MpExit implements MpEscapeMenu.Actions {

	private final SetupSession setup;
	private final EnginePlay engine;
	private final WorldSetupAdapter game;
	private final MpSaves saves;
	private final MpLog log;
	/** This PC's player chose to quit, and the save that ends the session is asked for or under way. */
	private boolean quitting;
	/** What this PC does once its player's quit ends the session: close the game, or go to the main menu. */
	private boolean exitGame;
	/** Host: the player whose quit waits for a save to start; NO_QUITTER when none. */
	private int pendingQuitter = SaveOrder.NO_QUITTER;
	/** After a save that ended the session: its outcome, empty when every PC saved; null before. */
	private String outcome;
	/** Host: the players who asked to leave frozen, whose save waits to start (SOS-194). */
	private final List<Integer> pendingFreezers = new ArrayList<>();
	/** A joining PC: its player chose Leave and freeze, and the host is asked. */
	private boolean freezeAsked;
	/** A joining PC that left frozen: nothing more happens here. */
	private boolean leftFrozen;

	MpExit(SetupSession setup, EnginePlay engine, WorldSetupAdapter game, MpSaves saves, MpLog log) {
		this.setup = setup;
		this.engine = engine;
		this.game = game;
		this.saves = saves;
		this.log = log;
	}

	/** Once per frame while playing, even after the session closed: a save that ends it ends it on every PC. */
	void frame() {
		if (outcome != null || leftFrozen)
			return;
		if (saves.exited() != null) {
			end(saves.exited(), saves.exitedQuitter());
			return;
		}
		if (saves.frozeHere() != null) {
			leaveFrozen(saves.frozeHere());
			return;
		}
		if (setup.closed())
			return;
		if (setup.isHost()) {
			int asked = setup.takeQuitAsk();
			if (asked >= 0 && pendingQuitter == SaveOrder.NO_QUITTER && !saves.exiting()) {
				pendingQuitter = asked;
				log.line(name(asked) + " asked to quit: the campaign is saved, then the session ends");
			}
			if (pendingQuitter != SaveOrder.NO_QUITTER && saves.canSaveToExit()) {
				saves.saveAndExit(pendingQuitter);
				pendingQuitter = SaveOrder.NO_QUITTER;
			}
			freezeLeavers();
		}
		if ((saves.exiting() || quitting) && engine.desyncHold() == null && !VIEW.inters().fullScreen.isActivated())
			VIEW.inters().fullScreen.activate(saves.exiting() ? "Saving the campaign. The session ends once every player has saved."
					: "Waiting for the host to save the campaign. The session ends once every player has saved.", COLOR.WHITE100, null);
		if (freezeAsked && engine.desyncHold() == null && !VIEW.inters().fullScreen.isActivated())
			VIEW.inters().fullScreen.activate(saves.freezing() ? "Saving the campaign. You leave once every player has saved."
					: "Waiting for the host to save the campaign. You leave once every player has saved.", COLOR.WHITE100, null);
	}

	/**
	 * Host: each player who asked to leave frozen gets their save as soon as one can start and the engine would freeze
	 * them; one who left meanwhile is in the disconnect window instead.
	 */
	private void freezeLeavers() {
		for (int id = setup.takeFreezeAsk(); id >= 0; id = setup.takeFreezeAsk()) {
			pendingFreezers.add(id);
			log.line(name(id) + " asked to leave frozen: the campaign is saved, then they leave");
		}
		if (pendingFreezers.isEmpty() || pendingQuitter != SaveOrder.NO_QUITTER || saves.saving() || saves.exiting())
			return;
		int id = pendingFreezers.get(0);
		if (!present(id)) {
			pendingFreezers.remove(0);
			log.line(name(id) + " left before their Leave and freeze save");
			return;
		}
		int slot = setup.factions().slotOf(id);
		if (engine.freezeRefusal(slot) == null && saves.canSaveToExit()) {
			pendingFreezers.remove(0);
			saves.saveAndFreeze(id);
		}
	}

	private boolean present(int playerId) {
		for (LobbyPlayer p : setup.players())
			if (p.id() == playerId)
				return true;
		return false;
	}

	@Override
	public boolean freezeOffered() {
		return !setup.isHost();
	}

	@Override
	public CharSequence freezeRefusal() {
		if (setup.isHost())
			return "The host can't leave frozen: Quit saves the campaign and ends the session.";
		if (!setup.playing() || setup.closed() || outcome != null)
			return "The game isn't running.";
		if (freezeAsked || quitting || saves.saving())
			return "A campaign save is under way.";
		if (engine.anyBattle())
			return "No leaving frozen during a battle.";
		if (engine.assault() != null)
			return "No leaving frozen during an assault.";
		if (engine.assaultTaken() != null)
			return "No leaving frozen while an attacker chooses what to take.";
		return null;
	}

	@Override
	public void leaveFrozen() {
		if (freezeRefusal() != null)
			return;
		confirm("Leave and freeze? Every player saves the campaign, then you leave. Your capital waits, frozen, until you come"
				+ " back; your other regions become free land, your vassals go free and your wars end.", this::freezeConfirmed);
	}

	/** The player confirmed Leave and freeze: the host is asked. Test tooling calls it too. */
	void freezeConfirmed() {
		if (freezeAsked || freezeRefusal() != null)
			return;
		freezeAsked = true;
		log.line("this player leaves frozen: the campaign is saved, then this PC leaves");
		setup.askFreeze();
	}

	/** A joining PC, once its Leave and freeze save is done on every PC: it leaves, held at that save's step. */
	private void leaveFrozen(String saveOutcome) {
		leftFrozen = true;
		freezeAsked = false;
		log.line("this player left frozen" + (saveOutcome.isEmpty() ? "" : "; the campaign save: " + saveOutcome));
		if (!setup.closed())
			setup.leave();
		game.endSession();
		GAME.SPEED.speedSet(0);
		MpPrompt.show("You left the game. Your city is frozen until you come back: join this campaign's game while it runs.\n"
				+ (saveOutcome.isEmpty() ? "Every player saved the campaign." : "The campaign save: " + saveOutcome),
				new GButt.ButtPanel("Main menu") {
					@Override
					protected void clickA() {
						go(false);
					}
				});
	}

	@Override
	public CharSequence saveRefusal() {
		return saves.saveRefusal();
	}

	@Override
	public void save() {
		saves.saveNow(CampaignRecord.Kind.SAVE);
	}

	@Override
	public void quit(boolean exitGame) {
		if (outcome != null || setup.closed()) {
			leave(exitGame); // nothing left to save
			return;
		}
		if (quitting || saves.exiting())
			return; // already on its way out
		if (!setup.playing()) {
			confirm(setup.isHost() ? "Leave and end the setup for every player?" : "Leave the setup? The others go on without you.",
					() -> leave(exitGame));
			return;
		}
		String refusal = setup.isHost() ? saves.saveRefusal() : engine.anyBattle() ? "No saves during a battle."
				: engine.assault() != null ? "No saves during an assault."
				: engine.assaultTaken() != null ? "No saves while an attacker chooses what to take." : null;
		if (refusal != null && !saves.saving()) {
			confirm(refusal + (setup.isHost() ? " End the session for every player without saving? The last save resumes it."
					: " Leave without saving? The host chooses whether the others go on without you."), () -> leave(exitGame));
			return;
		}
		confirm(setup.isHost() ? "Save the campaign and end the session for every player?"
				: "Ask the host to save the campaign and end the session for every player?", () -> quitConfirmed(exitGame));
	}

	/** The player confirmed: the host's save is ordered as soon as one can start, or asked of the host. Test tooling calls it too. */
	void quitConfirmed(boolean exitGame) {
		if (quitting || saves.exiting() || setup.closed())
			return;
		quitting = true;
		this.exitGame = exitGame;
		log.line("this player quits (" + (exitGame ? "exit" : "to the menu") + "): the campaign is saved, then the session ends");
		if (setup.isHost())
			pendingQuitter = setup.localPlayerId();
		else
			setup.askQuit();
	}

	private static void confirm(String question, ACTION yes) {
		VIEW.inters().yesNo.activate(question, yes, ACTION.NOP, true);
	}

	/** Without a save: the host ends the session for every player, a joining PC drops out of it. */
	private void leave(boolean exitGame) {
		log.line("this player left without a campaign save");
		if (!setup.closed())
			setup.leave();
		game.endSession();
		go(exitGame);
	}

	private static void go(boolean exitGame) {
		if (exitGame)
			CORE.annihilate();
		else
			CORE.setCurrentState(Menu::make);
	}

	/** Every PC, once the campaign save that ends the session is done: ends the session and the engine, and says why. */
	private void end(String outcome, int quitter) {
		this.outcome = outcome;
		if (!setup.closed())
			setup.leave();
		game.endSession();
		GAME.SPEED.speedSet(0);
		boolean mine = quitting && quitter == setup.localPlayerId();
		if (mine && outcome.isEmpty()) {
			go(exitGame);
			return;
		}
		String why = quitter == SaveOrder.NO_QUITTER ? "The host chose Save and exit after a world desync."
				: mine ? "You quit the game." : name(quitter) + " quit the game.";
		MpPrompt.show(why + "\n"
				+ (outcome.isEmpty() ? "The campaign is saved; Load Saved Lobby resumes it." : "The campaign save: " + outcome)
				+ "\nThe session has ended.", new GButt.ButtPanel(mine && exitGame ? "Exit" : "Main menu") {
					@Override
					protected void clickA() {
						go(mine && exitGame);
					}
				});
	}

	private String name(int playerId) {
		for (LobbyPlayer p : setup.players())
			if (p.id() == playerId)
				return p.name();
		return "A player";
	}

	/** After a save that ended the session: its outcome, empty when every PC saved; null before. */
	String outcome() {
		return outcome;
	}
}
