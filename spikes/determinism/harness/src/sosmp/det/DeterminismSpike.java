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
 *   generate <seed> <menPerSide>                                         seed set after the pre-battle save
 *   run <battleSeconds> <tag> [speed]                                    start like the Start button, hash every step
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
				return;
			case "run":
				run(Double.parseDouble(c[1]), c[2], c.length > 3 ? Double.parseDouble(c[3]) : 1);
				dumpSteps = c.length > 4 ? c[4] : "";
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
				set("inSim", true);
				set("simDraws", 0L);
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
			VIEW.b().state().deploy();
			hideDeployPanel();
			VIEW.inters().load.deactivate(); // the "click to continue" screen after a load also pauses the game
			tag = runTag;
			battleLeft = seconds;
			battleTime = 0;
			steps = 0;
			simNanos = 0;
			try {
				log = Files.newBufferedWriter(dir.resolve("det").resolve(tag + ".csv"), StandardCharsets.UTF_8);
				log.write("step,battleTime,hash,playerMen,enemyMen,simDraws\n");
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
			if (!dumpSteps.isEmpty() && ("," + dumpSteps + ",").contains("," + steps + ","))
				dump();
			try {
				long draws = switches == null ? -1 : (Long) get("simDraws");
				log.write(steps + "," + String.format(Locale.ROOT, "%.4f", battleTime) + ","
						+ Long.toHexString(stateHash()) + "," + GAME.ARMIES().player().men() + ","
						+ GAME.ARMIES().enemy().men() + "," + draws + "\n");
			} catch (IOException | ReflectiveOperationException e) {
				throw new IllegalStateException("SOSMP-SPIKE: log failed", e);
			}
			if (battleLeft <= 0)
				finish();
		}

		private void finish() {
			running = false;
			GAME.SPEED.speedSet(0);
			try {
				log.close();
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: cannot close log", e);
			}
			write(String.format(Locale.ROOT,
					"{\"event\":\"finished\",\"tag\":\"%s\",\"steps\":%d,\"battleTime\":%.2f,\"playerMen\":%d,\"enemyMen\":%d,\"simMsPerStep\":%.3f}",
					tag, steps, battleTime, GAME.ARMIES().player().men(), GAME.ARMIES().enemy().men(),
					simNanos / 1e6 / Math.max(1, steps)));
		}

		/** Every soldier's fields at this step, to find what differs first between two runs. */
		private void dump() {
			try (BufferedWriter w = Files.newBufferedWriter(dir.resolve("det").resolve(tag + "-step" + steps + ".csv"), StandardCharsets.UTF_8)) {
				w.write("i,id,x1,y1,speedX,speedY,health,div,tx,ty\n");
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
							+ s.division().index() + "," + e.tc().x() + "," + e.tc().y() + "\n");
				}
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: dump failed", e);
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
