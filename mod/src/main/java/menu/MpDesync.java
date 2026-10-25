package menu;

import game.GAME;
import snake2d.CORE;
import snake2d.LOG;
import snake2d.util.color.COLOR;
import sosdet.DesyncHold;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.SetupSession;
import sosgsgmp.session.WorldResync;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * SOSGSGMP: the desync window (spec §11 World desyncs, SOS-59). When the engine holds every PC at a world desync, every
 * player sees which player's world differs; the host chooses Resync (its world goes to every PC, each keeping its own
 * city, and play continues) or Save and exit (the same resync, then a campaign save on every PC, and the session ends;
 * SOS-68); the others wait. Once per frame from {@link MpPlay}, between steps. The window is vanilla's full-screen
 * prompt, shown again if clicked away while the hold or the save lasts.
 */
final class MpDesync {

	private final SetupSession setup;
	private final EnginePlay engine;
	private final WorldSetupAdapter game;
	private final MpSaves saves;
	/** The hold the window shows; null when there is none. */
	private DesyncHold shown;
	/** Host: Resync chosen for {@link #shown}, and whether its resync went out. */
	private boolean resyncChosen, resyncSent;
	/** Host: Save and exit chosen; the save is ordered once the resync has loaded. */
	private boolean exitChosen;
	/** The host's resync, until this PC reaches its step and loads it. */
	private WorldResync pending;
	/** The step of the last resync this PC loaded; -1 before the first. */
	private long resyncedStep = -1;
	/** After Save and exit ended the session: the campaign save's outcome, empty when every PC saved; null before. */
	private String exitOutcome;
	private CharSequence message = "";

	MpDesync(SetupSession setup, EnginePlay engine, WorldSetupAdapter game, MpSaves saves) {
		this.setup = setup;
		this.engine = engine;
		this.game = game;
		this.saves = saves;
	}

	/** Once per frame while playing, even after the session closed: Save and exit ends it on every PC. */
	void frame() {
		if (exitOutcome != null)
			return;
		if (saves.exited() != null) {
			exit(saves.exited());
			return;
		}
		if (setup.closed())
			return;
		WorldResync r = setup.takeResync();
		if (r != null)
			pending = r;
		DesyncHold h = engine.desyncHold();
		if (h != null && h != shown) {
			shown = h;
			resyncChosen = false;
			resyncSent = false;
			exitChosen = false;
			message = setup.isHost()
					? "The world desynced at step " + h.desyncStep + ": " + differs(h.pc) + "'s world differs from yours.\n"
							+ "Resync: every player's game reloads your world, each keeping their own city.\n"
							+ "Save and exit: the same resync, then every player saves the campaign and the session ends. Load "
							+ "Saved Lobby resumes it."
					: "The world desynced at step " + h.desyncStep + ": " + differs(h.pc) + "'s world differs from the host's.\n"
							+ "Waiting for the host to resync, or to save and exit.";
			show();
		}
		if (setup.isHost() && resyncChosen && !resyncSent && shown != null && engine.step() == shown.holdStep) {
			setup.resync(engine.worldResyncSave());
			resyncSent = true;
		}
		load();
		if (exitChosen && shown == null && saves.canSave()) {
			saves.saveAndExit();
			exitChosen = false;
		}
		if ((shown != null || saves.exiting()) && !VIEW.inters().fullScreen.isActivated())
			show();
	}

	/** The pending resync at its step: loaded, and the window closes once the engine's hold ends. */
	private void load() {
		if (pending == null)
			return;
		if (engine.step() > pending.step()) {
			LOG.ln("SOSGSGMP: a world resync for step " + pending.step() + " came at step " + engine.step() + "; dropped");
			pending = null;
			return;
		}
		if (engine.step() < pending.step())
			return; // this PC hasn't reached the hold yet
		long t = System.nanoTime();
		engine.worldResyncLoad(pending);
		resyncedStep = pending.step();
		LOG.ln("SOSGSGMP: world resync loaded at step " + resyncedStep + " in " + (System.nanoTime() - t) / 1_000_000 + " ms");
		pending = null;
		if (engine.desyncHold() == null) {
			shown = null;
			VIEW.inters().fullScreen.deactivate();
		}
	}

	/** Every PC, once the campaign save that ends the session is done: ends the session and the engine, and offers the main menu. */
	private void exit(String outcome) {
		exitOutcome = outcome;
		if (!setup.closed())
			setup.leave();
		game.endSession();
		GAME.SPEED.speedSet(0);
		shown = null;
		VIEW.inters().fullScreen.activate("The host chose Save and exit after a world desync.\n"
				+ (outcome.isEmpty() ? "The campaign is saved; Load Saved Lobby resumes it." : "The campaign save: " + outcome)
				+ "\nThe session has ended.", COLOR.WHITE100, null, new GButt.ButtPanel("Main menu") {
					@Override
					protected void clickA() {
						CORE.setCurrentState(Menu::make);
					}
				});
	}

	private void show() {
		if (shown == null && saves.exiting()) {
			VIEW.inters().fullScreen.activate("Saving the campaign. The session ends once every player has saved.", COLOR.WHITE100, null);
			return;
		}
		if (!setup.isHost() || resyncChosen) {
			VIEW.inters().fullScreen.activate(resyncChosen || pending != null ? "Resyncing the world from the host..." : message,
					COLOR.WHITE100, null);
			return;
		}
		VIEW.inters().fullScreen.activate(message, COLOR.WHITE100, null, new GButt.ButtPanel("Resync") {
			@Override
			protected void clickA() {
				choose(true);
			}
		}, new GButt.ButtPanel("Save and exit") {
			@Override
			protected void clickA() {
				choose(false);
			}
		});
	}

	/** Host: the choice for the window's desync, from its buttons or the test autopilot. */
	void choose(boolean resync) {
		if (!setup.isHost() || shown == null || resyncChosen)
			return;
		resyncChosen = true;
		exitChosen = !resync;
		show();
	}

	private String differs(int pc) {
		for (LobbyPlayer p : setup.players())
			if (setup.factions().slotOf(p.id()) == pc)
				return p.name();
		return "PC " + pc;
	}

	/** The hold the window shows, or null. */
	DesyncHold shown() {
		return shown;
	}

	/** The step of the last resync this PC loaded, or -1. */
	long resyncedStep() {
		return resyncedStep;
	}

	/** After Save and exit ended the session: the campaign save's outcome, empty when every PC saved; null before. */
	String exitOutcome() {
		return exitOutcome;
	}
}
