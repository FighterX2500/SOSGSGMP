# SoS GSG MP

Multiplayer mod for Songs of Syx: each player runs their own city-state on one shared world map, with diplomacy, trade, real-time battles and sieges between players.

@AGENTS.md

AGENTS.md holds the coding rules: Java, the game, hooks, threads, saves, network data, dependencies. This file holds the project workflow: where things live, the current phase, how to work with me. The two don't overlap; if they ever seem to conflict, stop and ask.

## Source of truth

- `docs/spec.md`: what the mod must do. Read the relevant section before any planning or feature work. Never change a rule in it without asking me first; when I approve a change, update the spec in the same PR.
- `docs/architecture.md`: the proposed architecture. It is a direction, not a decision, until an ADR confirms it.
- `docs/decisions/`: ADRs, one per decision, numbered `0001-short-name.md`. Each records context, the decision, the options rejected and why.
- `docs/plan/`: plans per milestone or sprint.
- `docs/hooks.md`: every change to the game's own code that the mod makes, with the reason it exists.
- The deterministic engine is a separate project, [SOSDET](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine), with its own docs, rules and hooks ([ADR 0004](docs/decisions/0004-engine-split.md)). The mod uses it only through its API.

## Current phase

- **Engine (SOSDET): implementation** (owner, 2026-10-22). Cards under the epic SOS-22 are merged up to SOS-46, several humans on one world (SOSDET PR #31), and SOS-31, the scenario suite (SOSDET PR #30), both on 2026-10-25. The engine has no API for the mod to add a human yet (`addHuman`, SOSDET ADR 0004). The work happens in the SOSDET repository and follows its CLAUDE.md.
- **Mod (this repository): implementation of the walking skeleton** (owner, 2026-10-25), in the order of [docs/plan/walking-skeleton.md](docs/plan/walking-skeleton.md). The lobby (SOS-49, SOSGSGMP PR #26) is merged. The setup's map step is in draft SOSGSGMP PR #28, with no card yet. Factions, locations and world init need the engine's `addHuman`. The rest of this section applies to it.

Feature code for the mod is allowed for approved cards only. Also allowed:

- Read the game source (path in AGENTS.md Project Facts) and the docs.
- Write plans, ADRs and reports under `docs/`.
- Write spikes under `spikes/<name>/`: small, throwaway experiments that answer one question. Spike code is never moved into `mod/` as-is; each spike ends with a short report in `docs/plan/spikes/<name>.md` and, if it settles a choice, an ADR.

Planning tasks from Sprint 0, in rough order (7 is now the walking-skeleton plan):

1. **Rebuild check:** build the untouched game source and confirm it runs exactly like the shipped game.
2. **Hook surface:** map where the multiplayer features need to hook into the game: the main update loop, world tick, faction/player handling, battle start and end, save/load, city boundary crossings (soldiers, goods, caravans), pause and speed. For each, say whether the game's script/mod API is enough or the game's code must change.
3. **Single-player assumptions:** find the places that assume exactly one player faction.
4. **Spikes:**
    - ~~Battle streaming (ADR 0002)~~: replaced by lockstep (ADR 0003); the engine showed two-PC battles in SOS-15. Order latency at 300 ms ping is still to measure.
    - ~~Late-game save size and save time~~: checked in playtests instead (owner, 2026-10-24).
    - ~~Two game processes on one PC~~: not needed, viewing is lockstep (ADR 0006).
    - Networking from inside the game process: direct IP, and Steam P2P under Songs of Syx's App ID (1162750).
5. **Distribution:** how players get the changed game code (the engine's patches and the mod's) and the mod, without committing game files. SOS-25 covers the engine's half.
6. **Test plan:** what runs before every pull request, before a release and in play sessions, and how each [AT] criterion in spec section 15 becomes an automated test.
7. **Walking skeleton plan:** two players connect over Steam P2P and see the same world map, with one army's movement synced.
8. **Board:** propose Jira cards (spikes, decisions, stories) with acceptance criteria. Don't create them until I approve the list.

## Working style

- Planning first: for anything larger than a small fix, propose a plan and wait for my approval before editing.
- One Jira card at a time. Link the card in the branch name and PR.
- If the spec, architecture and code disagree, stop and ask; don't pick one silently.
- Report at the end as AGENTS.md "Reporting" describes, plus which spec sections the work touched.
