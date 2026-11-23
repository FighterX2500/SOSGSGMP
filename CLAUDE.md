# SoS GSG MP

Multiplayer mod for Songs of Syx: each player runs their own city-state on one shared world map, with diplomacy, trade, real-time battles and sieges between players.

@AGENTS.md

AGENTS.md holds the coding rules: Java, the game, hooks, threads, saves, network data, dependencies. This file holds the project workflow: where things live, the current phase, how to work with me. The two don't overlap; if they ever seem to conflict, stop and ask.

## Source of truth

- `docs/spec.md`: what the mod must do. Read the relevant section before any planning or feature work. Never change a rule in it without asking me first; when I approve a change, update the spec in the same PR.
- `docs/architecture.md`: the architecture as built. The choices behind it are recorded in the ADRs.
- `docs/decisions/`: ADRs, one per decision, numbered `0001-short-name.md`. Each records context, the decision, the options rejected and why.
- `docs/plan/`: `HANDOFF.md` (where things stand, what's next, the milestones) and the plans of work in progress.
- `docs/archive/`: finished plans, spikes and batch notes, kept as history and not kept up to date. When a plan's work is merged, fold what stays true into the spec, the architecture or an ADR, and move the plan here.
- `docs/hooks.md`: every change to the game's own code that the mod makes, with the reason it exists.
- The deterministic engine is a separate project, [SOSDET](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine), with its own docs, rules and hooks ([ADR 0004](docs/decisions/0004-engine-split.md)). The mod uses it only through its API.

## Current phase

- **Engine (SOSDET): release** (owner, 2026-11-01). The work happens in the SOSDET repository and follows its CLAUDE.md.
- **Mod (this repository): release** (owner, 2026-11-01). Every v1 feature is merged; playtest batches and the Steam Workshop release are what's going on.

Where things stand, what's next and what was built when: [docs/plan/HANDOFF.md](docs/plan/HANDOFF.md). The rest of this section applies to the mod.

Feature code for the mod is allowed for work I approved only. Also allowed:

- Read the game source (path in AGENTS.md Project Facts) and the docs.
- Write plans, ADRs and reports under `docs/`.
- Write spikes under `spikes/<name>/`: small, throwaway experiments that answer one question. Spike code is never moved into `mod/` as-is; each spike ends with a short report in `docs/plan/spikes/<name>.md` (moved to `docs/archive/spikes/` once its result is folded in) and, if it settles a choice, an ADR.

Planning tasks from Sprint 0, in rough order (7 is now the walking-skeleton plan):

1. ~~**Rebuild check:** build the untouched game source and confirm it runs exactly like the shipped game.~~ Done ([ADR 0001](docs/decisions/0001-game-compiler-ecj.md), [report](docs/archive/spikes/rebuild-check.md)).
2. ~~**Hook surface:** map where the multiplayer features need to hook into the game: the main update loop, world tick, faction/player handling, battle start and end, save/load, city boundary crossings (soldiers, goods, caravans), pause and speed. For each, say whether the game's script/mod API is enough or the game's code must change.~~ Done ([hook-surface.md](docs/archive/hook-surface.md)).
3. ~~**Single-player assumptions:** find the places that assume exactly one player faction.~~ Done ([single-player-assumptions.md](docs/archive/single-player-assumptions.md)).
4. **Spikes:**
    - ~~Battle streaming (ADR 0002)~~: replaced by lockstep (ADR 0003); the engine showed two-PC battles in SOS-15. Order latency at 300 ms ping is still to measure.
    - ~~Late-game save size and save time~~: checked in playtests instead (owner, 2026-10-24).
    - ~~Two game processes on one PC~~: not needed, viewing is lockstep (ADR 0006).
    - ~~Networking from inside the game process~~: built, direct IP (SOS-49) and Steam P2P under Songs of Syx's App ID 1162750 (SOS-60).
5. **Distribution:** how players get the changed game code (the engine's patches and the mod's) and the mod, without committing game files. Settled for the engine (SOSDET ADR 0001, from SOS-25) and the mod's hooks ([ADR 0010](docs/decisions/0010-mod-hooks-ship-in-script-jar.md)); publishing on the Steam Workshop is what's left.
6. **Test plan:** what runs before every pull request, before a release and in play sessions, and how each [AT] criterion in spec section 15 becomes an automated test.
7. ~~**Walking skeleton plan:** two players connect over Steam P2P and see the same world map, with one army's movement synced.~~ Done: steps 1 to 5 are merged ([walking-skeleton.md](docs/archive/walking-skeleton.md)).
8. **Work list:** propose the work (spikes, decisions, stories) with acceptance criteria. Don't start it until I approve the list.

## Working style

- Planning first: for anything larger than a small fix, propose a plan and wait for my approval before editing.
- One piece of work at a time, on its own branch and PR.
- If the spec, architecture and code disagree, stop and ask; don't pick one silently.
- Report at the end as AGENTS.md "Reporting" describes, plus which spec sections the work touched.
