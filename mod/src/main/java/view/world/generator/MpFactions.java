package view.world.generator;

import java.util.List;

import init.race.RACES;
import init.race.Race;
import init.sprite.UI.UI;
import menu.MpSetupLeave;
import menu.MpWorldLoad;
import snake2d.CORE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.WorldSetupAdapter;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.session.SetupSession;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;
import util.colors.GCOLOR;
import util.gui.misc.GButt;
import util.gui.misc.GHeader;
import util.gui.misc.GText;

/**
 * SOSGSGMP: the factions step on the world generator (setup-factions-step.md §2): race, Customize Faction and the
 * world location, at each player's own pace. This owns what the three screens share: the session pump, this PC's
 * picks sent whenever the game's change, every player's row, the status line and Leave. The screens themselves are
 * {@link MpFactionStage} and {@link MpLocationStage}; they switch from clicks, as vanilla's stages do.
 *
 * <p>Each PC changes only its own {@code FACTIONS.player()}, with vanilla's own buttons and Customize Faction section;
 * the pump reads it back twice a second and sends it when it changed (the section edits the faction directly and
 * says nothing). Texts are rebuilt only when the session reports a change.
 *
 * <p>The host's Initialize World builds the world here; once a PC has the host's save, the pump leaves the generator
 * for the load ({@link MpWorldLoad}), as the map step leaves it for a rebuild.
 */
final class MpFactions {

	private static final double PICK_CHECK_SECONDS = 0.5;
	static final int SIDE_WIDTH = 420;

	final WorldViewGenerator g;
	final SetupSession setup;
	private final WorldSetupAdapter game = new WorldSetupAdapter();

	private int shownChanges = -1;
	private double sincePickCheck;
	private boolean leaving;
	private final GText[] rows = new GText[LobbySettings.MAX_PLAYERS];
	private int shownRows;
	private final GText status = new GText(UI.FONT().M, 256);
	/** Whether the faction had a name at the last check: the status line follows it. */
	private boolean named;
	/** Host: every player has a location and the world isn't built yet. */
	private boolean canInitialize;
	/** This PC's location as last placed, or NONE: kept here so a frame never rebuilds the table to read it. */
	private int ownX = FactionPick.NONE;
	private int ownY = FactionPick.NONE;

	/** The other players' locations and names as last shown, for the location screen's markers. */
	final int[] otherX = new int[LobbySettings.MAX_PLAYERS];
	final int[] otherY = new int[LobbySettings.MAX_PLAYERS];
	final GText[] otherNames = new GText[LobbySettings.MAX_PLAYERS];
	int others;

	private MpFactions(WorldViewGenerator g, SetupSession setup) {
		this.g = g;
		this.setup = setup;
		for (int i = 0; i < rows.length; i++) {
			rows[i] = new GText(UI.FONT().M, 128);
			otherNames[i] = new GText(UI.FONT().S, FactionPick.MAX_NAME);
		}
	}

	/** From the map step's Next, once the map is confirmed: the race screen first, as vanilla. */
	static void open(WorldViewGenerator g, SetupSession setup) {
		if (setup.factions() == null)
			throw new IllegalStateException("SOSGSGMP: the factions step before the map is confirmed");
		MpFactions f = new MpFactions(g, setup);
		f.refresh();
		f.syncPick();
		MpFactionStage.race(f);
	}

	/**
	 * The side every screen shows: the players' rows, the status line, the screen's own buttons, and Leave. It pumps the
	 * session from its render, once per frame (the screens show exactly one of these at a time).
	 */
	GuiSection side(GButt.ButtPanel back, GButt.ButtPanel next) {
		GuiSection s = new GuiSection() {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				pump(ds);
				super.render(r, ds);
			}
		};
		int rowH = UI.FONT().M.height() + 2;
		s.add(new GHeader("players"), 0, 0);
		s.addDown(4, new RENDEROBJ.RenderImp(SIDE_WIDTH, rowH * rows.length) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				for (int i = 0; i < shownRows; i++)
					rows[i].render(r, body.x1(), body.y1() + i * rowH);
			}
		});
		s.addDown(12, new RENDEROBJ.RenderImp(SIDE_WIDTH, UI.FONT().M.height() * 3) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, status, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		});
		GuiSection buttons = new GuiSection();
		if (back != null)
			buttons.addRightC(0, back);
		if (next != null)
			buttons.addRightC(16, next);
		if (setup.isHost()) {
			buttons.addRightC(16, new GButt.ButtPanel("initialize world") {
				@Override
				protected void clickA() {
					setup.initialize();
				}

				@Override
				protected void renAction() {
					activeSet(canInitialize);
				}
			});
		}
		buttons.addRightC(16, MpSetupLeave.button(setup, () -> leaving = true));
		s.addDown(12, buttons);
		return s;
	}

	/** Places this PC's location at world tile {@code x, y} (the capital's centre), with its current race and faction. */
	void placeAt(int x, int y) {
		if (setup.closed() || setup.factions().initialized())
			return;
		FactionPick now = game.pick(x, y);
		if (now == null)
			return; // the status line asks for a name
		ownX = x;
		ownY = y;
		setup.pick(now);
	}

	/** This PC's location as last placed, or {@link FactionPick#NONE}. */
	int ownX() {
		return ownX;
	}

	int ownY() {
		return ownY;
	}

	private void pump(double ds) {
		if (leaving)
			return;
		setup.update();
		byte[] world = setup.takeWorld();
		if (world != null) {
			leaving = true;
			CORE.setCurrentState(new MpWorldLoad(setup, world));
			return;
		}
		sincePickCheck += ds;
		if (sincePickCheck >= PICK_CHECK_SECONDS) {
			sincePickCheck = 0;
			syncPick();
		}
		if (setup.changes() != shownChanges)
			refresh();
	}

	/** Sends this PC's picks when the game's differ from the last sent (the location as last placed). */
	private void syncPick() {
		if (setup.closed() || setup.factions().initialized())
			return;
		FactionPick now = game.pick(ownX, ownY);
		if ((now != null) != named)
			refresh();
		if (now != null && !now.equals(setup.factions().ownPick()))
			setup.pick(now);
	}

	private void refresh() {
		shownChanges = setup.changes();
		canInitialize = !setup.closed() && setup.isHost() && setup.factions().canInitialize();
		named = game.pick(FactionPick.NONE, FactionPick.NONE) != null;
		FactionStep f = setup.factions();
		List<FactionStep.PlayerPick> table = f.table();
		FactionStep.PlayerPick mine = find(table, setup.localPlayerId());
		FactionPick own = f.ownPick();
		// The host cleared this PC's location as sent: its table holds the same picks without it, and says why.
		boolean refused = own != null && mine != null && mine.refused() != null && mine.pick().equals(own.at(FactionPick.NONE, FactionPick.NONE));
		ownX = own == null || refused ? FactionPick.NONE : own.x();
		ownY = own == null || refused ? FactionPick.NONE : own.y();
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
			if (pick == null) {
				row.add(": picking");
				continue;
			}
			FactionPick fp = pick.pick();
			row.add(": ").add(fp.name()).add(", ").add(raceName(fp.race()));
			if (fp.hasLocation()) {
				row.add(", at ").add(fp.x()).add(',').add(fp.y());
				row.color(GCOLOR.T().IGOOD);
				if (p.id() != setup.localPlayerId()) {
					otherX[others] = fp.x();
					otherY[others] = fp.y();
					otherNames[others].clear().add(fp.name());
					others++;
				}
			} else if (pick.refused() != null) {
				row.add(", location refused");
				row.color(GCOLOR.T().IBAD);
			} else {
				row.add(", no location");
			}
		}

		status.clear();
		if (setup.closed())
			status.add(setup.closedReason());
		else if (f.initialized())
			status.add(setup.isHost() ? "Sending the world to every player." : "The host built the world; getting it.");
		else if (!named)
			status.add("Your faction needs a name.");
		else if (mine != null && mine.refused() != null)
			status.add("Your location was refused: ").add(mine.refused()).add(". Pick another.");
		else if (mine == null || !mine.pick().hasLocation())
			status.add("Pick your race, customize your faction and place your capital. You can change them until the host initializes the world.");
		else if (setup.isHost() && f.canInitialize())
			status.add("Every player has a location. Initialize World builds the world and starts every player's game; the picks can't change after it.");
		else if (!setup.isHost() && allPlaced(table, players))
			status.add("Every player has a location. Waiting for the host to initialize the world.");
		else
			status.add("Waiting for every player's location.");
	}

	private static boolean allPlaced(List<FactionStep.PlayerPick> table, List<LobbyPlayer> players) {
		for (LobbyPlayer p : players) {
			FactionStep.PlayerPick pick = find(table, p.id());
			if (pick == null || !pick.pick().hasLocation())
				return false;
		}
		return true;
	}

	private static FactionStep.PlayerPick find(List<FactionStep.PlayerPick> table, int playerId) {
		for (FactionStep.PlayerPick p : table)
			if (p.playerId() == playerId)
				return p;
		return null;
	}

	/** The race's name in this game, or its key when this game doesn't have it. */
	private static CharSequence raceName(String key) {
		for (Race r : RACES.all())
			if (r.key.equals(key))
				return r.info.name;
		return key;
	}
}
