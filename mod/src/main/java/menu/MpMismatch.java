package menu;

import game.GAME;
import snake2d.CORE;
import sosdet.report.ReportMismatch;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: another PC's game data isn't this one's (SOS-159): its city report has other sizes, so the world can't read
 * it. The lobby's game files check keeps such PCs out; this ends the session if one gets past it, with what differs
 * instead of a crash. Every PC finds it in the others' reports by itself.
 */
final class MpMismatch {

	private final SetupSession setup;
	private final EnginePlay engine;
	private final WorldSetupAdapter game;
	private final MpLog log;
	private boolean ended;
	/** The window's text, once the session ended. */
	private String text;

	MpMismatch(SetupSession setup, EnginePlay engine, WorldSetupAdapter game, MpLog log) {
		this.setup = setup;
		this.engine = engine;
		this.game = game;
		this.log = log;
	}

	void frame() {
		if (ended || !engine.active())
			return;
		ReportMismatch m = engine.reportMismatch();
		if (m != null)
			end(m);
	}

	private void end(ReportMismatch m) {
		ended = true;
		String who = name(m.pc);
		text = "Your game data and " + who + "'s differ (" + m.what + ": " + m.ours + " here, " + m.theirs + " on " + who
				+ "'s PC). Everyone needs the same game files and the same mods, in the same order.";
		log.line("the session ends: " + m.getMessage());
		if (!setup.closed())
			setup.leave();
		game.endSession();
		GAME.SPEED.speedSet(0);
		MpPrompt.show(text + "\nThe session has ended.", new GButt.ButtPanel("Main menu") {
			@Override
			protected void clickA() {
				CORE.setCurrentState(Menu::make);
			}
		});
	}

	/**
	 * The player of PC {@code pc} (a PC's number is its slot); its realm's name once it left, as it does when its own PC
	 * found the mismatch first.
	 */
	private String name(int pc) {
		for (LobbyPlayer p : setup.players())
			if (setup.factions().slotOf(p.id()) == pc)
				return p.name();
		return engine.factionName(pc);
	}

	/** What differs, as the window says it; null until the session ends. */
	String text() {
		return text;
	}

	/** After the session ended here. */
	boolean ended() {
		return ended;
	}
}
