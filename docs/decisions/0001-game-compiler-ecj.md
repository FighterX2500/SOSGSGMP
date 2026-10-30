# 0001: Build game source with the Eclipse compiler (ECJ)

Status: Accepted (owner, 2026-10-30), built: SOSDET's build compiles its patches this way since SOS-26 (SOSDET [ADR 0001](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/docs/decisions/0001-build-and-packaging.md), accepted 2026-10-22), and so does the mod's for its hooks M1 to M9 (`mod/patches/`, [hooks.md](../hooks.md)) · Date: 2026-10-20, amended 2026-10-22 (`-proc:none`, [SOS-26](https://fighterx2500.atlassian.net/browse/SOS-26)) · Card: [SOS-2](https://fighterx2500.atlassian.net/browse/SOS-2)

## Context

Hooks (AGENTS.md "Hooks into Game Code") change the game's own source, so we must recompile game classes. The rebuild check ([report](../plan/spikes/rebuild-check.md)) showed:

- The shipped `SongsOfSyx.jar` (V71, 0.71.44) was compiled with ECJ: `snake2d` and `integrations` at Java 8, the rest at Java 16, with unused locals kept.
- ECJ 3.40.0 with `-preserveAllLocals` rebuilds the shipped classes exactly: 9,345 of 9,371 are byte-identical, and the other 26 differ only in the order of compiler-generated bridge methods.
- `javac` also compiles the source, and the game runs, but it numbers anonymous classes differently (`PTech$3$1` becomes `PTech$1$1`) and changes 1,058 anonymous-class constructor signatures.

## Decision

Game source, original or patched by a hook, is compiled with **ECJ 3.40.0**:
- with `-g -preserveAllLocals -encoding UTF-8 -proc:none` (`-proc:none` added by the amendment below);
- at the shipped class version per package: `--release 8` for `snake2d` and `integrations`, `--release 16` for the rest.

ECJ is a build-time tool only. It comes from Maven Central as `org.eclipse.jdt:ecj:3.40.0` and is never packaged into the mod or shipped to players.

The mod's own code (`mod/`) is compiled with `javac --release 21`, as AGENTS.md says.

## Options rejected

- **javac for game classes.** Its class names and constructor signatures don't match the shipped classes, so a patched class can't be checked against the original. Anything that names or refers to anonymous inner classes would need a full rebuild, too.
- **Another ECJ release, to chase the last 26 classes.** It isn't needed: the remaining difference is method order, which doesn't change behaviour. We can revisit it if we ever need a 100% byte-identical check.
- **Binary patching (bytecode manipulation, no recompile).** It would add a third-party library (for example ASM) and makes hooks harder to review than a source diff. The source is available, so recompiling is simpler.

## Consequences

- The Gradle build needs an `ecj` configuration and a `JavaExec` compile task per unit. CI downloads ECJ from Maven Central.
- CI can verify any build of the game source against the shipped jar with the spike's comparison: same class list, no declared-member difference, and only the 26 known order-only classes may differ.
- This ADR doesn't decide how patched classes reach players. That is planning task 5 (distribution).

## Amendment (2026-10-22, SOS-26)

The engine's build (SOSDET) found where the last 26 classes come from.

- **The 26 differing classes came from ECJ's annotation processing pass.** Without `-proc:none`, ECJ runs it and writes compiler-generated bridge methods in a different order in those classes. With `-proc:none`, the rebuild of the untouched source is **byte-identical to the shipped jar: 9,371 of 9,371 classes**. A/B runs in the SOSDET build confirmed it was this flag and not `-proceedOnError` or the order of the source files.
- **Decision:** `-proc:none` joins the flags above.
- **"Another ECJ release, to chase the last 26 classes"** is no longer needed: ECJ 3.40.0 matches every class.
- **Consequences change:**
  - the rebuild check demands byte identity for every class, with no list of allowed differences;
  - ECJ runs inside the build's JDK through its `BatchCompiler` API rather than a `JavaExec` task per unit (`--release` needs the JDK's `ct.sym`).
- **Where this lives now:** the engine's build applies all of this ([SOSDET ADR 0001](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/docs/decisions/0001-build-and-packaging.md)). It also records how patched classes reach players: inside a mod's script jar, which the launcher puts ahead of `SongsOfSyx.jar` ([SOS-25](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/docs/plan/spikes/distribution.md)).
