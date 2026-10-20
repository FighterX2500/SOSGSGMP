package sosmp.spike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

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
import init.race.RACES;
import init.race.Race;
import script.SCRIPT;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.STATS;
import settlement.stats.colls.StatsBattle.StatTraining;
import settlement.stats.equip.EquipBattle;
import snake2d.util.rnd.RND;
import view.main.VIEW;

/**
 * SOS-10 spike (throwaway, CLAUDE.md): battlefield from a seed, and battle snapshot size.
 *
 * Driven by a command file so no UI clicks are needed:
 *   %APPDATA%\songsofsyx\sosmp-spike\command.txt   one command, deleted when read
 *   %APPDATA%\songsofsyx\sosmp-spike\results.jsonl one JSON object per line, appended
 *
 * Commands:
 *   ping
 *   generate <seed> <menPerSide> <tag>   RND.setSeed(seed), build a fixed army spec from the
 *                                        seed, BattleState.setGenerate, fingerprint the result
 *   start <battleSeconds> <tag>          deploy, run at 1x, snapshot every 0.1 s of battle time
 */
public final class BattleSpike implements SCRIPT {

	@Override
	public CharSequence name() {
		return "SoS MP spike: battle streaming";
	}

	@Override
	public CharSequence desc() {
		return "Throwaway measurement script for the multiplayer mod (SOS-10).";
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

		private static final double SNAPSHOT_INTERVAL = 0.1;
		private static final int POLL_EVERY_CALLS = 30;
		private static final int BYTES_PER_SOLDIER = 11;

		private final Path dir = Paths.get(System.getenv("APPDATA"), "songsofsyx", "sosmp-spike");
		private final Path commandFile = dir.resolve("command.txt");
		private final Path resultsFile = dir.resolve("results.jsonl");

		private int calls;

		private boolean capturing;
		private String captureTag;
		private double captureLeft;
		private double sinceSnapshot;
		private byte[] raw = new byte[BYTES_PER_SOLDIER * 8192];
		private final byte[] packed = new byte[BYTES_PER_SOLDIER * 8192 + 1024];
		private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		private long snapshots;
		private long soldiersSum;
		private long rawSum;
		private long packedSum;
		private int rawMax;
		private int packedMax;
		private long captureNanosSum;
		private long captureNanosMax;
		private int soldiersFirst = -1;
		private int soldiersLast;

		Instance() {
			try {
				Files.createDirectories(dir);
			} catch (IOException e) {
				throw new IllegalStateException("SOSMP-SPIKE: cannot create " + dir, e);
			}
			write("{\"event\":\"loaded\"}");
		}

		@Override
		public void update(double ds) {
			if (capturing)
				capture(ds);
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
			} catch (RuntimeException e) {
				write(json("error", command + " -> " + e));
			}
		}

		private void execute(String[] c) {
			switch (c[0]) {
			case "ping":
				write("{\"event\":\"pong\"}");
				return;
			case "generate":
				generate(Integer.parseInt(c[1]), Integer.parseInt(c[2]), c[3]);
				return;
			case "start":
				start(Double.parseDouble(c[1]), c[2]);
				return;
			case "rndsave":
				rndSave(Integer.parseInt(c[1]));
				return;
			case "generate-direct":
				generateDirect(Integer.parseInt(c[1]), Integer.parseInt(c[2]), c[3]);
				return;
			default:
				write(json("error", "unknown command: " + String.join(" ", c)));
			}
		}

		// ---------- question 1: battlefield from a seed ----------

		private void generate(int seed, int menPerSide, String tag) {
			int wx = FACTIONS.player().capitolRegion().cx() + 3;
			int wy = FACTIONS.player().capitolRegion().cy();

			RND.setSeed(seed);
			BattleStateSpec spec = new BattleStateSpec();
			fillSide(spec.player, menPerSide, wx, wy);
			fillSide(spec.enemy, menPerSide, wx + 1, wy);

			BattleStateExiter exiter = new BattleStateExiter() {
				@Override
				public void afterExit(BattleStateResult res) {
					write("{\"event\":\"exit\",\"result\":\"" + res.result + "\"}");
				}
			};

			long t0 = System.nanoTime();
			BattleState.setGenerate(exiter, spec);
			long genMs = (System.nanoTime() - t0) / 1_000_000;
			int probe = RND.rInt();

			write(String.format(Locale.ROOT,
					"{\"event\":\"generate\",\"tag\":\"%s\",\"seed\":%d,\"menPerSide\":%d,\"wx\":%d,\"wy\":%d,"
					+ "\"terrain\":\"%08x\",\"floor\":\"%08x\",\"ground\":\"%08x\",\"units\":\"%08x\","
					+ "\"soldiers\":%d,\"divisions\":%d,\"rndProbeAfter\":%d,\"generateMs\":%d}",
					tag, seed, menPerSide, wx, wy, terrainHash(), floorHash(), groundHash(), unitsHash(),
					soldierCount(), spec.player.divs.size() + spec.enemy.divs.size(), probe, genMs));
		}

		/** Does a full game save draw from RND? Same seed, probe with and without a save in between. */
		private void rndSave(int seed) {
			RND.setSeed(seed);
			int plain = RND.rInt();
			RND.setSeed(seed);
			GAME.saver().save("__sosmp_probe");
			int afterSave = RND.rInt();
			write(String.format(Locale.ROOT, "{\"event\":\"rndsave\",\"seed\":%d,\"plain\":%d,\"afterSave\":%d,\"saveDrawsRnd\":%b}",
					seed, plain, afterSave, plain != afterSave));
		}

		/**
		 * Same as BattleState.setGenerate, but seeds RND after the pre-battle save. Reflection on private
		 * game members is fine in a throwaway spike; the real mod would need a hook or the adapter layer.
		 */
		private void generateDirect(int seed, int menPerSide, String tag) {
			int wx = FACTIONS.player().capitolRegion().cx() + 3;
			int wy = FACTIONS.player().capitolRegion().cy();
			BattleStateExiter exiter = new BattleStateExiter() {
				@Override
				public void afterExit(BattleStateResult res) {
					write("{\"event\":\"exit\",\"result\":\"" + res.result + "\"}");
				}
			};
			long t0 = System.nanoTime();
			try {
				java.lang.reflect.Method save = BattleState.class.getDeclaredMethod("save", String.class);
				save.setAccessible(true);
				save.invoke(null, "__beforeBattle");
				if (!init.paths.PATHS.local().save().exists("__battle"))
					init.paths.PATHS.local().save().create("__battle");

				RND.setSeed(seed);
				BattleStateSpec spec = new BattleStateSpec();
				fillSide(spec.player, menPerSide, wx, wy);
				fillSide(spec.enemy, menPerSide, wx + 1, wy);

				java.lang.reflect.Constructor<BattleState> ctor = BattleState.class
						.getDeclaredConstructor(BattleStateExiter.class, Path.class);
				ctor.setAccessible(true);
				BattleState s = ctor.newInstance(exiter, init.paths.PATHS.local().save().get("__battle"));
				java.lang.reflect.Field tiles = BattleState.class.getDeclaredField("deploymentTiles");
				tiles.setAccessible(true);
				Class<?> genClass = Class.forName("game.battle.state.BattleStateGenerator");
				java.lang.reflect.Constructor<?> genCtor = genClass.getDeclaredConstructor();
				genCtor.setAccessible(true);
				Object gen = genCtor.newInstance();
				java.lang.reflect.Method generate = genClass.getDeclaredMethod("generate", BattleState.class,
						BattleStateSpec.class, snake2d.util.datatypes.Rec.class);
				generate.setAccessible(true);
				generate.invoke(gen, s, spec, tiles.get(s));
				long genMs = (System.nanoTime() - t0) / 1_000_000;
				int probe = RND.rInt();
				String fp = String.format(Locale.ROOT,
						"\"terrain\":\"%08x\",\"ground\":\"%08x\",\"units\":\"%08x\",\"soldiers\":%d,\"rndProbeAfter\":%d,\"generateMs\":%d",
						terrainHash(), groundHash(), unitsHash(), soldierCount(), probe, genMs);
				save.invoke(null, "__battle");
				java.lang.reflect.Method view = BattleState.class.getDeclaredMethod("view");
				view.setAccessible(true);
				view.invoke(s);
				write("{\"event\":\"generate-direct\",\"tag\":\"" + tag + "\",\"seed\":" + seed + "," + fp + "}");
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("SOSMP-SPIKE: generate-direct failed: " + e, e);
			}
		}

		private static void fillSide(BattleStateSpec.SpecSide side, int men, int wx, int wy) {
			int perDiv = Config.battle().MEN_PER_DIVISION;
			int divs = Math.min(Config.battle().DIVISIONS_PER_ARMY, (men + perDiv - 1) / perDiv);
			side.wCoo.set(wx, wy);
			side.moraleBase = 1.0;
			for (int i = 0; i < divs; i++)
				side.divs.add(division(Math.min(perDiv, men - i * perDiv)));
		}

		/** Like DivGeneration.rnd(), but with a fixed head count; every random draw comes from RND. */
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

		private static long terrainHash() {
			CRC32 crc = new CRC32();
			for (int t = 0; t < SETT.TAREA; t++)
				crc.update(SETT.TERRAIN().get(t).index());
			return crc.getValue();
		}

		private static long floorHash() {
			CRC32 crc = new CRC32();
			for (int ty = 0; ty < SETT.THEIGHT; ty++)
				for (int tx = 0; tx < SETT.TWIDTH; tx++) {
					var f = SETT.FLOOR().getter.get(tx, ty);
					crc.update(f == null ? 255 : f.index());
				}
			return crc.getValue();
		}

		private static long groundHash() {
			CRC32 crc = new CRC32();
			for (int t = 0; t < SETT.TAREA; t++)
				crc.update(SETT.GROUND().MAP.get(t).index);
			return crc.getValue();
		}

		/** Soldiers sorted by (division, tile), so entity order doesn't matter. */
		private static long unitsHash() {
			List<Long> keys = new ArrayList<>();
			for (Humanoid h : soldiers())
				keys.add(((long) h.division().index() << 32) | ((long) h.tc().x() << 16) | h.tc().y());
			Collections.sort(keys);
			CRC32 crc = new CRC32();
			for (long k : keys)
				for (int i = 0; i < 8; i++)
					crc.update((int) (k >>> (8 * i)));
			return crc.getValue();
		}

		private static int soldierCount() {
			return soldiers().size();
		}

		private static List<Humanoid> soldiers() {
			List<Humanoid> out = new ArrayList<>();
			ENTITY[] all = SETT.ENTITIES().getAllEnts();
			int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
			for (int i = 0; i < max; i++) {
				ENTITY e = all[i];
				if (e instanceof Humanoid && !e.isRemoved() && ((Humanoid) e).division() != null)
					out.add((Humanoid) e);
			}
			return out;
		}

		// ---------- question 2: snapshot size ----------

		private void start(double seconds, String tag) {
			VIEW.b().state().deploy();
			GAME.SPEED.speedSet(1);
			capturing = true;
			captureTag = tag;
			captureLeft = seconds;
			sinceSnapshot = 0;
			snapshots = soldiersSum = rawSum = packedSum = captureNanosSum = captureNanosMax = 0;
			rawMax = packedMax = 0;
			soldiersFirst = -1;
			write("{\"event\":\"started\",\"tag\":\"" + tag + "\"}");
		}

		private void capture(double ds) {
			sinceSnapshot += ds;
			captureLeft -= ds;
			while (sinceSnapshot >= SNAPSHOT_INTERVAL) {
				sinceSnapshot -= SNAPSHOT_INTERVAL;
				snapshot();
			}
			if (captureLeft <= 0)
				finish();
		}

		private void snapshot() {
			long t0 = System.nanoTime();
			ENTITY[] all = SETT.ENTITIES().getAllEnts();
			int max = Math.min(all.length, SETT.ENTITIES().Imax() + 1);
			int n = 0;
			int p = 0;
			for (int i = 0; i < max; i++) {
				ENTITY e = all[i];
				if (!(e instanceof Humanoid) || e.isRemoved())
					continue;
				Humanoid h = (Humanoid) e;
				if (h.division() == null)
					continue;
				if (p + BYTES_PER_SOLDIER > raw.length)
					raw = java.util.Arrays.copyOf(raw, raw.length * 2);
				p = putInt(raw, p, e.id());
				p = putShort(raw, p, e.body().cX());
				p = putShort(raw, p, e.body().cY());
				double angle = Math.atan2(e.speed.nY(), e.speed.nX());
				raw[p++] = (byte) Math.round(angle / (2 * Math.PI) * 256);
				raw[p++] = (byte) Math.round(255 * STATS.NEEDS().INJURIES.COUNT.indu().getD(h.indu()));
				raw[p++] = (byte) h.division().index();
				n++;
			}
			deflater.reset();
			deflater.setInput(raw, 0, p);
			deflater.finish();
			int packedLen = 0;
			while (!deflater.finished())
				packedLen += deflater.deflate(packed, 0, packed.length);
			long nanos = System.nanoTime() - t0;

			snapshots++;
			soldiersSum += n;
			rawSum += p;
			packedSum += packedLen;
			rawMax = Math.max(rawMax, p);
			packedMax = Math.max(packedMax, packedLen);
			captureNanosSum += nanos;
			captureNanosMax = Math.max(captureNanosMax, nanos);
			if (soldiersFirst < 0)
				soldiersFirst = n;
			soldiersLast = n;
		}

		private void finish() {
			capturing = false;
			GAME.SPEED.speedSet(0);
			double s = Math.max(1, snapshots);
			write(String.format(Locale.ROOT,
					"{\"event\":\"snapshots\",\"tag\":\"%s\",\"count\":%d,\"soldiersFirst\":%d,\"soldiersLast\":%d,"
					+ "\"soldiersAvg\":%.1f,\"rawAvg\":%.0f,\"rawMax\":%d,\"packedAvg\":%.0f,\"packedMax\":%d,"
					+ "\"kbitPerSecAt10Hz\":%.1f,\"captureUsAvg\":%.0f,\"captureUsMax\":%d}",
					captureTag, snapshots, soldiersFirst, soldiersLast, soldiersSum / s, rawSum / s, rawMax,
					packedSum / s, packedMax, packedSum / s * 10 * 8 / 1000.0, captureNanosSum / s / 1000.0,
					captureNanosMax / 1000));
		}

		private static int putInt(byte[] b, int p, int v) {
			b[p] = (byte) (v >>> 24);
			b[p + 1] = (byte) (v >>> 16);
			b[p + 2] = (byte) (v >>> 8);
			b[p + 3] = (byte) v;
			return p + 4;
		}

		private static int putShort(byte[] b, int p, int v) {
			b[p] = (byte) (v >>> 8);
			b[p + 1] = (byte) v;
			return p + 2;
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
		public void save(snake2d.util.file.FilePutter file) {
		}

		@Override
		public void load(snake2d.util.file.FileGetter file) throws IOException {
		}
	}
}
