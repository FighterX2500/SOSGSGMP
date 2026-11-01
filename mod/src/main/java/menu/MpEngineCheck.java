package menu;

import snake2d.Errors;

/**
 * SOSGSGMP: the message when SOSDET isn't enabled (SOS-127). The game loads every class of a script jar at start-up, and
 * hook M11 calls this when one won't load. It names no engine class, since it runs when they are missing.
 */
public final class MpEngineCheck {

	private MpEngineCheck() {
	}

	/** Stops with the game's own notice if SOSDET is missing; if it isn't, the caller's error stands. */
	public static void check() {
		try {
			Class.forName("sosdet.Sosdet", false, MpEngineCheck.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new Errors.GameError("SOS GSG MP needs the SOSDET mod, and SOSDET isn't enabled.\n\n"
					+ "Subscribe to SOSDET on the Steam Workshop and enable it in the game's launcher, or disable SOS GSG MP.");
		}
	}
}
