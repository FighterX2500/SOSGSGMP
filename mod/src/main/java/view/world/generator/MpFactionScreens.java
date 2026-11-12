package view.world.generator;

import snake2d.util.gui.GuiSection;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: what the race and Customize Faction screens ({@link MpFactionStage}) need from the flow that shows them:
 * the setup's factions step ({@link MpFactions}) or a hot-join's pick of its city-state ({@link MpJoinScreens},
 * SOS-198). Both show the same screens, each with its own side panel.
 */
interface MpFactionScreens {

	/** The screen's side: the players, the status line, {@code back} and {@code next} (either may be null), and Leave. */
	GuiSection side(GButt.ButtPanel back, GButt.ButtPanel next);

	/** {@code content}, greyed and taking no clicks while the picks can't change. */
	GuiSection locking(GuiSection content);

	/** Shows {@code content} in the generator's centred panel titled {@code title}, in place of any other screen. */
	void showCentred(GuiSection content, CharSequence title);

	/** Customize Faction's Next, to the location screen; null when Ready is on Customize (a living world's joiners). */
	GButt.ButtPanel afterCustomize();
}
