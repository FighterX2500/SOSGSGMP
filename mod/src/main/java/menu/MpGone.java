package menu;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p>A lost player (SOS-177), whose PC went silent with its connection open, holds every PC the same way, with the time
 * they have been lost; the host can wait as long as it likes. Once every player of the hold is back, before the host
 * chose, the hold ends by itself. A joining PC whose host went silent says so, with the time, until it is heard again.
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
	/** The players of the open hold the chat told left, by id. */
	private final List<Integer> toldLeft = new ArrayList<>();
	/** The players of the open hold the chat told lost, by id, with their names for when they are back. */
	private final Map<Integer, String> toldLost = new LinkedHashMap<>();
	/** After Return to the last save ended the session here. */
	private boolean ended;
	/** A joining PC shows that its host went silent (SOS-177). */
	private boolean hostLostShown;

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
		if (hostLost())
			return;
		if (d.waiting()) {
			if (heldAt != d.holdStep()) {
				heldAt = d.holdStep();
				engine.hold(EnginePlay.Hold.GONE, heldAt);
				LOG.ln("SOSGSGMP: " + d.names() + (leftCount(d) == 0 ? " lost" : " left") + "; holding at step " + heldAt);
			}
			tellChat(d);
			String text = text(d);
			if (text.isEmpty()) // only this PC's own player is lost: its hold ends as soon as the host hears it again
				return;
			if (!text.equals(shownText) || !VIEW.inters().fullScreen.isActivated())
				show(d, text);
			return;
		}
		if (heldAt >= 0 && d.choice() == null) { // every lost player is back before the host chose (SOS-177)
			LOG.ln("SOSGSGMP: every player lost is back; the hold at " + heldAt + " ends");
			tellChat(d);
			toldLeft.clear();
			heldAt = -1;
			if (shownText != null)
				VIEW.inters().fullScreen.deactivate();
			shownText = null;
			engine.release(EnginePlay.Hold.GONE);
			return;
		}
		if (d.choice() == Departures.Choice.CONTINUE) {
			for (Departures.Gone g : d.gone())
				toSave.add(g.slot());
			LOG.ln("SOSGSGMP: the host continued without " + d.names());
			d.continued();
			toldLeft.clear();
			toldLost.clear();
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

	/**
	 * The window's text: who left, and who is lost and for how long; empty when the hold is only this PC's own player
	 * lost, which it doesn't show itself.
	 */
	private String text(Departures d) {
		StringBuilder who = new StringBuilder();
		long now = System.nanoTime() / 1_000_000; // the sessions' clock, as Departures' lost times
		for (Departures.Gone g : d.gone())
			if (d.lost(g.playerId()) && g.playerId() != setup.localPlayerId())
				who.append(g.name()).append(" lost the connection: waiting, ").append(clock(now - d.lostSince(g.playerId())))
						.append(".\n");
		List<String> left = new ArrayList<>();
		for (Departures.Gone g : d.gone())
			if (!d.lost(g.playerId()))
				left.add(g.name());
		if (!left.isEmpty())
			who.append(String.join(", ", left)).append(left.size() == 1 ? " has" : " have").append(" left the game.\n");
		if (who.length() == 0)
			return "";
		boolean someLost = leftCount(d) < d.gone().size();
		if (!setup.isHost())
			return who + (someLost ? "The game goes on when they are heard again, unless the host continues without them"
					+ " or returns to the last save." : "Waiting for the host to continue without them, or to return to the"
					+ " last save.");
		String refusal = refusal(d);
		if (someLost) // the window has room for a few lines: the buttons say the rest
			return who + "The game goes on by itself when they are heard again; you can wait as long as you like."
					+ (refusal == null ? "" : "\n" + refusal);
		return who + "Continue without them: they are removed from the campaign; their regions become free and their"
				+ " armies disband.\nReturn to the last save: the session ends, and Load Saved Lobby resumes the campaign"
				+ " with them." + (refusal == null ? "" : "\n" + refusal);
	}

	/**
	 * A joining PC: while its host is silent, a window says so with the time; once the host is heard again, what it
	 * says next shows. Returns whether that window is up.
	 */
	private boolean hostLost() {
		long silent = setup.hostSilentMs();
		if (hostLostShown && silent < Departures.BACK_MS) {
			hostLostShown = false;
			shownText = null;
			VIEW.inters().fullScreen.deactivate();
			setup.chat().notice("The connection to the host is back.");
			LOG.ln("SOSGSGMP: the host is heard again");
			return false;
		}
		if (!hostLostShown && silent < Departures.LOST_MS)
			return false;
		if (!hostLostShown) {
			hostLostShown = true;
			LOG.ln("SOSGSGMP: the host is silent for " + silent / 1000 + " s");
		}
		String text = "Lost the connection to the host: waiting, " + clock(silent) + ".\nThe game goes on when the host is"
				+ " heard again. If it doesn't come back, the host can resume the campaign from its last save.";
		if (!text.equals(shownText) || !VIEW.inters().fullScreen.isActivated()) {
			shownText = text;
			VIEW.inters().fullScreen.activate(text, COLOR.WHITE100, null);
		}
		return true;
	}

	/** The chat tells once who of the open hold left, who was lost, and who of those lost is back. */
	private void tellChat(Departures d) {
		for (Departures.Gone g : d.gone()) {
			int id = g.playerId();
			if (d.lost(id)) {
				if (toldLost.put(id, g.name()) == null && id != setup.localPlayerId())
					setup.chat().notice(g.name() + " lost the connection.");
			} else if (!toldLeft.contains(id)) {
				toldLeft.add(id);
				toldLost.remove(id);
				setup.chat().notice(g.name() + " left the game.");
			}
		}
		for (Iterator<Map.Entry<Integer, String>> i = toldLost.entrySet().iterator(); i.hasNext();) {
			Map.Entry<Integer, String> l = i.next();
			if (d.lost(l.getKey()))
				continue;
			i.remove();
			if (l.getKey() != setup.localPlayerId())
				setup.chat().notice(l.getValue() + " is back.");
		}
	}

	/** How many players of the open hold left, not lost. */
	private static int leftCount(Departures d) {
		int n = 0;
		for (Departures.Gone g : d.gone())
			if (!d.lost(g.playerId()))
				n++;
		return n;
	}

	/** {@code ms} as m:ss. */
	private static String clock(long ms) {
		long s = ms / 1000;
		return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
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
