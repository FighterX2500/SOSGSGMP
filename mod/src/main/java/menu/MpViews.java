package menu;

import java.util.List;
import java.util.Locale;

import snake2d.util.color.COLOR;
import sosdet.View;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.net.Message;
import sosgsgmp.session.SetupSession;
import sosgsgmp.session.ViewRequests;
import view.interrupter.Interrupter;
import view.main.VIEW;

/**
 * SOSGSGMP: city viewing in the running game (spec §10, SOS-82; docs/plan/city-viewing.md §1 to §4). Once per frame
 * from {@link MpPlay}, between steps: the host checks each ask against the engine, begins the views their owners allow
 * and ends those their viewers close; the owner's player answers asks in a popup, and its PC sends its city and view
 * hashes; the viewer's PC loads the city, feeds the hashes and asks the host to end the view at a limit or a lost step.
 * The viewer sees a bar with Close view; the messages say why a view didn't open or ended. Every step of it goes to the
 * playtest log.
 *
 * <p>The faction window's View city button ({@link MpPlayerWindow}) is built from a game hook that has no reach to the
 * session, so it finds this through {@link #current()}: the one mutable static of the mod, set when the session's game
 * starts and cleared when it ends, so it never outlives the session.
 */
final class MpViews {

	/** The views of the running session, for the faction window's button; null outside one. */
	private static MpViews current;

	private final SetupSession setup;
	private final EnginePlay engine;
	private final MpLog log;
	/** This PC's view as the viewer, as last seen, and what of it was logged or shown. */
	private View seen;
	private int seenState = -1;
	private boolean closeSent, caughtUpLogged, desyncLogged, loadingShown;
	/** The owner's popup for the oldest ask, or null. */
	private MpViewAsk popup;
	/** The viewer's bar while it views, or null. */
	private MpViewBar bar;
	/** Every PC but the host: the notice while the host opens its view, or null. */
	private MpViewNotice hostHold;
	/** The host's slot, once known; it never changes in a session. */
	private int hostSlot = -1;
	/** Since when this PC holds at the host's view's freeze step; -1 while it doesn't. */
	private long holdSince = -1;
	/** Each slot's view and its state as last logged ({@link #watch}). */
	private final View[] watched = new View[LobbySettings.MAX_PLAYERS];
	private final int[] watchedStates = new int[LobbySettings.MAX_PLAYERS];
	/** A hold this long at the host's freeze step shows the notice. */
	private static final long HOLD_NOTICE_NANOS = 500_000_000L;

	MpViews(SetupSession setup, EnginePlay engine, MpLog log) {
		this.setup = setup;
		this.engine = engine;
		this.log = log;
	}

	/** The running session's views, or null. */
	static MpViews current() {
		return current;
	}

	/** Once per frame while playing, between steps. */
	void frame() {
		ViewRequests r = setup.views();
		if (r == null || setup.closed() || !engine.active()) {
			if (current == this)
				current = null;
			return;
		}
		current = this;
		if (setup.isHost())
			host(r);
		owner(r);
		viewer(r);
		hostHold();
		watch();
	}

	/** Every view's state as this PC sees it, into the playtest log when it changes: the freeze, the thaw, the reloads. */
	private void watch() {
		for (int slot = 0; slot < LobbySettings.MAX_PLAYERS; slot++) {
			View v = engine.view(slot);
			int state = v == null ? -1 : v.state();
			if (v == watched[slot] && state == watchedStates[slot])
				continue;
			watched[slot] = v;
			watchedStates[slot] = state;
			if (v != null && v.battle() && v.owner == setup.views().local()) {
				if (state == View.RUNNING)
					message(name(v.viewer) + " joined to watch your battle. It reloads for a few seconds.");
				else if (state == View.ENDED)
					message(name(v.viewer) + " stopped watching your battle.");
			}
			if (v != null)
				log.line(String.format(Locale.ROOT, "viewstate: %s viewing %s's city: %s at step %d (freeze %d, thaw %d)%s; this PC's"
						+ " last view load %.0f ms", name(v.viewer), name(v.owner), stateName(state), engine.step(), v.freeze, v.thaw(),
						v.reason() == null ? "" : ", " + v.reason(), engine.lastViewLoadMs()));
		}
	}

	// ---------------------------------------------------------------- the host

	private void host(ViewRequests r) {
		for (int slot = 0; slot < LobbySettings.MAX_PLAYERS; slot++) {
			View v = engine.view(slot);
			if (v != null && v.state() != View.ENDED && v.state() != View.CANCELLED)
				r.opened(v.viewer, v.owner);
		}
		ViewRequests.Pair p;
		while ((p = r.takeCheck()) != null) {
			String refusal = engine.viewRefusal(p.viewer(), p.owner());
			boolean battle = refusal == null && engine.localBattle(p.owner());
			if (battle)
				r.checkedNoAsk(p); // spec §8: a running NPC battle is watched without asking
			else
				r.checked(p, refusal);
			log.line("viewask: " + name(p.viewer()) + " asks to view " + name(p.owner()) + "'s " + (battle ? "battle" : "city")
					+ " at step " + engine.step() + (battle ? "; no answer needed" : refusal == null ? "; passed to them" : "; refused: " + refusal));
		}
		while ((p = r.takeBegin()) != null) {
			String refusal = engine.viewRefusal(p.viewer(), p.owner());
			if (refusal == null)
				engine.viewBegin(p.viewer(), p.owner());
			r.begun(p, refusal);
			log.line("viewbegin: " + name(p.viewer()) + " views " + name(p.owner()) + "'s city, logged at step " + engine.step()
					+ (refusal == null ? "" : "; couldn't begin: " + refusal));
		}
		ViewRequests.End e;
		while ((e = r.takeEnd()) != null) {
			View v = engine.view(e.viewer());
			boolean over = v != null && (v.state() == View.CANCELLED || v.state() == View.ENDED || v.thaw() >= 0);
			if (!over)
				engine.viewEnd(e.viewer(), e.why());
			log.line("viewclose: " + name(e.viewer()) + "'s view ends (" + why(e.why()) + ") at step " + engine.step()
					+ (over ? "; it was over already" : ""));
		}
	}

	// ---------------------------------------------------------------- the owner

	private void owner(ViewRequests r) {
		for (int viewer : r.expire())
			log.line("viewanswer: no answer to " + name(viewer) + "'s ask in 30 s: refused");
		ViewRequests.Ask a = r.ask();
		if (a == null && popup != null) {
			popup.close();
			popup = null;
		} else if (a != null && (popup == null || popup.viewer != a.viewer() || !shown(popup))) {
			if (popup != null)
				popup.close();
			popup = new MpViewAsk(this, a.viewer(), name(a.viewer()));
			popup.open();
		}
		int local = r.local();
		long t0 = System.nanoTime();
		byte[] out = engine.viewOut();
		if (out != null) {
			int sent = 0;
			for (int slot = 0; slot < LobbySettings.MAX_PLAYERS; slot++) { // an assault's viewers all freeze at one step
				View v = engine.view(slot);
				if (v != null && v.owner == local && v.freeze == engine.step() && v.frozen()) {
					r.city(v.viewer, v.freeze, out);
					log.line(String.format(Locale.ROOT, "viewout: this city for %s's view from step %d: %d bytes, saved in %.0f ms",
							name(v.viewer), v.freeze, out.length, (System.nanoTime() - t0) / 1e6));
					sent++;
				}
			}
			if (sent == 0)
				throw new IllegalStateException("SOSGSGMP: the engine handed out a city at step " + engine.step() + " for no view");
		}
		for (int slot = 0; slot < LobbySettings.MAX_PLAYERS; slot++) {
			View v = engine.view(slot);
			if (v == null || v.owner != local)
				continue;
			byte[] tape = engine.viewTapeOut(v.viewer); // after its thaw too: the watcher still needs the last steps
			if (tape.length > 0)
				r.tape(v.viewer, tape);
			if (v.frozen()) {
				byte[] h = engine.viewHashOut(v.viewer);
				if (h.length > 0)
					r.hashes(v.viewer, h);
			}
		}
	}

	/** The owner's popup: its player allows or refuses. */
	void answer(int viewer, boolean allow) {
		ViewRequests r = setup.views();
		if (r == null)
			return;
		r.answer(viewer, allow);
		log.line("viewanswer: " + (allow ? "allowed " : "refused ") + name(viewer) + "'s ask at step " + engine.step());
		if (popup != null && popup.viewer == viewer) {
			popup.close();
			popup = null;
		}
	}

	// ---------------------------------------------------------------- the viewer

	private void viewer(ViewRequests r) {
		Message.ViewAnswer answer = r.takeAnswer();
		if (answer != null) {
			log.line("viewanswer: " + name(answer.owner()) + (answer.allowed() ? " allowed the view" : " didn't: " + answer.reason()));
			if (!answer.allowed())
				message(refusal(answer));
		}
		int local = r.local();
		View v = engine.view(local);
		boolean open = v != null && v.state() != View.ENDED && v.state() != View.CANCELLED; // the engine keeps an ended view
		byte[] hashes = r.takeHashes(open, engine.viewingHere());
		if (v == null)
			return;
		if (v != seen) {
			seen = v;
			seenState = -1;
			closeSent = caughtUpLogged = desyncLogged = false;
			r.dropTape();
			r.takeHashes(false, false); // late pieces of an older view: this one's come after its freeze
		}
		if (v.state() == View.LOADING && engine.step() == v.freeze) {
			loading(v);
			byte[] city = r.takeCity(v.freeze);
			if (city != null) {
				engine.viewIn(city);
				log.line("viewin: " + name(v.owner) + "'s city, " + city.length + " bytes, loading at step " + engine.step());
			}
		}
		if (engine.viewingHere()) {
			for (byte[] t = r.takeTape(); t != null; t = r.takeTape())
				engine.viewTapeIn(t);
			if (hashes.length > 0)
				engine.viewHashIn(hashes);
			limits(r, v);
		}
		bar(v);
		if (v.state() != seenState)
			stateChanged(v);
	}

	/** While viewing: a lost step or a limit asks the host to end the view, once; catching up is logged. */
	private void limits(ViewRequests r, View v) {
		if (!caughtUpLogged && engine.viewCaughtUpSeconds() >= 0) {
			caughtUpLogged = true;
			log.line(String.format(Locale.ROOT, "viewcaughtup: with %s's city %.1f s after the load, at step %d", name(v.owner),
					engine.viewCaughtUpSeconds(), engine.step()));
		}
		if (closeSent || v.thaw() >= 0)
			return;
		if (engine.viewDesync()) {
			if (!desyncLogged) {
				desyncLogged = true;
				log.line("viewdesync: this copy of " + name(v.owner) + "'s city differs from theirs at step " + engine.step());
			}
			close(View.DESYNC);
			return;
		}
		int limit = engine.viewLimit();
		if (limit != View.OPEN) {
			log.line("viewlimit: " + why(limit) + ", " + engine.behindHost() + " steps behind the host at step " + engine.step());
			close(limit);
		}
	}

	/** This PC's player, or its limits, close its view. */
	void close(int why) {
		ViewRequests r = setup.views();
		if (r == null || closeSent || why == View.CLOSED && closeRefusal() != null)
			return;
		closeSent = true;
		r.close(why);
	}

	/** An attacker orders the retreat from the assault: its soldiers leave the city, and the view ends with it (spec §9). */
	void retreat() {
		ViewRequests r = setup.views();
		if (r != null && !closeSent) {
			closeSent = true;
			r.close(View.RETREAT);
			log.line("this PC's player ordered the retreat at step " + engine.step());
		}
	}

	/** Why this PC's player can't close its view; null when they can. Nobody walks out of an assault (spec §9). */
	String closeRefusal() {
		return engine.assault() != null && !engine.spectating() ? "You stay until the assault ends." : null;
	}

	private void stateChanged(View v) {
		int was = seenState;
		seenState = v.state();
		if (v.state() != View.LOADING && loadingShown) {
			loadingShown = false;
			if (VIEW.inters().fullScreen.isActivated())
				VIEW.inters().fullScreen.deactivate();
		}
		if (v.state() == View.CANCELLED) {
			log.line("viewend: " + name(v.owner) + "'s city never opened: " + v.reason());
			message("The view of " + name(v.owner) + "'s " + (engine.localBattle(v.owner) ? "battle" : "city") + " didn't open: "
					+ v.reason());
		} else if (v.state() == View.ENDED && was >= 0) {
			log.line("viewend: " + name(v.owner) + "'s city, " + why(v.why()) + (v.reason() == null ? "" : " (" + v.reason() + ")")
					+ ", thawed at step " + v.thaw());
			if (v.why() != View.CLOSED)
				message(ended(v));
		} else if (v.state() == View.RUNNING && was == View.LOADING) {
			log.line(String.format(Locale.ROOT, "viewloaded: %s's city at step %d, the load took %.0f ms", name(v.owner),
					engine.step(), engine.lastViewLoadMs()));
		}
	}

	private void loading(View v) {
		if (loadingShown && VIEW.inters().fullScreen.isActivated())
			return;
		loadingShown = true;
		VIEW.inters().fullScreen.activate("Loading " + subject(v) + "...", COLOR.WHITE100, null);
	}

	private void bar(View v) {
		boolean viewing = engine.viewingHere();
		if (!viewing) {
			if (bar != null) {
				bar.close();
				bar = null;
			}
			return;
		}
		if (!shown(bar)) {
			boolean attacker = engine.assault() != null && engine.assault().attacks(setup.views().local());
			bar = new MpViewBar(this, (attacker ? "Assault on " : v.battle() ? "Watching " : "Viewing ") + subject(v),
					attacker ? "You command the invaders." : null);
			bar.open();
			if (attacker)
				VIEW.s().battle.activate(); // the battle UI: the attacker's soldiers are theirs to order
		}
		bar.closing(closeSent || v.thaw() >= 0);
	}

	// ---------------------------------------------------------------- the host's own view, on every other PC

	/**
	 * While the host's PC opens its view, every PC holds at its freeze step: they see why (spec §10), once the hold has
	 * lasted {@link #HOLD_NOTICE_NANOS}, as every PC passes that step anyway.
	 */
	private void hostHold() {
		if (hostSlot < 0)
			hostSlot = slotOf(hostId());
		View v = hostSlot == setup.views().local() ? null : engine.view(hostSlot);
		boolean holding = v != null && v.frozen() && engine.step() == v.freeze;
		if (!holding) {
			holdSince = -1;
			if (hostHold != null) {
				hostHold.close();
				hostHold = null;
			}
			return;
		}
		long now = System.nanoTime();
		if (holdSince < 0)
			holdSince = now;
		if (now - holdSince >= HOLD_NOTICE_NANOS && !shown(hostHold)) {
			hostHold = new MpViewNotice("The host is opening a view of " + name(v.owner) + "'s city. The game waits until"
					+ " it is open, a few seconds.", false);
			hostHold.open();
		}
	}

	// ---------------------------------------------------------------- the faction window's button

	private static final int CAN_ASK = 0, NOT_RUNNING = 1, WAITING = 2, BATTLE = 3, VIEWING = 4, VIEWED = 5, OWNER_VIEWING = 6;

	/**
	 * Whether this PC's player can ask to view the city of the player in {@code owner} now: {@link #CAN_ASK}, or why
	 * not. Each frame the faction window draws, so it allocates nothing.
	 */
	private int askBlock(int owner) {
		ViewRequests r = setup.views();
		if (r == null || setup.closed() || !engine.active())
			return NOT_RUNNING;
		int local = r.local();
		if (r.asked() >= 0)
			return WAITING;
		if (engine.battle() != null || engine.localBattle(local))
			return BATTLE;
		if (open(engine.view(local)))
			return VIEWING;
		for (int slot = 0; slot < LobbySettings.MAX_PLAYERS; slot++) {
			View v = engine.view(slot);
			if (open(v) && v.owner == local)
				return VIEWED;
		}
		if (engine.localBattle(owner))
			return CAN_ASK; // the engine answers when it is starting, ending or watched already
		return open(engine.view(owner)) ? OWNER_VIEWING : CAN_ASK;
	}

	/** Whether this PC's player can ask to view the city of the player in {@code owner} now. No allocation. */
	boolean canAsk(int owner) {
		return askBlock(owner) == CAN_ASK;
	}

	/** Why this PC's player can't ask to view the city of the player in {@code owner} now; null when they can. */
	String askRefusal(int owner) {
		return switch (askBlock(owner)) {
			case CAN_ASK -> null;
			case NOT_RUNNING -> "The game isn't running.";
			case WAITING -> "Waiting for " + name(setup.views().asked()) + "'s answer.";
			case BATTLE -> "No views during a battle.";
			case VIEWING -> "You are viewing a city already.";
			case VIEWED -> "Your own city is being viewed.";
			default -> name(owner) + " is viewing another city.";
		};
	}

	/** Whether the player in {@code owner} fights an NPC battle: the button then watches it, without asking (spec §8). */
	boolean watches(int owner) {
		return engine.active() && engine.localBattle(owner);
	}

	/** Whether this PC's player is waiting for an answer from the player in {@code owner}. */
	boolean asked(int owner) {
		ViewRequests r = setup.views();
		return r != null && r.asked() == owner;
	}

	/** The button: asks to view the city of the player in {@code owner}. Check {@link #askRefusal} first. */
	void ask(int owner) {
		if (!canAsk(owner))
			return;
		setup.views().ask(owner);
		log.line("viewask: this PC asks to view " + name(owner) + "'s " + (watches(owner) ? "battle" : "city") + " at step "
				+ engine.step());
	}

	/**
	 * Whether {@code w} shows in the game's current view: a whole game's load (a view's, a battle's) builds a new view,
	 * so a window shown in the old one has to be shown again.
	 */
	private static boolean shown(Interrupter w) {
		return w != null && w.isActivated() && w.manager() == VIEW.inters().manager;
	}

	private static boolean open(View v) {
		return v != null && (v.state() == View.SCHEDULED || v.frozen());
	}

	// ---------------------------------------------------------------- text

	private static String stateName(int state) {
		return switch (state) {
			case View.SCHEDULED -> "scheduled";
			case View.LOADING -> "loading";
			case View.RUNNING -> "running";
			case View.CANCELLED -> "cancelled";
			case View.ENDED -> "ended";
			default -> "state " + state;
		};
	}

	private static String why(int why) {
		return switch (why) {
			case View.CLOSED -> "closed";
			case View.NOT_CAUGHT_UP -> "couldn't catch up";
			case View.FELL_BEHIND -> "fell behind";
			case View.DESYNC -> "lost step";
			case View.GAME -> "ended by the game";
			default -> "reason " + why;
		};
	}

	/** What the view shows, for the player's text. */
	private String subject(View v) {
		return name(v.owner) + (v.battle() ? "'s battle" : "'s city");
	}

	/** Why the view ended, for the viewer: the spec's messages, with the owner's name. */
	private String ended(View v) {
		if (v.battle())
			return v.reason() == null ? "The watch of " + subject(v) + " ended." : v.reason();
		String name = name(v.owner);
		return switch (v.why()) {
			case View.NOT_CAUGHT_UP -> "Your PC couldn't catch up with " + name + "'s city.";
			case View.FELL_BEHIND -> "Your PC fell behind " + name + "'s city for too long.";
			case View.DESYNC -> "The view lost step with " + name + "'s city.";
			default -> "The view of " + name + "'s city ended: " + v.reason();
		};
	}

	private String refusal(Message.ViewAnswer a) {
		String name = name(a.owner());
		return switch (a.reason()) {
			case ViewRequests.REFUSED -> name + " refused to let you view their city.";
			case ViewRequests.NO_ANSWER -> name + " didn't answer your request to view their city.";
			case ViewRequests.LEFT -> name + " left the game.";
			default -> "You can't view " + name + "'s city: " + a.reason();
		};
	}

	/** A message to this PC's player, in the chat (spec §12). */
	private void message(String text) {
		setup.chat().notice(text);
	}

	/** The name of the player in {@code slot}. */
	String name(int slot) {
		for (LobbyPlayer p : setup.players())
			if (slot(p) == slot)
				return p.name();
		return "Slot " + slot;
	}

	private int slot(LobbyPlayer p) {
		return setup.factions().slotOf(p.id());
	}

	private int slotOf(int id) {
		return setup.factions().slotOf(id);
	}

	private int hostId() {
		List<LobbyPlayer> players = setup.players();
		for (LobbyPlayer p : players)
			if (p.host())
				return p.id();
		return players.get(0).id();
	}
}
