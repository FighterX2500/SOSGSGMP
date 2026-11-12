package menu;

import java.nio.file.Path;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.FactionProfileFlusher;
import game.faction.player.Player;
import game.save.GameLoader;
import init.constant.C;
import init.sprite.SPRITES;
import init.sprite.UI.UI;
import snake2d.CORE;
import snake2d.CORE_STATE;
import snake2d.LOG;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.LIST;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.net.Message;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.FactionPick;
import util.colors.GCOLOR;
import util.gui.misc.GButt;
import util.gui.misc.GHeader;
import util.gui.misc.GText;
import view.tool.PLACABLE;
import view.tool.PlacableFixedImp;
import view.world.generator.MpJoinScreens;
import world.WORLD;
import world.map.regions.centre.WCentre;

/**
 * SOSGSGMP hot-join (SOS-190, docs/plan/hot-join-restart.md): a PC that joins a running game picks its race, faction
 * and the spot of its city-state on the host's snapshot, loaded outside any engine session and paused, while everyone
 * else plays on. It picks through the setup's own screens ({@link MpJoinScreens}, SOS-198): race, Customize Faction,
 * then the location. The host checks the spot on the live world; once it takes it, the founding save is made and every
 * PC restarts from it, this one from the host's copy ({@link MpWorldLoad}). The snapshot is a copy of the host's game:
 * the faction these screens edit is the host's Player in that copy, which nothing keeps.
 */
public final class MpJoinSettle implements CORE_STATE.Constructor {

	private final SetupSession setup;
	private final byte[] save;

	public MpJoinSettle(SetupSession setup, byte[] save) {
		this.setup = setup;
		this.save = save.clone();
	}

	@Override
	public CORE_STATE getState() {
		WorldSetupAdapter game = new WorldSetupAdapter();
		long t = System.nanoTime();
		Path file = game.saveFile(save);
		CORE_STATE view;
		try {
			view = new GameLoader(file).getState();
		} finally {
			game.deleteSaveFile(file);
		}
		GAME.SPEED.speedSet(0);
		Player p = FACTIONS.player();
		int hostX = p.capitolRegion() == null ? WORLD.TWIDTH() / 2 : p.capitolRegion().cx();
		int hostY = p.capitolRegion() == null ? WORLD.THEIGHT() / 2 : p.capitolRegion().cy();
		Join join = new Join(setup, game, hostX, hostY); // reads the players before this PC's faction replaces the host's
		// this PC's own faction, not the host's: its saved default, else a name to type
		if (FactionProfileFlusher.canLoad(p))
			FactionProfileFlusher.load(p);
		else
			p.name.clear();
		LOG.ln("SOSGSGMP: the host's snapshot to join on loaded in " + (System.nanoTime() - t) / 1_000_000 + " ms");
		MpJoinScreens screens = MpJoinScreens.open(join, hostX, hostY);
		if (join.autopilot != null)
			screens.location(); // the autopilot picks its spot at once: the location screen, as a newcomer would reach it
		MpChat.show(setup);
		return view;
	}

	/** The pick's session and what its screens show: every player's row, the status line, Ready, Leave and the placer. */
	private static final class Join implements MpJoinScreens.Pick {

		private static final int WIDTH = 420;
		private static final int STATUS_LINES = 6;
		private static final int HALF = WCentre.TILE_DIM / 2;
		private static final CharSequence TITLE = "Place your city-state";
		private static final String PLACE = "Pick your race and customize your faction, then pick where your city-state goes:"
				+ " 6 tiles or more from every town and every player's capital. Ready sends it to the host. Everyone plays on"
				+ " meanwhile.";

		private final SetupSession setup;
		private final WorldSetupAdapter game;
		private final GText status = new GText(UI.FONT().M, 384);
		private final GText ownRow = new GText(UI.FONT().M, 128);
		private final int hostX;
		private final int hostY;
		private final MpAutopilot autopilot = MpAutopilot.fromEnv();
		private final Placer placer = new Placer();
		private int ownX = FactionPick.NONE;
		private int ownY = FactionPick.NONE;
		/** The pick went to the host, which hasn't answered yet, or took it. */
		private boolean sent;
		private boolean taken;
		private String answer = "";
		/** After the host's restart: the new setup, until its slot map and the founding save are here. */
		private SetupSession restarted;
		private boolean leaving;
		/** Every player in the snapshot: its capital, for the placer's marks, and its row as the snapshot had it. */
		private final int[] capX = new int[FACTIONS.MAX()];
		private final int[] capY = new int[FACTIONS.MAX()];
		private final GText[] capNames = new GText[FACTIONS.MAX()];
		private final GText[] rows = new GText[FACTIONS.MAX()];
		private int caps;
		private int shownStatus = -1;

		Join(SetupSession setup, WorldSetupAdapter game, int hostX, int hostY) {
			this.setup = setup;
			this.game = game;
			this.hostX = hostX;
			this.hostY = hostY;
			for (int i = 0; i < FACTIONS.MAX(); i++) {
				if (FACTIONS.getByIndex(i) instanceof Player h && h.capitolRegion() != null) {
					capX[caps] = h.capitolRegion().cx();
					capY[caps] = h.capitolRegion().cy();
					capNames[caps] = new GText(UI.FONT().S, 32);
					capNames[caps].add(h.name);
					rows[caps] = new GText(UI.FONT().M, 128).setMaxWidth(WIDTH);
					rows[caps].add(h.name).add(i == FACTIONS.player().index() ? " (host)" : "").add(": ").add(h.race().info.name)
							.add(", at ").add(capX[caps]).add(',').add(capY[caps]);
					rows[caps].color(GCOLOR.T().NORMAL);
					caps++;
				}
			}
			ownRow.setMaxWidth(WIDTH);
		}

		@Override
		public GuiSection info() {
			GuiSection s = new GuiSection();
			s.add(new GHeader("players"), 0, 0);
			int line = UI.FONT().M.height() + 2;
			s.addDown(4, new RENDEROBJ.RenderImp(WIDTH, line * (caps + 1)) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					int y = body.y1();
					for (int i = 0; i < caps; i++, y += line)
						rows[i].render(r, body.x1(), y);
					ownRow(); // the faction and race change on the other screens
					ownRow.render(r, body.x1(), y);
				}
			});
			s.addDown(12, new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.height() * STATUS_LINES) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					if (shownStatus != statusKey())
						showStatus();
					status.render(r, body.x1(), body.y1());
				}
			});
			status.setMaxWidth(WIDTH);
			status.setMultipleLines(true);
			return s;
		}

		/** This PC's row: its faction as now named, its race, and where the pick stands. No allocation: every frame. */
		private void ownRow() {
			Player p = FACTIONS.player();
			ownRow.clear().add(p.name.length() == 0 ? "Your faction" : p.name).add(" (you): ").add(p.race().info.name);
			if (taken || restarted != null)
				ownRow.add(", taken by the host");
			else if (sent)
				ownRow.add(", sent to the host");
			else if (ownX != FactionPick.NONE)
				ownRow.add(", at ").add(ownX).add(',').add(ownY);
			else
				ownRow.add(", picking");
			ownRow.color(ownX != FactionPick.NONE ? GCOLOR.T().IGOOD : GCOLOR.T().NORMAL);
		}

		@Override
		public GButt.ButtPanel ready() {
			return new GButt.ButtPanel("ready") {
				@Override
				protected void clickA() {
					sendReady();
				}

				@Override
				protected void renAction() {
					activeSet(!locked() && ownX != FactionPick.NONE && named());
				}
			};
		}

		@Override
		public GButt.ButtPanel leave() {
			return MpSetupLeave.button(setup, () -> leaving = true);
		}

		@Override
		public PLACABLE placer() {
			return placer;
		}

		@Override
		public boolean locked() {
			return sent || taken || restarted != null || setup.closed();
		}

		private boolean named() {
			return !FACTIONS.player().name.toString().isBlank();
		}

		private void placeAt(int x, int y) {
			if (locked())
				return;
			ownX = x;
			ownY = y;
			answer = "";
			showStatus();
		}

		/** The pick goes to the host: the race and faction as the screens left them, and the spot. */
		private void sendReady() {
			FactionPick p = game.pick(ownX, ownY);
			if (p == null || locked())
				return;
			setup.pick(p);
			sent = true;
			showStatus();
		}

		/** What the status line depends on, so it is rebuilt only when that changes. */
		private int statusKey() {
			int k = setup.closed() ? 1 : restarted != null ? 2 : taken ? 3 : sent ? 4 : !answer.isEmpty() ? 5 : !named() ? 6 : 7;
			return k * 2 + (ownX != FactionPick.NONE ? 1 : 0);
		}

		private void showStatus() {
			shownStatus = statusKey();
			status.clear();
			if (setup.closed())
				status.add(setup.closedReason());
			else if (restarted != null)
				status.add("The host took your spot. Every game saved and is restarting with your city-state in the world;"
						+ " getting the host's save.");
			else if (taken)
				status.add("The host took your spot. Every game saves now, then all of them reload with your city-state.");
			else if (sent)
				status.add("Waiting for the host to check your spot on the live world.");
			else if (!answer.isEmpty())
				status.add("The host can't use that spot: ").add(answer).add(" Pick another one.");
			else if (!named())
				status.add("Your faction needs a name: Customize Faction. ").add(PLACE);
			else if (ownX != FactionPick.NONE)
				status.add("Press Ready to send your spot to the host, or click another one.");
			else
				status.add(PLACE);
		}

		@Override
		public void frame() {
			if (leaving)
				return;
			if (!GAME.SPEED.isPaused())
				GAME.SPEED.speedSet(0); // a map to pick on: nothing in it runs
			if (restarted == null) {
				setup.update();
				String a = setup.takeJoinPicked();
				if (a != null) {
					sent = false;
					taken = a.isEmpty();
					answer = a;
					showStatus();
				}
				SetupSession next = setup.takeRestart();
				if (next != null) {
					restarted = next;
					LOG.ln("SOSGSGMP: the host restarts every PC from save " + next.restartInfo().number() + " with this PC in it");
					showStatus();
				}
				if (setup.closed())
					showStatus();
			}
			if (restarted != null) {
				restarted.update();
				byte[] world = restarted.factions() != null && restarted.factions().initialized() ? restarted.takeWorld() : null;
				if (world != null) {
					Message.Restart r = restarted.restartInfo();
					CampaignRecord record = new CampaignRecord(r.campaign(), r.number(), CampaignRecord.Kind.AUTO,
							System.currentTimeMillis(), restarted.factions().slotOf(restarted.localPlayerId()), "the host's", 0);
					leaving = true;
					CORE.setCurrentState(new MpWorldLoad(restarted, record, world));
					return;
				}
				if (restarted.closed())
					throw new IllegalStateException("SOSGSGMP: the connection ended during the restart: " + restarted.closedReason());
			}
			if (autopilot != null && restarted == null)
				autopilot.joinSettle(hostX, hostY, ownX != FactionPick.NONE, game::joinProblem, (x, y) -> {
					placeAt(x, y);
					return ownX;
				}, () -> {
					if (!locked() && named())
						sendReady();
				});
		}

		/** The city-state placer: a 3x3 square whose centre is the spot, checked by the carving rule on the snapshot. */
		private final class Placer extends PlacableFixedImp {

			private int checkedX = -1;
			private int checkedY = -1;
			private CharSequence problem;

			Placer() {
				super(TITLE, 1, 1);
			}

			@Override
			public int width() {
				return WCentre.TILE_DIM;
			}

			@Override
			public int height() {
				return WCentre.TILE_DIM;
			}

			@Override
			public void place(int tx, int ty, int rx, int ry) {
				// Nothing on the map: the spot goes to the host with Ready.
			}

			@Override
			public void afterPlaced(int tx1, int ty1) {
				placeAt(tx1 + HALF, ty1 + HALF);
			}

			@Override
			public CharSequence placableWhole(int tx1, int ty1) {
				if (locked())
					return "Your spot went to the host.";
				int cx = tx1 + HALF;
				int cy = ty1 + HALF;
				if (cx != checkedX || cy != checkedY) {
					checkedX = cx;
					checkedY = cy;
					problem = game.joinProblem(cx, cy);
				}
				return problem;
			}

			@Override
			public CharSequence placable(int tx, int ty, int rx, int ry) {
				return null;
			}

			@Override
			public LIST<CLICKABLE> getAdditionalButt() {
				return null;
			}

			@Override
			public void renderPlaceHolder(SPRITE_RENDERER r, int mask, int x, int y, int tx, int ty, int rx, int ry, boolean isPlacable, boolean areaIsPlacable) {
				super.renderPlaceHolder(r, mask, x, y, tx, ty, rx, ry, isPlacable, areaIsPlacable);
				if (rx != 0 || ry != 0)
					return;
				for (int i = 0; i < caps; i++) {
					int sx = square(x, tx, capX[i]);
					int sy = square(y, ty, capY[i]);
					mark(r, GCOLOR.MAP().BAD, sx, sy);
					capNames[i].render(r, sx, sy - capNames[i].height());
				}
				if (ownX != FactionPick.NONE) // the picked spot as the placer draws a spot, fixed there (SOS-198)
					mark(r, GCOLOR.MAP().SOSO, square(x, tx, ownX), square(y, ty, ownY));
			}

			private int square(int at, int t, int c) {
				return at + (c - HALF - t) * C.TILE_SIZE;
			}

			/** A capital's square in vanilla's placer tiles, edges masked as PlacableFixedTool masks them. */
			private void mark(SPRITE_RENDERER r, COLOR color, int x1, int y1) {
				int n = WCentre.TILE_DIM;
				color.bind();
				for (int dy = 0; dy < n; dy++) {
					for (int dx = 0; dx < n; dx++) {
						int m = (dx == 0 ? DIR.W.mask() : 0) | (dx == n - 1 ? DIR.E.mask() : 0)
								| (dy == 0 ? DIR.N.mask() : 0) | (dy == n - 1 ? DIR.S.mask() : 0);
						SPRITES.cons().BIG.solid.render(r, ~m & 0x0F, x1 + dx * C.TILE_SIZE, y1 + dy * C.TILE_SIZE);
					}
				}
				COLOR.unbind();
			}
		}
	}
}
