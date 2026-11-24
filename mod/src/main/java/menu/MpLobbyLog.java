package menu;

import java.time.Clock;

import snake2d.LOG;
import sosgsgmp.game.DataFolders;
import sosgsgmp.lobby.Builds;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.log.PlayLog;
import sosgsgmp.session.LobbySession;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: the playtest log from Host, Join, a resume or a Steam join on (SOS-246), before any world loads: the system
 * line, the lobby's players, its refusals, its start and its end. A lobby that starts gives its file to the setup, and
 * the game's {@link MpLog} goes on in it; a lobby or a setup left without a game closes it. Each frame compares a
 * counter; it builds text only when it writes.
 */
final class MpLobbyLog {

	private final LobbySession session;
	private final PlayLog log;
	private int changes = -1;
	private LobbyState state;
	private String players = "";

	private MpLobbyLog(LobbySession session, PlayLog log) {
		this.session = session;
		this.log = log;
	}

	/** A new file for the lobby {@code session} this PC's player {@code name} opens, with the system line first. */
	static MpLobbyLog open(LobbySession session, String name, Builds builds) {
		PlayLog log = PlayLog.open(DataFolders.layout().logs(), (session.isHost() ? "host-" : "join-") + name, Clock.systemDefaultZone());
		if (log.failure() == null)
			LOG.ln("SOSGSGMP: playtest log " + log.file());
		else
			LOG.err("SOSGSGMP: the playtest log " + log.file() + " can't be written: " + log.failure());
		log.line(MpLog.system(builds));
		log.line("lobby: " + (session.isHost() ? "hosting" : "joining") + " as " + name + ", network: " + session.network());
		session.keepLog(log);
		return new MpLobbyLog(session, log);
	}

	/** Every frame the lobby screen updates the session: its notes, and its state and players when they changed. */
	void frame() {
		String note;
		while ((note = session.takeNote()) != null)
			log.line("lobby: " + note);
		if (session.changes() == changes)
			return;
		changes = session.changes();
		if (session.state() != state) {
			state = session.state();
			log.line("lobby " + state.name().toLowerCase(java.util.Locale.ROOT) + (state == LobbyState.CLOSED ? ": " + session.closedReason() : "")
					+ (state == LobbyState.STARTED && session.running() ? (session.returning() ? ", coming back into the running game" : ", joining the running game") : ""));
		}
		StringBuilder b = new StringBuilder();
		for (LobbyPlayer p : session.players())
			b.append(b.length() == 0 ? "" : ", ").append(p.name()).append(" (id ").append(p.id()).append(')');
		if (!b.toString().equals(players)) {
			players = b.toString();
			log.line("lobby players: " + players);
		}
	}

	/** The lobby closed with no game: the last notes and {@code why}, and the file closes. */
	void close(String why) {
		frame();
		log.line("lobby left without a game: " + why);
		log.close();
	}

	/**
	 * A setup left before its world loaded (the map step, the factions step, a hot-join's pick): its network notes, then
	 * {@code why}, and the file closes. In the game, {@link MpLog} owns the file instead.
	 */
	static void left(SetupSession setup, String why) {
		PlayLog log = setup.log();
		if (log == null || setup.playing())
			return;
		String note;
		while ((note = setup.takeNote()) != null)
			log.line("network: " + note);
		log.line("setup left without a game: " + why);
		log.close();
	}
}
