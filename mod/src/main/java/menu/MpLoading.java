package menu;

import java.util.List;

import init.constant.C;

import sosdet.LocalBattle;
import sosdet.OwnBattle;
import sosdet.View;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.Loading;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the loading window on screen (SOS-158), in the setup's screens and in the game. In the game it also opens
 * the window for each freeze the lockstep announces, on every PC at the same step, so it is up before the freeze: a
 * battle's load, a view's start and end. The host closes it on every PC once every PC has passed the freeze step.
 * Setup's windows open and close in the session. Per frame: it allocates only when a window opens.
 */
public final class MpLoading {

	private final SetupSession setup;
	/** Null in the setup's screens, which only draw the window. */
	private final EnginePlay engine;
	/** Every player's slot, and the other PCs, as of {@link #seenChanges}. */
	private int[] slots = new int[0];
	private int[] peers = new int[0];
	private int seenChanges = -1;
	/** The open battle-end window's thaw step, the last seen: it closes once every PC is past it; -1 before. */
	private long endThaw = -1;
	private Loading.Window drawn;
	private MpLoadingWindow window;

	/** The setup's screens: the window only. */
	public MpLoading(SetupSession setup) {
		this(setup, null);
	}

	MpLoading(SetupSession setup, EnginePlay engine) {
		this.setup = setup;
		this.engine = engine;
	}

	/** Once per frame, after the session's update. */
	public void frame() {
		if (engine != null && setup.playing() && engine.active()) {
			players();
			freezesAhead();
			if (setup.isHost())
				closeWhenPassed();
		}
		show(setup.loading().shown());
	}

	private void players() {
		if (setup.changes() == seenChanges)
			return;
		seenChanges = setup.changes();
		List<LobbyPlayer> players = setup.players();
		slots = new int[players.size()];
		for (int i = 0; i < slots.length; i++)
			slots[i] = setup.factions().slotOf(players.get(i).id());
		peers = MpPlay.otherPcs(setup);
	}

	/**
	 * The freezes every PC knows of from the lockstep, each opened once, before its step. The text is made only when a
	 * window is new, so a frame with nothing new allocates nothing.
	 */
	private void freezesAhead() {
		long step = engine.step();
		long ahead = engine.battleAhead();
		if (ahead >= 0 && isNew(Loading.Kind.BATTLE_START, ahead))
			open(Loading.Kind.BATTLE_START, ahead, "A battle is loading." + WAIT);
		OwnBattle b = engine.battle();
		if (b != null && b.awaiting() && isNew(Loading.Kind.BATTLE_START, b.step))
			open(Loading.Kind.BATTLE_START, b.step, name(b.human) + "'s battle is loading." + WAIT);
		for (int slot : slots) {
			if (slot < 0)
				continue;
			LocalBattle l = engine.localBattleOf(slot);
			if (l != null && l.resultStep() < 0 && step <= l.freeze + LATE && isNew(Loading.Kind.BATTLE_START, l.freeze))
				open(Loading.Kind.BATTLE_START, l.freeze, name(slot) + "'s battle is loading." + WAIT);
			// its end: the battle PC loads its city at the result's step; it knows from its own exit, every other PC from
			// the result, a few report steps before the thaw holds it
			if (l != null && (l.resultSent() || l.resultStep() >= 0) && isNew(Loading.Kind.BATTLE_END, l.freeze))
				open(Loading.Kind.BATTLE_END, l.freeze, name(slot) + "'s battle is over; " + name(slot) + " returns to the city." + WAIT);
			if (l != null && l.thaw() >= 0 && setup.loading().isOpen(Loading.Kind.BATTLE_END, l.freeze))
				endThaw = l.thaw();
			View v = engine.view(slot);
			if (v == null)
				continue;
			if (v.thaw() < 0 && v.state() != View.CANCELLED && v.state() != View.ENDED && step <= v.freeze + LATE
					&& isNew(Loading.Kind.VIEW_START, v.freeze))
				open(Loading.Kind.VIEW_START, v.freeze, joining(v) + (battle(v) ? " join" + (joiningMany(v) ? "" : "s") + " " + name(v.owner)
						+ "'s battle." : " is opening " + name(v.owner) + "'s city.") + WAIT);
			long rejoin = v.rejoinAt(); // another viewer joins: the owner and this view's viewer load again (SOS-151)
			if (rejoin >= 0 && step <= rejoin + LATE && isNew(Loading.Kind.VIEW_START, rejoin))
				open(Loading.Kind.VIEW_START, rejoin, "Another player joins " + name(v.owner) + (battle(v) ? "'s battle." : "'s city.") + WAIT);
			long thaw = v.thaw();
			if (thaw >= 0 && step <= thaw + LATE && isNew(Loading.Kind.VIEW_END, thaw))
				open(Loading.Kind.VIEW_END, thaw, name(v.viewer) + " is returning from " + name(v.owner)
						+ (battle(v) ? "'s battle." : "'s city.") + WAIT);
		}
	}

	/** Every player whose view of {@code v}'s owner loads at its freeze step: several allies join a battle at once (SOS-161). */
	private String joining(View v) {
		StringBuilder b = new StringBuilder();
		int n = 0;
		for (int slot = 0; slot < sosgsgmp.lobby.LobbySettings.MAX_PLAYERS; slot++) {
			View o = engine.view(slot);
			if (o != null && o.owner == v.owner && o.freeze == v.freeze && o.state() != View.CANCELLED && o.state() != View.ENDED) {
				b.append(n++ == 0 ? "" : " and ").append(name(o.viewer));
			}
		}
		return n == 0 ? name(v.viewer) : b.toString();
	}

	private boolean joiningMany(View v) {
		int n = 0;
		for (int slot = 0; slot < sosgsgmp.lobby.LobbySettings.MAX_PLAYERS; slot++) {
			View o = engine.view(slot);
			if (o != null && o.owner == v.owner && o.freeze == v.freeze && o.state() != View.CANCELLED && o.state() != View.ENDED)
				n++;
		}
		return n > 1;
	}

	/** A view of a battle: the engine marks it at the freeze, so before that the owner's running battle tells. */
	private boolean battle(View v) {
		return v.battle() || engine.localBattleOf(v.owner) != null;
	}

	/**
	 * Steps a window may still open after its step: at a high speed one frame runs dozens of steps, so a PC can pass a
	 * freeze step announced a few steps ahead between two frames, while the load itself comes a little later; a window
	 * opened late is closed by the host as soon as every PC is past (10 s at 1x).
	 */
	private static final long LATE = 600;

	private static final String WAIT = " Every player's game waits until each PC is done.";

	private boolean isNew(Loading.Kind kind, long step) {
		Loading l = setup.loading();
		return !l.isOpen(kind, step) && !l.wasClosed(kind, step);
	}

	private void open(Loading.Kind kind, long step, String text) {
		setup.announceLoading(kind, step, text, true);
	}

	private String name(int slot) {
		for (LobbyPlayer p : setup.players())
			if (setup.factions().slotOf(p.id()) == slot)
				return p.name();
		return "A player";
	}

	/**
	 * Host: a play window closes on every PC once this PC and every other has run past its step: a battle's end past the
	 * thaw, once the battle is gone here (its thaw can still move until then).
	 */
	private void closeWhenPassed() {
		Loading.Window w = setup.loading().shown();
		if (w == null || w.kind() == Loading.Kind.WORLD_GENERATION || w.kind() == Loading.Kind.WORLD_INITIALIZATION)
			return;
		long past = w.key();
		if (w.kind() == Loading.Kind.BATTLE_END) {
			if (endThaw < 0 || battleRuns(w.key()))
				return;
			past = endThaw;
		}
		if (engine.step() <= past)
			return;
		for (int pc : peers)
			if (engine.peerStep(pc) <= past)
				return;
		setup.announceLoading(w.kind(), w.key(), "", false);
	}

	/** True while the battle against NPCs that froze at {@code freeze} is still on record here. */
	private boolean battleRuns(long freeze) {
		for (int slot : slots) {
			LocalBattle l = slot < 0 ? null : engine.localBattleOf(slot);
			if (l != null && l.freeze == freeze)
				return true;
		}
		return false;
	}

	/**
	 * The step at which the window's freeze comes, for a test's screenshot that must come before it: the battle's end at
	 * its result's step, which the battle PC learns only as it applies ({@link Long#MAX_VALUE} until then); -1 once past.
	 */
	long freezeStep(Loading.Window w) {
		if (w.kind() != Loading.Kind.BATTLE_END)
			return w.key();
		for (int slot : slots) {
			LocalBattle l = slot < 0 ? null : engine.localBattleOf(slot);
			if (l != null && l.freeze == w.key())
				return l.resultStep() >= 0 ? l.resultStep() : Long.MAX_VALUE;
		}
		return -1;
	}

	/** Draws the model's window; again after a whole-game load, which brings a new screen. */
	private void show(Loading.Window w) {
		if (w == drawn && (w == null || window.isActivated()))
			return;
		if (window != null && window.isActivated())
			window.close();
		drawn = w;
		window = null;
		if (w == null)
			return;
		window = new MpLoadingWindow(w);
		window.open();
		window.moveTop(C.DIM().height() / 6); // above the game's own messages, which open in the middle
	}
}
