package menu;

import sosgsgmp.game.PlayerSieges;

/**
 * SOSGSGMP: the siege of this PC's player's capital (spec §9; SOS-92): the notice when one begins, once per siege. The
 * engine keeps the siege; a new one has a new start, which is how this tells it from the last. Called from the session
 * panel's refresh while playing.
 */
final class MpSieges {

	/** The start of the last siege this PC's player was told of; -1 for none. */
	private int toldSince = -1;

	/** True while this PC's player's capital is besieged. */
	boolean besieged() {
		return PlayerSieges.besiegedSince() >= 0;
	}

	void refresh() {
		int since = PlayerSieges.besiegedSince();
		if (since < 0 || since == toldSince)
			return;
		toldSince = since;
		new MpSiegeNotice(PlayerSieges.assaultInDays()).open();
	}
}
