# AGENTS.md

Guidance for coding agents working on this Songs of Syx mod. These are the general coding rules; `CLAUDE.md` adds the project-specific workflow, and `docs/spec.md` defines what the mod must do.

## Project Facts
Fill these in and keep them current. Agents must not guess them.

*   **Target game version:** `V71` (build 71.44, from `game/VERSION.java`), the version in the working folder, pinned (spec section 3). Mod content lives under `<mod>/V71/`.
*   **Platform:** Windows only. Use `gradlew.bat` and Windows paths.
*   **Game folder (`<game>`):** outside the repository, located through the `SOS_GAME_DIR` environment variable. On the main dev PC: `C:\ClaudeFolder\Songs of Syx`. Nothing under it is ever committed.
*   **JDK / bytecode level:** compile with `--release 21`, matching the JRE bundled with the game (`<game>/jre/release`: Temurin 21.0.11). Do not raise it. Building needs JDK 21 or newer. Note: the shipped `SongsOfSyx.jar` itself is compiled at class version 60 (Java 16); rebuilds of the game's own source use `--release 16` so they match it.
*   **Game jar:** `<game>/SongsOfSyx.jar`, referenced as `compileOnly` (Gradle) / `provided` (Maven). Never shade, copy, or commit it. It already contains steamworks4j 1.9.0 and the Steam native libraries.
*   **Game source:** `<game>/info/SongsOfSyx-sources/`, shipped with the game. Read it freely; never copy or commit it. Changes to it go through hooks only (see "Hooks into Game Code").
*   **Script API reference:** `<game>/info/scriptGuide.odt`; example scripts in `<game>/base/script/`.
*   **Game source index:** codegraph (`npm i -g @colbymchenry/codegraph`, telemetry off). Build it once per PC with `codegraph init -y "%SOS_GAME_DIR%\info\SongsOfSyx-sources"`; the index (`.codegraph/`, ~260 MB) stays in the game folder and is never committed. Claude Code reaches it through `.mcp.json` (tool `codegraph_explore`; CLI: `codegraph callers|callees|impact|node <symbol>` run in the source folder). Use it first to find definitions and callers; confirm with grep when a complete list matters (for example, counting every call site).
*   **Build:** `gradlew.bat build` (Gradle not set up yet).
*   **Fast check:** `gradlew.bat compileJava compileTestJava` (Gradle not set up yet).
*   **Tests:** `gradlew.bat test` (Gradle not set up yet).
*   **Install for manual testing:** `gradlew.bat installMod` → copies the jar and assets to `%APPDATA%\songsofsyx\mods\<ModName>\` (Gradle and mod name not set up yet).
*   **Game log / crash output:** `%APPDATA%\songsofsyx\logs\` (error files `error<date>.txt`); also `<game>/errorLogs/`.

## Design Review: KISS, DRY, SRP, Cohesion, and Coupling
Apply these criteria to the touched behavior before declaring a change complete. Explain concrete tradeoffs when a criterion is only partially met; passing tests alone does not establish design quality.

*   **KISS:** Prefer direct control flow and the smallest existing API that expresses the requirement. Use early returns and named methods to keep the main flow readable. Do not introduce interfaces, abstract base classes, generic type parameters, builders, factories, or configuration solely for hypothetical reuse. If the behavior can be expressed in the game's data files (`.txt` assets), prefer that over Java code.
*   **DRY:** Give each business rule and side effect one authoritative implementation. Multiple triggers for the same operation must use the same execution entry point. Similar-looking code with different semantics does not justify a shared abstraction.
*   **SRP:** Keep input recognition, interaction ownership, and domain execution separate. UI/render code translates accepted input into semantic requests; domain owners manage state transitions, per-tick updates, and persistence. Extract a coherent responsibility rather than merely moving a long block into a generic helper or `*Utils` class.
*   **High Cohesion:** Keep an operation's state, lifecycle, invariants, and focused tests with its owner. Avoid scattering one behavior's flags and completion rules across unrelated classes or static fields.
*   **Low Coupling:** Call narrow semantic APIs instead of reaching into another subsystem's internals. Pass the state the operation needs; avoid passing the entire game/settlement object or adding a package dependency cycle. All contact with game internals goes through the adapter layer (see below).
*   **Behavioral Evidence:** For a new trigger, test the flow from representative input through the real command or state boundary. Assert its target, exactly-once behavior where required, and the absence of conflicting side effects. Exercise save/load round-trips whenever persisted state changes. Predicate and serialization tests supplement this coverage.
*   **Scope Discipline:** Improve boundaries required by the change without redesigning adjacent systems. Distinguish pre-existing debt from new coupling. Report remaining material gaps specifically instead of claiming blanket compliance with these principles.

## Surgical Changes
Touch only what you must. Clean up only your own mess.

*   **Minimal Impact:** Don't "improve" adjacent code, comments, or formatting. Don't refactor things that aren't broken. Don't run a formatter or "optimize imports" over whole files.
*   **Style Match:** Match existing style, even if you'd do it differently (naming, `final` usage, brace style, import ordering, wildcard vs. explicit imports).
*   **Cleanup Scope:** Remove imports/fields/methods that YOUR changes made unused. Don't remove pre-existing dead code unless asked.
*   **Traceability:** Every changed line should trace directly to the user's request or the Jira card being worked on.

## Goal-Driven Execution
Define success criteria. Loop until verified.

*   **Verifiable Goals:** Transform tasks into verifiable goals (e.g., "Add validation" → "Write tests for invalid inputs, then make them pass").
*   **Reproduce First:** For bugs, write a test that reproduces it before fixing. If the bug only reproduces in-game, state the manual reproduction steps and what you could not verify.
*   **Stepwise Verification:** For multi-step tasks, state a brief plan with verification steps for each part.
*   **Build Verification:** Always build after changes. Run the fast check, then the tests, then the full build. A change is not done while there are compiler errors or new warnings in touched files.

## Java Rules

### Language and API
*   Stay within the configured `--release` level. Do not use language features or JDK APIs newer than the game's bundled JRE, even if your local JDK has them.
*   Always use `@Override`. No raw types. No unchecked casts without a one-line justification and the narrowest possible `@SuppressWarnings`.
*   Use the narrowest visibility that works: `private` first, then package-private. Do not widen visibility just to make something testable; move the logic into a testable class instead.
*   Prefer `final` fields and immutable value objects for data passed between layers.
*   Return empty collections, not `null`. Where game APIs can return `null`, check at the adapter boundary and do not let `null` leak into mod domain code.
*   Implement `equals`/`hashCode` together, and only when the type is used as a key or compared by value.
*   Use try-with-resources for anything closeable.

### Exceptions and Logging
*   Do not swallow exceptions. No empty `catch` blocks; no `catch (Throwable)` or `catch (Exception)` except at a single top-level boundary that logs with context and rethrows or disables the feature deliberately.
*   Fail fast on programmer errors (invalid arguments, broken invariants) with a message naming the offending value.
*   Prefix all log output with the mod name. Never log inside per-frame/per-tick code paths except behind a one-shot guard.

### Performance in Hot Paths
Code called every tick/frame or per entity (`update(double ds)`, per-humanoid, per-tile, render) must:
*   Not allocate: no `new`, no autoboxing, no varargs, no string concatenation, no lambdas/streams/iterators that allocate. Reuse preallocated arrays and buffers.
*   Not perform reflection lookups, file I/O, or logging.
*   Prefer the game's own collections and primitive arrays when iterating game data.
Outside hot paths, readability wins; don't micro-optimize setup or UI-action code.

### State and Lifecycle
*   No mutable `static` state. A game session (new game / load save) can start several times in the same JVM; static fields leak state between sessions. Per-session state belongs to the script instance and is created in `createInstance()`.
*   Constants may be `static final` only if truly immutable.
*   All game state is touched from the game thread only. Do not start threads or executors without explicit approval.
*   Approved exception: networking may use its own I/O thread (Steam callbacks, socket reads). That thread only decodes incoming messages and puts them on a thread-safe queue. The game thread drains the queue at a tick boundary and makes every state change itself.

## Songs of Syx Rules

### Adapter Boundary
*   Isolate every use of game classes behind a thin adapter package (e.g. `…/game/`). Domain logic in other packages must not import game classes, so it can be unit-tested as plain Java.
*   Game classes often have heavy static initialization and cannot be constructed or mocked outside a running game. Do not attempt to mock them; test the domain logic directly and keep adapters too simple to need unit tests.
*   Reflection into private game members is allowed only inside the adapter layer, in one place per member. Resolve `Field`/`Method` handles once at init, not per call, and fail with a clear message naming the member and the targeted game version.

### Hooks into Game Code
Multiplayer needs entry points the game's script/mod API may not offer. Use, in this order of preference:
1.  The game's script/mod API.
2.  Reflection through the adapter layer (above).
3.  A change to the game's own code, as a small named hook.

A hook only calls into the mod (for example `isLocalPlayer(f)` asking the multiplayer module); the logic stays in the mod, behind the adapter boundary. Every hook is listed in `docs/hooks.md` with the game file it touches and why no API or adapter route was enough. How hooks are distributed to players is decided in an ADR; until then, do not add one without approval.

### Determinism
Code that runs in lockstep lives in the deterministic engine, [SOSDET](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine), and follows its determinism rules ([ADR 0004](docs/decisions/0004-engine-split.md)). In v1 that is field battles (ADR 0003); siege assaults are still open. The mod never simulates battle state itself: it carries orders, the host's AI log, state hashes and resync saves, and talks to the engine only through its API.
*   Any mod code or hook that runs inside the battle step or feeds the state hash (for example B3 and B6 in the hook map) follows SOSDET's determinism rules. It never feeds a per-PC value, such as which faction is the local player, into the step.
*   Orders, AI log entries and resync saves are applied at the step the host confirmed, never on arrival.

### Persistence (Save/Load)
*   `save` and `load` must write and read the same fields in exactly the same order. Change them together, in the same commit.
*   Persisted format changes require a format version number written first, and `load` must handle every previous version still in circulation.
*   Every change to persisted state needs a round-trip test: save → load → assert equal state. Also consider loading a save created before the mod (or this feature) was installed.
*   Never persist object references, indices, or IDs whose meaning can change between sessions or game versions without a stable lookup key.
*   These rules also apply to multiplayer data: the campaign save (world save plus every player's city save) and network messages. Every network message type carries a protocol version. Never use Java built-in serialization (`ObjectOutputStream`) for saves or network data.
*   Every message that changes shared state carries the world tick it applies at.
*   Anything that moves goods, soldiers or money between a city and the world carries a transaction ID and is applied exactly once; test that a repeated message changes nothing.

### Dependencies and Packaging
*   No third-party runtime libraries unless explicitly approved. Propose each one (for example steamworks4j for Steam P2P) in an ADR under `docs/decisions/`. If approved, shade and relocate it to avoid classloader conflicts with the game.
*   The built mod jar must contain only this mod's classes, resources and approved shaded libraries, never game classes. Verify jar contents after packaging changes.
*   Data files under `assets/` follow the game's own text format; match the existing keys and structure of the game's vanilla files exactly, and verify them in-game since the compiler cannot check them.

## Reporting
When finishing, report: what changed and why, how it was verified (commands run and results), what was verified only by reasoning or needs manual in-game testing, which `docs/spec.md` sections and acceptance criteria the work touched, and any remaining gaps against the principles above.
