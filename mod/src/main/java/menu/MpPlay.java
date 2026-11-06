package menu;

import java.util.List;

import snake2d.util.misc.ACTION;
import sosdet.Assault;
import sosdet.game.siege.AssaultTerms;
import sosdet.BattleOutcome;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.SetupSession;

import view.main.VIEW;

/**
 * SOSGSGMP: the game over the network, once per frame after the game's update, outside the step
 * (game-over-network.md §3): what arrived goes to the engine, and once every PC placed its landing party
 * ({@code Go}) this PC's engine starts and its streams go out every frame. The setup panel shows the state; this does
 * the work, so it runs whatever view or window is open.
 */
final class MpPlay implements ACTION {

	private final SetupSession setup;
	private final EnginePlay engine;
	/** The desync window: it runs the host's choice on every PC. */
	private final MpDesync desync;
	/** The window when a player left: it holds every PC and runs the host's choice (SOS-65). */
	private final MpGone gone;
	/** Test tooling: the autopilot, or null in play. */
	private final MpAutopilot autopilot = MpAutopilot.fromEnv();
	private final MpLog log;
	private boolean spectatorShown;
	private boolean lastShown;
	/** The slots whose player fights a battle against NPCs, as last told in the chat (SOS-144). */
	private final boolean[] fighting = new boolean[game.faction.FACTIONS.MAX()];
	/** The slots whose capital is invaded, as the log last noted (SOS-143). */
	private final boolean[] invaded = new boolean[game.faction.FACTIONS.MAX()];
	/** Steps a spectator's PC may fall behind: 45 s at 25×, 5 minutes at 1×. */
	private static final long SPECTATOR_LAG = 18000;
	private Assault assaultShown;
	/** Campaign saves: the host's orders, and every PC's save at their step (SOS-64). */
	private final MpSaves saves;
	/** The escape menu's save and quit, and every save that ends the session (SOS-79). */
	private final MpExit exit;
	/** City viewing: the requests, the owner's city going out, the viewer's load and limits (SOS-82). */
	private final MpViews views;
	/** The engine's events as chat lines (SOS-110). */
	private final MpNotices notices;
	private MpAllyJoin ally;
	/** The pause every PC shares, and who set it (spec §4, SOS-110). */
	private final MpPause pause;
	/** A joining PC's logs and saves on their way to the host (SOS-133). */
	private final MpReports reports;
	/** The loading window during the freezes every PC waits through (SOS-158). */
	private final MpLoading loading;
	/** Another PC's game data isn't this one's: the session ends with what differs (SOS-159). */
	private final MpMismatch mismatch;

	MpPlay(SetupSession setup, EnginePlay engine, WorldSetupAdapter game, MpLog log, MpSaves saves, MpExit exit, MpReports reports) {
		this.setup = setup;
		this.engine = engine;
		this.desync = new MpDesync(setup, engine, saves);
		this.gone = new MpGone(setup, engine, game, saves);
		this.log = log;
		this.saves = saves;
		this.exit = exit;
		this.views = new MpViews(setup, engine, log);
		this.notices = new MpNotices(setup, engine);
		this.pause = new MpPause(setup, engine);
		this.reports = reports;
		this.loading = new MpLoading(setup, engine);
		this.mismatch = new MpMismatch(setup, engine, game, log);
	}

	@Override
	public void exe() {
		try {
			frame();
		} catch (RuntimeException | Error e) {
			// the mod's per-frame boundary: the playtest log keeps what broke, and the game reports it as before
			log.error("the game over the network failed at step " + (engine.active() ? engine.step() : -1), e);
			throw e;
		}
		log.frame(desync, exit);
	}

	private void frame() {
		if (!setup.closed()) {
			setup.update();
			if (!setup.playing() && !setup.closed() && setup.factions().started()) {
				engine.start(setup.isHost(), otherPcs(setup));
				setup.play(engine);
			}
			if (setup.playing()) {
				setup.exchange();
				engine.battleFrame();
				outcome();
				assaultStart();
				assaultOutcome();
				assaultTerms();
				spectator();
				invasions();
				battles();
				notices.frame();
				allyPrompt();
				reports.frame();
			}
		}
		if (setup.playing()) {
			mismatch.frame();
			if (!mismatch.ended()) // the session ended here: the connections it closed are no players gone
				gone.frame();
			if (!gone.ended() && !mismatch.ended()) { // Return to the last save, or other game data, ended the engine
				desync.frame();
				views.frame();
				saves.frame();
				exit.frame(); // after the saves: a save that ends the session ends it in the frame it is done
				if (engine.active())
					pause.frame();
			}
		}
		loading.frame();
		if (autopilot != null && mismatch.ended())
			autopilot.mismatch(mismatch.text());
		if (autopilot != null)
			autopilot.game(setup, engine, desync, saves, gone, exit, views, loading);
	}

	/** The window asking this PC's player to join an ally's battle, while the battle's prompt waits for them (SOS-118). */
	private void allyPrompt() {
		boolean asked = engine.allyAsked();
		if (asked && ally == null) {
			ally = new MpAllyJoin(engine);
			ally.open();
		} else if (!asked && ally != null) {
			ally.close();
			ally = null;
		}
	}

	/** A battle between two players ended (SOS-83): the log and the chat note it, and the loser gets its window. */
	private void outcome() {
		BattleOutcome o = engine.battleOutcomeTake();
		if (o == null)
			return;
		log.line("battle between players over: " + o);
		notices.battleOver(o);
		if (o.lost(setup.factions().slotOf(setup.localPlayerId())))
			new MpBattleOutcome(o).open();
	}

	/**
	 * The world took an assault's outcome (SOS-92 C): the chat tells every player, and the attacker and the defender get
	 * its window.
	 */
	private void assaultOutcome() {
		Assault a = engine.assaultOutcomeTake();
		if (a == null)
			return;
		log.line("assault outcome applied at step " + engine.step() + ": " + a);
		setup.chat().notice(MpAssaultOutcome.othersText(notices.name(a.attacker), notices.name(a.defender), a.outcome()));
		int slot = setup.factions().slotOf(setup.localPlayerId());
		if (slot == a.chooser && a.outcome() == Assault.TAKEN) {
			new MpAssaultTerms(a.defender).open();
			return;
		}
		if (a.attacks(slot) || slot == a.defender) {
			new MpAssaultOutcome(a.attacker, a.defender, a.outcome(), a.men, a.attacks(slot)).open();
		}
	}

	/** The attacker's terms after a taken throne applied (SOS-92 D): the chat tells every player, and the defender gets a window. */
	private void assaultTerms() {
		Assault a = engine.assaultTermsTake();
		if (a == null)
			return;
		String what = MpAssaultOutcome.termsText(engine.factionName(a.attacker), engine.factionName(a.defender), a.termsKind(), a.termsPercent(),
				a.termsCredits(), a.termsPeople(AssaultTerms.MOVED), a.termsPeople(AssaultTerms.KILLED), a.termsPeople(AssaultTerms.FREED));
		log.line("assault terms applied at step " + engine.step() + ": " + what);
		setup.chat().notice(MpAssaultOutcome.termsText(notices.name(a.attacker), notices.name(a.defender), a.termsKind(), a.termsPercent(),
				a.termsCredits(), a.termsPeople(AssaultTerms.MOVED), a.termsPeople(AssaultTerms.KILLED), a.termsPeople(AssaultTerms.FREED)));
		if (setup.factions().slotOf(setup.localPlayerId()) == a.defender)
			new MpViewNotice("Assault", what, true).open();
	}

	/** This PC's player was eliminated: the world map, and a word on why nothing works any more (SOS-111). */
	/** An assault on this PC's player's city: they go to its battle view at once, their soldiers are theirs to order. */
	private void assaultStart() {
		Assault a = engine.assault();
		if (a == null || a == assaultShown)
			return;
		assaultShown = a;
		if (a.defender == setup.factions().slotOf(setup.localPlayerId()))
			VIEW.s().battle.activate();
	}

	private void spectator() {
		if (setup.isHost()) {
			for (int slot = 0; slot < LobbySettings.MAX_PLAYERS; slot++)
				if (engine.spectator(slot) && !setup.campaign().inSlot(slot).eliminated())
					setup.eliminated(slot);
				else if (engine.spectator(slot) && engine.view(slot) == null && engine.peerStep(slot) >= 0
						&& engine.step() - engine.peerStep(slot) > SPECTATOR_LAG)
					setup.dropSpectator(slot);
			for (Integer slot = setup.takeSpectatorLeft(); slot != null; slot = setup.takeSpectatorLeft())
				engine.spectatorLeft(slot);
		}
		if (!lastShown && engine.lastHuman() >= 0) {
			lastShown = true;
			setup.chat().notice(notices.name(engine.lastHuman()) + " is the last player standing. Play goes on.");
		}
		if (spectatorShown || !engine.spectating())
			return;
		spectatorShown = true;
		log.line("this PC's player is a spectator from step " + engine.step());
		VIEW.world().activate();
		new MpViewNotice("Eliminated", "Your city has fallen. You stay in the game as a spectator: you can use the chat, but you"
				+ " can't act any more.", true).open();
	}

	/** An NPC army invades a player's capital (SOS-92 F): the chat tells every player, and the playtest log notes it. */
	private void invasions() {
		long[] n;
		while ((n = engine.invasionNoticeTake()) != null) {
			int slot = (int) n[1];
			String text = MpAssaultOutcome.invasionText(engine.factionName(slot), engine.factionName((int) n[2]));
			log.line("invasion at step " + n[0] + ": " + text);
			String line = MpAssaultOutcome.invasionText(notices.name(slot), engine.factionName((int) n[2]));
			if (slot == setup.factions().slotOf(setup.localPlayerId()))
				setup.chat().notice(line);
			else
				setup.chat().notice(line + " Click here to open their window and View city.", slot);
		}
		List<LobbyPlayer> players = setup.players();
		for (int i = 0; i < players.size(); i++) { // the log notes each invasion's end, as the world reads it (SOS-143)
			int slot = setup.factions().slotOf(players.get(i).id());
			if (slot < 0 || engine.invading(slot) == invaded[slot])
				continue;
			invaded[slot] = !invaded[slot];
			if (!invaded[slot])
				log.line("invasion of " + engine.factionName(slot) + "'s capital over at step " + engine.step());
		}
	}

	/**
	 * Another player's battle against NPCs starts or ends (SOS-144): the chat tells this PC's player, and a click on the
	 * start opens their window, where Watch battle joins it. Their own PC says nothing: the battle is on its screen.
	 */
	private void battles() {
		int me = setup.factions().slotOf(setup.localPlayerId());
		List<LobbyPlayer> players = setup.players();
		for (int i = 0; i < players.size(); i++) {
			int slot = setup.factions().slotOf(players.get(i).id());
			if (slot < 0 || slot == me || engine.localBattle(slot) == fighting[slot])
				continue;
			fighting[slot] = !fighting[slot];
			if (fighting[slot])
				setup.chat().notice(notices.name(slot) + " is fighting a battle against NPCs. Click here to open their window,"
						+ " then Watch battle to join it.", slot);
			else
				setup.chat().notice(notices.name(slot) + "'s battle against NPCs is over.");
		}
	}

	/** The other players' PC numbers: their slots. */
	/** Every other player's PC: their slots (the host's is any slot when a campaign resumes). */
	static int[] otherPcs(SetupSession setup) {
		List<LobbyPlayer> players = setup.players();
		int[] pcs = new int[players.size() - 1];
		int n = 0;
		for (LobbyPlayer p : players)
			if (p.id() != setup.localPlayerId())
				pcs[n++] = setup.factions().slotOf(p.id());
		return pcs;
	}
}
