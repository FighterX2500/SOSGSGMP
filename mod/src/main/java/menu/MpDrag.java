package menu;

import init.constant.C;
import snake2d.MButt;
import snake2d.util.gui.GuiSection;

/** SOSGSGMP: dragging a window in the running game by its title bar, keeping the whole of it on the screen. */
final class MpDrag {

	private boolean dragging;
	private int grabX, grabY;

	/** A press at {@code mx, my}: above {@code content}, on the title bar, it grabs {@code window}. Returns whether it did. */
	boolean grab(GuiSection window, GuiSection content, int mx, int my) {
		if (my >= content.body().y1() || !window.body().holdsPoint(mx, my))
			return false;
		dragging = true;
		grabX = mx - window.body().x1();
		grabY = my - window.body().y1();
		return true;
	}

	/** Once per frame: follows the mouse while the button is held. Returns whether {@code window} is being dragged. */
	boolean follow(GuiSection window, int mx, int my) {
		if (!dragging)
			return false;
		if (!MButt.LEFT.isDown()) {
			dragging = false;
			return false;
		}
		int x = Math.max(0, Math.min(C.WIDTH() - window.body().width(), mx - grabX));
		int y = Math.max(0, Math.min(C.HEIGHT() - window.body().height(), my - grabY));
		window.body().moveX1Y1(x, y);
		return true;
	}

	void drop() {
		dragging = false;
	}
}
