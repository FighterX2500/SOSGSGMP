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
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.FactionStep;
import util.colors.GCOLOR;
import util.gui.misc.GBox;
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
	private final GuiSection section = new GuiSection();
	private final MpCursors cursors;
	private final GText[] rows = new GText[LobbySettings.MAX_PLAYERS];
	private int shownRows;
	private final GText status = new GText(UI.FONT().M, 256);
	private int shownChanges = -1;
	private boolean leaving;
	/** This PC's landing party is placed and the session told. */
	private boolean landed;

	static void show(SetupSession setup, WorldSetupAdapter game) {
		new MpSessionPanel(setup, game).show(VIEW.inters().manager);
	}

	private MpSessionPanel(SetupSession setup, WorldSetupAdapter game) {
		super(true, true, false);
		this.setup = setup;
		this.game = game;
		this.cursors = new MpCursors(setup);
		for (int i = 0; i < rows.length; i++)
			rows[i] = new GText(UI.FONT().M, 96);
		int rowH = UI.FONT().M.height() + 2;
		GuiSection inner = new GuiSection();
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

	@Override
	protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
		return section.hover(mCoo);
	}

	@Override
	protected void mouseClick(MButt button) {
		if (button == MButt.LEFT)
			section.click();
	}

	@Override
	protected void hoverTimer(GBox text) {
		section.hoverInfoGet(text);
	}

	@Override
	protected boolean render(Renderer r, float ds) {
		pump();
		if (!leaving && VIEW.world().isActive())
			cursors.render(r, VIEW.world().window);
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
		setup.update();
		cursors.send(VIEW.world().window, VIEW.world().isActive());
		if (!landed && !setup.closed() && game.landed()) {
			landed = true;
			setup.startSpot();
		}
		if (setup.changes() != shownChanges)
			refresh();
	}

	private void refresh() {
		shownChanges = setup.changes();
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
					row.add(": ready");
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
		else if (f.started())
			status.add("Every player has placed their landing party: the game starts. (Running it over the network is the next card, SOS-57.)");
		else if (loading > 0)
			status.add("Waiting for ").add(loading).add(loading == 1 ? " player" : " players").add(" to load the world.");
		else if (!landed)
			status.add("Every player has the same world. Open your city and place your landing party; the game starts when every player has.");
		else
			status.add("Waiting for every player's landing party.");
	}
}
