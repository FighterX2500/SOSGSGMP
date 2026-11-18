package menu;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import init.sprite.UI.UI;
import menu.GUI.COLORS;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.clickable.Scrollable;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.game.DataFolders;
import sosgsgmp.game.SteamworksPort;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.net.SteamNode;
import sosgsgmp.session.HostedLobby;
import util.gui.misc.GText;
import util.gui.table.GScrollable;
import view.menu.MenuScreen;

/**
 * SOSGSGMP: Load Saved Lobby (spec §11 Resuming, SOS-64): this PC's campaign saves, newest first, in a list that scrolls
 * as vanilla's load screen does (SOS-228). The host picks one and opens a lobby over IP or Steam with its name and port
 * from Create Lobby; only the campaign's players can join it.
 */
final class MpScreenResume extends GUI.Shadower implements SC {

	/** The rows shown at once; the list scrolls through the rest (SOS-228). */
	private static final int ROWS = 12;
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final MpMenu mp;
	private final List<CampaignRecord> records = new ArrayList<>();
	/** Whether each listed save's game file is still in vanilla's saves folder. */
	private final List<Boolean> present = new ArrayList<>();
	/** Each listed save's line, made as the screen opens. */
	private final List<GText> lines = new ArrayList<>();
	private final GScrollable list;
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

		Scrollable.ScrollRow[] rows = new Scrollable.ScrollRow[ROWS];
		for (int i = 0; i < ROWS; i++)
			rows[i] = new Row(UI.FONT().M.height() + 10);
		list = new GScrollable(rows) {
			@Override
			public int nrOFEntries() {
				return records.size();
			}
		};
		GuiSection section = new GuiSection();
		section.add(list.getView());
		section.addDown(16, new RENDEROBJ.RenderImp(900, UI.FONT().M.height() * 5) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, note, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
				error.render(r, body.x1(), body.y1() + UI.FONT().M.height() * 2);
			}
		});
		error.color(COLORS.error);
		section.body().centerIn(MenuScreen.inner);
		add(section);
	}

	/** A row of the list, showing the save {@link Scrollable} gives it as the list scrolls. */
	private final class Row extends CLICKABLE.ClickableAbs implements Scrollable.ScrollRow {

		private int index = -1;

		Row(int height) {
			super(900, height);
		}

		@Override
		public void init(int index) {
			this.index = index;
		}

		@Override
		protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
			if (index < 0 || index >= records.size())
				return;
			if (isSelected)
				COLOR.WHITE100.renderFrame(r, body, -3, 2);
			else if (isHovered && isActive)
				COLOR.WHITE50.renderFrame(r, body, -3, 1);
			lines.get(index).renderCY(r, body.x1() + 8, body.cY());
		}

		@Override
		protected void clickA() {
			selected = index;
			error.clear();
		}

		@Override
		protected void renAction() {
			activeSet(index >= 0 && index < records.size() && present.get(index));
			selectedSet(index >= 0 && selected == index);
		}
	}

	/** Lists this PC's campaign saves again, as the screen opens. */
	void open() {
		records.clear();
		present.clear();
		selected = -1;
		lines.clear();
		error.clear();
		var store = MpSaves.store();
		for (CampaignRecord r : store.all()) { // every save, newest first: the list scrolls (SOS-228)
			records.add(r);
			present.add(Files.exists(DataFolders.layout().save(r.file())));
		}
		list.set(0); // the newest at the top
		for (int i = 0; i < records.size(); i++) {
			CampaignRecord r = records.get(i);
			GText line = new GText(UI.FONT().M, 160);
			lines.add(line);
			line.add(r.campaign().name()).add(", save ").add(r.number()).add(r.kind() == CampaignRecord.Kind.AUTO ? " (auto)" : "")
					.add(", ").add(WHEN.format(Instant.ofEpochMilli(r.time()))).add(": ");
			for (int p = 0; p < r.campaign().players().size(); p++)
				line.add(p == 0 ? "" : ", ").add(r.campaign().players().get(p).name());
			if (!present.get(i))
				line.add("  (its game save is gone)");
			line.color(present.get(i) ? COLOR.WHITE100 : COLORS.inactive);
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

	/** Host (IP) or Host Steam lobby: the picked save's lobby opens; false when it can't, with why shown. */
	boolean host(boolean steam) {
		if (selected < 0)
			return false;
		CampaignRecord r = records.get(selected);
		Campaign c = r.campaign();
		if (c.player(mp.prefs.playerKey) == null) {
			error.clear().add("This PC's player key isn't in the campaign any more (its lobby settings were reset), so it can't host it.");
			return false;
		}
		LobbySettings settings = LobbySettings.resuming(new LobbySettings.Resume(c, r.number()));
		if (steam) {
			mp.invites.stop(); // Steam runs once in the process (SOS-103)
			SteamNode node = SteamNode.host(SteamworksPort::new, LobbySettings.MAX_PLAYERS); // room for a player joining later (SOS-190)
			mp.openLobby(new HostedLobby(settings, mp.prefs.name, mp.prefs.playerKey, mp.builds, node), node);
			return true;
		}
		try {
			mp.openLobby(new HostedLobby(settings, mp.prefs.name, mp.prefs.playerKey, mp.builds, mp.prefs.port), null);
			return true;
		} catch (IOException e) {
			error.clear().add("Can't open port ").add(mp.prefs.port).add(": ").add(String.valueOf(e.getMessage()));
			return false;
		}
	}

	/** Called every frame the screen shows; the test autopilot drives the screen from here (SOS-228). */
	@Override
	public boolean hover(COORDINATE mCoo) {
		if (mp.autopilot != null)
			mp.autopilot.resumeScreen(this);
		return super.hover(mCoo);
	}

	/** Test autopilot: how many saves the list holds. */
	int saves() {
		return records.size();
	}

	/** Test autopilot, as a player does: the list scrolled to its end, as its slider dragged down, and the oldest save clicked. */
	void pickOldest() {
		list.set(list.max());
		if (!records.isEmpty() && present.get(records.size() - 1)) {
			selected = records.size() - 1;
			error.clear();
		}
	}

	/** The picked save, or null. */
	CampaignRecord picked() {
		return selected < 0 ? null : records.get(selected);
	}

	@Override
	public boolean back(Menu menu) {
		menu.switchScreen(mp.main);
		return true;
	}
}
