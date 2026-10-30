package menu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import init.sprite.UI.UI;
import snake2d.MButt;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.text.Font;
import sosgsgmp.session.Chat;

/**
 * SOSGSGMP: the chat's lines and field (spec §12), shown by the lobby's screen and by {@link MpChat} in the running
 * game. Each line wraps into rows, with the speaker's name in their cursor colour. The wheel over it scrolls back; the
 * view stays where it is while new lines arrive. The rows are rebuilt only when a line arrives, never while rendering.
 */
final class MpChatBox extends GuiSection {

	private record Row(String text, int nameEnd, COLOR colour) {
	}

	private final Font font = UI.FONT().M;
	private final int width;
	private final int visible;
	private final List<Row> rows = new ArrayList<>();
	private Chat chat;
	private int builtAdded = -1;
	/** Rows scrolled back from the newest. */
	private int back;

	MpChatBox(int width, int visible, Consumer<String> say) {
		this.width = width;
		this.visible = visible;
		add(new RENDEROBJ.RenderImp(width, font.height() * visible) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				if (chat != null)
					rows(r, body.x1(), body.y1());
			}
		}, 0, 0);
		addDown(4, new MpChatField(width, say));
	}

	/** The chat it shows, once a session has one. */
	void show(Chat chat) {
		this.chat = chat;
		builtAdded = -1;
		back = 0;
	}

	/** Once per frame: the wheel over the box scrolls it, and doesn't zoom the map behind. */
	void wheel() {
		if (!hoveredIs())
			return;
		float spin = MButt.clearWheelSpin();
		if (spin > 0)
			back = Math.min(back + 1, Math.max(0, rows.size() - visible));
		else if (spin < 0)
			back = Math.max(0, back - 1);
	}

	private void rows(SPRITE_RENDERER r, int x, int y) {
		if (chat.added() != builtAdded)
			build();
		int end = rows.size() - back;
		int start = Math.max(0, end - visible);
		// the newest row sits on the bottom edge
		int rowY = y + font.height() * (visible - (end - start));
		for (int i = start; i < end; i++) {
			row(r, rows.get(i), x, rowY);
			rowY += font.height();
		}
		COLOR.unbind();
	}

	private void row(SPRITE_RENDERER r, Row row, int x, int y) {
		int length = row.text.length();
		if (row.nameEnd > 0) {
			row.colour.bind();
			font.render(r, row.text, x, y, 0, row.nameEnd, 1.0);
			x += font.width(row.text, 0, row.nameEnd, 1.0);
		}
		(row.nameEnd == length && row.nameEnd > 0 ? row.colour : row.colour == null ? COLOR.WHITE65 : COLOR.WHITE100).bind();
		font.render(r, row.text, x, y, row.nameEnd, length, 1.0);
	}

	private void build() {
		int before = rows.size();
		rows.clear();
		for (int i = 0; i < chat.size(); i++)
			add(chat.line(i));
		builtAdded = chat.added();
		if (back > 0)
			back = Math.min(back + Math.max(0, rows.size() - before), Math.max(0, rows.size() - visible));
	}

	private void add(Chat.Line line) {
		boolean spoken = line.playerId() != Chat.NOTICE;
		String prefix = spoken ? line.name() + ": " : "";
		CharSequence[] wrapped = font.getRows(prefix + line.text(), width);
		for (int i = 0; i < wrapped.length; i++) {
			String text = wrapped[i].toString();
			rows.add(new Row(text, i == 0 ? Math.min(prefix.length(), text.length()) : 0, spoken ? MpCursors.colour(line.colour()) : null));
		}
	}
}
