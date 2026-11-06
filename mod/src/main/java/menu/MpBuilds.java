package menu;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import game.VERSION;
import init.paths.ModInfo;
import init.paths.PATHS;
import snake2d.LOG;
import sosdet.Sosdet;
import sosgsgmp.lobby.Builds;
import sosgsgmp.lobby.GameFiles;
import sosgsgmp.lobby.GameFilesReader;

/** SOSGSGMP: this PC's builds, read from the running game and the jars it loaded (SOS-128), and its game files (SOS-159). */
final class MpBuilds {

	private MpBuilds() {
	}

	static Builds local() {
		return new Builds(VERSION.VERSION_STRING, jarHash(Sosdet.class), jarHash(MpBuilds.class), files());
	}

	/** The game's files and the enabled mods', as the game loaded them: the same folders, in its load order. */
	private static GameFiles files() {
		if (MpAutopilot.sameFiles()) {
			LOG.ln("SOSGSGMP: test autopilot: every PC sends the same empty game files");
			return new GameFiles(0, List.of());
		}
		long t = System.nanoTime();
		List<GameFilesReader.ModFolder> mods = new ArrayList<>();
		for (ModInfo m : PATHS.currentMods())
			mods.add(new GameFilesReader.ModFolder(m.name, m.version, Paths.get(m.absolutePath).resolve("V" + m.majorVersion)));
		// the game's data: base\data.zip, or a zipdata folder instead; PATHS_BASE.DATA is its base\data
		Path data = PATHS.BASE().DATA.get().getParent().getParent().resolve("data").resolve("assets").resolve("init");
		if (!Files.isDirectory(data))
			throw new IllegalStateException("SOSGSGMP: the game's data has no folder " + data + " (game " + VERSION.VERSION_STRING + ")");
		GameFiles f = GameFilesReader.read(jarPath(VERSION.class), data, Paths.get("base").resolve("script"), mods);
		LOG.ln("SOSGSGMP: game files " + f.code() + ", read in " + (System.nanoTime() - t) / 1_000_000 + " ms; mods in load order: " + mods);
		return f;
	}

	private static long jarHash(Class<?> c) {
		try {
			byte[] jar = Files.readAllBytes(jarPath(c));
			return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(jar)).getLong();
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new IllegalStateException("SOSGSGMP: can't hash the jar of " + c.getName(), e);
		}
	}

	private static Path jarPath(Class<?> c) {
		try {
			return Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException("SOSGSGMP: can't find the jar of " + c.getName(), e);
		}
	}
}
