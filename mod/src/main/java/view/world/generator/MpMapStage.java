package view.world.generator;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import init.sprite.UI.UI;
import game.GAME;
import menu.MpAutopilot;
import menu.MpSetupLeave;
import snake2d.CORE;
import snake2d.CORE_STATE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.text.StringInputSprite;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStatus;
import sosgsgmp.setup.MapStep;
import util.colors.GCOLOR;
import util.data.INT.IntImp;
import util.gui.misc.GButt;
import util.gui.misc.GHeader;
import util.gui.misc.GInput;
import util.gui.misc.GText;
import util.gui.slider.GSliderInt;
import view.main.VIEW;

/**
 * SOSGSGMP: the map step's panel (setup-map-step.md §2): the host's map options with Generate and Confirm, or the
 * host's choices read-only on a joining PC, and every player's progress. It replaces the world generator's stages
 * right after the game is built, while the generator view keeps drawing the world map. It sits in the generator's
 * package because reset(), dummy, minimap and the stage flags are package-private (docs/hooks.md, note next to M1).
 * The panel's render pumps the session once per frame (Intr always renders it); texts are rebuilt only when the
 * session reports a change. Once the map is confirmed, Next opens the factions step ({@link MpFactions}): from a click,
 * as vanilla's stages switch, never from inside the frame's render.
 */
public final class MpMapStage {

	private static final CharSequence RANDOM_NAME = "Random";
	private static final int LABEL_WIDTH = 150;
	private static final int TEXT_WIDTH = 300;

	private final WorldViewGenerator g;
	private final SetupSession setup;
	private final List<String> mapTypes;
	private final CORE_STATE.Constructor rebuild;
	private final GuiSection panel = new GuiSection() {
		@Override
		public void render(SPRITE_RENDERER r, float ds) {
			pump();
			super.render(r, ds);
		}
	};

	// The host's choices; a new size takes effect with the next Generate.
	private int size;
	private final IntImp latitude = new IntImp(MapOptions.DEFAULT_LATITUDE, 0, MapOptions.MAX_LATITUDE);
	/** 0: Random; otherwise the preset mapTypes[mapType - 1]. */
	private int mapType;
	private int seed;

	// What the panel shows, from the session; updated by refresh().
	private int shownChanges = -1;
	private boolean canGenerate;
	private boolean canConfirm;
	/** The map is confirmed and this PC's matches it: the factions step can open. */
	private boolean canGoOn;
	private final GText sizeText = new GText(UI.FONT().H2, 16);
	private final GText typeText = new GText(UI.FONT().H2, MapOptions.MAX_MAP_TYPE + 32);
	private final GText latitudeText = new GText(UI.FONT().H2, 16);
	private final GText seedText = new GText(UI.FONT().H2, 16);
	private final GText[] rows = new GText[LobbySettings.MAX_PLAYERS];
	private int shownRows;
	private final GText status = new GText(UI.FONT().M, 256);
	private boolean leaving;
	/** The factions step once Next opened it, pumped every frame through this; null before. */
	private MpFactions factions;

	/**
	 * Right after GAME.create: replaces the world generator's stage with the map step's panel. The race stage the
	 * generator opened is dropped; vanilla's stage order (WorldViewGenerator.set) runs only from its own stage
	 * buttons, which the map step never shows.
	 */
	public static void takeOver(SetupSession setup, List<String> mapTypes, CORE_STATE.Constructor rebuild) {
		if (!(VIEW.current() instanceof WorldViewGenerator g))
			throw new IllegalStateException("SOSGSGMP: the new game shows " + VIEW.current() + ", not the world generator (Songs of Syx V71)");
		g.reset();
		// Spec §5: no titles during setup.
		g.hasSelectedTitles = true;
		MpMapStage stage = new MpMapStage(g, setup, mapTypes, rebuild);
		g.dummy.add(stage.panel, "map");
		// Intr centres the panel; move it to the left so the map stays in view.
		stage.panel.body().moveX1(16);
		if (!setup.isHost())
			g.minimap.show();
		MpAutopilot autopilot = MpAutopilot.fromEnv();
		GAME.addAfterUpdate(() -> stage.afterUpdate(autopilot));
	}

	/**
	 * Once per frame after the game's update, whatever view shows: the factions step's session keeps going even when
	 * its screen isn't drawn (the world build opens the host's city; two-PC test T3). With the test autopilot
	 * ({@code autopilot} not null) it also plays the map step, then the factions.
	 */
	private void afterUpdate(MpAutopilot autopilot) {
		if (leaving)
			return;
		if (factions != null)
			factions.afterUpdate(autopilot);
		else if (autopilot != null)
			autopilot.map(setup, canGoOn, () -> factions = MpFactions.open(g, setup));
	}

	private MpMapStage(WorldViewGenerator g, SetupSession setup, List<String> mapTypes, CORE_STATE.Constructor rebuild) {
		this.g = g;
		this.setup = setup;
		this.mapTypes = mapTypes;
		this.rebuild = rebuild;
		for (int i = 0; i < rows.length; i++)
			rows[i] = new GText(UI.FONT().M, 96);
		if (setup.isHost())
			addHostControls();
		else
			addHostChoices();

		int rowH = UI.FONT().M.height() + 2;
		panel.addDown(16, new GHeader("players"));
		panel.addDown(4, new RENDEROBJ.RenderImp(LABEL_WIDTH + TEXT_WIDTH, rowH * rows.length) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				for (int i = 0; i < shownRows; i++)
					rows[i].render(r, body.x1(), body.y1() + i * rowH);
			}
		});
		panel.addDown(12, new RENDEROBJ.RenderImp(LABEL_WIDTH + TEXT_WIDTH, UI.FONT().M.height() * 3) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, status, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		});
		GuiSection buttons = new GuiSection();
		buttons.add(new GButt.ButtPanel("next") {
			@Override
			protected void clickA() {
				factions = MpFactions.open(g, setup);
			}

			@Override
			protected void renAction() {
				activeSet(canGoOn);
			}
		});
		buttons.addRightC(16, MpSetupLeave.button(setup, () -> leaving = true));
		panel.addDown(12, buttons);
		refresh();
	}

	private void addHostControls() {
		// The host's latest choices survive a rebuild: the session keeps them.
		MapOptions last = setup.choices();
		size = last == null ? setup.step().size() : last.size();
		if (last != null) {
			latitude.set(last.latitude());
			mapType = last.isRandom() ? 0 : mapTypes.indexOf(last.mapType()) + 1;
		}

		GuiSection sizeRow = new GuiSection();
		sizeRow.add(sizeButton(-1));
		sizeRow.addRightC(8, text(sizeText, 60));
		sizeRow.addRightC(8, sizeButton(1));
		panel.addDown(0, row("map size", sizeRow));
		panel.addDown(4, new GText(UI.FONT().S, "A new size reloads every player's game when you generate.").normalify2());

		boolean presets = !mapTypes.isEmpty();
		GuiSection type = new GuiSection();
		type.add(typeButton(-1, presets));
		type.addRightC(8, text(typeText, TEXT_WIDTH - 80));
		type.addRightC(8, typeButton(1, presets));
		panel.addDown(12, row("map type", type));
		if (presets)
			panel.addDown(4, new GText(UI.FONT().S, "Presets only at size " + MapOptions.DEFAULT_SIZE + ".").normalify2());
		showMapType();

		panel.addDown(12, row("latitude", new GSliderInt(latitude, 200, 24, true, false)));

		StringInputSprite seedInput = new StringInputSprite(10, UI.FONT().M) {
			@Override
			protected void acceptChar(char c) {
				if (c >= '0' && c <= '9')
					super.acceptChar(c);
			}

			@Override
			protected void change() {
				seed = MapOptions.parseSeed(text());
				super.change();
			}
		};
		setSeed(seedInput, last != null ? last.seed() : ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
		GuiSection seedRow = new GuiSection();
		seedRow.add(new GInput(seedInput));
		seedRow.addRightC(8, new GButt.ButtPanel("random") {
			@Override
			protected void clickA() {
				// The mod's own random numbers, never the game's: its stream must stay untouched (setup-map-step.md §7).
				setSeed(seedInput, ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
			}

			@Override
			protected void renAction() {
				activeSet(canGenerate);
			}
		});
		panel.addDown(12, row("seed", seedRow));

		GuiSection buttons = new GuiSection();
		buttons.add(new GButt.ButtPanel("generate") {
			@Override
			protected void clickA() {
				String type = presetChosen() ? mapTypes.get(mapType - 1) : MapOptions.RANDOM;
				setup.generate(new MapOptions(size, type, latitude.get(), seed));
			}

			@Override
			protected void renAction() {
				activeSet(canGenerate && seed >= 0);
			}
		});
		buttons.addRightC(16, new GButt.ButtPanel("confirm") {
			@Override
			protected void clickA() {
				setup.confirm();
			}

			@Override
			protected void renAction() {
				activeSet(canConfirm);
			}
		});
		panel.addDown(16, buttons);
	}

	private void addHostChoices() {
		panel.addDown(0, row("map size", text(sizeText, TEXT_WIDTH)));
		panel.addDown(8, row("map type", text(typeText, TEXT_WIDTH)));
		panel.addDown(8, row("latitude", text(latitudeText, TEXT_WIDTH)));
		panel.addDown(8, row("seed", text(seedText, TEXT_WIDTH)));
	}

	private GButt.ButtPanel sizeButton(int step) {
		return new GButt.ButtPanel(step < 0 ? UI.icons().m.arrow_left : UI.icons().m.arrow_right) {
			@Override
			protected void clickA() {
				size = MapOptions.stepSize(size, step);
				sizeText.clear().add(size);
				showMapType();
			}

			@Override
			protected void renAction() {
				activeSet(canGenerate && MapOptions.stepSize(size, step) != size);
			}
		};
	}

	private GButt.ButtPanel typeButton(int step, boolean visible) {
		GButt.ButtPanel b = new GButt.ButtPanel(step < 0 ? UI.icons().m.arrow_left : UI.icons().m.arrow_right) {
			@Override
			protected void clickA() {
				mapType = Math.floorMod(mapType + step, mapTypes.size() + 1);
				showMapType();
			}

			@Override
			protected void renAction() {
				activeSet(canGenerate && MapOptions.presetsAllowed(size));
			}
		};
		b.visableSet(visible);
		return b;
	}

	private void showMapType() {
		typeText.clear().add(presetChosen() ? mapTypes.get(mapType - 1) : RANDOM_NAME);
	}

	/** A preset is picked and the chosen size allows presets; otherwise the map is Random. */
	private boolean presetChosen() {
		return mapType > 0 && MapOptions.presetsAllowed(size);
	}

	private void setSeed(StringInputSprite input, int value) {
		input.text().clear().add(value);
		seed = value;
	}

	/** A label of fixed width followed by a field. */
	private static GuiSection row(CharSequence label, RENDEROBJ field) {
		GuiSection row = new GuiSection();
		row.add(new GHeader(label));
		row.addRightCAbs(LABEL_WIDTH, field);
		return row;
	}

	private static RENDEROBJ text(GText text, int width) {
		return new RENDEROBJ.RenderImp(width, UI.FONT().H2.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				text.render(r, body.x1(), body.y1());
			}
		};
	}

	/** Once per frame: applies the network, rebuilds the game when a build is pending, and redraws texts on change. */
	private void pump() {
		if (leaving)
			return;
		setup.update();
		if (!setup.closed() && setup.step().pendingBuild() > 0) {
			leaving = true;
			CORE.setCurrentState(rebuild);
			return;
		}
		if (setup.changes() != shownChanges)
			refresh();
	}

	private void refresh() {
		shownChanges = setup.changes();
		MapStep step = setup.step();
		canGenerate = !setup.closed() && step.canGenerate();
		canConfirm = !setup.closed() && step.canConfirm();
		canGoOn = !setup.closed() && step.confirmed() && step.hash() == step.confirmedHash() && setup.factions() != null;

		sizeText.clear().add(setup.isHost() ? size : step.size());
		if (!setup.isHost()) {
			MapOptions o = step.options();
			typeText.clear().add(o == null ? "-" : o.isRandom() ? RANDOM_NAME : o.mapType());
			latitudeText.clear();
			seedText.clear();
			if (o != null) {
				latitudeText.add(o.latitude());
				seedText.add(o.seed());
			}
		}

		List<LobbyPlayer> players = setup.players();
		shownRows = Math.min(players.size(), rows.length);
		for (int i = 0; i < shownRows; i++) {
			LobbyPlayer p = players.get(i);
			GText row = rows[i].clear();
			row.add(p.name());
			if (p.host())
				row.add(" (host)");
			if (p.id() == setup.localPlayerId())
				row.add(" (you)");
			MapStep.PlayerState state = step.state(p.id());
			row.color(GCOLOR.T().NORMAL);
			if (state != null) {
				row.add(": ").add(stateName(state, p.host()));
				if (state == MapStep.PlayerState.MATCH)
					row.color(GCOLOR.T().IGOOD);
				if (state == MapStep.PlayerState.MISMATCH || state == MapStep.PlayerState.ERROR)
					row.color(GCOLOR.T().IBAD);
			}
			if (p.id() == setup.localPlayerId() && step.status() == MapStatus.OK)
				row.add("  ").add(Long.toHexString(step.hash()));
		}

		status.clear();
		if (setup.closed())
			status.add(setup.closedReason());
		else if (step.confirmed() && step.hash() != step.confirmedHash())
			status.add("The host confirmed a map that differs from yours.");
		else if (step.confirmed())
			status.add("Map confirmed. Next: race, faction and location.");
		else if (!setup.isHost())
			status.add("The host picks the map and confirms it. Talk it over; there is no vote.");
		else if (canConfirm)
			status.add("Every player has the same map. Confirm it, or change the options and generate again.");
		else if (step.options() == null)
			status.add("Pick the options and generate. Every player sees the map.");
		else
			status.add("Waiting until every player's map matches yours.");
	}

	/** A joining PC doesn't generate; it gets the host's map. */
	private static CharSequence stateName(MapStep.PlayerState state, boolean host) {
		return switch (state) {
			case BUILDING -> "building";
			case READY -> "ready";
			case GENERATING -> host ? "generating" : "getting the map";
			case GENERATED -> "generated";
			case MATCH -> "match";
			case MISMATCH -> "mismatch";
			case ERROR -> "error";
		};
	}
}
