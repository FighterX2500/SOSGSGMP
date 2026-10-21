package sosmp.det;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import game.GAME;
import game.battle.state.BattleState;
import game.battle.state.BattleStateExiter;
import game.battle.state.BattleStateResult;
import game.battle.state.BattleStateSpec;
import game.battle.util.DIV_SPEC;
import game.battle.util.DivGeneration;
import game.faction.FACTIONS;
import game.faction.Faction;
import init.constant.Config;
import init.paths.PATHS;
import init.race.RACES;
import init.race.Race;
import script.SCRIPT;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBattle.StatTraining;
import settlement.stats.equip.EquipBattle;
import snake2d.util.datatypes.Rec;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.rnd.RND;
import snake2d.util.misc.ACTION;
import view.main.VIEW;

/**
 * SOS-11 spike harness (throwaway, CLAUDE.md): is a battle deterministic?
 *
 * Commands in %APPDATA%\songsofsyx\sosmp-spike\command.txt, results in results.jsonl,
 * per-step hashes in det\<tag>.csv:
 *   ping
 *   load <saveName>                                                      load a save (the harness restarts with the game)
 *   mode <fixedStep> <syncThreads 0|1> <syncPaths 0|1> <splitRnd 0|1>   switches of the patched build
 *   loadbattle <saveName>                                                load a battle save and enter its deployment
 *   reload                                                               reload the battle save (__battle) like the game's own reload
 *   hash                                                                 current state hash
 *   seed <n>                                                             seed the simulation random stream
 *   generate <seed> <menPerSide> [dumpTag]                               seed set after the pre-battle save; dumpTag = also dump soldiers
 *   run <battleSeconds> <tag> [speed] [dumpSteps|-] [traceSteps]          start like the Start button, hash every step;
 *                                                                        traceSteps = log the caller of every simulation draw
 *   world <0|1>                                                          SOS-23: 1 = hold the world still in the city view
 *   cityrun <seconds> <tag> [speed] [dumpSteps|-] [traceSteps]           SOS-23: run the loaded city, hash every person and
 *                                                                        animal every step
 */
public final class DeterminismSpike implements SCRIPT {

	@Override
	public CharSequence name() {
		return "SoS MP spike: determinism";
	}

	@Override
	public CharSequence desc() {
		return "Throwaway measurement script for the multiplayer mod (SOS-11).";
	}

	@Override
	public boolean isSelectable() {
		return false;
	}

	@Override
	public boolean forceInit() {
		return true;
	}

	@Override
	public SCRIPT_INSTANCE createInstance() {
		return new Instance();
	}

	private static final class Instance implements SCRIPT_INSTANCE {

		private static final int POLL_EVERY_CALLS = 30;

		private final Path dir = Paths.get(System.getenv("APPDATA"), "songsofsyx", "sosmp-spike");
		private final Path commandFile = dir.resolve("command.txt");
		private final Path resultsFile = dir.resolve("results.jsonl");
		private final Class<?> switches = switchesClass();

		private int calls;
		private boolean running;
		private String tag;
		private double battleLeft;
		private double battleTime;
		private long steps;
		private BufferedWriter log;
		private long frameStart = -1;
		private long simNanos;
		private String dumpSteps = "";
		private int traceSteps;
		private int traceFrom;
		private boolean city;
		private int invadeMen;
		private int saveAt;

		Instance() {
			try {
				Files.createDirectories(dir.resolve("det"));
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: cannot create " + dir, e);
			}
			GAME.addAfterUpdate(new ACTION() {
				@Override
				public void exe() {
					if (frameStart >= 0) {
						simNanos += System.nanoTime() - frameStart;
						frameStart = -1;
					}
					saveAtStep();
					poll();
				}
			});
			write("{\"event\":\"loaded\",\"patched\":" + (switches != null) + "}");
		}

		@Override
		public void update(double ds) {
			if (running && ds > 0)
				step(ds);
		}

		/** SOS-23: the game held at step saveAt (switch stepsLeft); save the whole state there, then let it run on. */
		private void saveAtStep() {
			if (!running || saveAt <= 0 || steps != saveAt)
				return;
			try {
				if ((Long) get("stepsLeft") != 0)
					return;
				String name = "sosmp-step" + saveAt + "-" + tag;
				write("{\"event\":\"saved\",\"step\":" + steps + ",\"path\":\"" + String.valueOf(GAME.saver().save(name)).replace("\\", "/") + "\"}");
				saveAt = 0;
				set("stepsLeft", -1L);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("SOSMP-SPIKE: save at step failed", e);
			}
		}

		/** Once per frame from GAME.addAfterUpdate, so commands work while the simulation is paused. */
		private void poll() {
			if (++calls % POLL_EVERY_CALLS != 0 || !Files.exists(commandFile))
				return;
			String command;
			try {
				command = new String(Files.readAllBytes(commandFile), StandardCharsets.UTF_8).trim();
				Files.delete(commandFile);
			} catch (IOException e) {
				write(json("error", "reading command: " + e));
				return;
			}
			try {
				execute(command.split("\\s+"));
			} catch (RuntimeException | ReflectiveOperationException e) {
				write(json("error", command + " -> " + e));
			}
		}

		private void execute(String[] c) throws ReflectiveOperationException {
			switch (c[0]) {
			case "ping":
				write("{\"event\":\"pong\"}");
				return;
			case "mode":
				mode(Double.parseDouble(c[1]), c[2].equals("1"), c[3].equals("1"), c[4].equals("1"));
				return;
			case "load":
				// every run starts from the same saved state (game time, update counter, world)
				write("{\"event\":\"loading\",\"save\":\"" + c[1] + "\"}");
				new game.save.GameLoader(PATHS.local().save().get(String.join(" ", java.util.Arrays.copyOfRange(c, 1, c.length)))).set();
				return;
			case "generate":
				generate(Integer.parseInt(c[1]), Integer.parseInt(c[2]));
				if (c.length > 3)
					dump(c[3] + "-gen.csv");
				return;
			case "loadbattle":
				// SOS-11: load a battle save made by another copy, as a lockstep peer would load the host's
				loadBattle(String.join(" ", java.util.Arrays.copyOfRange(c, 1, c.length)));
				return;
			case "reload":
				// SOS-11: start the battle from the freshly loaded battle save, as every lockstep peer would
				write("{\"event\":\"loading\",\"save\":\"__battle\"}");
				VIEW.b().state().reloadBattle();
				return;
			case "seed":
				RND.setSeed(Integer.parseInt(c[1]));
				write("{\"event\":\"seeded\",\"seed\":" + Integer.parseInt(c[1]) + "}");
				return;
			case "hash":
				write(String.format(Locale.ROOT, "{\"event\":\"hash\",\"stateHash\":\"%016x\",\"cityHash\":\"%016x\"}", stateHash(), cityHash()));
				return;
			case "statdump":
				// SOS-23: every person's stats by name plus the raw packed data, to name a stat that differs between copies
				statDump(c[1]);
				write("{\"event\":\"statdump\",\"file\":\"" + c[1] + "\"}");
				return;
			case "save":
				// SOS-23: save the current state, to compare whole saves between copies
				write("{\"event\":\"saved\",\"path\":\"" + String.valueOf(GAME.saver().save(c[1])).replace("\\", "/") + "\"}");
				return;
			case "invade":
				// SOS-23: the next cityrun starts an invasion of this many men at step 1 and skips its warning at steps 2-3
				invadeMen = Integer.parseInt(c[1]);
				write("{\"event\":\"invade\",\"men\":" + invadeMen + "}");
				return;
			case "tracestart":
				// SOS-23: trace every simulation draw from now (survives a load: the trace is a static switch)
				set("trace", new StringBuilder());
				write("{\"event\":\"tracestart\"}");
				return;
			case "tracewrite":
				StringBuilder tr = (StringBuilder) get("trace");
				set("trace", null);
				try {
					Files.write(dir.resolve("det").resolve(c[1]), (tr == null ? "" : tr.toString()).getBytes(StandardCharsets.UTF_8));
				} catch (IOException e) {
					throw new IllegalStateException("SOSMP-SPIKE: cannot write " + c[1], e);
				}
				write("{\"event\":\"tracewrite\",\"file\":\"" + c[1] + "\"}");
				return;
			case "speakeracts":
				// SOS-23: the speakers' cheer/boo table, drawn from RND when the room type is created and not saved
				StringBuilder sa = new StringBuilder();
				for (Object sp : SETT.ROOMS().SPEAKERS)
					sa.append(java.util.Arrays.toString((byte[]) field(field(sp, "spec"), "acts"))).append(' ');
				write(json("speakeracts", sa.toString()));
				return;
			case "roomat":
				// SOS-23: every field of the room at a tile (and its superclasses up to Room), to find unsaved state
				write(json("roomat", roomFields(Integer.parseInt(c[1]), Integer.parseInt(c[2]))));
				return;
			case "saveat":
				// SOS-23: the next run holds after this many steps and saves (sosmp-step<N>-<tag>), then runs on
				saveAt = Integer.parseInt(c[1]);
				write("{\"event\":\"saveat\",\"step\":" + saveAt + "}");
				return;
			case "loadseed":
				// SOS-23: 0 = vanilla loading; otherwise loads run on the simulation stream seeded with this
				set("loadSeed", Long.parseLong(c[1]));
				write("{\"event\":\"loadseed\",\"seed\":" + Long.parseLong(c[1]) + "}");
				return;
			case "world":
				set("freezeWorld", c[1].equals("1"));
				write("{\"event\":\"world\",\"frozen\":" + c[1].equals("1") + "}");
				return;
			case "run":
			case "cityrun":
				city = c[0].equals("cityrun");
				run(Double.parseDouble(c[1]), c[2], c.length > 3 ? Double.parseDouble(c[3]) : 1);
				dumpSteps = c.length > 4 ? c[4] : "";
				// traceSteps: "N" = steps 1..N, "A-B" = steps A..B
				String ts = c.length > 5 ? c[5] : "0";
				traceFrom = ts.contains("-") ? Integer.parseInt(ts.substring(0, ts.indexOf('-'))) : 1;
				traceSteps = Integer.parseInt(ts.substring(ts.indexOf('-') + 1));
				if (switches != null) {
					set("simDraws", 0L);
					set("pathJobs", 0L);
					set("pathNanos", 0L);
					set("foreignDraws", 0L);
					set("stepsLeft", saveAt > 0 ? (long) saveAt : -1L);
				}
				if (traceSteps > 0 && traceFrom <= 1)
					set("trace", new StringBuilder());
				return;
			default:
				write(json("error", "unknown command: " + String.join(" ", c)));
			}
		}

		// ---------- switches of the patched build ----------

		private static Class<?> switchesClass() {
			try {
				return Class.forName("snake2d.SosMpDeterminism");
			} catch (ClassNotFoundException e) {
				return null;
			}
		}

		private void set(String field, Object value) throws ReflectiveOperationException {
			if (switches == null)
				throw new IllegalStateException("not the patched build");
			switches.getField(field).set(null, value);
		}

		private Object get(String field) throws ReflectiveOperationException {
			return switches == null ? null : switches.getField(field).get(null);
		}

		private void mode(double fixedStep, boolean syncThreads, boolean syncPaths, boolean splitRnd)
				throws ReflectiveOperationException {
			set("fixedStep", fixedStep);
			set("syncThreads", syncThreads);
			set("syncPaths", syncPaths);
			set("splitRnd", splitRnd);
			write(String.format(Locale.ROOT, "{\"event\":\"mode\",\"fixedStep\":%s,\"syncThreads\":%b,\"syncPaths\":%b,\"splitRnd\":%b}",
					fixedStep, syncThreads, syncPaths, splitRnd));
		}

		// ---------- battle from a seed (seeded after the pre-battle save, SOS-10) ----------

		private void generate(int seed, int menPerSide) throws ReflectiveOperationException {
			int wx = FACTIONS.player().capitolRegion().cx() + 3;
			int wy = FACTIONS.player().capitolRegion().cy();
			BattleStateExiter exiter = new BattleStateExiter() {
				@Override
				public void afterExit(BattleStateResult res) {
					write("{\"event\":\"exit\",\"result\":\"" + res.result + "\"}");
				}
			};
			Method save = BattleState.class.getDeclaredMethod("save", String.class);
			save.setAccessible(true);
			save.invoke(null, "__beforeBattle");
			if (!PATHS.local().save().exists("__battle"))
				PATHS.local().save().create("__battle");

			if (switches != null) {
				set("simThread", Thread.currentThread());
				set("inSim", true);
				set("simDraws", 0L);
				set("foreignDraws", 0L);
			}
			RND.setSeed(seed);
			BattleStateSpec spec = new BattleStateSpec();
			fillSide(spec.player, menPerSide, wx, wy);
			fillSide(spec.enemy, menPerSide, wx + 1, wy);
			Constructor<BattleState> ctor = BattleState.class.getDeclaredConstructor(BattleStateExiter.class, Path.class);
			ctor.setAccessible(true);
			BattleState s = ctor.newInstance(exiter, PATHS.local().save().get("__battle"));
			Field tiles = BattleState.class.getDeclaredField("deploymentTiles");
			tiles.setAccessible(true);
			Class<?> genClass = Class.forName("game.battle.state.BattleStateGenerator");
			Constructor<?> genCtor = genClass.getDeclaredConstructor();
			genCtor.setAccessible(true);
			Method generate = genClass.getDeclaredMethod("generate", BattleState.class, BattleStateSpec.class, Rec.class);
			generate.setAccessible(true);
			generate.invoke(genCtor.newInstance(), s, spec, tiles.get(s));
			if (switches != null)
				set("inSim", false);
			long h = stateHash();
			save.invoke(null, "__battle");
			Method view = BattleState.class.getDeclaredMethod("view");
			view.setAccessible(true);
			view.invoke(s);
			write(String.format(Locale.ROOT, "{\"event\":\"generated\",\"seed\":%d,\"menPerSide\":%d,\"stateHash\":\"%016x\",\"simDraws\":%s}",
					seed, menPerSide, h, get("simDraws")));
		}

		private void loadBattle(String name) {
			Path p = PATHS.local().save().get(name);
			write("{\"event\":\"loading\",\"save\":\"" + name + "\"}");
			new game.save.GameLoader(p) {
				@Override
				public void doAfterSet() {
					BattleState.setLoaded(new BattleStateExiter() {
						@Override
						public void afterExit(BattleStateResult res) {
						}
					}, p, true);
				}
			}.set();
		}

		private static void fillSide(BattleStateSpec.SpecSide side, int men, int wx, int wy) {
			int perDiv = Config.battle().MEN_PER_DIVISION;
			int divs = Math.min(Config.battle().DIVISIONS_PER_ARMY, (men + perDiv - 1) / perDiv);
			side.wCoo.set(wx, wy);
			side.moraleBase = 1.0;
			for (int i = 0; i < divs; i++)
				side.divs.add(division(Math.min(perDiv, men - i * perDiv)));
		}

		private static DivGeneration division(int men) {
			final Race race = RACES.all().rnd();
			final var type = GAME.battle().types.rnd(race, FACTIONS.player(), RND.rFloat());
			final String name = "" + race.info.armyNames.rnd();
			final int banner = RND.rInt(GAME.ARMIES().banners.size());
			final double tr = RND.rFloat();
			final double eq = RND.rFloat();
			final double ex = RND.rFloat();
			DIV_SPEC spec = new DIV_SPEC() {
				@Override
				public double training(StatTraining t) {
					return type.training(t) * tr;
				}

				@Override
				public double equip(EquipBattle e) {
					return type.equip(e) * eq;
				}

				@Override
				public Race race() {
					return race;
				}

				@Override
				public int men() {
					return men;
				}

				@Override
				public Faction faction() {
					return null;
				}

				@Override
				public double experience() {
					return ex;
				}

				@Override
				public CharSequence name() {
					return name;
				}

				@Override
				public int bannerI() {
					return banner;
				}
			};
			return new DivGeneration(spec, spec);
		}

		// ---------- run and hash ----------

		private void run(double seconds, String runTag, double speed) throws ReflectiveOperationException {
			// Battle start is part of the simulation: deploy initialises the battle AI, which draws random numbers
			// (Strategos offence Context.flanking). Run it on the seeded simulation stream, as a lockstep tick 0 would.
			if (!city) {
				if (switches != null) {
					set("simThread", Thread.currentThread());
					set("inSim", true);
				}
				VIEW.b().state().deploy();
				if (switches != null)
					set("inSim", false);
				hideDeployPanel();
			}
			VIEW.inters().load.deactivate(); // the "click to continue" screen after a load also pauses the game
			tag = runTag;
			battleLeft = seconds;
			battleTime = 0;
			steps = 0;
			simNanos = 0;
			try {
				log = Files.newBufferedWriter(dir.resolve("det").resolve(tag + ".csv"), StandardCharsets.UTF_8);
				log.write("step,battleTime,hash,playerMen,enemyMen,simDraws,pathJobs\n");
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: cannot open log", e);
			}
			running = true;
			GAME.SPEED.speedSet(speed);
			write("{\"event\":\"running\",\"tag\":\"" + tag + "\"}");
		}

		/** What the Start button does besides deploy(): hide the panel that pauses the game every frame. */
		private static void hideDeployPanel() throws ReflectiveOperationException {
			Object manager = field(VIEW.b(), "uiManager");
			Iterable<?> inters = (Iterable<?>) field(manager, "inters");
			for (Object i : inters) {
				if (i.getClass().getName().equals("view.battle.IDeploy")) {
					Method hide = Class.forName("view.interrupter.Interrupter").getDeclaredMethod("hide");
					hide.setAccessible(true);
					hide.invoke(i);
				}
			}
		}

		private static Object field(Object o, String name) throws ReflectiveOperationException {
			for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
				try {
					Field f = c.getDeclaredField(name);
					f.setAccessible(true);
					return f.get(o);
				} catch (NoSuchFieldException e) {
					// keep looking in the superclass
				}
			}
			throw new NoSuchFieldException(name + " in " + o.getClass());
		}

		private void step(double ds) {
			if (frameStart < 0)
				frameStart = System.nanoTime();
			steps++;
			battleTime += ds;
			battleLeft -= ds;
			if (city && invadeMen > 0 && steps <= 3)
				invasion();
			if (traceSteps > 0)
				trace();
			if (!dumpSteps.isEmpty() && ("," + dumpSteps + ",").contains("," + steps + ","))
				dump(tag + "-step" + steps + ".csv");
			try {
				long draws = switches == null ? -1 : (Long) get("simDraws");
				long paths = switches == null ? -1 : (Long) get("pathJobs");
				log.write(steps + "," + String.format(Locale.ROOT, "%.4f", battleTime) + ","
						+ Long.toHexString(city ? cityHash() : stateHash()) + "," + men() + ","
						+ GAME.ARMIES().enemy().men() + "," + draws + "," + paths + "\n");
			} catch (IOException | ReflectiveOperationException e) {
				throw new IllegalStateException("SOSMP-SPIKE: log failed", e);
			}
			if (battleLeft <= 0)
				finish();
		}

		/**
		 * SOS-23: inside the step, so on the simulation stream. Step 1: the debug panel's invasion. Steps 2-3: its
		 * fast-forward (warning -> bombardment -> launch), as the debug panel's "Invasion Finish" does.
		 */
		private void invasion() {
			try {
				Object invador = SETT.INVADOR();
				if (steps == 1) {
					Method invade = invador.getClass().getDeclaredMethod("invade", int.class, double.class);
					invade.setAccessible(true);
					invade.invoke(invador, invadeMen, 0.2);
					write("{\"event\":\"invaded\",\"men\":" + invadeMen + ",\"step\":" + steps + "}");
					return;
				}
				Iterable<?> active = (Iterable<?>) field(invador, "active");
				for (Object inv : active) {
					Method ff = inv.getClass().getDeclaredMethod("fastForward");
					ff.setAccessible(true);
					ff.invoke(inv);
					break;
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("SOSMP-SPIKE: invasion failed at step " + steps, e);
			}
		}

		private Object foreignDraws() {
			try {
				return get("foreignDraws");
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("SOSMP-SPIKE: no foreignDraws", e);
			}
		}

		private void finish() {
			running = false;
			GAME.SPEED.speedSet(0);
			try {
				log.close();
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: cannot close log", e);
			}
			long pathNanos;
			Object pathJobs;
			try {
				pathNanos = switches == null ? 0 : (Long) get("pathNanos");
				pathJobs = get("pathJobs");
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("SOSMP-SPIKE: no path counters", e);
			}
			write(String.format(Locale.ROOT,
					"{\"event\":\"finished\",\"tag\":\"%s\",\"steps\":%d,\"battleTime\":%.2f,\"playerMen\":%d,\"enemyMen\":%d,\"simMsPerStep\":%.3f,\"foreignDraws\":%s,\"pathJobs\":%s,\"pathMsPerStep\":%.3f}",
					tag, steps, battleTime, men(), GAME.ARMIES().enemy().men(),
					simNanos / 1e6 / Math.max(1, steps), foreignDraws(), pathJobs, pathNanos / 1e6 / Math.max(1, steps)));
		}

		/** Battle: the player's soldiers. City: every person (citizens, slaves, soldiers, visitors, invaders). */
		private int men() {
			if (!city)
				return GAME.ARMIES().player().men();
			int n = 0;
			ENTITY[] all = SETT.ENTITIES().getAllEnts();
			int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
			for (int i = 0; i < max; i++)
				if (all[i] instanceof Humanoid && !all[i].isRemoved())
					n++;
			return n;
		}

		/** Step marker in the draw trace; after traceSteps steps, write it to det/<tag>-trace.txt and stop tracing. */
		private void trace() {
			try {
				if (steps == traceFrom - 1) {
					set("trace", new StringBuilder());
					return;
				}
				StringBuilder t = (StringBuilder) get("trace");
				if (t == null)
					return;
				t.append("--- step ").append(steps).append(" (harness update)\n");
				if (steps < traceSteps)
					return;
				Files.write(dir.resolve("det").resolve(tag + "-trace.txt"), t.toString().getBytes(StandardCharsets.UTF_8));
				set("trace", null);
				traceSteps = 0;
			} catch (IOException | ReflectiveOperationException e) {
				throw new IllegalStateException("SOSMP-SPIKE: trace failed", e);
			}
		}

		/** Every soldier's fields at this step, to find what differs first between two runs. */
		private void dump(String file) {
			if (city) {
				dumpCity(file);
				return;
			}
			try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("det").resolve(file), StandardCharsets.UTF_8)) {
				w.write("i,id,x1,y1,speedX,speedY,health,div,tx,ty,nX,nY,mag,dir\n");
				ENTITY[] all = SETT.ENTITIES().getAllEnts();
				int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
				for (int i = 0; i < max; i++) {
					ENTITY e = all[i];
					if (!(e instanceof Humanoid) || e.isRemoved() || ((Humanoid) e).division() == null)
						continue;
					Humanoid s = (Humanoid) e;
					w.write(i + "," + e.id() + "," + e.body().x1() + "," + e.body().y1() + ","
							+ Double.toHexString(e.speed.x()) + "," + Double.toHexString(e.speed.y()) + ","
							+ Double.toHexString(STATS.NEEDS().INJURIES.COUNT.indu().getD(s.indu())) + ","
							+ s.division().index() + "," + e.tc().x() + "," + e.tc().y() + ","
							+ Double.toHexString(e.speed.nX()) + "," + Double.toHexString(e.speed.nY()) + ","
							+ Double.toHexString(e.speed.magnitude()) + "," + e.speed.dir().id() + "\n");
				}
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: dump failed", e);
			}
			dumpDivisions(file.replace(".csv", "-divs.csv"));
		}

		/** Every division's order, target, formation and closest enemy: the decisions behind the soldiers' moves. */
		private void dumpDivisions(String file) {
			game.battle.thread.order.BattleOrderTask task = new game.battle.thread.order.BattleOrderTask();
			try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("det").resolve(file), StandardCharsets.UTF_8)) {
				w.write("i,men,task,targetDiv,ttx,tty,cx,cy,dir,enemyClosest,enemyDist,fighting\n");
				for (game.battle.div.Div d : GAME.ARMIES().divisions()) {
					if (d.menNrOf() == 0)
						continue;
					d.order().task.get(task);
					game.battle.div.Div t = task.targetDiv();
					game.battle.div.Div ec = d.status().enemyClosest();
					snake2d.util.datatypes.COORDINATE c = d.position().centreTile();
					w.write(d.index() + "," + d.menNrOf() + "," + task.task() + "," + (t == null ? -1 : t.index()) + ","
							+ task.targetTileX() + "," + task.targetTileY() + ","
							+ (c == null ? "-" : c.x() + "," + c.y()) + "," + (d.position().dir() == null ? -1 : d.position().dir().id()) + ","
							+ (ec == null ? -1 : ec.index()) + "," + d.status().enemyClosestDist() + "," + d.status().isFighting() + "\n");
				}
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: division dump failed", e);
			}
		}

		/** FNV-1a over every soldier's index, id, position, speed, health and division, in entity order. */
		private static long stateHash() {
			long h = 0xcbf29ce484222325L;
			ENTITY[] all = SETT.ENTITIES().getAllEnts();
			int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
			for (int i = 0; i < max; i++) {
				ENTITY e = all[i];
				if (!(e instanceof Humanoid) || e.isRemoved())
					continue;
				Humanoid s = (Humanoid) e;
				if (s.division() == null)
					continue;
				h = mix(h, i);
				h = mix(h, e.id());
				h = mix(h, e.body().x1());
				h = mix(h, e.body().y1());
				h = mix(h, Double.doubleToLongBits(e.speed.x()));
				h = mix(h, Double.doubleToLongBits(e.speed.y()));
				h = mix(h, Double.doubleToLongBits(STATS.NEEDS().INJURIES.COUNT.indu().getD(s.indu())));
				h = mix(h, s.division().index());
			}
			return h;
		}

		/** SOS-23: FNV-1a over every entity (people and animals): index, type, id, position, speed; people also health. */
		private static long cityHash() {
			long h = 0xcbf29ce484222325L;
			ENTITY[] all = SETT.ENTITIES().getAllEnts();
			int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
			for (int i = 0; i < max; i++) {
				ENTITY e = all[i];
				if (e == null || e.isRemoved())
					continue;
				h = mix(h, i);
				h = mix(h, e.getClass().getName().hashCode());
				h = mix(h, e.id());
				h = mix(h, e.body().x1());
				h = mix(h, e.body().y1());
				h = mix(h, Double.doubleToLongBits(e.speed.x()));
				h = mix(h, Double.doubleToLongBits(e.speed.y()));
				if (e instanceof Humanoid)
					h = mix(h, Double.doubleToLongBits(STATS.NEEDS().INJURIES.COUNT.indu().getD(((Humanoid) e).indu())));
			}
			return h;
		}

		/** SOS-23: every entity with its AI plan and state, to see which behaviour differs first. */
		private void dumpCity(String file) {
			try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("det").resolve(file), StandardCharsets.UTF_8)) {
				w.write("i,type,id,x1,y1,speedX,speedY,tx,ty,plan,state,planTx,planTy\n");
				ENTITY[] all = SETT.ENTITIES().getAllEnts();
				int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
				for (int i = 0; i < max; i++) {
					ENTITY e = all[i];
					if (e == null || e.isRemoved())
						continue;
					String plan = "-", state = "-", planTile = "-,-";
					if (e instanceof Humanoid && ((Humanoid) e).ai() instanceof settlement.entity.humanoid.ai.main.AIManager) {
						settlement.entity.humanoid.ai.main.AIManager m = (settlement.entity.humanoid.ai.main.AIManager) ((Humanoid) e).ai();
						plan = m.plan() == null ? "null" : m.plan().getClass().getName();
						state = m.state() == null ? "null" : m.state().getClass().getName();
						planTile = m.planTile.x() + "," + m.planTile.y();
					}
					w.write(i + "," + e.getClass().getSimpleName() + "," + e.id() + "," + e.body().x1() + "," + e.body().y1() + ","
							+ Double.toHexString(e.speed.x()) + "," + Double.toHexString(e.speed.y()) + ","
							+ e.tc().x() + "," + e.tc().y() + "," + plan + "," + state + "," + planTile + "\n");
				}
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: city dump failed", e);
			}
		}

		private static String roomFields(int tx, int ty) throws ReflectiveOperationException {
			settlement.room.main.Room r = SETT.ROOMS().map.get(tx, ty);
			if (r == null)
				return "no room at " + tx + "," + ty;
			StringBuilder sb = new StringBuilder(r.getClass().getName()).append(" @").append(tx).append(',').append(ty);
			for (Class<?> k = r.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
				for (Field f : k.getDeclaredFields()) {
					if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
						continue;
					f.setAccessible(true);
					Object v = f.get(r);
					if (v == null || v instanceof Number || v instanceof Boolean || v instanceof CharSequence || v instanceof Enum)
						sb.append(' ').append(k.getSimpleName()).append('.').append(f.getName()).append('=').append(v);
				}
			}
			return sb.toString();
		}

		private void statDump(String file) throws ReflectiveOperationException {
			Field data = settlement.stats.Induvidual.class.getDeclaredField("data");
			data.setAccessible(true);
			Method count = STATS.class.getDeclaredMethod("count");
			count.setAccessible(true);
			Iterable<?> entries = (Iterable<?>) field(field(count.invoke(null), "entries"), "entries");
			Method entryGet = Class.forName("util.data.LONG_O").getMethod("get", Object.class);
			try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("det").resolve(file), StandardCharsets.UTF_8)) {
				ENTITY[] all = SETT.ENTITIES().getAllEnts();
				int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
				for (int i = 0; i < max; i++) {
					if (!(all[i] instanceof Humanoid) || all[i].isRemoved())
						continue;
					settlement.stats.Induvidual in = ((Humanoid) all[i]).indu();
					StringBuilder sb = new StringBuilder().append(i).append(" raw");
					for (long l : (long[]) data.get(in))
						sb.append(' ').append(Long.toHexString(l));
					w.write(sb.append('\n').toString());
					for (Object e : entries)
						w.write(i + " " + field(e, "mkey") + " " + entryGet.invoke(field(e, "ii"), in) + "\n");
				}
				// where each packed field was created (the entry keeps a stack trace from its creation)
				for (Object e : entries) {
					StackTraceElement[] st = ((RuntimeException) field(e, "ee")).getStackTrace();
					StringBuilder sb = new StringBuilder("site ").append(field(e, "mkey"));
					for (int k = 0; k < Math.min(st.length, 8); k++)
						sb.append(' ').append(st[k].getClassName()).append(':').append(st[k].getLineNumber());
					w.write(sb.append('\n').toString());
				}
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: stat dump failed", e);
			}
		}

		private static long mix(long h, long v) {
			for (int b = 0; b < 8; b++) {
				h ^= (v >>> (8 * b)) & 0xff;
				h *= 0x100000001b3L;
			}
			return h;
		}

		// ---------- output ----------

		private static String json(String event, String message) {
			return "{\"event\":\"" + event + "\",\"message\":\"" + message.replace("\\", "/").replace("\"", "'") + "\"}";
		}

		private void write(String line) {
			try {
				Files.write(resultsFile, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: cannot write " + resultsFile, e);
			}
		}

		@Override
		public void save(FilePutter file) {
		}

		@Override
		public void load(FileGetter file) throws IOException {
		}
	}
}
