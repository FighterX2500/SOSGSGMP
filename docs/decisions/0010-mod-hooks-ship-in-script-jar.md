# 0010: The mod's hooks ship as patched classes in its own script jar

Status: Accepted (owner, 2026-11-16). Records the route every hook has shipped by since M1 (2026-10-25); each hook was approved on its own with its card · Builds on: SOSDET [ADR 0001](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/docs/decisions/0001-build-and-packaging.md) (the engine's build and packaging), its [SOS-25 spike](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/docs/archive/spikes/distribution.md) (how changed game classes reach players), [ADR 0001](0001-game-compiler-ecj.md) (ECJ), [ADR 0004](0004-engine-split.md) (the split)

## Context

Some multiplayer features need entry points the game's script API doesn't offer: the main menu's Multiplayer button, another player's row in the faction list, the escape menu's Save Campaign, and so on. AGENTS.md ("Hooks into Game Code") allows a small named hook in the game's own code as the last resort, and `docs/hooks.md` lists each one. Both said that how hooks reach players would be decided in an ADR, and that until then no hook is added without approval.

That ADR was never written. When M1 was added (SOS-49, 2026-10-25), the mod took over the engine's route, which SOS-25 had proven and SOSDET ADR 0001 had accepted, and every later hook, M2 to M14, was approved on its own with its card. This ADR records that route as the mod's decision.

The developer's conditions (owner, via the developers' Discord): the project stays a mod that the game's own launcher loads, never patches `SongsOfSyx.jar`, and is not sold. Shipping patched game classes inside the mod is allowed.

## Decision

**Each hook is a patch, and players get the compiled classes in the mod's jar.**

- **Patches.** Each hook is a unified diff in `mod/patches/`, named `M<n>-topic.patch`, against the game source shipped with V71 build 71.44 (`<game>/info/SongsOfSyx-sources`). The repository holds only the diffs, never game files.
- **Build** (`build.gradle.kts`, `applyPatches`): the build copies the game files the patches touch into `build/patched-src`, applies the patches with `git apply`, and compiles the changed files with ECJ 3.40.0 at `--release 16` with SOSDET's flags (`-g -preserveAllLocals -proc:none`). It fails when a patch no longer applies, leaves its file unchanged, or changes a file another of the mod's patches changes.
- **Shipping.** The classes compiled from the patched files go into the mod's script jar, `SOSGSGMP/V71/script/SOSGSGMP.jar`. The launcher puts an enabled mod's script jar on the class path ahead of `SongsOfSyx.jar`, so these classes replace the game's own (SOS-25). Players get them with the mod folder (README, "Playing"), and need no JDK, no game source and no build step.
- **One owner per game file.** The mod never patches a game file that SOSDET patches. Two copies of one class would leave the launcher's mod order to decide which runs, and one patch would be silently lost. Where the mod needs a change in a file the engine owns, the hook goes into a caller the mod may patch (M8, M9 and M13 do this), or into the engine. No build check enforces this yet: the mod's build doesn't read SOSDET's patch set. On 2026-11-16 none of the 20 files M1 to M14 touch is in SOSDET's patches.
- **Approval.** A new hook still needs the owner's approval, and a row in `docs/hooks.md` with its game file, what it calls in the mod, and why no API or adapter route was enough. A hook only calls into the mod; the logic stays in the mod, behind the adapter boundary.
- **Disabling the mod** gives the vanilla game again: the game folder is never changed.

## Options rejected

- **Patching `SongsOfSyx.jar`.** Breaks the developer's condition, and Steam's file check and updates would undo it.
- **Folding the mod's hooks into SOSDET's patch set.** The engine stays a general deterministic engine with no multiplayer UI (ADR 0004); menu and diplomacy screens don't belong in it.
- **Building on each player's PC.** Every player would need ECJ, the game source and a build step before the game starts, which a mod can't run itself (SOS-25 option C).
- **Changing classes at load time with a Java agent.** Needs a JVM argument on every PC and a bytecode library (SOS-25 option D). Not needed, since shipping patched classes is allowed.
- **Reflection only.** Reflection reaches private fields and methods (AGENTS.md, adapter layer), but it can't add a branch inside a game method, such as a click handler's `else` for a player faction. Where reflection is enough, it is used instead of a hook.

## Consequences

- **Pinned game build.** A patch is written against build 71.44. The build refuses another game jar, and the lobby refuses a PC whose game files differ (SOS-159). A game update means every patch has to be checked again.
- **Other mods.** A mod that ships its own copy of a class the mod patches clashes with it, and the launcher's order decides which runs. Spec §2 allows other mods only untested.
- **Menu safety.** The game's script scan loads every class in a script jar while it builds the main menu. A patched class whose static initializer touches game state crashes the menu, as in SOSDET.
- **The rule's wording.** AGENTS.md and `docs/hooks.md` point here instead of to a future ADR.
- **Open:** a build check that fails when a mod patch touches a file SOSDET's patch set owns.
