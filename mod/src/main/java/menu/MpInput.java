package menu;

import init.sprite.UI.UI;
import snake2d.CORE;
import snake2d.MButt;
import snake2d.Mouse;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.sprite.text.StringInputSprite;
import util.colors.GCOLOR;

/**
 * SOSGSGMP: a one-line text field for the main menu. Works like the game's util.gui.misc.GInput, which reads the
 * mouse through the in-game VIEW and so can't be used before a game is loaded.
 */
final class MpInput extends CLICKABLE.ClickableAbs {

	private final StringInputSprite input;

	MpInput(int chars, boolean digitsOnly, CharSequence text) {
		input = digitsOnly ? new StringInputSprite(chars, UI.FONT().M) {
			@Override
			protected void acceptChar(char c) {
				if (c >= '0' && c <= '9')
					super.acceptChar(c);
			}
		} : new StringInputSprite(chars, UI.FONT().M);
		input.text().add(text, chars);
		body.setWidth(input.font().maxCWidth * chars + 12);
		body.setHeight(input.height() + 12);
	}

	@Override
	protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
		GCOLOR.UI().bg(isActive, isSelected, isHovered).render(r, body);
		// The keyboard forgets its listener every frame; the focused field claims it again.
		if (Mouse.currentClicked == this)
			input.listen();
		if (isHovered || Mouse.currentClicked == this)
			GCOLOR.UI().NORMAL.hovered.render(r, body);
		input.render(r, body.x1() + 6, body.y1() + (body.height() - input.height()) / 2);
		GCOLOR.UI().border().renderFrame(r, body, 0, 2);
	}

	@Override
	public boolean click() {
		if (!super.click())
			return false;
		Mouse.currentClicked = this;
		if (!input.listening() || MButt.LEFT.isDouble()) {
			input.listen();
			input.selectAll();
		} else {
			input.click(CORE.getInput().getMouse().getCoo().x() - body.x1() - 6);
		}
		return true;
	}

	/** The text as typed, trimmed. */
	String text() {
		return input.text().toString().strip();
	}
}
