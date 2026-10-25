package menu;

import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import view.menu.MenuScreen;

/** SOSGSGMP: Multiplayer: create, load (later) or join a lobby (spec §5, lobby step 1). */
final class MpScreenMain extends GUI.Shadower implements SC {

	MpScreenMain(MpMenu mp) {
		MenuScreen screen = new MenuScreen("multiplayer", GUI.labelColor) {
			@Override
			protected void back() {
				mp.menu.switchScreen(mp.menu.main);
			}
		};
		add(screen);

		GuiSection col = new GuiSection();
		CLICKABLE b = GUI.getNavButt("create lobby");
		b.clickActionSet(() -> mp.menu.switchScreen(mp.create));
		col.addDown(0, b);

		b = GUI.getNavButt("load saved lobby");
		b.activeSet(false);
		col.addDown(8, b);

		b = GUI.getNavButt("join by IP");
		b.clickActionSet(() -> mp.menu.switchScreen(mp.join));
		col.addDown(8, b);

		b = GUI.getNavButt("join through Steam");
		b.clickActionSet(mp::openSteamJoin);
		col.addDown(8, b);

		col.addDown(32, MpMenu.note("Saved lobbies come with campaign saves."));

		col.body().centerIn(MenuScreen.inner);
		add(col);
	}

	@Override
	public boolean back(Menu menu) {
		menu.switchScreen(menu.main);
		return true;
	}
}
