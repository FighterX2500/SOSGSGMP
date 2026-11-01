package menu;

import snake2d.LOG;
import snake2d.util.color.COLOR;
import sosdet.DesyncHold;
import sosgsgmp.game.EnginePlay;
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
 * prompt, shown again if clicked away while the hold lasts. The save that ends the session, and its end, are
 * {@link MpExit}'s (SOS-79).
 */
final class MpDesync {

	private final SetupSession setup;
	private final EnginePlay engine;
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
	private CharSequence message = "";

	MpDesync(SetupSession setup, EnginePlay engine, MpSaves saves) {
		this.setup = setup;
		this.engine = engine;
		this.saves = saves;
	}

	/** Once per frame while playing. */
	void frame() {
		if (saves.exited() != null || setup.closed())
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
			if (engine.battle() != null) { // a battle recovers by itself (spec §8 Desyncs): no window, no choice
				message = null;
				resyncChosen = setup.isHost();
				LOG.ln("SOSGSGMP: the battle desynced at step " + h.desyncStep + ": every PC reloads the host's battle");
			} else {
			message = setup.isHost()
					? "The world desynced at step " + h.desyncStep + ": " + differs(h.pc) + "'s world differs from yours.\n"
							+ "Resync: every player's game reloads your world, each keeping their own city.\n"
							+ "Save and exit: the same resync, then every player saves the campaign and the session ends. Load "
							+ "Saved Lobby resumes it."
					: "The world desynced at step " + h.desyncStep + ": " + differs(h.pc) + "'s world differs from the host's.\n"
							+ "Waiting for the host to resync, or to save and exit.";
			show();
			}
		}
		if (setup.isHost() && resyncChosen && !resyncSent && shown != null && engine.step() == shown.holdStep) {
			setup.resync(engine.worldResyncSave());
			resyncSent = true;
		}
		load();
		if (exitChosen && shown == null && saves.canSaveToExit()) {
			saves.saveAndExit();
			exitChosen = false;
		}
		if (shown != null && message != null && !VIEW.inters().fullScreen.isActivated())
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
			if (message != null)
				VIEW.inters().fullScreen.deactivate();
		}
	}

	private void show() {
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
}
