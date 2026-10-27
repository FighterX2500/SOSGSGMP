package menu;

import java.util.ArrayList;
import java.util.List;

import game.GAME;
import snake2d.CORE;
import snake2d.LOG;
import snake2d.util.color.COLOR;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.session.Departures;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * SOSGSGMP: the window when a player's PC leaves the running game (spec §11 Disconnects, SOS-65). Every PC holds at the
 * host's hold step and shows who is gone; the host chooses Continue without them (the engine removes their humans, and
 * once it has, the host saves the campaign so the removal lasts) or Return to the last save (the session ends on every
 * PC). Continue isn't offered while the engine would refuse the removal. Once per frame from {@link MpPlay}, between
 * steps, even after the session closed.
 */
final class MpGone {

	private final SetupSession setup;
	private final EnginePlay engine;
	private final WorldSetupAdapter game;
	private final MpSaves saves;
	/** The hold step this PC holds the engine at; -1 when it holds none. */
	private long heldAt = -1;
	/** The window's text as last shown: shown again when it changes (another player left, Continue became possible). */
	private String shownText;
	/** Host, after Continue: the slots whose removal the next campaign save records, once the engine removed them. */
	private final List<Integer> toSave = new ArrayList<>();
	/** After Return to the last save ended the session here. */
	private boolean ended;

	MpGone(SetupSession setup, EnginePlay engine, WorldSetupAdapter game, MpSaves saves) {
		this.setup = setup;
		this.engine = engine;
		this.game = game;
		this.saves = saves;
	}

	void frame() {
		if (ended)
			return;
		Departures d = setup.departures();
		if (d.choice() == Departures.Choice.RETURN) {
			end();
			return;
		}
		if (d.waiting()) {
			if (heldAt != d.holdStep()) {
				heldAt = d.holdStep();
				engine.hold(EnginePlay.Hold.GONE, heldAt);
				LOG.ln("SOSGSGMP: " + d.names() + " left; holding at step " + heldAt);
			}
			String text = text(d);
			if (!text.equals(shownText) || !VIEW.inters().fullScreen.isActivated())
				show(d, text);
			return;
		}
		if (d.choice() == Departures.Choice.CONTINUE) {
			for (Departures.Gone g : d.gone())
				toSave.add(g.slot());
			LOG.ln("SOSGSGMP: the host continued without " + d.names());
			d.continued();
			heldAt = -1;
			shownText = null;
			engine.release(EnginePlay.Hold.GONE);
			VIEW.inters().fullScreen.deactivate();
			return;
		}
		saveRemoval();
	}

	/** Host: once the engine removed every player Continue left out, the campaign save that keeps the removal. */
	private void saveRemoval() {
		if (!setup.isHost() || toSave.isEmpty() || !saves.canSave())
			return;
		for (int slot : toSave)
			if (!engine.retired(slot))
				return;
		toSave.clear();
		saves.saveNow(CampaignRecord.Kind.AUTO);
	}

	/** Host: why Continue can't remove the players gone now, or null when it can. */
	private String refusal(Departures d) {
		for (Departures.Gone g : d.gone()) {
			String r = engine.removeRefusal(g.slot());
			if (r != null)
				return g.name() + " can't be removed now: " + r;
		}
		return null;
	}

	private String text(Departures d) {
		String who = d.names() + (d.gone().size() == 1 ? " has" : " have") + " left the game.";
		if (!setup.isHost())
			return who + "\nWaiting for the host to continue without them, or to return to the last save.";
		String refusal = refusal(d);
		return who + "\nContinue without them: they are removed from the campaign; their regions become free and their"
				+ " armies disband.\nReturn to the last save: the session ends, and Load Saved Lobby resumes the campaign"
				+ " with them." + (refusal == null ? "" : "\n" + refusal);
	}

	private void show(Departures d, String text) {
		shownText = text;
		if (!setup.isHost()) {
			VIEW.inters().fullScreen.activate(text, COLOR.WHITE100, null);
			return;
		}
		GButt.ButtPanel back = new GButt.ButtPanel("Return to the last save") {
			@Override
			protected void clickA() {
				choose(false);
			}
		};
		if (refusal(d) != null) {
			MpPrompt.show(text, back);
			return;
		}
		VIEW.inters().fullScreen.activate(text, COLOR.WHITE100, null, new GButt.ButtPanel("Continue without them") {
			@Override
			protected void clickA() {
				choose(true);
			}
		}, back);
	}

	/** Host: the choice for the open hold, from the window's buttons or the test autopilot. Between steps. */
	void choose(boolean continued) {
		Departures d = setup.departures();
		if (!setup.isHost() || !d.waiting())
			return;
		if (!continued) {
			setup.chooseGone(Departures.Choice.RETURN);
			return;
		}
		if (refusal(d) != null)
			return;
		for (Departures.Gone g : d.gone())
			engine.removeHuman(g.slot());
		setup.chooseGone(Departures.Choice.CONTINUE);
	}

	/** Every PC after Return to the last save: the session and the engine end, and the main menu is offered. */
	private void end() {
		ended = true;
		if (!setup.closed())
			setup.leave();
		game.endSession();
		GAME.SPEED.speedSet(0);
		LOG.ln("SOSGSGMP: the session ended: the host chose to return to the last save");
		MpPrompt.show(setup.closedReason() + "\nThe session has ended.",
				new GButt.ButtPanel("Main menu") {
					@Override
					protected void clickA() {
						CORE.setCurrentState(Menu::make);
					}
				});
	}

	/** Whether this PC holds for players gone, waiting for the host. */
	boolean waiting() {
		return setup.departures().waiting();
	}

	/** After Return to the last save ended the session here. */
	boolean ended() {
		return ended;
	}
}
