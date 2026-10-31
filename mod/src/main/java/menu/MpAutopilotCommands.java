package menu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import game.GAME;
import game.battle.div.Div;
import game.battle.state.BattleState;
import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import game.faction.npc.FactionNPC;
import init.constant.C;
import init.race.RACES;
import init.resources.RESOURCES;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.STATS;
import snake2d.CORE;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.game.OwnArmy;
import sosdet.game.cmd.MapBattles;
import sosdet.game.cmd.Sieges;
import sosdet.game.cmd.StepPrompts;
import sosdet.game.cmd.World;
import sosdet.game.cmd.WorldBattles;
import sosdet.game.deal.DealBook;
import sosdet.game.siege.AssaultTerms;
import sosgsgmp.game.EnginePlay;
import sosgsgmp.game.PlayerDeals;
import sosgsgmp.session.SetupSession;
import view.main.VIEW;
import world.WORLD;
import world.army.AD;
import world.battle.spec.BATTLE_RESULT;
import world.battle.spec.WBattleResult;
import world.battle.spec.WBattleSiege;
import world.battle.spec.WBattleSpec;
import world.entity.army.WArmy;
import world.map.regions.Region;

/**
 * SOSGSGMP test tooling, never active in play: the autopilot's hands for a long playtest (SOS-83). An outside player,
 * such as an LLM agent, writes actions to {@code command.txt} next to the autopilot's events file, one per line; each
 * frame the autopilot takes the file and does them through the same calls the UI's buttons make, and reports each as a
 * {@code cmd} event. Battles are fought on their own with {@code battles=auto}: Fight, Start, fight on, and accept the
 * result, so a battle never waits for the agent.
 *
 * <p>Actions: {@code status}; {@code shot} (an in-game screenshot, as the screenshot key); {@code ui diplomacy|world|city|profile};
 * {@code war}; {@code peace <denari>}; {@code trade}; {@code accept} (every proposal to this player); {@code army} (a new
 * army at the capital); {@code mercs} (the mercenary bands for hire); {@code merc <index>} (hire one into the first army);
 * {@code divs} (the city's divisions); {@code attach <division|all>} (the city army screen's send out, to the first army);
 * {@code attack} (the first army intercepts the other player's nearest army); {@code fight}, {@code start}, {@code retreat}
 * (the open battle prompt or battle); {@code speed <x>} (host); {@code view} (ask to view the other player's city) and
 * {@code closeview} (SOS-82); {@code say <text>} (the chat, SOS-110); {@code pause} (as the pause key); {@code pop} (this
 * city's people); {@code besiege} (the first army besieges the other player's capital); {@code siegewait} (test tooling:
 * the siege of the other player's capital has waited long enough); {@code storm} (test tooling for the prompt: the next
 * siege prompt is answered with Storm, and the first army's siege order opens it); {@code sally}; {@code fallback} (the
 * next sally prompt is answered with Fall Back); {@code assault} (the
 * assault running and the last one over); {@code terms sack|vassalage <percent> [moved:killed:freed]} (the attacker's
 * outcome, the people being of the city's largest race); {@code order all|<division> <tx> <ty>} (as placing a division in
 * the battle UI, to that tile); {@code teleport <tx> <ty>} (test tooling: the
 * first army stands at that world tile); {@code npcbesiege} (test tooling: the strongest NPC army marches on this
 * city); {@code nudge} (test tooling: a forced desync in a battle or an assault); {@code assaultretreat} (an attacker orders the retreat from the assault).
 */
final class MpAutopilotCommands {

	private final Path file;
	private long traceAt = -1;
	private final boolean host;
	private final boolean autoBattles;
	/** The last simulation question answered, by its key. */
	private long answeredQuestion = -1;
	private final BiConsumer<String, String> event;
	private Object answeredPrompt;
	private boolean started;
	private boolean concluded;
	private int chatSeen;
	/** The next siege prompt is answered with Storm, not Wait. */
	private boolean storm;
	/** The next sally prompt against a player is answered with Fall Back, not Fight. */
	private boolean fallBack;

	MpAutopilotCommands(Path events, boolean host, boolean autoBattles, BiConsumer<String, String> event) {
		this.file = events.resolveSibling("command.txt");
		this.host = host;
		this.autoBattles = autoBattles;
		this.event = event;
	}

	/** Once per frame while playing, outside the step. */
	void frame(EnginePlay engine, SetupSession setup) {
		traceDue();
		if (autoBattles)
			battles();
		if (setup.chat().added() != chatSeen) {
			chatSeen = setup.chat().added();
			event.accept("chatline", "\"added\":" + chatSeen + ",\"step\":" + engine.step() + ",\"text\":\""
					+ clean(setup.chat().line(setup.chat().size() - 1).text()) + "\"");
		}
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
				result = run(l.split("\\s+"), engine, setup);
			} catch (RuntimeException e) {
				result = "failed: " + e;
			}
			event.accept("cmd", "\"action\":\"" + clean(l) + "\",\"step\":" + engine.step() + ",\"result\":\"" + clean(result) + "\"");
		}
	}

	private Faction other() {
		return FACTIONS.getByIndex(Engine.local() == 0 ? 1 : 0);
	}

	private String run(String[] a, EnginePlay engine, SetupSession setup) {
		int slot = other().index();
		switch (a[0]) {
		case "say":
			setup.say(String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
			return "said";
		case "pause":
			GAME.SPEED.togglePause();
			return "pause toggled";
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
			if (a[1].equals("profile")) {
				VIEW.UI().profile.activate();
				return "profile window";
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
		case "pop":
			return "population " + STATS.POP().POP.data().get(null);
		case "divs": {
			StringBuilder b = new StringBuilder();
			for (Div d : GAME.ARMIES().player().divisions())
				if (d.menNrOf() > 0 || AD.cityDivs().attachedArmy(d) != null)
					b.append(d.index()).append(": men ").append(d.menNrOf()).append(" stored ").append(AD.cityDivs().get(d).men())
							.append(" army ").append(AD.cityDivs().attachedArmy(d) == null ? -1 : AD.cityDivs().attachedArmy(d).armyIndex())
							.append("; ");
			return b.length() == 0 ? "no divisions" : b.toString();
		}
		case "attach": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			int n = 0;
			for (Div d : GAME.ARMIES().player().divisions())
				if ((a[1].equals("all") ? d.menNrOf() > 0 && AD.cityDivs().attachedArmy(d) == null : d.index() == Integer.parseInt(a[1]))
						&& World.cityDivAttach(w, d))
					n++;
			return n + " divisions sent to army " + w.armyIndex();
		}
		case "mercs": {
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < Math.min(AD.mercenaries().max(), AD.mercenaries().size()); i++) // the rest aren't for hire yet, as in vanilla's list
				b.append(i).append(':').append(AD.mercenaries().get(i).name()).append(" men ").append(AD.mercenaries().get(i).men())
						.append(" cost ").append(AD.mercenaries().signingCost(i)).append(AD.mercenaries().get(i).army() != null ? " hired" : "")
						.append("; ");
			return b.length() == 0 ? "none for hire" : b.toString();
		}
		case "merc": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			World.mercHire(w, Integer.parseInt(a[1]));
			return "hiring into army " + w.armyIndex();
		}
		case "besiege": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			w.besiege(other().capitolRegion());
			return "besieging " + other().name + "'s capital";
		}
		case "siegewait":
			Sieges.waitPassedForTest(slot);
			return "the siege has waited long enough, at its step on every PC";
		case "storm": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			String why = Sieges.stormRefusal(other().capitolRegion());
			if (why != null)
				return "refused: " + why;
			storm = true;
			w.besiege(other().capitolRegion());
			return "the siege order opens the prompt; Storm answers it";
		}
		case "nudge":
			for (ENTITY e : SETT.ENTITIES().getAllEnts())
				if (e instanceof Humanoid && !e.isRemoved() && ((Humanoid) e).division() != null) {
					STATS.NEEDS().INJURIES.COUNT.indu().set(((Humanoid) e).indu(), STATS.NEEDS().INJURIES.COUNT.indu().max(((Humanoid) e).indu()));
					return "a soldier here dies, on this PC only";
				}
			return "no soldier";
		case "npcs":
			return npcs();
		case "why":
			return Engine.holdWhy();
		case "supplies": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			StringBuilder b = new StringBuilder("health ").append(AD.supplies().health(w)).append(", morale ").append(AD.supplies().morale(w));
			for (init.resources.ResSupply s : init.resources.RESOURCES.SUP().ALL)
				b.append("; ").append(s.resource.name).append(' ').append(AD.supplies().get(s).current().get(w)).append('/')
						.append(AD.supplies().get(s).targetAmount(w)).append(" stock ")
						.append(settlement.main.SETT.ROOMS().STOCKPILE.tally().amountReservable.get(s.resource));
			return b.toString();
		}
		case "trace":
			if (a.length < 2)
				return "trace at which step?";
			traceAt = Long.parseLong(a[1]);
			Engine.traceDraws(1200);
			return "world draws traced, written to trace.txt at step " + traceAt;
		case "npcbesiege":
			Sieges.npcBesiegeForTest(FACTIONS.player().index());
			return "the strongest NPC army marches on this city";
		case "teleport": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			Sieges.teleportForTest(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]));
			return "army " + w.armyIndex() + " teleports, at its step on every PC";
		}
		case "assaultretreat":
			MpViews.current().retreat();
			return "retreat ordered";
		case "fallback":
			fallBack = true;
			return "the next sally prompt falls back";
		case "sally": {
			String why = Engine.sallyRefusal();
			if (why != null)
				return "refused: " + why;
			Engine.sallyOut();
			return "sallying out";
		}
		case "assault":
			return "now " + Engine.assault() + "; last " + Engine.lastAssault() + "; awaiting terms " + Engine.assaultTaken()
					+ (Engine.assault() != null ? "; " + sosdet.game.GameAssault.fight() : "");
		case "terms": {
			int kind = a[1].equals("vassalage") ? AssaultTerms.VASSALAGE
					: a[1].equals("eliminate") ? AssaultTerms.ELIMINATE : AssaultTerms.SACK;
			int percent = Integer.parseInt(a[2]);
			int[] people = a.length > 3 ? people(a[3]) : AssaultTerms.NO_PEOPLE;
			String why = Engine.assaultTermsRefusal(kind, percent, people);
			if (why != null)
				return "refused: " + why;
			Engine.assaultTerms(kind, percent, people);
			return "terms chosen";
		}
		case "order": {
			int x = Integer.parseInt(a[2]) * C.TILE_SIZE, y = Integer.parseInt(a[3]) * C.TILE_SIZE, n = 0;
			for (Div d : OwnArmy.own().divisions())
				if (d.menNrOf() > 0 && OwnArmy.selectable(d) && (a[1].equals("all") || d.index() == Integer.parseInt(a[1]))) {
					GAME.ARMIES().placer.deploy(d, x, x + 12 * C.TILE_SIZE, y + n * 3 * C.TILE_SIZE, y + n * 3 * C.TILE_SIZE);
					n++;
				}
			return n + " divisions ordered";
		}
		case "besiegenpc": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			Region target = nearestNpc(w);
			if (target == null)
				return "refused: no NPC settlement";
			w.besiege(target);
			return "besieging region " + target.index() + " of " + target.faction().name;
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
		if (Engine.viewing() != null)
			return; // a watcher only watches
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
		long question = Hooks.stepPromptOldest();
		if (question >= 0 && question != answeredQuestion) {
			answeredQuestion = question;
			if (Hooks.stepPromptShown() == question && VIEW.inters().yesNo.isActivated())
				VIEW.inters().yesNo.deactivate(); // the button hides the dialog as it is clicked; nobody clicks here
			StepPrompts.answer(question, true);
			event.accept("battle", "\"what\":\"question yes\",\"step\":" + Engine.step());
			return;
		}
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
			boolean stormNow = storm;
			storm = false;
			WorldBattles.decide(stormNow ? WorldBattles.AUTO : WorldBattles.WAIT);
			what = stormNow ? "storm" : "siege wait";
		} else if (open instanceof WBattleSpec && fallBack && Hooks.worldPromptSally() >= 0) {
			fallBack = false;
			WorldBattles.decide(WorldBattles.RETREAT);
			what = "fall back";
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

	/** The people by race and fate for the terms: a split of the city's largest race, moved:killed:freed. */
	private static int[] people(String split) {
		int[] in = Engine.assaultPeopleInCity(Engine.assaultTaken().defender);
		int largest = 0;
		for (int r = 0; r < in.length; r++)
			if (in[r] > in[largest])
				largest = r;
		int[] people = new int[in.length * AssaultTerms.FATES];
		String[] counts = split.split(":");
		for (int f = 0; f < counts.length; f++)
			people[largest * AssaultTerms.FATES + f] = Integer.parseInt(counts[f]);
		return people;
	}

	/** The nearest settlement of an NPC faction: a test's way to a battle against NPCs. */
	private static Region nearestNpc(WArmy from) {
		Region best = null;
		long bestD = Long.MAX_VALUE;
		for (Region r : WORLD.REGIONS().active()) {
			if (!(r.faction() instanceof FactionNPC))
				continue;
			long dx = r.cx() - from.ctx(), dy = r.cy() - from.cty();
			if (dx * dx + dy * dy < bestD) {
				bestD = dx * dx + dy * dy;
				best = r;
			}
		}
		return best;
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

	/** A desync hunt: the kept draws go to trace.txt once the step has come, before the desync's hold stops the game. */
	private void traceDue() {
		if (traceAt < 0 || Engine.step() < traceAt)
			return;
		try {
			Files.writeString(file.resolveSibling("trace.txt"), Engine.drawTrace(traceAt - 600, traceAt), StandardCharsets.UTF_8);
		} catch (IOException e) {
			event.accept("trace", "\"failed\":\"" + clean(e.getMessage()) + "\"");
		}
		event.accept("trace", "\"step\":" + Engine.step());
		traceAt = -1;
	}

	/** The NPC armies: how many, and the three nearest this PC's capital. */
	private String npcs() {
		Faction me = FACTIONS.getByIndex(Engine.local());
		int cx = me.capitolRegion() == null ? 0 : me.capitolRegion().cx();
		int cy = me.capitolRegion() == null ? 0 : me.capitolRegion().cy();
		ArrayList<WArmy> found = new ArrayList<>(WORLD.ENTITIES().armies.max());
		for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
			WArmy w = WORLD.ENTITIES().armies.tryGet(i);
			if (w != null && w.faction() instanceof FactionNPC && AD.men(null).get(w) > 0)
				found.add(w);
		}
		StringBuilder b = new StringBuilder().append(found.size()).append(" NPC armies");
		for (int n = 0; n < 3 && found.size() > 0; n++) {
			WArmy near = found.get(0);
			for (WArmy w : found)
				if (Math.abs(w.ctx() - cx) + Math.abs(w.cty() - cy) < Math.abs(near.ctx() - cx) + Math.abs(near.cty() - cy))
					near = w;
			found.remove(near);
			b.append("; ").append(near.faction().name).append(" men ").append(AD.men(null).get(near)).append(" at ").append(near.ctx())
					.append(',').append(near.cty()).append(' ').append(near.state().name(near));
		}
		return b.toString();
	}

	private String status(EnginePlay engine) {
		StringBuilder armies = new StringBuilder();
		for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
			WArmy w = WORLD.ENTITIES().armies.tryGet(i);
			if (w != null && (w.faction() == FACTIONS.player() || w.faction() == other()))
				armies.append(w.faction() == FACTIONS.player() ? "mine " : "theirs ").append(w.armyIndex()).append(" men ")
						.append(AD.men(null).get(w)).append(" at ").append(w.ctx()).append(',').append(w.cty()).append(' ')
						.append(w.state().name(w)).append("; ");
		}
		return String.format(Locale.ROOT, "step %d, credits %d, at war %b, spectating %b, last player %d, my capital besieged %b, theirs %b, armies: %s",
				engine.step(), (long) FACTIONS.player().credits().getD(),
				DIP.WAR().is(FACTIONS.player(), other()), Engine.spectating(), Engine.lastHuman(),
				Engine.besiegedSince(FACTIONS.player().index()) >= 0, Engine.besiegedSince(other().index()) >= 0, armies);
	}

	private static String clean(String s) {
		return s == null ? "" : s.replace('"', '\'').replace('\\', '/').replace('\n', ' ');
	}
}
