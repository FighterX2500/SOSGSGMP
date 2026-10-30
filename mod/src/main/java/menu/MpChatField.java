package menu;

import java.util.function.Consumer;

import init.sprite.UI.UI;
import snake2d.Input.CHAR_LISTENER;
import snake2d.Mouse;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.sprite.text.Font;
import snake2d.util.sprite.text.Str;
import sosgsgmp.session.Chat;
import util.colors.GCOLOR;

/**
 * SOSGSGMP: the chat's one-line field (spec §12), for the lobby and the running game: the game's own fields read the
 * mouse through the in-game VIEW and don't scroll, and a message is longer than the box. It shows the end of the text,
 * with the caret after it. A click focuses it; Enter sends the text and leaves it.
 */
final class MpChatField extends CLICKABLE.ClickableAbs {

	private static final CharSequence PROMPT = "Click to chat";

	private final Font font = UI.FONT().M;
	private final Consumer<String> say;
	private final CHAR_LISTENER input = new CHAR_LISTENER(Chat.MAX_TEXT) {
		@Override
		protected void enter() {
			Mouse.currentClicked = null;
			String text = text().toString();
			del();
			say.accept(text);
		}

		@Override
		protected void change() {
		}
	};

	MpChatField(int width, Consumer<String> say) {
		this.say = say;
		body.setWidth(width);
		body.setHeight(font.height() + 12);
	}

	@Override
	protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
		boolean focused = Mouse.currentClicked == this;
		GCOLOR.UI().bg(isActive, isSelected, isHovered).render(r, body);
		// The keyboard forgets its listener every frame; the focused field claims it again.
		if (focused)
			input.listen();
		if (isHovered || focused)
			GCOLOR.UI().NORMAL.hovered.render(r, body);
		int x = body.x1() + 6;
		int y = body.y1() + (body.height() - font.height()) / 2;
		Str text = input.text();
		if (text.length() == 0 && !focused) {
			COLOR.WHITE65.bind();
			font.render(r, PROMPT, x, y);
			COLOR.unbind();
		} else {
			int start = lastThatFits(text, body.width() - 24);
			font.render(r, text, x, y, start, text.length(), 1.0);
			if (focused)
				font.render(r, "|", x + font.width(text, start, text.length(), 1.0) + 2, y);
		}
		GCOLOR.UI().border().renderFrame(r, body, 0, 2);
	}

	/** Where the text's end that fits in {@code width} starts. */
	private int lastThatFits(Str text, int width) {
		int start = text.length();
		int used = 0;
		while (start > 0) {
			used += font.width(text.charAt(start - 1), 1.0);
			if (used > width)
				break;
			start--;
		}
		return start;
	}

	@Override
	public boolean click() {
		if (!super.click())
			return false;
		Mouse.currentClicked = this;
		return true;
	}
}
