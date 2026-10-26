// SOSGSGMP build (SOS-49).
//
// Reads the game from SOS_GAME_DIR and never copies game files into the
// repository. Output: build/mod/SOSGSGMP, a Songs of Syx mod folder whose jar
// holds the mod's classes and the game classes compiled from the mod's hooks
// (mod/patches, listed in docs/hooks.md). The launcher puts a mod's script jar
// ahead of SongsOfSyx.jar, so those classes replace the game's own; the engine
// (SOSDET) ships its patches the same way and never touches the same files.

import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    java
}

version = "0.1.0"

repositories {
    mavenCentral()
}

sourceSets {
    main { java.setSrcDirs(listOf("mod/src/main/java")) }
    test { java.setSrcDirs(listOf("mod/src/test/java")) }
}

// ---------------------------------------------------------------- the game

/** SHA-256 of SongsOfSyx.jar, V71 build 71.44: the one game build the mod supports (AGENTS.md Project Facts). */
val pinnedGameJarSha256 = "fe0137c05408e5ee9e31f06fc44fe37a5d4372d9c985b280f0852d1347c0224b"

fun gameDir(): File {
    val path = providers.environmentVariable("SOS_GAME_DIR").orNull
        ?: throw GradleException("SOSGSGMP: set SOS_GAME_DIR to the Songs of Syx folder (the one that holds SongsOfSyx.jar)")
    return file(path)
}

fun gameJar(): File = gameDir().resolve("SongsOfSyx.jar")

val verifyGameJar by tasks.registering {
    group = "verification"
    description = "Checks that SOS_GAME_DIR holds the pinned game build."
    doLast {
        val jar = gameJar()
        if (!jar.isFile) throw GradleException("SOSGSGMP: no SongsOfSyx.jar in $jar")
        val sha = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
        if (sha != pinnedGameJarSha256) {
            throw GradleException("SOSGSGMP: $jar is not Songs of Syx V71 build 71.44 (SHA-256 $sha, expected $pinnedGameJarSha256)")
        }
    }
}

// ---------------------------------------------------------------- the engine

/**
 * SOSDET's built mod jar, compile-only like the game jar and never shipped: players install SOSDET as its own mod
 * (ADR 0007). SOSDET_DIR names the SOSDET repository; by default it sits next to this one.
 */
fun engineJar(): File {
    val dir = providers.environmentVariable("SOSDET_DIR").orNull?.let { file(it) } ?: rootDir.resolve("../SOSDET")
    return dir.resolve("build/mod/SOSDET/V71/script/SOSDET.jar")
}

val verifyEngineJar by tasks.registering {
    group = "verification"
    description = "Checks that the SOSDET jar the mod compiles against exists."
    doLast {
        val jar = engineJar()
        if (!jar.isFile) {
            throw GradleException("SOSGSGMP: no SOSDET jar at $jar; build SOSDET first (gradlew.bat assemble there), or set SOSDET_DIR")
        }
    }
}

val ecjTool: Configuration by configurations.creating

dependencies {
    ecjTool("org.eclipse.jdt:ecj:3.40.0")
    compileOnly(files({ gameJar() }))
    compileOnly(files({ engineJar() }))
    // the engine's plain-Java types (DealBook) in the mod's view-model tests; the game jar stays out of them
    testImplementation(files({ engineJar() }))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.compileJava {
    dependsOn(verifyGameJar, verifyEngineJar)
}

// ---------------------------------------------------------------- hooks (docs/hooks.md)

val patchesDir = layout.projectDirectory.dir("mod/patches")
val patchedSourceDir = layout.buildDirectory.dir("patched-src")
val gamePatchClassesDir = layout.buildDirectory.dir("classes/game-patches")

fun patchFiles(): List<File> =
    patchesDir.asFile.listFiles { f -> f.name.endsWith(".patch") }?.sortedBy { it.name } ?: emptyList()

/** Game files a patch changes, relative to the source root (its "+++ b/..." lines). */
fun touchedFiles(patch: File): List<String> = patch.readLines()
    .filter { it.startsWith("+++ ") }
    .map { line ->
        val path = line.removePrefix("+++ ").substringBefore('\t').trim()
        if (!path.startsWith("b/")) throw GradleException("SOSGSGMP: ${patch.name}: '$path' needs an a/ b/ path relative to the game source")
        path.removePrefix("b/")
    }

val applyPatches by tasks.registering {
    description = "Copies the game files the hooks touch to build/patched-src and applies mod/patches there."
    dependsOn(verifyGameJar)
    inputs.files(fileTree(patchesDir) { include("*.patch") })
    outputs.dir(patchedSourceDir)
    doLast {
        val src = gameDir().resolve("info/SongsOfSyx-sources")
        val dest = patchedSourceDir.get().asFile
        dest.deleteRecursively()
        dest.mkdirs()
        val owner = sortedMapOf<String, String>()
        for (patch in patchFiles()) {
            for (path in touchedFiles(patch)) {
                owner.put(path, patch.name)?.let { throw GradleException("SOSGSGMP: $path is changed by both $it and ${patch.name}") }
                val from = src.resolve(path)
                if (!from.isFile) throw GradleException("SOSGSGMP: ${patch.name} changes $path, which is not in the game source $src")
                from.copyTo(dest.resolve(path))
            }
        }
        for (patch in patchFiles()) {
            val builder = ProcessBuilder("git", "apply", "--whitespace=nowarn", patch.absolutePath)
                .directory(dest).redirectErrorStream(true)
            // build/ lies inside this repository; inside a repository git apply skips paths that aren't the
            // repository's own, without an error. Stop git from finding it, so the patch applies to dest.
            builder.environment()["GIT_CEILING_DIRECTORIES"] = dest.parentFile.absolutePath
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw GradleException("SOSGSGMP: ${patch.name} no longer applies to the game source:\n$output")
        }
        for ((path, patch) in owner) {
            if (dest.resolve(path).readBytes().contentEquals(src.resolve(path).readBytes())) {
                throw GradleException("SOSGSGMP: $patch left $path unchanged; it did not apply")
            }
        }
    }
}

/** ECJ 3.40.0 with the flags the engine uses for game source (SOSDET ADR 0001), run inside the build JVM. */
fun ecj(release: String, classpath: List<File>, sources: List<File>, dest: File) {
    if (sources.isEmpty()) return
    dest.mkdirs()
    val args = listOf("--release", release, "-g", "-preserveAllLocals", "-encoding", "UTF-8", "-nowarn", "-proc:none",
        "-cp", classpath.joinToString(File.pathSeparator) { it.absolutePath }, "-d", dest.absolutePath) +
        sources.map { it.absolutePath }
    val log = StringWriter()
    URLClassLoader(ecjTool.files.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
        val compiler = loader.loadClass("org.eclipse.jdt.core.compiler.batch.BatchCompiler")
        val progress = loader.loadClass("org.eclipse.jdt.core.compiler.CompilationProgress")
        val compile = compiler.getMethod("compile", Array<String>::class.java, PrintWriter::class.java, PrintWriter::class.java, progress)
        val out = PrintWriter(log)
        val ok = compile.invoke(null, args.toTypedArray(), out, out, null) as Boolean
        out.flush()
        if (!ok) throw GradleException("SOSGSGMP: ECJ failed at --release $release:\n$log")
    }
}

fun classesUnder(dir: File): Map<String, File> =
    if (!dir.isDirectory) emptyMap()
    else dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.associateBy { it.relativeTo(dir).invariantSeparatorsPath }

val compileGamePatches by tasks.registering {
    description = "Compiles the patched game files with ECJ at the shipped class level (16), against the game and the mod."
    dependsOn(applyPatches, tasks.compileJava)
    inputs.dir(patchedSourceDir)
    inputs.files(sourceSets.main.get().output.classesDirs)
    outputs.dir(gamePatchClassesDir)
    doLast {
        val out = gamePatchClassesDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val sources = patchedSourceDir.get().asFile.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.toList()
        ecj("16", sourceSets.main.get().output.classesDirs.files.toList() + gameJar(), sources, out)
        // Ship only the classes a hook changed; the game's own copies serve for the rest (as in SOSDET).
        ZipFile(gameJar()).use { jar ->
            classesUnder(out).forEach { (name, file) ->
                val entry = jar.getEntry(name)
                if (entry != null && jar.getInputStream(entry).use { it.readBytes() }.contentEquals(file.readBytes())) file.delete()
            }
        }
    }
}

// ---------------------------------------------------------------- the mod

/**
 * Mod classes live in package sosgsgmp; the adapter classes that need package-private game members sit in the game's
 * packages, named Mp*: menu (the lobby screens) and view/world/generator (the map step's panel).
 */
fun isModClass(path: String) = path.startsWith("sosgsgmp/") || path.startsWith("menu/Mp") || path.startsWith("view/world/generator/Mp")

tasks.jar {
    description = "The mod's script jar: mod classes plus the game classes compiled from the hooks."
    dependsOn(compileGamePatches)
    archiveFileName.set("SOSGSGMP.jar")
    from(gamePatchClassesDir)
    manifest {
        attributes("Implementation-Title" to "SOSGSGMP", "Implementation-Version" to project.version)
    }
    doFirst {
        sourceSets.main.get().output.classesDirs.forEach { dir ->
            classesUnder(dir).keys.filterNot(::isModClass).takeIf { it.isNotEmpty() }?.let {
                throw GradleException("SOSGSGMP: mod classes outside sosgsgmp/, menu/Mp* and view/world/generator/Mp*: $it")
            }
        }
    }
}

val checkJar by tasks.registering {
    group = "verification"
    description = "Checks that the mod jar holds only mod classes and the hooked game classes (AGENTS.md packaging rule)."
    dependsOn(tasks.jar)
    doLast {
        val hooked = classesUnder(gamePatchClassesDir.get().asFile).keys
        ZipFile(tasks.jar.get().archiveFile.get().asFile).use { jar ->
            val wrong = jar.entries().asSequence().map { it.name }
                .filter { it.endsWith(".class") && !isModClass(it) && it !in hooked }.toList()
            if (wrong.isNotEmpty()) throw GradleException("SOSGSGMP: unexpected classes in the mod jar: $wrong")
        }
    }
}

tasks.check {
    dependsOn(checkJar)
}

val modInfo by tasks.registering {
    description = "Writes the mod's _Info.txt."
    val file = layout.buildDirectory.file("mod-info/_Info.txt")
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.file(file)
    doLast {
        file.get().asFile.writeText(
            """
            VERSION: "$version",
            NAME: "SOS GSG MP",
            DESC: "Multiplayer for Songs of Syx V71: each player runs their own city on one shared world map. Needs SOSDET.",
            AUTHOR: "FighterX2500",
            INFO: "https://github.com/FighterX2500/SOSGSGMP",
            """.trimIndent() + "\n")
    }
}

val modDir = layout.buildDirectory.dir("mod/SOSGSGMP")

val assembleMod by tasks.registering(Sync::class) {
    group = "build"
    description = "Assembles the mod folder build/mod/SOSGSGMP (_Info.txt, V71/script/SOSGSGMP.jar)."
    from(modInfo)
    from(tasks.jar) { into("V71/script") }
    into(modDir)
}

tasks.assemble {
    dependsOn(assembleMod)
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------- dev install

fun installedModDir(): File {
    val appData = providers.environmentVariable("APPDATA").orNull ?: throw GradleException("SOSGSGMP: APPDATA is not set")
    return file(appData).resolve("songsofsyx/mods/SOSGSGMP")
}

val installMod by tasks.registering(Sync::class) {
    group = "dev"
    description = "Copies the mod to %APPDATA%\\songsofsyx\\mods\\SOSGSGMP. Enable it in the game's launcher."
    from(assembleMod)
    into({ installedModDir() })
}

val uninstallMod by tasks.registering(Delete::class) {
    group = "dev"
    description = "Removes the mod from %APPDATA%\\songsofsyx\\mods\\SOSGSGMP."
    delete({ installedModDir() })
}
