package menu;

import java.nio.file.Path;
import java.util.List;
import game.GAME;
import game.faction.FACTIONS;
import game.save.GameLoader;
import init.race.RACES;
import init.race.Race;
import init.sprite.UI.UI;
import snake2d.CORE;
import snake2d.CORE_STATE;
import snake2d.LOG;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.TitlesMode;
import sosgsgmp.setup.FactionStep;
import util.colors.GCOLOR;
import util.gui.misc.GButt;
import util.gui.misc.GHeader;
import util.gui.misc.GText;
import view.main.VIEW;
import view.world.generator.MpJoinScreens;
import view.world.generator.MpSpotMarks;
import view.world.generator.MpSpotPlacer;
import world.WORLD;
import world.map.regions.Region;

/**
 * SOSGSGMP: a living world's settling (spec §5 World, SOS-187). Every PC loads the world the host built around a stand-in
 * capital (SOS-214), outside any engine session, and the game stays paused: it is a map to pick on, not a game. Every
 * player, the host too, places a 3x3 city-state on its real map, with its kingdoms, borders and roads, by the engine's
 * carving rule (the host checks again), and presses Ready. The host's Initialize World then carves them in on this
 * world and sends the world every PC starts from ({@link MpWorldLoad}). The pick is on the world generator's location
 * screen, as a hot-join's ({@link MpJoinScreens}, SOS-213), with the spot's hover window and notices.
 */
public final class MpSettle implements CORE_STATE.Constructor {

	private final SetupSession setup;
	private final byte[] save;
	/** The map step's rebuild, where a passed vote for a new world goes (spec §5). */
	private final CORE_STATE.Constructor rebuild;

	public MpSettle(SetupSession setup, byte[] save, CORE_STATE.Constructor rebuild) {
		this.setup = setup;
		this.save = save.clone();
		this.rebuild = rebuild;
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
		Region standIn = FACTIONS.getByIndex(0).capitolRegion();
		int cx = standIn == null ? WORLD.TWIDTH() / 2 : standIn.cx();
		int cy = standIn == null ? WORLD.THEIGHT() / 2 : standIn.cy();
		FactionPick own = setup.factions().ownPick();
		if (own == null)
			throw new IllegalStateException("SOSGSGMP: settling with no picks of this PC's");
		game.wear(own); // race, faction and banner can change until Ready, as before the world was built
		Settle settle = new Settle(setup, rebuild);
		MpJoinScreens.settle(settle, cx, cy);
		// every frame, as the factions step: vanilla's "click to continue" after the load hides the screen's own render
		GAME.addAfterUpdate(settle::pump);
		MpChat.show(setup);
		return view;
	}

	/** The players' rows, the status line, Ready, the host's Initialize World, Leave, and the placer. */
	private static final class Settle implements MpJoinScreens.Pick {

		private static final int WIDTH = 420;
		/** The status box's least height, in lines; it grows to the host's longest text, so the buttons under it fit. */
		private static final int STATUS_LINES = 9;
		private static final CharSequence TITLE = "Place your city-state";
		private static final String PLACE = "Click where your city-state goes: 6 tiles or more from every town and every"
				+ " other player. Hovering a spot shows its land, climate and road links; the tooltip says why a spot can't be"
				+ " used. A region of a large kingdom breaks away as a new neutral state; unowned land becomes one.";
		private static final String STAND_IN = " Your town on this map is a stand-in: it becomes a neutral state when the"
				+ " world is initialized.";

		private final SetupSession setup;
		private final GText[] rows = new GText[LobbySettings.MAX_PLAYERS];
		private int shownRows;
		private final GText status = new GText(UI.FONT().M, 512);
		private int shownChanges = -1;
		private boolean leaving;
		/** Race, faction name and banner as this PC's player has them: sent when they change (owner, 2026-11-26). */
		private final WorldSetupAdapter game = new WorldSetupAdapter();
		private static final double PICK_CHECK_SECONDS = 0.5;
		private long lastPickCheck;
		/** This PC's spot as last placed, or NONE. */
		private int ownX = FactionPick.NONE;
		private int ownY = FactionPick.NONE;
		/** The other players' spots and names, for the placer's marks and the notice. */
		private final int[] otherX = new int[LobbySettings.MAX_PLAYERS];
		private final int[] otherY = new int[LobbySettings.MAX_PLAYERS];
		private final GText[] otherNames = new GText[LobbySettings.MAX_PLAYERS];
		private int others;
		private final MpSpotMarks marks = new MpSpotMarks();
		/** This PC's race, as its picks hold it: the hover rates the climate for it. */
		private Race race = FACTIONS.player().race();
		private final MpAutopilot autopilot = MpAutopilot.fromEnv();
		/** The host's loading window for Initialize World's freeze, on every PC (SOS-158). */
		private final MpLoading loading;
		private final MpCursors cursors;
		private final Placer placer = new Placer();

		/** The host's vote for a new world, and every PC's answer (spec §5). */
		private final MpWorldVote worldVote;
		private final CORE_STATE.Constructor rebuild;

		Settle(SetupSession setup, CORE_STATE.Constructor rebuild) {
			this.setup = setup;
			this.rebuild = rebuild;
			this.worldVote = new MpWorldVote(setup);
			this.loading = new MpLoading(setup);
			this.cursors = new MpCursors(setup);
			marks.own(FACTIONS.player().name, FACTIONS.player().banner()); // this PC's player wears its picks
			for (int i = 0; i < rows.length; i++) {
				rows[i] = new GText(UI.FONT().M, 128).setMaxWidth(WIDTH);
				rows[i].setMultipleLines(true);
				otherNames[i] = new GText(UI.FONT().S, FactionPick.MAX_NAME);
			}
		}

		@Override
		public GuiSection info() {
			GuiSection s = new GuiSection();
			int rowH = UI.FONT().M.height() * 2 + 2;
			s.add(new GHeader("players"), 0, 0);
			s.addDown(4, new RENDEROBJ.RenderImp(WIDTH, rowH * setup.players().size()) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					int y = body.y1();
					for (int i = 0; i < shownRows && y + rows[i].height() <= body.y2(); i++) {
						rows[i].render(r, body.x1(), y);
						y += rows[i].height() + 2;
					}
				}
			});
			s.addDown(12, new RENDEROBJ.RenderImp(WIDTH, statusHeight()) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					COLOR.WHITE100.bind();
					UI.FONT().M.render(r, status, body.x1(), body.y1(), body.width(), 1.0);
					COLOR.unbind();
				}
			});
			return s;
		}

		/** The longest status, the host's, measured as the box wraps it, and one line more for a refusal's reason. */
		private static int statusHeight() {
			int longest = UI.FONT().M.getHeight(PLACE + STAND_IN, WIDTH) + UI.FONT().M.height();
			return Math.max(UI.FONT().M.height() * STATUS_LINES, longest);
		}

		@Override
		public GButt.ButtPanel ready() {
			GButt.ButtPanel b = new GButt.ButtPanel("ready") {
				@Override
				protected void clickA() {
					FactionStep f = setup.factions();
					if (!setup.closed() && f != null && f.canReady())
						setup.ready(!f.ready());
				}

				@Override
				protected void renAction() {
					FactionStep f = setup.factions();
					activeSet(!setup.closed() && f != null && f.canReady());
					selectedSet(!setup.closed() && f != null && f.ready());
				}
			};
			b.hoverInfoSet("While you are ready your spot can't change. The host starts the game once every player is ready.");
			return b;
		}

		@Override
		public GButt.ButtPanel start() {
			if (!setup.isHost())
				return null;
			GButt.ButtPanel b = new GButt.ButtPanel("initialize world") {
				@Override
				protected void clickA() {
					FactionStep f = setup.factions();
					if (!setup.closed() && f != null && f.canInitialize())
						setup.initialize();
				}

				@Override
				protected void renAction() {
					FactionStep f = setup.factions();
					activeSet(!setup.closed() && f != null && f.canInitialize());
				}
			};
			b.hoverInfoSet("Carves every player's city-state into this world and starts every player's game.");
			return b;
		}

		@Override
		public GButt.ButtPanel leave() {
			return MpSetupLeave.button(setup, () -> leaving = true);
		}

		@Override
		public MpSpotPlacer placer() {
			return placer;
		}

		@Override
		public MpCursors cursors() {
			return cursors;
		}

		@Override
		public MpSpotMarks marks() {
			return marks;
		}

		@Override
		public TitlesMode titles() {
			return setup.titlesToPick();
		}

		@Override
		public GButt.ButtPanel regenerate() {
			return setup.isHost() ? worldVote.button() : null;
		}

		/** Ready, or the world being built, or gone after a vote for a new world: the spot can't change. */
		@Override
		public boolean locked() {
			FactionStep f = setup.factions();
			return setup.closed() || f == null || f.initialized() || f.ready();
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

		/** Sends this PC's picks when its player's race, names or banner differ from the last sent, twice a second. */
		private void syncPick() {
			long now = System.nanoTime();
			if (now - lastPickCheck < (long) (PICK_CHECK_SECONDS * 1e9) || locked())
				return;
			lastPickCheck = now;
			FactionPick mine = setup.factions().ownPick();
			FactionPick worn = game.pick(FactionPick.NONE, FactionPick.NONE);
			if (mine == null || worn == null)
				return;
			FactionPick next = worn.at(mine.x(), mine.y());
			if (!next.equals(mine))
				setup.pick(next);
		}

		/** The screen's render pumps nothing: {@link #pump} runs after every update, under any load screen too. */
		@Override
		public void frame() {
		}

		/** Once per frame after the game's update: the session, the loading window, the world when it comes, the autopilot. */
		void pump() {
			if (leaving)
				return;
			if (!GAME.SPEED.isPaused())
				GAME.SPEED.speedSet(0); // a map to pick on: nothing in it runs
			setup.update();
			worldVote.frame(); // first: its result, and on the host the vote's end, may take the factions step away
			if (setup.factions() == null) { // a vote for a new world passed: the map step, once the host's build is here
				if (!setup.closed() && setup.step().pendingBuild() > 0) {
					leaving = true;
					CORE.setCurrentState(rebuild);
				}
				return;
			}
			loading.frame();
			byte[] world = setup.takeWorld();
			if (world != null) {
				leaving = true;
				CORE.setCurrentState(new MpWorldLoad(setup, world));
				return;
			}
			syncPick();
			if (setup.changes() != shownChanges)
				refresh();
			if (autopilot == null)
				return;
			autopilot.settle(setup, ownX != FactionPick.NONE, (x, y) -> {
				placeAt(x, y);
				return ownX;
			});
			if (autopilot.spotHover() && ownX != FactionPick.NONE) // a GUI preview's shot of the hover window
				placer.placeInfo(VIEW.hoverBox(), ownX - 1, ownY - 1);
		}

		private void refresh() {
			shownChanges = setup.changes();
			FactionStep f = setup.factions();
			List<FactionStep.PlayerPick> table = f.table();
			FactionStep.PlayerPick mine = null;
			List<LobbyPlayer> players = setup.players();
			shownRows = Math.min(players.size(), rows.length);
			others = 0;
			marks.clear();
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
					row.add(", at ").add(fp.x()).add(',').add(fp.y());
					if (pick.ready())
						row.add(", ready");
					row.color(GCOLOR.T().IGOOD);
					if (p.id() != setup.localPlayerId()) {
						otherX[others] = fp.x();
						otherY[others] = fp.y();
						otherNames[others].clear().add(fp.name());
						others++;
						marks.add(fp);
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
			FactionPick own = f.ownPick();
			if (own != null)
				race = race(own.race());

			status.clear();
			if (setup.closed())
				status.add(setup.closedReason());
			else if (f.initialized())
				status.add(setup.isHost() ? "Carving every city-state in and sending the world to every player." : "The host is carving every city-state in; getting the world.");
			else if (mine != null && mine.refused() != null)
				status.add("Your spot was refused: ").add(mine.refused()).add(". Pick another.");
			else if (mine == null || !mine.pick().hasLocation())
				status.add(PLACE).add(setup.isHost() ? STAND_IN : "");
			else if (!f.ready())
				status.add("Press Ready when your city-state is where you want it.");
			else if (setup.isHost() && f.canInitialize())
				status.add("Every player has settled. Initialize World carves every city-state into this world and starts every player's game.");
			else if (setup.isHost())
				status.add("Waiting for every player to settle. The game is paused until it starts.");
			else
				status.add("Waiting for every player to settle, then for the host to start.");
		}

		private static FactionStep.PlayerPick find(List<FactionStep.PlayerPick> table, int playerId) {
			for (FactionStep.PlayerPick p : table)
				if (p.playerId() == playerId)
					return p;
			return null;
		}

		/** The race of {@code key} in this game; this world's player's when this game doesn't have it. */
		private static Race race(String key) {
			for (Race r : RACES.all())
				if (r.key.equals(key))
					return r;
			return FACTIONS.player().race();
		}

		/** The city-state placer, checked by the engine's carving rule with the other players' spots held. */
		private final class Placer extends MpSpotPlacer {

			Placer() {
				super(TITLE, true);
			}

			@Override
			protected CharSequence locked() {
				return Settle.this.locked() ? "You are ready: press Ready again to move your city-state." : null;
			}

			@Override
			protected CharSequence problem(int cx, int cy) {
				return setup.locationProblem(cx, cy);
			}

			@Override
			protected void picked(int cx, int cy) {
				placeAt(cx, cy);
			}

			@Override
			protected Race race() {
				return race;
			}

			@Override
			protected int ownX() {
				return ownX;
			}

			@Override
			protected int ownY() {
				return ownY;
			}

			@Override
			public int noticeKey() {
				return setup.changes();
			}

			@Override
			public int others() {
				return others;
			}

			@Override
			public int otherX(int i) {
				return otherX[i];
			}

			@Override
			public int otherY(int i) {
				return otherY[i];
			}

			@Override
			public GText otherName(int i) {
				return otherNames[i];
			}
		}
	}
}
