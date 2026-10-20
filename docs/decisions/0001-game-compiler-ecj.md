# 0001: Build game source with the Eclipse compiler (ECJ)

Status: Proposed · Date: 2026-10-20 · Card: [SOS-2](https://fighterx2500.atlassian.net/browse/SOS-2)

## Context

Hooks (AGENTS.md "Hooks into Game Code") change the game's own source, so we must recompile game classes. The rebuild check ([report](../plan/spikes/rebuild-check.md)) showed:

- The shipped `SongsOfSyx.jar` (V71, 0.71.44) was compiled with ECJ: `snake2d` and `integrations` at Java 8, the rest at Java 16, with unused locals kept.
- ECJ 3.40.0 with `-preserveAllLocals` rebuilds the shipped classes exactly: 9,345 of 9,371 are byte-identical, and the other 26 differ only in the order of compiler-generated bridge methods.
- `javac` also compiles the source, and the game runs, but it numbers anonymous classes differently (`PTech$3$1` becomes `PTech$1$1`) and changes 1,058 anonymous-class constructor signatures.

## Decision

Game source, original or patched by a hook, is compiled with **ECJ 3.40.0**:
- with `-g -preserveAllLocals -encoding UTF-8`;
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
