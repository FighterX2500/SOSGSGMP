package menu;

import init.constant.C;
import snake2d.util.color.COLOR;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * SOSGSGMP: vanilla's full-screen prompt with a single button, centred. Vanilla's IPromtScreen places a lone button
 * half a slot left of the centre; with two buttons it is centred already.
 */
final class MpPrompt {

	private MpPrompt() {
	}

	static void show(CharSequence text, GButt.ButtPanel button) {
		VIEW.inters().fullScreen.activate(text, COLOR.WHITE100, null, button);
		button.body().moveCX(C.WIDTH() / 2);
	}
}
