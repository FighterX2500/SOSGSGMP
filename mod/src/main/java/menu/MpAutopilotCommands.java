package menu;

import java.io.IOException;
import java.lang.reflect.Field;
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
import game.raiding.Raider;
import init.constant.C;
import init.race.RACES;
import init.resources.RESOURCES;
import init.trade.TR;
import init.trade.TRADABLE;
import init.trade.TRADE_TYPE;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.room.home.house.HomeInstance;
import settlement.room.main.Room;
import settlement.stats.STATS;
import snake2d.CORE;
import snake2d.PathTile;
import snake2d.util.datatypes.DIR;
import snake2d.util.sets.LIST;
import sosdet.Engine;
import sosdet.Hooks;
import sosdet.game.AiHooks;
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
import util.data.DOUBLE_O.DOUBLE_OE;
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
 * {@code closeview} (SOS-82); {@code route} (the walk to the other player's capital, SOS-122); {@code say <text>} (the chat, SOS-110); {@code pause} (as the pause key); {@code pop} (this
 * city's people); {@code besiege} (the first army besieges the other player's capital); {@code siegewait} (test tooling:
 * the siege of the other player's capital has waited long enough); {@code storm} (test tooling for the prompt: the next
 * siege prompt is answered with Storm, and the first army's siege order opens it); {@code sally}; {@code fallback} (the
 * next sally prompt is answered with Fall Back); {@code assault} (the
 * assault running and the last one over); {@code terms sack|vassalage <percent> [moved:killed:freed]} (the attacker's
 * outcome, the people being of the city's largest race); {@code order all|<division> <tx> <ty>} (as placing a division in
 * the battle UI, to that tile); {@code teleport <tx> <ty>} (test tooling: the
 * first army stands at that world tile); {@code npcbesiege} (test tooling: the strongest NPC army marches on this
 * city); {@code nudge} (test tooling: a forced desync in a battle or an assault); {@code assaultretreat} (an attacker orders the retreat from the assault);
 * {@code invasionattack} (the invasion message's Attack on this city's oldest invasion, SOS-145); {@code charge} (test
 * tooling: this PC's divisions in its battle fight on their own); {@code rout} (test tooling: on this PC, the enemy
 * divisions' morale stays below zero until the battle ends, so they desert; only for a battle nobody watches);
 * {@code raid} (this city's invasion, this player's raid and its state, the raiders, the treasury, the last messages);
 * {@code victoryaccept} (the Accept of the newest won invasion's spoils message, with every lot selected);
 * {@code nextmessage} (the open message closes, as its close button, and the next queued one opens).
 *
 * <p>Three-player test tooling (SOS-162), each applied at a step the host stamps, on every PC that runs what it changes:
 * {@code target <slot>} (the player that {@code war}, {@code peace}, {@code ally}, {@code trade}, {@code cancel},
 * {@code attack}, {@code besiege}, {@code siegewait}, {@code storm} and {@code view} act on; by default slot 1, or 0 on
 * slot 1's PC); {@code cancel} (every treaty with that player ends at once); {@code pick <n>} (the n-th of this player's
 * armies, from 0, is "the first army" of the army commands); {@code auto on|off} (battles=auto answers this PC's prompts,
 * or leaves them to their time-outs); {@code attacknpc} (the first army intercepts the nearest NPC army at war with this
 * player); {@code diplo} (the relations between the players, their NPC wars and vassals); {@code morale player|enemy|mine|theirs}
 * (from its step, that side's morale is held below zero until the battle ends, on every PC that runs the battle; it
 * replaces {@code rout} wherever a battle runs on more than one PC, and a view opened after it would desync);
 * {@code recruit <n>} (n of this city's citizens join a new division at once, trained and equipped; then {@code attach});
 * {@code giveregion <slot>} (the NPC region nearest that player's capital, not a capital, becomes theirs: a world command);
 * {@code givevassal <slot>} (the nearest NPC faction with no overlord becomes that player's vassal: a world command);
 * {@code state} (the prompts, battle, view and terms open on this PC); {@code save} (host: the escape menu's Save
 * Campaign, at any time, a battle included).
 */
final class MpAutopilotCommands {

	private final Path file;
	private long traceAt = -1;
	private final boolean host;
	private boolean autoBattles;
	/** The player the two-player commands act on, from {@code target}; -1 for the default. */
	private int target = -1;
	/** Which of this player's armies the army commands use, from {@code pick}. */
	private int armyPick;
	/** The last simulation question answered, by its key. */
	private long answeredQuestion = -1;
	private final BiConsumer<String, String> event;
	private Object answeredPrompt;
	/** The prompt last seen open, when, and whether its screenshot was taken (SOS-162). */
	private Object shotPrompt;
	private long promptSeenAt;
	private boolean promptShot;
	private boolean started;
	private boolean concluded;
	private int chatSeen;
	/** The next siege prompt is answered with Storm, not Wait. */
	private boolean storm;
	/** The next sally prompt against a player is answered with Fall Back, not Fight. */
	private boolean fallBack;
	/** When the victory or defeat panel was first seen open; -1 before. */
	private long concludeShotAt = -1;
	private boolean concludeShot;
	/** The enemy's morale is held below zero each frame while this PC's battle runs. */
	private boolean routing;
	/** The battle's morale of each division ({@code DivFactors.morale}), once {@code rout} needs it. */
	private DOUBLE_OE<Div> morale;
	/** The step this PC's run ends at instead of its {@code steps}, from {@code endat}; -1 before. */
	private long endAt = -1;

	MpAutopilotCommands(Path events, boolean host, boolean autoBattles, BiConsumer<String, String> event) {
		this.file = events.resolveSibling("command.txt");
		this.host = host;
		this.autoBattles = autoBattles;
		this.event = event;
	}

	/** The host's escape menu Save Campaign is due, from {@code save}. */
	private boolean saveWanted;

	/** True once after {@code save}: the host presses Save Campaign. */
	boolean takeSave() {
		boolean due = saveWanted;
		saveWanted = false;
		return due;
	}

	/** The step {@code endat} set for this PC's run to end at, or -1. */
	long endAt() {
		return endAt;
	}

	/** Once per frame while playing, outside the step. */
	void frame(EnginePlay engine, SetupSession setup) {
		traceDue();
		rout();
		if (autoBattles)
			battles(setup);
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
		return FACTIONS.getByIndex(target >= 0 ? target : Engine.local() == 0 ? 1 : 0);
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
			if (setup.loading().shown() != null) // a shot at a loading window's freeze would deadlock the game (SOS-158)
				return "refused: a loading window is open";
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
		case "ally":
		case "trade": {
			int treaty = a[0].equals("peace") ? DealBook.PEACE : a[0].equals("ally") ? DealBook.ALLY : DealBook.TRADE;
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
			int[] at = armyTile(); // the engine picks a tile itself only for a player with no army yet
			if (!World.armyCreate(at[0], at[1]))
				return "refused: no session";
			return "army ordered at the capital";
		}
		case "pop":
			return "population " + STATS.POP().POP.data().get(null);
		case "target":
			target = Integer.parseInt(a[1]);
			return "acting on " + other().name + " (slot " + target + ")";
		case "pick":
			armyPick = Integer.parseInt(a[1]);
			return firstArmy() == null ? "no army " + armyPick : "army " + firstArmy().armyIndex();
		case "auto":
			autoBattles = a[1].equals("on");
			return "battles answered " + (autoBattles ? "by the autopilot" : "by their time-outs");
		case "cancel": {
			String why = PlayerDeals.cancelRefusal(slot);
			if (why != null)
				return "refused: " + why;
			PlayerDeals.cancel(slot);
			return "treaties with " + other().name + " cancelled";
		}
		case "diplo":
			return diplo();
		case "state":
			return state(engine);
		case "save":
			if (!host)
				return "refused: the host saves the campaign";
			saveWanted = true;
			return "Save Campaign pressed";
		case "morale": {
			if (VIEW.b().state() == null)
				return "refused: no battle on this PC";
			game.battle.Army mine = OwnArmy.own();
			game.battle.Army side = a[1].equals("enemy") ? GAME.ARMIES().enemy()
					: a[1].equals("player") ? GAME.ARMIES().player()
					: a[1].equals("mine") ? mine : mine == GAME.ARMIES().player() ? GAME.ARMIES().enemy() : GAME.ARMIES().player();
			MapBattles.moraleForTest(side == GAME.ARMIES().player() ? 0 : 1);
			return "army " + (side == GAME.ARMIES().player() ? 0 : 1) + "'s morale held below zero from its step";
		}
		case "recruit":
			sosdet.game.cmd.Military.recruitForTest(Integer.parseInt(a[1]));
			return a[1] + " citizens join an empty division, at its step in this city";
		case "giveregion": {
			Faction to = FACTIONS.getByIndex(Integer.parseInt(a[1]));
			Region r = nearestNpcRegion(to);
			if (r == null)
				return "refused: no NPC region";
			Sieges.regionGiveForTest(to.index(), r.index());
			return "region " + r.index() + " " + r.info.name() + " of " + r.faction().name + " goes to " + to.name;
		}
		case "givevassal": {
			Faction to = FACTIONS.getByIndex(Integer.parseInt(a[1]));
			FactionNPC f = nearestFreeNpc(to);
			if (f == null)
				return "refused: no NPC faction";
			Sieges.npcVassalForTest(to.index(), f.index());
			return f.name + " (" + f.index() + ") becomes " + to.name + "'s vassal";
		}
		case "attacknpc": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			WArmy target = nearestNpcEnemy(w);
			if (target == null)
				return "refused: no NPC army at war with this player";
			World.armyIntercept(w, target);
			return "intercepting " + target.faction().name + "'s army " + target.armyIndex() + " (" + AD.men(null).get(target) + " men)";
		}
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
			Engine.traceDraws(1400);
			return "world draws traced, written to trace.txt at step " + traceAt;
		case "npcbesiege":
			Sieges.npcBesiegeForTest(FACTIONS.player().index());
			return "the strongest NPC army marches on this city";
		case "fill": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			Sieges.fillForTest(w);
			return "army " + w.armyIndex() + "'s supplies filled, at its step on every PC";
		}
		case "teleport": {
			WArmy w = firstArmy();
			if (w == null)
				return "refused: no army";
			Sieges.teleportForTest(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]));
			return "army " + w.armyIndex() + " teleports, at its step on every PC";
		}
		case "route": { // the walk to the other player's capital (SOS-122)
			Region me = FACTIONS.player().capitolRegion();
			Region to = other().capitolRegion();
			double walked = 0;
			for (PathTile p = WORLD.PATH().path(me.cx(), me.cy(), to.cx(), to.cy()); p != null && p.getParent() != null; p = p.getParent())
				walked += Math.hypot(p.x() - p.getParent().x(), p.y() - p.getParent().y());
			return String.format(Locale.ROOT, "route %d,%d to %d,%d: %.1f tiles apart, %.1f walked", me.cx(), me.cy(), to.cx(), to.cy(),
					Math.hypot(me.cx() - to.cx(), me.cy() - to.cy()), walked);
		}
		case "npcarmies": { // the NPC armies with men, for a two-PC test of an ally in an NPC battle
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
				WArmy e = WORLD.ENTITIES().armies.tryGet(i);
				if (e != null && e.faction() instanceof FactionNPC && AD.men(null).get(e) > 0)
					b.append(e.faction().index()).append(' ').append(AD.men(null).get(e)).append(' ').append(e.ctx()).append(' ').append(e.cty()).append("; ");
			}
			return b.toString();
		}
		case "npcwar":
			Sieges.npcWarForTest(FACTIONS.player().index(), Integer.parseInt(a[1]));
			return "at war with " + a[1] + ", at its step on every PC";
		case "assaultretreat":
			MpViews.current().retreat();
			return "retreat ordered";
		case "fallback":
			fallBack = true;
			return "the next sally prompt falls back";
		case "invasionattack":
			for (int ref = 1; ref < 10_000; ref++) {
				if (SETT.INVADOR().spec(ref) != null)
					return "attack on invasion " + ref + ": " + Hooks.invasionSally(ref);
			}
			return "refused: no invasion runs in this city";
		case "charge": {
			int n = 0;
			for (Div d : GAME.ARMIES().player().divisions()) {
				if (d.menNrOf() > 0) {
					AiHooks.running(d, true);
					AiHooks.guard(d, true);
					AiHooks.mopUp(d, true);
					n++;
				}
			}
			return n + " divisions charge";
		}
		case "rout":
			if (VIEW.b().state() == null)
				return "refused: no battle on this PC";
			routing = true;
			return "the enemy's morale stays below zero on this PC until the battle ends";
		case "raid":
			return raid();
		case "victoryaccept":
			return victoryAccept();
		case "nextmessage":
			VIEW.inters().messages.hide();
			return "message closed";
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
		case "join":
			return WorldBattles.allyAnswer(true) ? "Join" : "no session";
		case "stayout":
			return WorldBattles.allyAnswer(false) ? "Stay out" : "no session";
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
		case "endat": // a driver ends the run early, at the same step on every PC
			endAt = Long.parseLong(a[1]);
			return "the run ends at step " + endAt;
		case "closeview":
			if (MpViews.current() == null)
				return "no session";
			MpViews.current().close(sosdet.View.CLOSED);
			return "closing the view";
		case "look":
			return look(a);
		case "speed":
			if (!host && !Hooks.speedVoteHere()) // a battle's commanders vote (spec §8)
				return "refused: the host sets the speed";
			GAME.SPEED.speedSet(Double.parseDouble(a[1]));
			return "speed set";
		default:
			return "unknown action";
		}
	}

	/** Test tooling: holds every enemy division's morale below zero, so its soldiers desert (vanilla's PlanEscape). */
	private void rout() {
		if (!routing)
			return;
		if (VIEW.b().state() == null) {
			routing = false;
			return;
		}
		if (morale == null)
			morale = moraleOfDivisions();
		for (Div d : GAME.ARMIES().enemy().divisions())
			morale.setD(d, -10);
	}

	// DivFactors.morale is a DataDiv, a DOUBLE_OE<Div>: the cast only names its type
	@SuppressWarnings("unchecked")
	private static DOUBLE_OE<Div> moraleOfDivisions() {
		return (DOUBLE_OE<Div>) field(GAME.ARMIES().factors, "morale");
	}

	/**
	 * The city's camera to what drawing changed in vanilla (SOS-154), for a screenshot: {@code house} the house whose
	 * residents have the most furniture, {@code fence} a fence, wall or edge with floor on both sides, {@code heads} the
	 * death monument; or a tile, {@code look X Y}.
	 */
	private static String look(String[] a) {
		if (a.length == 3)
			return lookAt(Integer.parseInt(a[1]), Integer.parseInt(a[2]), "tile");
		String what = a.length > 1 ? a[1] : "";
		int best = -1;
		double bestScore = 0;
		for (int tile = 0; tile < SETT.TAREA; tile++) {
			int tx = tile % SETT.TWIDTH;
			int ty = tile / SETT.TWIDTH;
			double score = lookScore(what, tx, ty, tile);
			if (score > bestScore) {
				bestScore = score;
				best = tile;
			}
		}
		if (best < 0)
			return "nothing to look at for '" + what + "'";
		return lookAt(best % SETT.TWIDTH, best / SETT.TWIDTH, what);
	}

	private static double lookScore(String what, int tx, int ty, int tile) {
		switch (what) {
		case "house": {
			HomeInstance h = SETT.ROOMS().HOME.getter.get(tile);
			if (h == null || h.occupants() == 0 || h.body().x1() != tx || h.body().y1() != ty)
				return 0;
			double am = 0;
			for (int i = 0; i < h.occupants(); i++) {
				Humanoid o = h.occupant(i);
				for (int ri = 0; ri < o.race().home().clas(o).resources().size(); ri++)
					am += STATS.HOME().current(o, ri);
			}
			return am / h.occupants();
		}
		case "fence": {
			if (SETT.ROOMS().map.is(tile) || !SETT.TERRAIN().get(tx, ty).wantsFloorUnderneath(tx, ty))
				return 0;
			int floors = 0;
			for (int di = 0; di < DIR.ORTHO.size(); di++)
				if (SETT.FLOOR().getter.get(tx, ty, DIR.ORTHO.get(di)) != null)
					floors++;
			return floors >= 2 ? floors : 0;
		}
		case "heads": {
			Room r = SETT.ROOMS().map.get(tx, ty);
			return r != null && "MONUMENT_DEATH".equals(r.blueprint().key) ? 1 : 0;
		}
		default:
			return 0;
		}
	}

	private static String lookAt(int tx, int ty, String what) {
		VIEW.s().activate();
		VIEW.s().getWindow().setZoomout(0);
		VIEW.s().getWindow().centerAtTile(tx, ty);
		return "looking at " + what + " " + tx + "," + ty;
	}

	/** What a sally's end changes (SOS-149): this city's invasion, the raid and its state, the raiders, the treasury. */
	private static String raid() {
		StringBuilder b = new StringBuilder("invading ").append(SETT.INVADOR().invading());
		Object current = call(GAME.raiders(), "current");
		Raider raider = (Raider) call(current, "current");
		b.append(", raid ").append(raider == null ? "none" : raider.name).append(" state ").append(field(current, "state"));
		int defeated = 0;
		for (Raider r : GAME.raiders().ALL()) {
			if (r.defeated)
				defeated++;
		}
		b.append(", raiders active ").append(GAME.raiders().active().size()).append(" defeated ").append(defeated);
		b.append(", treasury ").append((long) FACTIONS.player().credits().getD()).append(", messages");
		LIST<?> all = (LIST<?>) field(VIEW.messages(), "all");
		for (int i = Math.max(0, all.size() - 4); i < all.size(); i++)
			b.append(' ').append(all.get(i).getClass().getSimpleName());
		return b.toString();
	}

	/**
	 * The spoils message's Accept ({@code Attack.MVictory2}), with every lot selected: the same calls its button makes,
	 * which a test can't click.
	 */
	private static String victoryAccept() {
		LIST<?> all = (LIST<?>) field(VIEW.messages(), "all");
		for (int i = all.size() - 1; i >= 0; i--) {
			Object m = all.get(i);
			if (!m.getClass().getSimpleName().equals("MVictory2"))
				continue;
			if ((Boolean) field(m, "accepted"))
				return "refused: already answered";
			int[] res = (int[]) field(m, "res");
			StringBuilder b = new StringBuilder("accepted");
			for (TRADABLE r : TR.ALL()) {
				int am = res[r.index()];
				if (am > 0) {
					FACTIONS.player().buyer(r).addReserveAndDeliver(am, TRADE_TYPE.spoils);
					b.append(' ').append(am).append(' ').append(r.key());
				}
			}
			set(m, "accepted", true);
			VIEW.inters().messages.hide();
			return b.toString();
		}
		return "refused: no spoils message";
	}

	private static void set(Object target, String name, Object value) {
		try {
			Field f = target.getClass().getDeclaredField(name);
			f.setAccessible(true);
			f.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("SOSGSGMP: no " + target.getClass().getSimpleName() + "." + name, e);
		}
	}

	/** A private field's value: test tooling only. */
	private static Object field(Object target, String name) {
		try {
			Field f = target.getClass().getDeclaredField(name);
			f.setAccessible(true);
			return f.get(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("SOSGSGMP: no " + target.getClass().getSimpleName() + "." + name, e);
		}
	}

	/** A public method of the engine's patched game classes, which the mod doesn't compile against: test tooling only. */
	private static Object call(Object target, String name) {
		try {
			return target.getClass().getMethod(name).invoke(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("SOSGSGMP: no " + target.getClass().getSimpleName() + "." + name + "()", e);
		}
	}

	/** Answers this PC's oldest simulation question yes; false with none. */
	private boolean question() {
		long question = Hooks.stepPromptOldest();
		if (question < 0 || question == answeredQuestion)
			return false;
		answeredQuestion = question;
		if (Hooks.stepPromptShown() == question && VIEW.inters().yesNo.isActivated())
			VIEW.inters().yesNo.deactivate(); // the button hides the dialog as it is clicked; nobody clicks here
		StepPrompts.answer(question, true);
		event.accept("battle", "\"what\":\"question yes\",\"step\":" + Engine.step());
		return true;
	}

	/** The battles on their own (battles=auto): Fight, Start, fight on, Close, and accept the result. */
	private void battles(SetupSession setup) {
		BattleState b = VIEW.b().state();
		if (Engine.viewing() != null) {
			question(); // a watcher only watches, but a question holds its view open
			return;
		}
		if (b != null) {
			if (Engine.battle() != null && Engine.battle().awaiting())
				return;
			if (b.deploying() && !started) {
				started = MapBattles.deploy();
				if (started)
					event.accept("battle", "\"what\":\"start\",\"step\":" + Engine.step());
			}
			if (Hooks.battleConcludeOpen() && !concluded) {
				if (concludeShotAt < 0) // the panel on screen first, for the run's report: a shot once it is drawn
					concludeShotAt = System.currentTimeMillis();
				long open = System.currentTimeMillis() - concludeShotAt;
				if (open < 1500) {
					if (open >= 500 && !concludeShot) {
						CORE.getGraphics().makeScreenShot();
						concludeShot = true;
					}
					return;
				}
				concluded = MapBattles.conclude();
				if (concluded)
					event.accept("battle", "\"what\":\"close\",\"step\":" + Engine.step());
			}
			return;
		}
		started = concluded = false;
		concludeShotAt = -1;
		concludeShot = false;
		if (question())
			return;
		Object open = Hooks.worldPromptHere() ? Hooks.worldPrompt() : null;
		if (open == null) {
			answeredPrompt = null;
			return;
		}
		if (open == answeredPrompt)
			return;
		if (open != shotPrompt) { // the prompt on screen first, for the run's report: a shot once it is drawn (SOS-162)
			shotPrompt = open;
			promptSeenAt = System.currentTimeMillis();
			promptShot = false;
		}
		long shown = System.currentTimeMillis() - promptSeenAt;
		if (shown >= 500 && !promptShot && setup.loading().shown() == null) { // never at a loading window's freeze (SOS-158)
			CORE.getGraphics().makeScreenShot();
			promptShot = true;
			event.accept("battle", "\"what\":\"prompt shot\",\"prompt\":\"" + open.getClass().getSimpleName() + "\",\"step\":" + Engine.step());
		}
		if (shown < 1500 || !promptShot && shown < 20_000)
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
			WBattleResult result = (WBattleResult) open;
			boolean won = Hooks.resultWon(result);
			WorldBattles.accept(won ? result.capturedRaces.clone() : null, won ? result.lostResources.clone() : null, 0); // all of it
			what = won ? "victory accept" : "defeat accept";
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

	private WArmy firstArmy() {
		int n = 0;
		for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
			WArmy w = WORLD.ENTITIES().armies.tryGet(i);
			if (w != null && w.faction() == FACTIONS.player() && n++ == armyPick)
				return w;
		}
		return null;
	}

	/** A tile of the capital's region next to its centre that an army can stand on; -1, -1 for the engine's choice. */
	private static int[] armyTile() {
		Region c = FACTIONS.player().capitolRegion();
		if (c == null || FACTIONS.player().armies().all().size() == 0)
			return new int[] { -1, -1 };
		for (int r = 1; r < 6; r++)
			for (int dy = -r; dy <= r; dy++)
				for (int dx = -r; dx <= r; dx++) {
					int x = c.cx() + dx, y = c.cy() + dy;
					if (WORLD.PATH().map.is.is(x, y) && WORLD.REGIONS().map.get(x, y) == c)
						return new int[] { x, y };
				}
		return new int[] { -1, -1 };
	}

	/** The NPC region nearest {@code f}'s capital that is no faction's capital: what {@code giveregion} gives. */
	private static Region nearestNpcRegion(Faction f) {
		Region best = null;
		long bestD = Long.MAX_VALUE;
		Region c = f.capitolRegion();
		for (Region r : WORLD.REGIONS().active()) {
			if (!(r.faction() instanceof FactionNPC) || r.capitol() || c == null)
				continue;
			long dx = r.cx() - c.cx(), dy = r.cy() - c.cy();
			if (dx * dx + dy * dy < bestD) {
				bestD = dx * dx + dy * dy;
				best = r;
			}
		}
		return best;
	}

	/** The active NPC faction nearest {@code f}'s capital with no overlord: what {@code givevassal} makes a vassal. */
	private static FactionNPC nearestFreeNpc(Faction f) {
		FactionNPC best = null;
		long bestD = Long.MAX_VALUE;
		Region c = f.capitolRegion();
		for (int i = 0; i < FACTIONS.MAX(); i++) {
			Faction n = FACTIONS.getByIndex(i);
			if (!(n instanceof FactionNPC) || !n.isActive() || n.capitolRegion() == null || DIP.overlord(n) != null || c == null)
				continue;
			long dx = n.capitolRegion().cx() - c.cx(), dy = n.capitolRegion().cy() - c.cy();
			if (dx * dx + dy * dy < bestD) {
				bestD = dx * dx + dy * dy;
				best = (FactionNPC) n;
			}
		}
		return best;
	}

	/** The NPC army with men nearest {@code from} whose faction is at war with this player. */
	private static WArmy nearestNpcEnemy(WArmy from) {
		WArmy best = null;
		long bestD = Long.MAX_VALUE;
		for (int i = 0; i < WORLD.ENTITIES().armies.max(); i++) {
			WArmy w = WORLD.ENTITIES().armies.tryGet(i);
			if (w == null || !(w.faction() instanceof FactionNPC) || AD.men(null).get(w) == 0 || !DIP.WAR().is(FACTIONS.player(), w.faction()))
				continue;
			long dx = w.ctx() - from.ctx(), dy = w.cty() - from.cty();
			if (dx * dx + dy * dy < bestD) {
				bestD = dx * dx + dy * dy;
				best = w;
			}
		}
		return best;
	}

	/** What waits for this PC's player: the prompts, the battle and its panel, a view, an assault's terms. */
	private static String state(EnginePlay engine) {
		BattleState b = VIEW.b().state();
		Object prompt = Hooks.worldPromptHere() ? Hooks.worldPrompt() : null;
		return "prompt " + (prompt == null ? "none" : prompt.getClass().getSimpleName()) + ", sally " + Hooks.worldPromptSally()
				+ ", ally asked " + engine.allyAsked() + ", battle " + (b == null ? "none" : b.deploying() ? "deploying"
						: Hooks.battleConcludeOpen() ? "over" : "running")
				+ ", viewing " + (Engine.viewing() != null) + ", terms " + (Engine.assaultTaken() != null)
				+ ", notice " + VIEW.inters().fullScreen.isActivated() + ", step " + engine.step();
	}

	/** Each human's relations with the others, their NPC wars and vassals, for a test's log. */
	private static String diplo() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < FACTIONS.MAX(); i++) {
			Faction h = FACTIONS.getByIndex(i);
			if (!(h instanceof game.faction.player.Player) || !h.isActive())
				continue;
			b.append(h.index()).append(' ').append(h.name).append(":");
			for (int j = 0; j < FACTIONS.MAX(); j++) {
				Faction o = FACTIONS.getByIndex(j);
				if (o == h || o == null || !o.isActive())
					continue;
				boolean human = o instanceof game.faction.player.Player;
				String rel = DIP.WAR().is(h, o) ? "war" : DIP.overlord(o) == h ? "vassal" : DIP.overlord(h) == o ? "overlord"
						: human && DIP.get(h, o) != null ? DIP.get(h, o).name.toString() : null;
				if (rel != null)
					b.append(' ').append(o.index()).append('=').append(rel);
			}
			b.append(" regions ").append(h.realm().regions()).append("; ");
		}
		return b.toString();
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
			Files.writeString(file.resolveSibling("trace.txt"), Engine.drawTrace(traceAt - 1200, traceAt), StandardCharsets.UTF_8);
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
		if (engine.anyBattle())
			armies.append("a battle is running: nothing else happens until it ends; ");
		return String.format(Locale.ROOT, "step %d, credits %d, at war %b, spectating %b, last player %d, my capital besieged %b, theirs %b, armies: %s",
				engine.step(), (long) FACTIONS.player().credits().getD(),
				DIP.WAR().is(FACTIONS.player(), other()), Engine.spectating(), Engine.lastHuman(),
				Engine.besiegedSince(FACTIONS.player().index()) >= 0, Engine.besiegedSince(other().index()) >= 0, armies);
	}

	private static String clean(String s) {
		return s == null ? "" : s.replace('"', '\'').replace('\\', '/').replace('\n', ' ');
	}
}
