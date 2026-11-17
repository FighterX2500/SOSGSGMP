package menu;

import init.constant.C;
import snake2d.util.color.COLOR;
import sosgsgmp.game.PromptBox;
import util.gui.misc.GButt;
import view.main.VIEW;

/**
 * SOSGSGMP: vanilla's full-screen prompt with its buttons centred and none of its text cut. Vanilla's IPromtScreen
 * centres a row of buttons only when there are two of the same width: a lone button sits half a slot left of the
 * centre, three half a slot right. Its text box is 100 high and draws five lines; here it is as high as the text
 * ({@link PromptBox}). Every full-screen window of the mod goes through here.
 */
final class MpPrompt {

	private MpPrompt() {
	}

	/** The prompt with no button: a click, Escape or Enter closes it. */
	static void show(CharSequence text) {
		VIEW.inters().fullScreen.activate(text, COLOR.WHITE100, PromptBox.fit(text));
	}

	static void show(CharSequence text, GButt.ButtPanel button) {
		VIEW.inters().fullScreen.activate(text, COLOR.WHITE100, PromptBox.fit(text), button);
		button.body().moveCX(C.WIDTH() / 2);
	}

	/** The same with several buttons in one centred row: vanilla spaces them a sixth of the screen apart, off-centre. */
	static void show(CharSequence text, GButt.ButtPanel... buttons) {
		VIEW.inters().fullScreen.activate(text, COLOR.WHITE100, PromptBox.fit(text), buttons);
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
