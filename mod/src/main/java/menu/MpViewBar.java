package menu;

import init.constant.C;
import init.sprite.UI.UI;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosdet.View;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.panel.GPanel;
import view.interrupter.Interrupter;
import view.main.VIEW;

/**
 * SOSGSGMP: the viewer's bar at the top of the screen while it views another player's city (spec §10 Viewing, SOS-82):
 * "Viewing Ann's city" and Close view. The player watches and chats; the engine drops every order meanwhile. It never
 * pauses the game, and stays on top of every view until the view is over.
 */
final class MpViewBar extends Interrupter {

	private static final String WATCHING = "Only watching: orders are dropped.";
	private static final String CLOSING = "Closing the view; your city loads back...";

	private final GuiSection section = new GuiSection();
	private boolean closing;

	MpViewBar(MpViews views, String title) {
		super(true, true, false);
		GuiSection inner = new GuiSection();
		// one line, as wide as the longer text: at a fixed 320 px the line wrapped below the bar (G82F)
		int width = Math.max(UI.FONT().M.width(WATCHING), UI.FONT().M.width(CLOSING)) + 4;
		inner.add(new RENDEROBJ.RenderImp(width, UI.FONT().M.height() + 4) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, closing ? CLOSING : WATCHING,
						body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addRightC(8, new GButt.ButtPanel("Close view") {
			@Override
			protected void clickA() {
				views.close(View.CLOSED);
			}

			@Override
			protected void renAction() {
				activeSet(!closing);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				if (closing)
					((GBox) text).error("The view is closing.");
			}
		});
		GPanel panel = new GPanel(inner.body().width(), inner.body().height());
		panel.setTitle(title);
		panel.inner().set(inner);
		section.add(panel);
		section.add(inner);
		section.body().moveCX(C.WIDTH() / 2).moveY1(48);
	}

	void open() {
		show(VIEW.inters().manager);
	}

	void close() {
		hide();
	}

	/** The view's end is on its way: Close view is done. */
	void closing(boolean on) {
		closing = on;
	}

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
