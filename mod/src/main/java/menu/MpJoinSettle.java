package menu;

import java.nio.file.Path;

import game.GAME;
import game.faction.FACTIONS;
import game.faction.FactionProfileFlusher;
import game.faction.player.Player;
import game.save.GameLoader;
import init.constant.C;
import init.race.RACES;
import init.race.Race;
import init.sprite.SPRITES;
import init.sprite.UI.UI;
import snake2d.CORE;
import snake2d.CORE_STATE;
import snake2d.LOG;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
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
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GText;
import util.gui.panel.GPanel;
import view.interrupter.InterManager;
import view.interrupter.Interrupter;
import view.main.VIEW;
import view.tool.PlacableFixedImp;
import view.ui.profile.UIProfile;
import view.world.generator.MpJoinStage;
import world.WORLD;
import world.map.regions.centre.WCentre;

/**
 * SOSGSGMP hot-join (SOS-190, docs/plan/hot-join-restart.md): a PC that joins a running game picks its race, faction
 * and the spot of its city-state on the host's snapshot, loaded outside any engine session and paused, while everyone
 * else plays on. The host checks the spot on the live world; once it takes it, the founding save is made and every PC
 * restarts from it, this one from the host's copy ({@link MpWorldLoad}). The snapshot is a copy of the host's game: the
 * faction this panel edits is the host's Player in that copy, which nothing keeps.
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
		String race = p.race().key;
		// this PC's own faction, not the host's: its saved default, else a name to type
		if (FactionProfileFlusher.canLoad(p))
			FactionProfileFlusher.load(p);
		else
			p.name.clear();
		LOG.ln("SOSGSGMP: the host's snapshot to join on loaded in " + (System.nanoTime() - t) / 1_000_000 + " ms");
		Panel panel = new Panel(setup, game, race, hostX, hostY);
		// the setup's location screen, not the running game's: the generator's view on the whole map, unfogged (SOS-198)
		panel.open(MpJoinStage.open(panel.placer, hostX, hostY));
		MpChat.show(setup);
		return view;
	}

	/** The race, Customize, the status line, Ready and Leave; the placer is on the map throughout. */
	private static final class Panel extends Interrupter {

		private static final int WIDTH = 400;
		private static final int STATUS_LINES = 7;
		private static final int HALF = WCentre.TILE_DIM / 2;
		private static final CharSequence TITLE = "Place your city-state";
		private static final String PLACE = "Pick your race, name your faction (Customize), click a spot on the map 6 tiles"
				+ " or more from every town and every player's capital, then press Ready. Everyone plays on meanwhile.";

		private final SetupSession setup;
		private final WorldSetupAdapter game;
		private final GuiSection section = new GuiSection();
		private final GuiSection inner = new GuiSection();
		private final MpDrag drag = new MpDrag();
		private final GText status = new GText(UI.FONT().M, 384);
		private final GText raceText = new GText(UI.FONT().M, 64);
		private final int hostX;
		private final int hostY;
		private final MpAutopilot autopilot = MpAutopilot.fromEnv();
		final Placer placer = new Placer();
		private String race;
		private int ownX = FactionPick.NONE;
		private int ownY = FactionPick.NONE;
		/** The pick went to the host, which hasn't answered yet, or took it. */
		private boolean sent;
		private boolean taken;
		private String answer = "";
		/** After the host's restart: the new setup, until its slot map and the founding save are here. */
		private SetupSession restarted;
		private boolean leaving;
		/** Every player's capital in the snapshot, for the placer's marks. */
		private final int[] capX = new int[FACTIONS.MAX()];
		private final int[] capY = new int[FACTIONS.MAX()];
		private final GText[] capNames = new GText[FACTIONS.MAX()];
		private int caps;

		Panel(SetupSession setup, WorldSetupAdapter game, String race, int hostX, int hostY) {
			super(true, true, false);
			this.setup = setup;
			this.game = game;
			this.race = race;
			this.hostX = hostX;
			this.hostY = hostY;
			for (int i = 0; i < FACTIONS.MAX(); i++) {
				if (FACTIONS.getByIndex(i) instanceof Player h && h.capitolRegion() != null) {
					capX[caps] = h.capitolRegion().cx();
					capY[caps] = h.capitolRegion().cy();
					capNames[caps] = new GText(UI.FONT().S, 32);
					capNames[caps].add(i == FACTIONS.player().index() ? "the host" : h.name);
					caps++;
				}
			}
			inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.height()) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					raceText.clear().add("Race: ").add(raceName(Panel.this.race));
					raceText.render(r, body.x1(), body.y1());
				}
			}, 0, 0);
			GuiSection raceButtons = new GuiSection();
			raceButtons.add(new GButt.ButtPanel("previous race") {
				@Override
				protected void clickA() {
					cycleRace(-1);
				}

				@Override
				protected void renAction() {
					activeSet(!locked());
				}
			});
			raceButtons.addRightC(8, new GButt.ButtPanel("next race") {
				@Override
				protected void clickA() {
					cycleRace(1);
				}

				@Override
				protected void renAction() {
					activeSet(!locked());
				}
			});
			raceButtons.addRightC(8, new GButt.ButtPanel("customize") {
				@Override
				protected void clickA() {
					new Customize().open();
				}

				@Override
				protected void renAction() {
					activeSet(!locked());
				}
			});
			inner.addDown(4, raceButtons);
			inner.addDown(8, new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.height() * STATUS_LINES) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					status.render(r, body.x1(), body.y1());
				}
			});
			status.setMaxWidth(WIDTH);
			status.setMultipleLines(true);
			GuiSection buttons = new GuiSection();
			buttons.add(new GButt.ButtPanel("ready") {
				@Override
				protected void clickA() {
					ready();
				}

				@Override
				protected void renAction() {
					activeSet(!locked() && ownX != FactionPick.NONE && named());
				}
			});
			buttons.addRightC(16, MpSetupLeave.button(setup, () -> leaving = true));
			inner.addDown(8, buttons);
			GPanel panel = new GPanel(inner.body().width(), inner.body().height());
			panel.setTitle(TITLE);
			panel.inner().set(inner);
			section.add(panel);
			section.add(inner);
			section.body().moveX1(16).moveY1(64); // on the left, as the setup's location screen
			showStatus();
		}

		void open(InterManager screen) {
			show(screen);
		}

		/** Sent and waiting for the host, taken, or restarting: nothing changes. */
		private boolean locked() {
			return sent || taken || restarted != null || setup.closed();
		}

		private boolean named() {
			return !FACTIONS.player().name.toString().isBlank();
		}

		private void cycleRace(int by) {
			int n = RACES.all().size();
			int at = 0;
			for (int i = 0; i < n; i++)
				if (RACES.all().get(i).key.equals(race))
					at = i;
			for (int k = 1; k <= n; k++) {
				Race r = RACES.all().get(Math.floorMod(at + by * k, n));
				if (r.playable) {
					race = r.key;
					return;
				}
			}
		}

		private static CharSequence raceName(String key) {
			for (Race r : RACES.all())
				if (r.key.equals(key))
					return r.info.name;
			return key;
		}

		private void placeAt(int x, int y) {
			if (locked())
				return;
			ownX = x;
			ownY = y;
			answer = "";
			showStatus();
		}

		/** The pick goes to the host: this race, the faction as customized, the spot. */
		private void ready() {
			FactionPick p = game.pick(ownX, ownY);
			if (p == null || locked())
				return;
			setup.pick(new FactionPick(race, p.name(), p.ruler(), p.banner(), ownX, ownY));
			sent = true;
			showStatus();
		}

		private void showStatus() {
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
				status.add("Name your faction: press Customize. ").add(PLACE);
			else
				status.add(PLACE);
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
					hide();
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
						ready();
				});
		}

		/** Vanilla's faction name, ruler name and banner, with Save and Load Default, on this PC's faction. */
		private final class Customize extends MpWindow {
			Customize() {
				super("Customize Faction");
				inner.add(UIProfile.section(false));
				inner.addDown(8, new GButt.ButtPanel("done") {
					@Override
					protected void clickA() {
						close();
						showStatus();
					}
				});
			}

			@Override
			void frame() {
			}
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
				if (ownX != FactionPick.NONE) // the placed spot as the placer draws a spot, fixed there (SOS-198)
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
