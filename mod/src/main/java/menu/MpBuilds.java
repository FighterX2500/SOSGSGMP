package menu;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import game.VERSION;
import sosdet.Sosdet;
import sosgsgmp.lobby.Builds;

/** SOSGSGMP: this PC's builds, read from the running game and the jars it loaded (SOS-128). */
final class MpBuilds {

	private MpBuilds() {
	}

	static Builds local() {
		return new Builds(VERSION.VERSION_STRING, jarHash(Sosdet.class), jarHash(MpBuilds.class));
	}

	private static long jarHash(Class<?> c) {
		try {
			byte[] jar = Files.readAllBytes(Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI()));
			return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(jar)).getLong();
		} catch (URISyntaxException | IOException | NoSuchAlgorithmException e) {
			throw new IllegalStateException("SOSGSGMP: can't hash the jar of " + c.getName(), e);
		}
	}
}
