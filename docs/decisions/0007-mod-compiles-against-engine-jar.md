# 0007: The mod compiles against the engine's jar

Status: Accepted (owner decision, 2026-10-25) · Card: [SOS-56](https://fighterx2500.atlassian.net/browse/SOS-56) · Settles the build side of [ADR 0004](0004-engine-split.md)'s "the mod uses the engine only through its API"

## Context

- [ADR 0004](0004-engine-split.md) put the deterministic engine in its own repository, SOSDET, and said the mod uses it only through its API (package `sosdet`, class `Engine`). It left open how the mod's build reaches that API. Releases by tag, pinned by the mod, wait for the distribution decision (SOS-25, SOS-18).
- Until SOS-56 the mod made one engine call, `Engine.active()`, through reflection in `WorldGenAdapter`.
- SOS-56 (the setup) calls `Engine.buildWorld` with the engine's own `HumanStart` objects, `locationProblem`, `bannerBytes` and the session start (`begin`, `ownCity`, `localHuman`, `pcHuman`). SOS-57 (the game over the network) calls the AI log, orders, reports and hashes. Reflection for all of it would be a lot of code with no compiler checks.

## Decision

- The mod compiles against SOSDET's built mod jar, `build/mod/SOSDET/V71/script/SOSDET.jar` in the SOSDET repository, as a `compileOnly` dependency, like the game jar.
- The build finds the repository through the `SOSDET_DIR` environment variable; without it, in `../SOSDET` next to this repository. `verifyEngineJar` fails the build with a clear message when the jar is missing.
- The engine's classes never go into the mod's jar. Players install SOSDET as its own mod, ahead of SOSGSGMP, as today.
- Every call into the engine goes through the mod's adapter layer (`sosgsgmp.game`), the same as calls into the game. Plain Java classes in the mod never import `sosdet`, so the unit tests run without the engine.
- An engine API change needs a SOSDET change and a mod change together, as ADR 0004 says. Until SOSDET has tagged releases, the mod compiles against the engine's main branch, built locally.

## Options rejected

- **Reflection only.** No build coupling, but every engine method would be looked up by name, with no compile errors when the API changes. SOS-57 alone would need dozens of handles.
- **A copy of the engine's API classes in this repository.** Two copies of one API that would drift apart.
- **Shading the engine into the mod.** The engine ships game classes compiled from its patches; shading would put them in two jars, and SOSDET is meant to stay a separate mod for other modders.
