package menu;

import java.util.ArrayList;
import java.util.List;

import snake2d.util.datatypes.COORDINATE;
import sosgsgmp.game.PlayerStations;
import sosgsgmp.session.SetupSession;
import world.entity.army.WArmy;
import world.map.regions.Region;

/**
 * SOSGSGMP (owner, 2026-11-19): this PC's player's stationing outside the Armies tab, for one session; {@link MpViews}
 * owns it.
 * <ul>
 * <li>Hook M15: over the capital of another player this player can station an army at, the world map's move tool says
 * Station instead of Besiege. A click marches the army beside that capital; once it stands still in reach, this PC
 * stations it. Any other order the player gives the army meanwhile wins: an army that stops out of reach is let be.
 * <li>An invasion of a capital where this player's army is stationed: a window asks whether they watch the city, to
 * command their soldiers there, or decline, which gives the host command of them. No answer in 30 seconds declines.
 * </ul>
 */
public final class MpStationFlow {

	/** Hook M15's hover over a partner's capital. */
	public static final String HOVER = "Station";
	/** Frames an ordered army may take to start moving before its order counts as replaced (about 10 seconds). */
	private static final int START_FRAMES = 600;

	private final MpViews views;
	private final SetupSession setup;
	private final MpLog log;
	private final List<March> marching = new ArrayList<>();
	private MpStationDefend defend;

	/** An army marching to a capital to be stationed there. */
	private static final class March {
		final WArmy army;
		final int host;
		boolean moved;
		int waited;

		March(WArmy army, int host) {
			this.army = army;
			this.host = host;
		}
	}

	MpStationFlow(MpViews views, SetupSession setup, MpLog log) {
		this.views = views;
		this.setup = setup;
		this.log = log;
	}

	/** Hook M15, the move tool's hover: true over the capital of another player this PC's player can station {@code a} at. */
	public static boolean offers(WArmy a, Region reg) {
		int host = PlayerStations.capitalOf(reg);
		return host >= 0 && MpViews.current() != null && PlayerStations.mine(a) && PlayerStations.partner(host);
	}

	/** Hook M15 (owner, 2026-11-19): why the move tool can't move {@code a}, stationed with divisions in the host's city, or null. */
	public static CharSequence moveRefusal(WArmy a) {
		return MpViews.current() == null ? null : PlayerStations.moveRefusal(a);
	}

	/** Hook M15 (owner, 2026-11-19): why the move tool can't attack or intercept {@code a}, a stationed army, or null. */
	public static CharSequence attackRefusal(WArmy a) {
		return MpViews.current() == null ? null : PlayerStations.attackRefusal(a);
	}

	/** Hook M15: a move the tool refused; the click opens a popup saying why, as the owner asked (2026-11-19). */
	public static void blocked(CharSequence why) {
		view.main.VIEW.inters().yesNo.activate(why, snake2d.util.misc.ACTION.NOP, snake2d.util.misc.ACTION.NOP, true);
	}

	/** Hook M15, the move tool's click on such a capital: the army marches beside it and is stationed when it stands still. */
	public static void order(WArmy a, Region reg) {
		MpViews v = MpViews.current();
		if (v != null)
			v.stations().march(a, PlayerStations.capitalOf(reg), reg);
	}

	private void march(WArmy a, int host, Region reg) {
		if (host < 0 || !PlayerStations.mine(a))
			return;
		marching.removeIf(m -> m.army == a);
		if (PlayerStations.inReach(a, host) && PlayerStations.standsStill(a)) {
			station(a, host);
			return;
		}
		COORDINATE t = PlayerStations.tileBeside(a, reg);
		if (t == null) {
			setup.chat().notice("Your army can't reach " + views.name(host) + "'s capital.", host);
			return;
		}
		PlayerStations.march(a, t.x(), t.y());
		marching.add(new March(a, host));
		log.line("station march: this PC's army " + a.armyIndex() + " marches to " + views.name(host) + "'s capital, to "
				+ t.x() + "," + t.y());
	}

	/** Once per frame while playing, between steps. */
	void frame() {
		for (int i = marching.size() - 1; i >= 0; i--) {
			March m = marching.get(i);
			if (!PlayerStations.mine(m.army)) {
				marching.remove(i);
				continue;
			}
			boolean still = PlayerStations.standsStill(m.army);
			if (!m.moved) {
				m.moved = !still;
				if (still && ++m.waited > START_FRAMES)
					marching.remove(i); // the order never started: another one replaced it
				continue;
			}
			if (!still)
				continue;
			marching.remove(i);
			if (PlayerStations.inReach(m.army, m.host))
				station(m.army, m.host);
		}
		if (defend != null && defend.frameEnds())
			defend = null;
	}

	private void station(WArmy a, int host) {
		String why = PlayerStations.stationRefusal(a, host);
		if (why == null) {
			PlayerStations.station(a, host);
			log.line("station march: this PC stations army " + a.armyIndex() + " at " + views.name(host) + "'s capital");
		} else {
			setup.chat().notice("Your army couldn't be stationed at " + views.name(host) + "'s capital: " + why, host);
		}
	}

	/**
	 * An invasion of the capital of the player in {@code host}, where this PC's player's army is stationed (spec §9): the
	 * chat says so and a window asks whether they watch. When they can't view the city now, the host commands their
	 * soldiers at once.
	 */
	void invaded(int host, String invasion) {
		String refusal = views.askRefusal(host);
		if (refusal != null) {
			setup.chat().notice(invasion + " Your army stationed there fights in their city; " + views.name(host)
					+ " commands it, since you can't view the city now (" + refusal + ")", host);
			decline(host);
			return;
		}
		setup.chat().notice(invasion + " Your army stationed there fights in their city.", host);
		if (defend != null)
			defend.close();
		defend = new MpStationDefend(this, host, views.name(host));
		defend.open();
	}

	/** The window's Watch: this PC asks to view the city, where its player commands their soldiers. */
	void watch(int host) {
		log.line("station defend: this PC watches " + views.name(host) + "'s city");
		views.ask(host);
	}

	/** The window's Decline, or no answer: the host commands this player's soldiers stationed in their city. */
	void decline(int host) {
		log.line("station defend: " + views.name(host) + " commands this PC's stationed soldiers");
		for (PlayerStations.Stationed s : PlayerStations.all()) {
			if (s.host() == host && PlayerStations.mine(s.army()) && !PlayerStations.granted(s.army())
					&& PlayerStations.grantRefusal(s.army()) == null)
				PlayerStations.grant(s.army(), true);
		}
	}
}
