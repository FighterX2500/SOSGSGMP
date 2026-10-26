package menu;

import java.util.List;

import init.constant.C;
import init.sprite.UI.UI;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.FactionStep;
import util.colors.GCOLOR;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GHeader;
import util.gui.misc.GText;
import util.gui.panel.GPanel;
import view.interrupter.Interrupter;
import view.main.VIEW;

/**
 * SOSGSGMP: the setup's panel in the loaded game (setup-factions-step.md §5): every player's progress (loading, placing
 * the landing party, ready, a world that differs), the status line and Leave. It tells the host when this PC's landing
 * party is placed (vanilla's landing view, while the engine holds at step 0). It stays on top of every view (persistent, pinned, never hiding
 * others) and pumps the session from its render, once per frame. The game can't be saved while it shows: the setup
 * isn't a game yet. Leaving ends the engine session.
 */
final class MpSessionPanel extends Interrupter {

	private static final int WIDTH = 380;

	private final SetupSession setup;
	private final WorldSetupAdapter game;
	private final EnginePlay engine;
	private final MpSaves saves;
	private int shownSaves = -1;
	/** While playing, the status is rebuilt this often: the step and speed move every frame. */
	private static final double PLAY_REFRESH_SECONDS = 0.5;
	private double sinceRefresh;
	private final GuiSection section = new GuiSection();
	private final MpCursors cursors;
	/** Deals with the other players (spec §6, SOS-72): their popups and the latest answer. */
	private final MpDeals deals;
	private final GText[] rows = new GText[LobbySettings.MAX_PLAYERS];
	private int shownRows;
	private final GText status = new GText(UI.FONT().M, 512);
	private int shownChanges = -1;
	private boolean leaving;
	/** This PC's landing party is placed and the session told. */
	private boolean landed;
	/** The panel's content, below its title bar: a press above it, on the title bar, drags the panel (owner, 2026-10-25). */
	private final GuiSection inner = new GuiSection();
	/** While the title bar is held: where the mouse grabbed it, from the panel's corner. */
	private boolean dragging;
	private int grabX, grabY;

	/** Shows the panel in this view, and the escape menu's multiplayer column (SOS-79). */
	static void show(SetupSession setup, WorldSetupAdapter game, EnginePlay engine, MpSaves saves, MpExit exit) {
		new MpSessionPanel(setup, game, engine, saves).show(VIEW.inters().manager);
		MpEscapeMenu.install(exit);
	}

	private MpSessionPanel(SetupSession setup, WorldSetupAdapter game, EnginePlay engine, MpSaves saves) {
		super(true, true, false);
		this.setup = setup;
		this.game = game;
		this.engine = engine;
		this.saves = saves;
		this.cursors = new MpCursors(setup);
		this.deals = new MpDeals(setup, engine);
		for (int i = 0; i < rows.length; i++)
			rows[i] = new GText(UI.FONT().M, 96);
		int rowH = UI.FONT().M.height() + 2;
		inner.add(new GHeader("players"), 0, 0);
		inner.addDown(4, new RENDEROBJ.RenderImp(WIDTH, rowH * setup.players().size()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				for (int i = 0; i < shownRows; i++)
					rows[i].render(r, body.x1(), body.y1() + i * rowH);
			}
		});
		inner.addDown(8, new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.height() * 3) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, status, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		});
		GuiSection dealButtons = new GuiSection();
		int others = 0;
		for (LobbyPlayer p : setup.players()) {
			if (p.id() == setup.localPlayerId())
				continue;
			int slot = setup.factions().slotOf(p.id());
			dealButtons.addRightC(others++ == 0 ? 0 : 4, new GButt.ButtPanel("deal: " + p.name()) {
				@Override
				protected void clickA() {
					if (canDeal(slot))
						deals.offer(slot);
				}

				@Override
				protected void renAction() {
					activeSet(canDeal(slot));
				}
			});
		}
		if (others > 0)
			inner.addDown(8, dealButtons);
		inner.addDown(8, MpSetupLeave.button(setup, () -> {
			leaving = true;
			game.endSession();
		}));
		GPanel panel = new GPanel(inner.body().width(), inner.body().height());
		panel.setTitle("Multiplayer setup");
		panel.inner().set(inner);
		section.add(panel);
		section.add(inner);
		section.body().moveX2(C.WIDTH() - 16).moveY1(96);
	}

	/** A deal is offered to a player in the running game who wasn't removed. */
	private boolean canDeal(int slot) {
		return slot >= 0 && setup.playing() && !setup.closed() && engine.active() && !engine.retired(slot);
	}

	@Override
	protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
		// the whole panel, its title bar too, takes the mouse: a press there drags it
		return section.hover(mCoo) || section.body().holdsPoint(mCoo.x(), mCoo.y());
	}

	@Override
	protected void mouseClick(MButt button) {
		if (button != MButt.LEFT)
			return;
		int mx = VIEW.mouse().x(), my = VIEW.mouse().y();
		if (my < inner.body().y1() && section.body().holdsPoint(mx, my)) {
			dragging = true;
			grabX = mx - section.body().x1();
			grabY = my - section.body().y1();
			return;
		}
		section.click();
	}

	/** Follows the mouse while the title bar is held, keeping the whole panel on the screen. */
	private void drag() {
		if (!dragging)
			return;
		if (!MButt.LEFT.isDown()) {
			dragging = false;
			return;
		}
		int x = Math.max(0, Math.min(C.WIDTH() - section.body().width(), VIEW.mouse().x() - grabX));
		int y = Math.max(0, Math.min(C.HEIGHT() - section.body().height(), VIEW.mouse().y() - grabY));
		section.body().moveX1Y1(x, y);
	}

	@Override
	protected void hoverTimer(GBox text) {
		section.hoverInfoGet(text);
	}

	@Override
	protected boolean render(Renderer r, float ds) {
		pump(ds);
		if (!leaving && VIEW.world().isActive())
			cursors.render(r, VIEW.world().window);
		drag();
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

	private void pump(float ds) {
		if (leaving)
			return;
		cursors.send(VIEW.world().window, VIEW.world().isActive());
		if (!landed && !setup.closed() && game.landed()) {
			landed = true;
			setup.startSpot();
		}
		sinceRefresh += ds;
		if (setup.changes() != shownChanges || saves.changes() != shownSaves || setup.playing() && sinceRefresh >= PLAY_REFRESH_SECONDS)
			refresh();
	}

	private void refresh() {
		shownChanges = setup.changes();
		shownSaves = saves.changes();
		sinceRefresh = 0;
		FactionStep f = setup.factions();
		List<LobbyPlayer> players = setup.players();
		shownRows = Math.min(players.size(), rows.length);
		int loading = 0;
		boolean mismatch = false;
		for (int i = 0; i < shownRows; i++) {
			LobbyPlayer p = players.get(i);
			GText row = rows[i].clear();
			row.color(GCOLOR.T().NORMAL);
			row.add(p.name());
			if (p.id() == setup.localPlayerId())
				row.add(" (you)");
			FactionStep.Progress progress = f.progress(p.id());
			if (progress == null || progress == FactionStep.Progress.LOADING) {
				row.add(": loading the world");
				loading++;
				continue;
			}
			switch (progress) {
				case MISMATCH -> {
					row.add(": world differs from the host's");
					row.color(GCOLOR.T().IBAD);
					mismatch = true;
				}
				case LANDING -> row.add(": placing the landing party");
				case READY -> {
					if (setup.playing() && p.id() != setup.localPlayerId()) {
						long at = engine.peerStep(f.slotOf(p.id()));
						row.add(": at step ").add(at < 0 ? 0 : at);
					} else {
						row.add(": ready");
					}
					row.color(GCOLOR.T().IGOOD);
				}
				default -> throw new IllegalStateException("SOSGSGMP: progress " + progress);
			}
		}

		status.clear();
		if (setup.closed())
			status.add(setup.closedReason());
		else if (mismatch)
			status.add("A player's world differs from the host's, so the game can't start. Leave, and start a new setup.");
		else if (setup.playing() && engine.desyncStep() >= 0)
			status.add("The worlds differ from step ").add(engine.desyncStep()).add(" on: a desync. The host chooses Resync or Save and exit.");
		else if (setup.playing()) {
			status.add("Playing: step ").add(engine.step()).add(", speed ").add(engine.agreedSpeed(), 1).add("x. The slowest PC sets the pace.");
			if (!saves.status().isEmpty())
				status.add(" ").add(saves.status());
			if (engine.active()) {
				deals.refresh();
				if (!deals.lastAnswer().isEmpty())
					status.add(" ").add(deals.lastAnswer());
			}
		}
		else if (f.started())
			status.add("Every player has placed their landing party: the game starts.");
		else if (loading > 0)
			status.add("Waiting for ").add(loading).add(loading == 1 ? " player" : " players").add(" to load the world.");
		else if (!landed)
			status.add("Every player has the same world. Open your city and place your landing party; the game starts when every player has.");
		else
			status.add("Waiting for every player's landing party.");
	}
}
