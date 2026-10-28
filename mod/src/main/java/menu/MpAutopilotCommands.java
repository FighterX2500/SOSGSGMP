package menu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import game.GAME;
import game.battle.state.BattleState;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import init.race.RACES;
import init.resources.RESOURCES;
import snake2d.CORE;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.game.cmd.MapBattles;
import sosdet.game.cmd.World;
import sosdet.game.cmd.WorldBattles;
import sosdet.game.deal.DealBook;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.PlayerDeals;
import view.main.VIEW;
import world.WORLD;
import world.army.AD;
import world.battle.spec.BATTLE_RESULT;
import world.battle.spec.WBattleResult;
import world.battle.spec.WBattleSiege;
import world.battle.spec.WBattleSpec;
import world.entity.army.WArmy;

/**
 * SOSGSGMP test tooling, never active in play: the autopilot's hands for a long playtest (SOS-83). An outside player,
 * such as an LLM agent, writes actions to {@code command.txt} next to the autopilot's events file, one per line; each
 * frame the autopilot takes the file and does them through the same calls the UI's buttons make, and reports each as a
 * {@code cmd} event. Battles are fought on their own with {@code battles=auto}: Fight, Start, fight on, and accept the
 * result, so a battle never waits for the agent.
 *
 * <p>Actions: {@code status}; {@code shot} (an in-game screenshot, as the screenshot key); {@code ui diplomacy|world|city};
 * {@code war}; {@code peace <denari>}; {@code trade}; {@code accept} (every proposal to this player); {@code army} (a new
 * army at the capital); {@code mercs} (the mercenary bands for hire); {@code merc <index>} (hire one into the first army);
 * {@code attack} (the first army intercepts the other player's nearest army); {@code fight}, {@code start}, {@code retreat}
 * (the open battle prompt or battle); {@code speed <x>} (host); {@code view} (ask to view the other player's city) and
 * {@code closeview} (SOS-82).
 */
final class MpAutopilotCommands {

	private final Path file;
	private final boolean host;
	private final boolean autoBattles;
	private final BiConsumer<String, String> event;
	private Object answeredPrompt;
	private boolean started;
	private boolean concluded;

	MpAutopilotCommands(Path events, boolean host, boolean autoBattles, BiConsumer<String, String> event) {
		this.file = events.resolveSibling("command.txt");
		this.host = host;
		this.autoBattles = autoBattles;
		this.event = event;
	}

	/** Once per frame while playing, outside the step. */
	void frame(EnginePlay engine) {
		if (autoBattles)
			battles();
		if (!Files.exists(file))
			return;
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			Files.delete(file);
		} catch (IOException e) {
			return; // the writer still holds it: the next frame
		}
		for (String line : lines) {
			String l = line.trim();
			if (l.isEmpty() || l.startsWith("#"))
				continue;
			String result;
			try {
				result = run(l.split("\\s+"), engine);
			} catch (RuntimeException e) {
				result = "failed: " + e;
			}
			event.accept("cmd", "\"action\":\"" + clean(l) + "\",\"step\":" + engine.step() + ",\"result\":\"" + clean(result) + "\"");
		}
	}

	private Faction other() {
		return FACTIONS.getByIndex(FACTIONS.player().index() == 0 ? 1 : 0);
	}

	private String run(String[] a, EnginePlay engine) {
		int slot = other().index();
		switch (a[0]) {
		case "status":
			return status(engine);
		case "shot":
			CORE.getGraphics().makeScreenShot();
			return "screenshot taken";
		case "ui":
			if (a.length < 2)
				return "ui what?";
			if (a[1].equals("diplomacy")) {
				MpPlayers.open(other(), true);
				return "opened " + other().name + "'s window, Diplomacy tab";
			}
			if (a[1].equals("world")) {
				VIEW.world().activate();
				return "world view";
			}
			if (a[1].equals("city")) {
				VIEW.s().activate();
				return "city view";
			}
			return "unknown view " + a[1];
		case "war": {
			String why = PlayerDeals.warRefusal(slot);
			if (why != null)
				return "refused: " + why;
			PlayerDeals.declareWar(slot);
			return "war declared on " + other().name;
		}
		case "peace":
		case "trade": {
			int treaty = a[0].equals("peace") ? DealBook.PEACE : DealBook.TRADE;
			int credits = a.length > 1 ? Integer.parseInt(a[1]) : 0;
			DealBook.Terms t = new DealBook.Terms(treaty, credits, 0, new int[0], new int[0]);
			String why = PlayerDeals.refusal(slot, t);
			if (why != null)
				return "refused: " + why;
			PlayerDeals.propose(slot, t);
			return "proposed";
		}
		case "accept": {
			int n = 0;
			for (DealBook.Proposal p : PlayerDeals.open())
				if (p.to() == FACTIONS.player().index() && PlayerDeals.acceptRefusal(p.id()) == null) {
					PlayerDeals.answer(p.id(), true);
					n++;
				}
			return "accepted " + n;
		}
		case "army": {
			if (!World.armyCreate(-1, -1))
				return "refused: no session";
			return "army ordered at the capital";
		}
		case "mercs": {
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < AD.mercenaries().size(); i++)
				b.append(i).append(':').append(AD.mercenaries().get(i).name()).append(" men ").append(AD.mercenaries().get(i).men()).append("; ");
			return b.length() == 0 ? "none for hire" : b.toString();
		}
		case "merc": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			World.mercHire(w, Integer.parseInt(a[1]));
			return "hiring into army " + w.armyIndex();
		}
		case "attack": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			WArmy target = nearestOf(other(), w);
			if (target == null)
				return "refused: the other player has no army";
			if (!DIP.WAR().is(FACTIONS.player(), other()))
				return "refused: not at war";
			World.armyIntercept(w, target);
			return "intercepting army " + target.armyIndex();
		}
		case "fight":
			return WorldBattles.engage() ? "Fight" : "no session";
		case "start":
			return MapBattles.deploy() ? "Start" : "no session";
		case "retreat":
			return MapBattles.retreat() ? "Retreat" : "no session";
		case "view": {
			MpViews views = MpViews.current();
			String why = views == null ? "no session" : views.askRefusal(slot);
			if (why != null)
				return "refused: " + why;
			views.ask(slot);
			return "asked to view " + other().name + "'s city";
		}
		case "closeview":
			if (MpViews.current() == null)
				return "no session";
			MpViews.current().close(sosdet.View.CLOSED);
			return "closing the view";
		case "speed":
			if (!host)
				return "refused: the host sets the speed";
			GAME.SPEED.speedSet(Double.parseDouble(a[1]));
			return "speed set";
		default:
			return "unknown action";
		}
	}

	/** The battles on their own (battles=auto): Fight, Start, fight on, Close, and accept the result. */
	private void battles() {
		BattleState b = VIEW.b().state();
		if (b != null) {
			if (Engine.battle() != null && Engine.battle().awaiting())
				return;
			if (b.deploying() && !started) {
				started = MapBattles.deploy();
				if (started)
					event.accept("battle", "\"what\":\"start\",\"step\":" + Engine.step());
			}
			if (Hooks.battleConcludeOpen() && !concluded) {
				concluded = MapBattles.conclude();
				if (concluded)
					event.accept("battle", "\"what\":\"close\",\"step\":" + Engine.step());
			}
			return;
		}
		started = concluded = false;
		Object open = Hooks.worldPromptHere() ? Hooks.worldPrompt() : null;
		if (open == null) {
			answeredPrompt = null;
			return;
		}
		if (open == answeredPrompt)
			return;
		answeredPrompt = open;
		String what;
		if (open instanceof WBattleSiege) {
			WorldBattles.decide(WorldBattles.WAIT);
			what = "siege wait";
		} else if (open instanceof WBattleSpec) {
			WorldBattles.engage();
			what = "fight";
		} else if (open instanceof WBattleResult) {
			boolean victory = ((WBattleResult) open).result == BATTLE_RESULT.VICTORY;
			WorldBattles.accept(victory ? new int[RACES.all().size()] : null, victory ? new int[RESOURCES.ALL().size()] : null, 0);
			what = victory ? "victory accept" : "defeat accept";
		} else {
			return; // a captured settlement's choice: left to its time-out
		}
		event.accept("battle", "\"what\":\"" + what + "\",\"step\":" + Engine.step());
	}

	private static WArmy firstArmy() {
		for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
			WArmy w = WORLD.ENTITIES().armies.tryGet(i);
			if (w != null && w.faction() == FACTIONS.player())
				return w;
		}
		return null;
	}

	private static WArmy nearestOf(Faction f, WArmy from) {
		WArmy best = null;
		long bestD = Long.MAX_VALUE;
		for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
			WArmy w = WORLD.ENTITIES().armies.tryGet(i);
			if (w == null || w.faction() != f || AD.men(null).get(w) == 0)
				continue;
			long dx = w.ctx() - from.ctx(), dy = w.cty() - from.cty();
			if (dx * dx + dy * dy < bestD) {
				bestD = dx * dx + dy * dy;
				best = w;
			}
		}
		return best;
	}

	private String status(EnginePlay engine) {
		StringBuilder armies = new StringBuilder();
		for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
			WArmy w = WORLD.ENTITIES().armies.tryGet(i);
			if (w != null && (w.faction() == FACTIONS.player() || w.faction() == other()))
				armies.append(w.faction() == FACTIONS.player() ? "mine " : "theirs ").append(w.armyIndex()).append(" men ")
						.append(AD.men(null).get(w)).append(" at ").append(w.ctx()).append(',').append(w.cty()).append(' ')
						.append(w.state()).append("; ");
		}
		return String.format(Locale.ROOT, "step %d, credits %d, at war %b, armies: %s", engine.step(),
				(long) FACTIONS.player().credits().getD(), DIP.WAR().is(FACTIONS.player(), other()), armies);
	}

	private static String clean(String s) {
		return s == null ? "" : s.replace('"', '\'').replace('\\', '/').replace('\n', ' ');
	}
}
