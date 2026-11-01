package menu;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import init.paths.PATHS;
import init.sprite.UI.UI;
import menu.GUI.COLORS;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.game.SteamworksPort;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.net.SteamNode;
import sosgsgmp.session.HostedLobby;
import util.gui.misc.GText;
import view.menu.MenuScreen;

/**
 * SOSGSGMP: Load Saved Lobby (spec §11 Resuming, SOS-64): this PC's campaign saves, newest first. The host picks one and
 * opens a lobby over IP or Steam with its name and port from Create Lobby; only the campaign's players can join it.
 */
final class MpScreenResume extends GUI.Shadower implements SC {

	private static final int ROWS = 12;
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final MpMenu mp;
	private final List<CampaignRecord> records = new ArrayList<>();
	/** Whether each listed save's game file is still in vanilla's saves folder. */
	private final List<Boolean> present = new ArrayList<>();
	private final GText[] rows = new GText[ROWS];
	private int selected = -1;
	private final GText note = new GText(UI.FONT().M, 160);
	private final GText error = new GText(UI.FONT().M, 512);

	MpScreenResume(MpMenu mp) {
		this.mp = mp;
		MenuScreen screen = new MenuScreen("load saved lobby", GUI.labelColor) {
			@Override
			protected void back() {
				mp.menu.switchScreen(mp.main);
			}
		};
		screen.addButt(new MenuScreen.ScreenButton("host (IP)") {
			@Override
			protected void clickA() {
				host(false);
			}

			@Override
			protected void renAction() {
				activeSet(selected >= 0);
			}
		});
		screen.addButt(new MenuScreen.ScreenButton("host Steam lobby") {
			@Override
			protected void clickA() {
				host(true);
			}

			@Override
			protected void renAction() {
				activeSet(selected >= 0);
			}
		});
		add(screen);

		GuiSection list = new GuiSection();
		int rowH = UI.FONT().M.height() + 8;
		for (int i = 0; i < ROWS; i++) {
			rows[i] = new GText(UI.FONT().M, 160);
			list.addDown(i == 0 ? 0 : 2, row(i, rowH));
		}
		list.addDown(16, new RENDEROBJ.RenderImp(900, UI.FONT().M.height() * 5) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, note, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
				error.render(r, body.x1(), body.y1() + UI.FONT().M.height() * 2);
			}
		});
		error.color(COLORS.error);
		list.body().centerIn(MenuScreen.inner);
		add(list);
	}

	private CLICKABLE row(int i, int height) {
		return new CLICKABLE.ClickableAbs(900, height) {
			@Override
			protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
				if (i >= records.size())
					return;
				if (isSelected)
					COLOR.WHITE100.renderFrame(r, body, -2, 2);
				else if (isHovered && isActive)
					COLOR.WHITE50.renderFrame(r, body, -2, 1);
				rows[i].renderCY(r, body.x1() + 8, body.cY());
			}

			@Override
			protected void clickA() {
				selected = i;
				error.clear();
			}

			@Override
			protected void renAction() {
				activeSet(i < records.size() && present.get(i));
				selectedSet(selected == i);
			}
		};
	}

	/** Lists this PC's campaign saves again, as the screen opens. */
	void open() {
		records.clear();
		present.clear();
		selected = -1;
		error.clear();
		var store = MpSaves.store();
		for (CampaignRecord r : store.all()) {
			if (records.size() == ROWS)
				break;
			records.add(r);
			present.add(Files.exists(PATHS.local().save().get(r.file())));
		}
		for (int i = 0; i < ROWS; i++) {
			rows[i].clear();
			if (i >= records.size())
				continue;
			CampaignRecord r = records.get(i);
			rows[i].add(r.campaign().name()).add(", save ").add(r.number()).add(r.kind() == CampaignRecord.Kind.AUTO ? " (auto)" : "")
					.add(", ").add(WHEN.format(Instant.ofEpochMilli(r.time()))).add(": ");
			for (int p = 0; p < r.campaign().players().size(); p++)
				rows[i].add(p == 0 ? "" : ", ").add(r.campaign().players().get(p).name());
			if (!present.get(i))
				rows[i].add("  (its game save is gone)");
			rows[i].color(present.get(i) ? COLOR.WHITE100 : COLORS.inactive);
		}
		note.clear();
		if (records.isEmpty())
			note.add("No campaign saves on this PC yet: the host saves a campaign from the multiplayer panel while playing.");
		else
			note.add("You host as ").add(mp.prefs.name).add(", on port ").add(mp.prefs.port)
					.add(" over IP (set both on Create Lobby). Every player of the campaign has to join before the game can start.");
		if (!store.problems().isEmpty())
			error.add(store.problems().size()).add(" campaign record(s) couldn't be read: ").add(store.problems().get(0));
	}

	private void host(boolean steam) {
		if (selected < 0)
			return;
		CampaignRecord r = records.get(selected);
		Campaign c = r.campaign();
		if (c.player(mp.prefs.playerKey) == null) {
			error.clear().add("This PC's player key isn't in the campaign any more (its lobby settings were reset), so it can't host it.");
			return;
		}
		LobbySettings settings = LobbySettings.resuming(new LobbySettings.Resume(c, r.number()));
		if (steam) {
			mp.invites.stop(); // Steam runs once in the process (SOS-103)
			SteamNode node = SteamNode.host(SteamworksPort::new, c.players().size());
			mp.openLobby(new HostedLobby(settings, mp.prefs.name, mp.prefs.playerKey, mp.builds, node), node);
			return;
		}
		try {
			mp.openLobby(new HostedLobby(settings, mp.prefs.name, mp.prefs.playerKey, mp.builds, mp.prefs.port), null);
		} catch (IOException e) {
			error.clear().add("Can't open port ").add(mp.prefs.port).add(": ").add(String.valueOf(e.getMessage()));
		}
	}

	@Override
	public boolean back(Menu menu) {
		menu.switchScreen(mp.main);
		return true;
	}
}
