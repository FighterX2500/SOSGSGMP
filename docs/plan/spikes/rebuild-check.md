# Spike: rebuild check (SOS-2)

Date: 2026-10-20 · Card: [SOS-2](https://fighterx2500.atlassian.net/browse/SOS-2) · Code: `spikes/rebuild-check/`

## Question

Can we build the untouched V71 game source ourselves and get a game that runs exactly like the shipped one? Every hook (AGENTS.md "Hooks into Game Code") depends on this.

## Answer

Yes. The Eclipse compiler (ECJ) 3.40.0 with `-preserveAllLocals` rebuilds the shipped jar:

- the same 9,371 classes, with no missing or extra ones;
- 9,345 classes (99.7%) are **byte-identical**;
- the other 26 have the same members and the same method bodies, but ECJ wrote a few compiler-generated bridge methods in a different order (details below);
- the rebuilt game launches and reaches the main menu.

`javac` also compiles the source without errors, and that build runs (play-tested by the user), but it is not like-for-like with the shipped jar (see "javac" below).

## What the shipped jar is

| Fact | Value | How we know |
| --- | --- | --- |
| Game version | 0.71.44 | `game/VERSION.java`, main menu |
| Compiler | Eclipse ECJ | ECJ-only `$SWITCH_TABLE$` fields and `lambda$0` names in the shipped classes |
| Class version, `snake2d` and `integrations` | 52 (Java 8) | class file headers |
| Class version, all other game packages | 60 (Java 16) | class file headers |
| Unused locals | kept | `codegen.unusedLocal=preserve` in the source's `.settings/org.eclipse.jdt.core.prefs` |
| Packaging | Ant 1.10.15 on JDK 23.0.2 | `META-INF/MANIFEST.MF` in the source dump |

The jar also contains code with no source in the dump. The rebuild carries it over unchanged from the shipped jar:
- the `tools` package (72 developer tool classes, not referenced by the game source) and `lwjglExport`;
- libraries: LWJGL (`org/lwjgl`), steamworks4j 1.9.0 (`com/codedisaster`), java-discord-rpc (`club/`);
- native libraries and resources.

## Method

`rebuild.py` reads the game from `SOS_GAME_DIR` and never writes there. It:

1. extracts the library classes from the shipped jar to use as the classpath;
2. compiles in three units, in dependency order:
   - `snake2d` at `--release 8`, which depends only on LWJGL;
   - `src/integrations` at `--release 8`, which depends on steamworks4j and discord-rpc;
   - everything else at `--release 16`, on top of the first two;
3. copies the shader `.txt` resources from `snake2d`;
4. compares the result with the shipped jar: the class list, class versions, class headers (access, superclass, interfaces), declared members (name, descriptor, access), compiler-generated members, and raw bytes;
5. assembles `SongsOfSyx-rebuilt.jar`: the shipped jar with every entry from a package that has source replaced by the rebuild.

`diff_classes.py` disassembles each class that is not byte-identical with `javap -c -p` and writes a diff. It also checks whether the two versions hold the same set of member bodies once member order and constant-pool numbers are ignored.

`run-rebuilt.ps1` starts the game with the bundled JRE and `jvmargs-launcher.txt`, with the rebuilt jar on the classpath. The game passes `java.class.path` on to the process it starts, so the shipped `SongsOfSyx.jar` is never replaced.

## Results

| | javac (JDK 21.0.12) | ECJ 3.40.0, no extra flags | ECJ 3.40.0 `-preserveAllLocals` |
| --- | --- | --- | --- |
| Compile errors | 0 | 0 | 0 |
| Compile time (snake2d / integrations / game) | 3.4 / 0.7 / 22.9 s | 2.7 / 1.2 / 13.9 s | 2.7 / 1.2 / 13.1 s |
| Class versions match | yes | yes | yes |
| Classes missing / extra | 11 / 31 | 0 / 0 | 0 / 0 |
| Classes with different declared members | 1,173 | 0 | 0 |
| Byte-identical | 52 of 9,360 | 9,272 of 9,371 | **9,345 of 9,371** |
| Same output on 3 runs | not tested | not tested | yes, 0 files differ |
| Launches to main menu | yes (user play-tested further) | not run | yes |

### The 26 classes that still differ (ECJ `-preserveAllLocals`)

All 26 hold exactly the same set of methods with the same bytecode. Only the order of a few compiler-generated bridge methods differs: the methods ECJ adds when an override narrows the return type, for example `Rec.setDim(double, double)` returning `RecFacade` versus `RECTANGLEE`. Because the order differs, constant-pool numbers differ too.

The classes are:
- `snake2d/util/datatypes/Rec`, `RecShort`, `RecFacade`
- `snake2d/util/map/MAP_PLACER`
- `snake2d/util/sprite/text/Text`
- `util/gui/misc/GBox`
- 17 `settlement/room/**/ROOM_*` classes and `RoomBlueprint`
- `StatsPopulation$Demography`, `Terrain`, `WorldWater`

The JVM looks methods up by name and descriptor, never by position, so behaviour is the same. The most likely cause is a small ECJ version difference in how bridge methods are collected. Trying other ECJ releases (3.38 to 3.41) might reach 100%, but this spike doesn't need it.

### javac

`javac` produces a working game, but not the same classes:
- **Anonymous classes are numbered differently.** For example, `PTech$3$1` in the shipped jar is `PTech$1$1` in the javac build, so 11 shipped names are missing and 31 new ones appear.
- **Captured variables are handled differently.** javac drops captured variables that an anonymous class never uses from its constructor, while ECJ passes them all. This makes 1,058 constructor signatures differ.
- **Checking a hook gets harder.** A javac-built hook class can't be checked against the shipped class it replaces, and any game class whose anonymous inner classes it refers to would also need rebuilding.

## Run checks

- **javac build:** the user started it and reports that it works. Claude's smoke launch reached the 0.71.44 main menu (screenshot taken during the spike).
- **ECJ build:** Claude's smoke launch started `init.MainProcess` from `build\ecj\SongsOfSyx-rebuilt.jar` with the "Songs of Syx" window open. No exceptions were in the output and no new error log was written.
- **Not yet done for the ECJ build:** a play-test (new game, load a save, 10 minutes at top speed). Given 99.7% byte identity and an order-only difference in the rest, the risk is low.
- `%APPDATA%\songsofsyx\logs\UnhandledDump.txt` (16:24) is the normal shutdown dump from force-closing the javac smoke run, not a crash.

## What could not be reproduced

- **26 classes:** byte order of bridge methods (above).
- **Exact ECJ version:** unknown. 3.40.0 was picked because it matches the JDK 23 in the manifest.
- **The developer's Ant build:** its `build.xml` belongs to a different Eclipse project ("Integration") and points at `../Syx`, so it doesn't apply to this dump. Our script replaces it.

## Recommendation for the Gradle build

Build the game source with ECJ, as recorded in [ADR 0001](../../decisions/0001-game-compiler-ecj.md):

- an `ecj` Gradle configuration resolving `org.eclipse.jdt:ecj:3.40.0` from Maven Central as a **build-time tool only**, never packaged;
- a `JavaExec` task per unit (snake2d and integrations at `--release 8`, the rest at `--release 16`) with `-g -preserveAllLocals -encoding UTF-8`;
- a CI check that reruns this comparison: identical class list, no declared-member difference, at most the 26 known order-only classes;
- the mod's own code (`mod/`) keeps compiling with `javac --release 21`, as AGENTS.md says. Only patched game classes use ECJ.

## Follow-ups

- Hook distribution (planning task 5) can rely on this: a patched game class built with ECJ differs from the shipped class only where the hook changed it.
- The CI plan (planning task 6) needs the game source and libraries on the runner. The comparison runs headless in about 35 s.
