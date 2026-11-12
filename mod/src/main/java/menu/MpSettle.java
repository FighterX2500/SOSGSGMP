package menu;

import java.nio.file.Path;
import java.util.List;
import game.GAME;
import game.save.GameLoader;
import init.constant.C;
import init.sprite.UI.UI;
import snake2d.CORE;
import snake2d.CORE_STATE;
import snake2d.LOG;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.LIST;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.Cursors;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;
import util.colors.GCOLOR;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GHeader;
import util.gui.misc.GText;
import util.gui.panel.GPanel;
import view.interrupter.Interrupter;
import view.main.VIEW;
import view.tool.PlacableFixedImp;
import view.tool.ToolConfig;
import world.WORLD;
import world.map.regions.centre.WCentre;

/**
 * SOSGSGMP: a living world's settling (spec §5 World, SOS-187). Every PC, the host's too, loads the world the host built
 * around its own capital, outside any engine session, and the game stays paused: it is a map to pick on, not a game.
 * Every other player places a 3x3 city-state on its real map, with its kingdoms, borders and roads, by the engine's
 * carving rule (the host checks again), and presses Ready. The host's Initialize World then carves them in on this
 * world and sends the world every PC starts from ({@link MpWorldLoad}). The panel pumps the session from its render, as
 * {@link MpSessionPanel} does.
 */
public final class MpSettle implements CORE_STATE.Constructor {

	private final SetupSession setup;
	private final byte[] save;

	public MpSettle(SetupSession setup, byte[] save) {
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
		WORLD.FOW().toggled.set(false); // the whole map to pick on, not the host's fog; the host's final save turns it on
		LOG.ln("SOSGSGMP: the world to settle on loaded in " + (System.nanoTime() - t) / 1_000_000 + " ms");
		Panel panel = new Panel(setup);
		panel.open();
		MpChat.show(setup);
		VIEW.world().activate();
		FactionPick host = setup.factions().table().isEmpty() ? null : setup.factions().table().get(0).pick();
		if (host != null && host.hasLocation())
			VIEW.world().window.centererTile.set(host.x(), host.y());
		if (!setup.isHost())
			panel.place();
		return view;
	}

	/** The players' rows, the status line, Ready or the host's Initialize World, and Leave. */
	private static final class Panel extends Interrupter {

		private static final int WIDTH = 400;
		private static final int STATUS_LINES = 8;
		private static final int HALF = WCentre.TILE_DIM / 2;
		private static final CharSequence TITLE = "Place your city-state";

		private final SetupSession setup;
		private final GuiSection section = new GuiSection();
		private final GuiSection inner = new GuiSection();
		private final MpCursors cursors;
		private final MpDrag drag = new MpDrag();
		private final GText[] rows = new GText[LobbySettings.MAX_PLAYERS];
		private int shownRows;
		private final GText status = new GText(UI.FONT().M, 384);
		private int shownChanges = -1;
		private boolean leaving;
		/** This PC's spot as last placed, or NONE. */
		private int ownX = FactionPick.NONE;
		private int ownY = FactionPick.NONE;
		/** The other players' spots (the host's capital among them) and names, for the placer's marks. */
		private final int[] otherX = new int[LobbySettings.MAX_PLAYERS];
		private final int[] otherY = new int[LobbySettings.MAX_PLAYERS];
		private final GText[] otherNames = new GText[LobbySettings.MAX_PLAYERS];
		private int others;
		private final MpAutopilot autopilot = MpAutopilot.fromEnv();
		/** The host's loading window for Initialize World's freeze, on every PC (SOS-158). */
		private final MpLoading loading;

		Panel(SetupSession setup) {
			super(true, true, false);
			this.setup = setup;
			this.cursors = new MpCursors(setup);
			this.loading = new MpLoading(setup);
			for (int i = 0; i < rows.length; i++) {
				rows[i] = new GText(UI.FONT().M, 128).setMaxWidth(WIDTH);
				rows[i].setMultipleLines(true);
				otherNames[i] = new GText(UI.FONT().S, FactionPick.MAX_NAME);
			}
			int rowH = UI.FONT().M.height() * 2 + 2;
			inner.add(new GHeader("players"), 0, 0);
			inner.addDown(4, new RENDEROBJ.RenderImp(WIDTH, rowH * setup.players().size()) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					int y = body.y1();
					for (int i = 0; i < shownRows && y + rows[i].height() <= body.y2(); i++) {
						rows[i].render(r, body.x1(), y);
						y += rows[i].height() + 2;
					}
				}
			});
			inner.addDown(8, new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.height() * STATUS_LINES) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					COLOR.WHITE100.bind();
					UI.FONT().M.render(r, status, body.x1(), body.y1(), body.width(), 1.0);
					COLOR.unbind();
				}
			});
			GuiSection buttons = new GuiSection();
			if (setup.isHost()) {
				buttons.add(new GButt.ButtPanel("initialize world") {
					@Override
					protected void clickA() {
						if (!setup.closed() && setup.factions().canInitialize())
							setup.initialize();
					}

					@Override
					protected void renAction() {
						activeSet(!setup.closed() && setup.factions().canInitialize());
					}
				}.hoverInfoSet("Carves every player's city-state into this world and starts every player's game."));
			} else {
				buttons.add(new GButt.ButtPanel("place") {
					@Override
					protected void clickA() {
						place();
					}

					@Override
					protected void renAction() {
						activeSet(!locked());
					}
				}.hoverInfoSet("Pick where your city-state goes on the map."));
				buttons.addRightC(16, new GButt.ButtPanel("ready") {
					@Override
					protected void clickA() {
						if (!setup.closed() && setup.factions().canReady())
							setup.ready(!setup.factions().ready());
					}

					@Override
					protected void renAction() {
						activeSet(!setup.closed() && setup.factions().canReady());
						selectedSet(!setup.closed() && setup.factions().ready());
					}
				}.hoverInfoSet("While you are ready your spot can't change. The host starts the game once every player is ready."));
			}
			buttons.addRightC(16, MpSetupLeave.button(setup, () -> leaving = true));
			inner.addDown(8, buttons);
			GPanel panel = new GPanel(inner.body().width(), inner.body().height());
			panel.setTitle("Settling");
			panel.inner().set(inner);
			section.add(panel);
			section.add(inner);
			section.body().moveX2(C.WIDTH() - 16).moveY1(96);
		}

		void open() {
			show(VIEW.inters().manager);
		}

		/** The placer on the world map: a 3x3 square whose centre is the spot. */
		void place() {
			if (locked())
				return;
			VIEW.world().tools.place(new Placer(), new ToolConfig() {
				@Override
				public boolean back() {
					return false;
				}
			});
		}

		/** Ready, or the world being built: the spot can't change. */
		private boolean locked() {
			return setup.closed() || setup.factions().initialized() || setup.factions().ready();
		}

		private void placeAt(int x, int y) {
			if (locked())
				return;
			FactionPick mine = setup.factions().ownPick();
			if (mine == null)
				throw new IllegalStateException("SOSGSGMP: settling with no picks of this PC's");
			ownX = x;
			ownY = y;
			setup.pick(mine.at(x, y));
		}

		@Override
		protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
			return section.hover(mCoo) || section.body().holdsPoint(mCoo.x(), mCoo.y());
		}

		@Override
		protected void mouseClick(MButt button) {
			if (button != MButt.LEFT)
				return;
			if (!drag.grab(section, inner, VIEW.mouse().x(), VIEW.mouse().y()))
				section.click();
		}

		@Override
		protected void hoverTimer(GBox text) {
			section.hoverInfoGet(text);
		}

		@Override
		protected boolean render(Renderer r, float ds) {
			pump();
			if (leaving)
				return true;
			if (VIEW.world().isActive())
				cursors.render(r, VIEW.world().window, Cursors.WORLD);
			drag.follow(section, VIEW.mouse().x(), VIEW.mouse().y());
			section.render(r, ds);
			return true;
		}

		@Override
		protected boolean update(float ds) {
			return true;
		}

		@Override
		public boolean canSave() {
			return false;
		}

		private void pump() {
			if (leaving)
				return;
			if (!GAME.SPEED.isPaused())
				GAME.SPEED.speedSet(0); // a map to pick on: nothing in it runs
			setup.update();
			loading.frame();
			byte[] world = setup.takeWorld();
			if (world != null) {
				leaving = true;
				hide();
				CORE.setCurrentState(new MpWorldLoad(setup, world));
				return;
			}
			cursors.send(VIEW.world().window, VIEW.world().isActive() ? Cursors.WORLD : Cursors.OFF);
			if (setup.changes() != shownChanges)
				refresh();
			if (autopilot != null)
				autopilot.settle(setup, ownX != FactionPick.NONE, (x, y) -> {
					placeAt(x, y);
					return ownX;
				});
		}

		private void refresh() {
			shownChanges = setup.changes();
			FactionStep f = setup.factions();
			List<FactionStep.PlayerPick> table = f.table();
			FactionStep.PlayerPick mine = null;
			List<LobbyPlayer> players = setup.players();
			shownRows = Math.min(players.size(), rows.length);
			others = 0;
			for (int i = 0; i < shownRows; i++) {
				LobbyPlayer p = players.get(i);
				GText row = rows[i].clear();
				row.color(GCOLOR.T().NORMAL);
				row.add(p.name());
				if (p.host())
					row.add(" (host)");
				if (p.id() == setup.localPlayerId())
					row.add(" (you)");
				FactionStep.PlayerPick pick = find(table, p.id());
				if (p.id() == setup.localPlayerId())
					mine = pick;
				if (pick == null) {
					row.add(": picking");
					continue;
				}
				FactionPick fp = pick.pick();
				row.add(": ").add(fp.name());
				if (fp.hasLocation()) {
					row.add(p.host() ? ", capital at " : ", at ").add(fp.x()).add(',').add(fp.y());
					if (pick.ready())
						row.add(", ready");
					row.color(GCOLOR.T().IGOOD);
					if (p.id() != setup.localPlayerId()) {
						otherX[others] = fp.x();
						otherY[others] = fp.y();
						otherNames[others].clear().add(fp.name());
						others++;
					}
				} else if (pick.refused() != null) {
					row.add(", spot refused: ").add(pick.refused());
					row.color(GCOLOR.T().IBAD);
				} else {
					row.add(", picking a spot");
				}
			}
			for (int i = 0; i < shownRows; i++)
				rows[i].adjustWidth();
			if (mine != null && mine.refused() != null) {
				ownX = FactionPick.NONE;
				ownY = FactionPick.NONE;
			}

			status.clear();
			if (setup.closed())
				status.add(setup.closedReason());
			else if (f.initialized())
				status.add(setup.isHost() ? "Carving every city-state in and sending the world to every player." : "The host is carving every city-state in; getting the world.");
			else if (setup.isHost() && f.canInitialize())
				status.add("Every player has settled. Initialize World carves their city-states into this world and starts every player's game.");
			else if (setup.isHost())
				status.add("The others are picking their spots on your world. The game is paused until it starts.");
			else if (mine != null && mine.refused() != null)
				status.add("Your spot was refused: ").add(mine.refused()).add(". Pick another.");
			else if (mine == null || !mine.pick().hasLocation())
				status.add("Press Place and pick where your city-state goes: 6 tiles or more from another player, away from roads and large water. The tooltip says why a spot can't be used. A region of a large kingdom breaks away as a new neutral state; unowned land becomes one.");
			else if (!f.ready())
				status.add("Press Ready when your city-state is where you want it.");
			else
				status.add("Waiting for every player to settle, then for the host to start.");
		}

		private static FactionStep.PlayerPick find(List<FactionStep.PlayerPick> table, int playerId) {
			for (FactionStep.PlayerPick p : table)
				if (p.playerId() == playerId)
					return p;
			return null;
		}

		/** The city-state placer: a 3x3 square whose centre is the spot, checked by the engine's carving rule. */
		private final class Placer extends PlacableFixedImp {

			private int checkedX = -1;
			private int checkedY = -1;
			private int checkedChanges = -1;
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
				// Nothing on the map: the spot goes to the host in afterPlaced.
			}

			@Override
			public void afterPlaced(int tx1, int ty1) {
				placeAt(tx1 + HALF, ty1 + HALF);
			}

			@Override
			public CharSequence placableWhole(int tx1, int ty1) {
				if (locked())
					return "You are ready: press Ready again to move your city-state.";
				int cx = tx1 + HALF;
				int cy = ty1 + HALF;
				if (cx != checkedX || cy != checkedY || setup.changes() != checkedChanges) {
					checkedX = cx;
					checkedY = cy;
					checkedChanges = setup.changes();
					problem = setup.locationProblem(cx, cy);
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

			/**
			 * Vanilla's placeholder, and once per frame (at its first tile) every other player's spot and this PC's: {@code x, y}
			 * is tile {@code tx, ty} on screen, so any tile is at the same offset in tiles (PlacableFixedTool.render).
			 */
			@Override
			public void renderPlaceHolder(SPRITE_RENDERER r, int mask, int x, int y, int tx, int ty, int rx, int ry, boolean isPlacable, boolean areaIsPlacable) {
				super.renderPlaceHolder(r, mask, x, y, tx, ty, rx, ry, isPlacable, areaIsPlacable);
				if (rx != 0 || ry != 0)
					return;
				for (int i = 0; i < others; i++) {
					int sx = square(x, tx, otherX[i]);
					int sy = square(y, ty, otherY[i]);
					mark(r, GCOLOR.MAP().BAD, sx, sy);
					otherNames[i].render(r, sx, sy - otherNames[i].height());
				}
				if (ownX != FactionPick.NONE)
					mark(r, GCOLOR.MAP().OK, square(x, tx, ownX), square(y, ty, ownY));
			}

			private int square(int at, int t, int c) {
				return at + (c - HALF - t) * C.TILE_SIZE;
			}

			/** A frame, not a filled square: the host's capital is a city on this map, and the land under a spot shows. */
			private void mark(SPRITE_RENDERER r, COLOR color, int x1, int y1) {
				int size = WCentre.TILE_DIM * C.TILE_SIZE;
				int t = 3;
				color.render(r, x1, x1 + size, y1, y1 + t);
				color.render(r, x1, x1 + size, y1 + size - t, y1 + size);
				color.render(r, x1, x1 + t, y1, y1 + size);
				color.render(r, x1 + size - t, x1 + size, y1, y1 + size);
				COLOR.unbind();
			}
		}
	}
}
