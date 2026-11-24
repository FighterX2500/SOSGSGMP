package menu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

import sosgsgmp.campaign.DataLayout;
import sosgsgmp.game.DataFolders;
import sosgsgmp.lobby.LobbyPrefs;
import sosgsgmp.log.RecorderStream;
import sosgsgmp.net.Message;
import sosgsgmp.report.HsErr;
import sosgsgmp.report.ReportOutbox;
import sosgsgmp.session.SetupSession;

/**
 * SOSGSGMP: reports (SOS-133). A joining PC sends the host, in the background, its playtest log and Steam log as they
 * grow, the game's crash files and the JVM's {@code hs_err} files (SOS-246) of the last week it hasn't sent before, and
 * each campaign save with its record. The host keeps them in {@code %APPDATA%/songsofsyx/sosgsgmp/reports} (SOS-155). {@code reports=false} in a PC's lobby settings
 * stops its sending.
 */
final class MpReports {

	/** Crash files older than this aren't sent: they belong to games long gone. */
	private static final long CRASH_AGE_MS = 7L * 24 * 3600 * 1000;

	private final SetupSession setup;
	private final MpLog log;
	private final DataLayout layout = DataFolders.layout();
	private boolean started;
	/** A joining PC's files on their way; null on the host, or while reports are off here. */
	private ReportOutbox outbox;

	MpReports(SetupSession setup, MpLog log) {
		this.setup = setup;
		this.log = log;
	}

	/** Every frame: once the game runs, the start, then the next piece within the budget. */
	void frame() {
		if (!setup.playing())
			return;
		if (!started)
			start();
		if (outbox == null)
			return;
		Path incident;
		while ((incident = log.takeIncident()) != null) // a copy of the recorder, after a desync, a freeze or an error (SOS-248)
			add(incident, () -> {
			});
		long now = System.nanoTime();
		for (int i = 0; i < 2; i++) { // the recorder stream's piece, then a log's or a file's (SOS-248)
			Message.Report piece = outbox.next(now);
			if (piece == null)
				break;
			setup.report(piece);
		}
	}

	private void start() {
		started = true;
		Path reports = layout.reports();
		if (setup.isHost()) {
			setup.reportsTo(reports);
			log.line("reports: joining PCs' logs and saves go to " + reports);
			return;
		}
		if (!on()) {
			log.line("reports: off on this PC (reports=false in its lobby settings)");
			return;
		}
		outbox = new ReportOutbox();
		if (log.recorder() != null) // this PC's own entries, as they come (SOS-248)
			outbox.stream(new RecorderStream(log.recorder()));
		outbox.follow(log.file());
		if (setup.network().startsWith("Steam")) {
			Path steam = newest(layout.logs(), "*-steam.log");
			if (steam != null)
				outbox.follow(steam);
		}
		Set<String> sent = sent(reports);
		long now = System.currentTimeMillis();
		int crashes = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(layout.gameLogs(), "error*.txt")) {
			for (Path f : files)
				if (!sent.contains(f.getFileName().toString()) && now - Files.getLastModifiedTime(f).toMillis() < CRASH_AGE_MS) {
					add(f, () -> markSent(reports, f.getFileName().toString()));
					crashes++;
				}
		} catch (IOException e) {
			// no crash files folder: nothing to send from it
		}
		crashes += hsErrs(reports, sent, now);
		setup.chat().notice("Your game sends its logs and saves to the host, for bug reports.");
		log.line("reports: this PC's logs, " + crashes + " crash files and its saves go to the host"
				+ (log.recorder() != null ? ", and its recorder's own entries as they come" : ""));
	}

	/**
	 * The JVM's fatal error files of the last week not sent before (SOS-246), without their environment. The JVM writes
	 * one in its working folder, the game's folder as the launcher starts the game on Windows, Linux and macOS alike (no
	 * {@code -XX:ErrorFile} in its arguments), or in the temp folder when it can't write there.
	 */
	private int hsErrs(Path reports, Set<String> sent, long now) {
		int n = 0;
		for (String dir : new String[] { System.getProperty("user.dir"), System.getProperty("java.io.tmpdir") }) {
			if (dir == null)
				continue;
			try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of(dir), HsErr.GLOB)) {
				for (Path f : files) {
					String name = f.getFileName().toString();
					if (sent.contains(name) || now - Files.getLastModifiedTime(f).toMillis() >= CRASH_AGE_MS)
						continue;
					try {
						outbox.add(name, HsErr.redact(Files.readAllBytes(f)), () -> markSent(reports, name));
						sent.add(name); // the temp folder may be the working folder
						n++;
					} catch (IOException | IllegalArgumentException e) {
						log.line("reports: " + name + " not sent: " + e.getMessage());
					}
				}
			} catch (IOException e) {
				log.line("reports: no hs_err files looked for in " + dir + ": " + e); // a folder that can't be listed
			}
		}
		return n;
	}

	/** A joining PC saved the campaign: the save and its record go to the host after what waits. */
	void saved(Path save, Path record) {
		if (outbox == null)
			return;
		add(save, () -> {
		});
		add(record, () -> {
		});
	}

	private void add(Path file, Runnable done) {
		try {
			outbox.add(file, done);
		} catch (UncheckedIOException | IllegalArgumentException e) {
			log.line("reports: " + file.getFileName() + " not sent: " + e.getMessage());
		}
	}

	/** Whether this PC's lobby settings let it send reports; on when they can't be read. */
	private boolean on() {
		try {
			return LobbyPrefs.load(layout.lobbyPrefsToRead()).reports;
		} catch (IOException e) {
			return true;
		}
	}

	private static Path newest(Path dir, String glob) {
		Path best = null;
		long bestTime = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
			for (Path f : files) {
				long t = Files.getLastModifiedTime(f).toMillis();
				if (best == null || t > bestTime) {
					best = f;
					bestTime = t;
				}
			}
		} catch (IOException e) {
			return null;
		}
		return best;
	}

	/** The crash files this PC sent in earlier sessions. */
	private static Set<String> sent(Path reports) {
		try {
			return new HashSet<>(Files.readAllLines(reports.resolve("sent.txt"), StandardCharsets.UTF_8));
		} catch (IOException e) {
			return new HashSet<>();
		}
	}

	private void markSent(Path reports, String name) {
		try {
			Files.createDirectories(reports);
			Files.writeString(reports.resolve("sent.txt"), name + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			log.line("reports: can't note " + name + " as sent: " + e);
		}
	}
}
