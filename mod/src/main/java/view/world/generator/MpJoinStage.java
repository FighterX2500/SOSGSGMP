package view.world.generator;

import view.interrupter.InterManager;
import view.tool.PLACABLE;
import view.tool.ToolConfig;

/**
 * SOSGSGMP hot-join (SOS-198): the newcomer's pick screen on the host's snapshot looks as the setup's location screen
 * ({@link MpLocationStage}): vanilla's world generator view, so no game bar, no city to enter and no fog, with the
 * minimap and the placer always on the map. It sits in the generator's package because reset(), tools, minimap and
 * window are package-private (docs/hooks.md, note next to M1).
 */
public final class MpJoinStage {

	private MpJoinStage() {
	}

	/**
	 * Shows the generator view with {@code placer} on the map, centred on {@code cx, cy}; returns its interrupter manager
	 * for the caller's panel. On a loaded game, which keeps the running game's view behind it.
	 */
	public static InterManager open(PLACABLE placer, int cx, int cy) {
		WorldViewGenerator g = new WorldViewGenerator();
		g.activate();
		g.reset();
		g.minimap.show();
		g.tools.place(placer, new ToolConfig() {
			@Override
			public boolean back() {
				return false;
			}
		});
		g.window.centererTile.set(cx, cy);
		return g.uiManager;
	}
}
