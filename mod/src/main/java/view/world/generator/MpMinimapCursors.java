package view.world.generator;

import java.util.function.BooleanSupplier;

import menu.MpCursors;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.datatypes.RECTANGLE;
import util.gui.misc.GBox;
import view.interrupter.Interrupter;
import view.main.VIEW;

/**
 * SOSGSGMP: the other players' cursors on the setup's minimap (SOS-124). The minimap draws over the screens' side panel,
 * so these draw from the manager above it. It takes no mouse.
 */
final class MpMinimapCursors extends Interrupter {

	private final MpCursors cursors;
	private final RECTANGLE map;
	private final BooleanSupplier shown;

	private MpMinimapCursors(MpCursors cursors, RECTANGLE map, BooleanSupplier shown) {
		super(true, true, false);
		this.cursors = cursors;
		this.map = map;
		this.shown = shown;
	}

	static void show(MpCursors cursors, RECTANGLE map, BooleanSupplier shown) {
		new MpMinimapCursors(cursors, map, shown).show(VIEW.inters().manager);
	}

	@Override
	protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
		return false;
	}

	@Override
	protected void mouseClick(MButt button) {
	}

	@Override
	protected void hoverTimer(GBox text) {
	}

	@Override
	protected boolean render(Renderer r, float ds) {
		if (shown.getAsBoolean())
			cursors.renderWorldMinimap(r, map);
		return true;
	}

	@Override
	protected boolean update(float ds) {
		return true;
	}

	@Override
	public boolean canSave() {
		return false;
	}
}
