package menu;

import init.constant.C;
import snake2d.util.color.COLOR;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * SOSGSGMP: vanilla's full-screen prompt with its buttons centred. Vanilla's IPromtScreen centres a row of buttons only
 * when there are two of the same width: a lone button sits half a slot left of the centre, three half a slot right.
 * Every window of the mod with buttons in that prompt goes through here.
 */
final class MpPrompt {

	private MpPrompt() {
	}

	static void show(CharSequence text, GButt.ButtPanel button) {
		VIEW.inters().fullScreen.activate(text, COLOR.WHITE100, null, button);
		button.body().moveCX(C.WIDTH() / 2);
	}

	/** The same with several buttons in one centred row: vanilla spaces them a sixth of the screen apart, off-centre. */
	static void show(CharSequence text, GButt.ButtPanel... buttons) {
		VIEW.inters().fullScreen.activate(text, COLOR.WHITE100, null, buttons);
		int gap = 16;
		int width = -gap;
		for (GButt.ButtPanel b : buttons)
			width += b.body().width() + gap;
		int x = (C.WIDTH() - width) / 2;
		for (GButt.ButtPanel b : buttons) {
			b.body().moveX1(x);
			x += b.body().width() + gap;
		}
	}
}
