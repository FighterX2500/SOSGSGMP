package view.sett.ui.minimap;

import snake2d.util.datatypes.RECTANGLE;

/** SOSGSGMP: the city minimap's map, package-private in the game (SOS-124). */
public final class MpCityMinimap {

	private MpCityMinimap() {
	}

	/** The map's body on the screen. */
	public static RECTANGLE map(UIMinimapSett minimap) {
		return minimap.map.body();
	}
}
