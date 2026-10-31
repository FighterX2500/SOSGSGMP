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
	private Assault assaultShown;
	/** Campaign saves: the host's orders, and every PC's save at their step (SOS-64). */
	private final MpSaves saves;
	/** The escape menu's save and quit, and every save that ends the session (SOS-79). */
	private final MpExit exit;
	/** City viewing: the requests, the owner's city going out, the viewer's load and limits (SOS-82). */
	private final MpViews views;
	/** The engine's events as chat lines (SOS-110). */
	private final MpNotices notices;
	/** The pause every PC shares, and who set it (spec §4, SOS-110). */
	private final MpPause pause;

	MpPlay(SetupSession setup, EnginePlay engine, WorldSetupAdapter game, MpLog log, MpSaves saves, MpExit exit) {
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
				notices.frame();
			}
		}
		if (setup.playing()) {
			gone.frame();
			if (!gone.ended()) { // Return to the last save ended the engine
				desync.frame();
				views.frame();
				saves.frame();
				exit.frame(); // after the saves: a save that ends the session ends it in the frame it is done
				pause.frame();
			}
		}
		if (autopilot != null)
			autopilot.game(setup, engine, desync, saves, gone, exit, views);
	}

	/** A battle between two players ended (SOS-83): the log and the chat note it, and the loser gets its window. */
	private void outcome() {
		BattleOutcome o = engine.battleOutcomeTake();
		if (o == null)
			return;
		log.line("battle between players over: " + o);
		notices.battleOver(o);
		if (o.loser == setup.factions().slotOf(setup.localPlayerId()))
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
			setup.chat().notice(slot == setup.factions().slotOf(setup.localPlayerId()) ? line
					: line + " You can ask to view the city from their window.");
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
