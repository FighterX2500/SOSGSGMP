# 0004: The deterministic engine is a separate project (SOSDET)

Status: Accepted (owner decision, 2026-10-22) for "Decision"; the items under "Proposed" are open until the cards named there settle them · Card: [SOS-24](https://fighterx2500.atlassian.net/browse/SOS-24)

## Context

Lockstep battles ([ADR 0003](0003-battle-lockstep.md)) need the game's simulation to be bit-identical on every PC. Spikes SOS-11 and SOS-23 ([report](../plan/spikes/determinism.md), [report](../plan/spikes/city-determinism.md)) got there with 14 determinism patches to our rebuild of the game (plus 2 that only serve the test rig: timing and autoload), a test rig and a set of rules. That work has different needs from the multiplayer mod:

- It is proven by replaying saves and logs in several game copies, with no network. The mod is proven with players, the network and the world map.
- It changes when the game build changes or a new desync cause turns up. The mod changes when features change.
- It follows the determinism rules; the mod follows the general coding rules.

The owner decided to split it off and build it before the multiplayer work continues.

## Decision

- **Two repositories.** The deterministic engine is its own project in its own private repository, [FighterX2500/SOSDET](https://github.com/FighterX2500/SOSDET). The multiplayer mod stays in this repository and uses the engine only through its API.
- **The engine owns:**
  - its changes to the game's own code: hook points B7–B11 and B15 in [hook-surface.md](../plan/hook-surface.md), and the city-side fixes from SOS-23 (random tile tables, ice setting, serial killer camera, loads and graphics-thread jobs in the simulation);
  - the engine code those hooks call, and the API points B12–B14 (deploy as step 0, the state hash, loading a battle save): the fixed step, the two random streams, the battle jobs inside the step, the AI general on the host and its log, the state hash and desync report, save and load at a step;
  - its build (game source patched and compiled with ECJ, as proposed in [ADR 0001](0001-game-compiler-ecj.md)), its tests and its scenario suite;
  - the determinism rules (moved from `.claude/rules/determinism.md` to SOSDET) and its own requirements file.
- **The mod owns:** Steam P2P, the lobby, the world map, diplomacy, trade, sieges, city viewing, and carrying the engine's data over the network: orders, the host's AI log, state hashes and resync saves. It never simulates battle state itself.
- **Order of work.** The engine is built now (cards SOS-25 to SOS-32, under the epic [SOS-22](https://fighterx2500.atlassian.net/browse/SOS-22) on the same Jira board, with `SOS-N` keys in SOSDET branch names and PR titles). Multiplayer work continues after it; the mod stays in its planning phase.

## Proposed (not decided)

- **API.** A narrow, versioned Java API in the engine's own package:
  - the current step, and the last step the engine may run (the mod holds every PC to the slowest one);
  - apply an order at a given step;
  - the seed;
  - host or peer mode; the host's AI log entries out, and entries in on every PC;
  - the state hash at a step;
  - save and load at a step.

  Its final shape is settled in SOS-27 to SOS-30, as each part is built.
- **Releases.** The engine is released by tag and the mod pins one release. What a release contains (patches only, or a patched jar with game classes) depends on how the engine reaches players: SOS-25, then the distribution ADR (SOS-18).
- **The mod's own hooks** (world map, factions, saves: the A, F, S and other points that have nothing to do with determinism) stay in this repository as a second patch set, applied on top of the engine's.
- **B1–B6** (battle start and end) aren't assigned yet. B3 (no AI general for a human army) and B6 (the player's army) touch code that runs inside the step, so each goes to the engine, or stays with the mod under the engine's rules. Settled when SOS-14 is picked up.

## Options rejected

- **One repository with two modules.** One CI and one place for the API, but the owner chose two repositories so each project has its own needs, rules and releases.
- **Keep the engine inside the mod.** The determinism work would stay tied to the mod's planning phase, and the engine's patches would be mixed with the mod's own hooks.

## Consequences

- **Two projects follow one game build.** Both follow the pinned game build (spec §3). An API change needs an engine change and a mod change together.
- **The mod's build needs the engine.** Where the mod's CI gets it from is settled with the test plan (SOS-19).
- **Patch order.** If the mod's patches go on top of the engine's, then where both touch one file (for example `GAME.java`: A1 and B7) the mod's patch changes when the engine's does.
- **Docs.**
  - ADR 0003 stays here: it says what multiplayer battles do. SOSDET implements the engine parts of it.
  - `docs/hooks.md` here lists only the mod's hooks; SOSDET has its own.
  - The spike reports and `spikes/determinism/` stay here as history. Spike code is not moved as-is; SOSDET writes the engine again (SOS-27).
- **Distribution covers both.** SOS-18 now has to cover the engine's patches and the mod's together, with SOS-25 as its engine half.
- **Mod code inside the step.** Any mod hook or code that runs inside the step or feeds the state hash follows SOSDET's determinism rules (AGENTS.md "Determinism").
- **Spec 0.10.** §13 and §14 name the engine as the project behind the battle sync targets.
