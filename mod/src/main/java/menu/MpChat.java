package menu;

import init.constant.C;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import sosgsgmp.session.Chat;
import sosgsgmp.session.SetupSession;
import util.gui.misc.GBox;
import util.gui.panel.GPanel;
import view.interrupter.Interrupter;
import view.main.VIEW;

/**
 * SOSGSGMP: the chat's box in the running game and the setup (spec §12, SOS-110): movable by its title bar, and the top
 * bar's button collapses and expands it. Like the multiplayer panel it stays on top of every view and never hides
 * others. A load rebuilds the view, so this is made again after each one; the lines and the box's place live in the
 * session's {@link Chat}.
 */
public final class MpChat extends Interrupter implements MpPanelButton.Panel {

	private static final int WIDTH = 460;
	private static final int ROWS = 8;
	private static final int MARGIN = 16;

	private final Chat chat;
	private final MpChatBox box;
	private final GuiSection section = new GuiSection();
	private final MpDrag drag = new MpDrag();

	/** Shows the box in this view; call after every load, as the view is new. */
	public static void show(SetupSession setup) {
		MpChat box = new MpChat(setup);
		box.show(VIEW.inters().manager);
		MpPanelButton.installChat(box);
	}

	private MpChat(SetupSession setup) {
		super(true, true, false);
		chat = setup.chat();
		box = new MpChatBox(WIDTH, ROWS, setup::say);
		box.show(chat);
		GPanel panel = new GPanel(box.body().width(), box.body().height());
		panel.setTitle("Chat");
		panel.inner().set(box);
		section.add(panel);
		section.add(box);
		if (chat.boxX() >= 0)
			section.body().moveX1Y1(chat.boxX(), chat.boxY());
		else
			section.body().moveX1(MARGIN).moveY2(C.HEIGHT() - MARGIN);
	}

	@Override
	public boolean shown() {
		return !chat.boxHidden();
	}

	@Override
	public void toggle() {
		chat.boxHidden(!chat.boxHidden());
		drag.drop();
	}

	@Override
	protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
		if (chat.boxHidden())
			return false;
		// the whole box, its title bar too, takes the mouse: a press there drags it
		return section.hover(mCoo) || section.body().holdsPoint(mCoo.x(), mCoo.y());
	}

	@Override
	protected void mouseClick(MButt button) {
		if (button != MButt.LEFT)
			return;
		if (!drag.grab(section, box, VIEW.mouse().x(), VIEW.mouse().y()))
			section.click();
	}

	@Override
	protected void hoverTimer(GBox text) {
		section.hoverInfoGet(text);
	}

	@Override
	protected boolean render(Renderer r, float ds) {
		if (chat.boxHidden())
			return true;
		if (drag.follow(section, VIEW.mouse().x(), VIEW.mouse().y()))
			chat.boxAt(section.body().x1(), section.body().y1());
		section.render(r, ds);
		return true;
	}

	@Override
	protected boolean update(float ds) {
		if (!chat.boxHidden())
			box.wheel();
		return true;
	}
}
