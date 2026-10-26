package menu;

import init.constant.C;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import util.gui.misc.GBox;
import util.gui.panel.GPanel;
import view.interrupter.Interrupter;
import view.main.VIEW;

/**
 * SOSGSGMP: a small window in the running game, centred on the screen: a titled panel around {@link #inner}, which the
 * subclass fills before {@link #open}. It stays until it closes itself and never pauses the game (spec §4).
 */
abstract class MpWindow extends Interrupter {

	/** The window's content, below its title bar. */
	final GuiSection inner = new GuiSection();
	private final GuiSection section = new GuiSection();
	private final String title;

	MpWindow(String title) {
		super(true, true, false);
		this.title = title;
	}

	/** Builds the panel around {@link #inner} and shows the window. */
	final void open() {
		GPanel panel = new GPanel(inner.body().width(), inner.body().height());
		panel.setTitle(title);
		panel.inner().set(inner);
		section.add(panel);
		section.add(inner);
		section.body().centerIn(C.DIM());
		show(VIEW.inters().manager);
	}

	/** Closes the window. */
	final void close() {
		hide();
	}

	/** Once per frame, before the window draws. */
	abstract void frame();

	@Override
	protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
		return section.hover(mCoo) || section.body().holdsPoint(mCoo.x(), mCoo.y());
	}

	@Override
	protected void mouseClick(MButt button) {
		if (button == MButt.LEFT)
			section.click();
	}

	@Override
	protected void hoverTimer(GBox text) {
		section.hoverInfoGet(text);
	}

	@Override
	protected boolean render(Renderer r, float ds) {
		frame();
		section.render(r, ds);
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
