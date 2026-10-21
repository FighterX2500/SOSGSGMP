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
- The deterministic engine is a separate project, [SOSDET](https://github.com/FighterX2500/SOSDET), with its own docs, rules and hooks ([ADR 0004](docs/decisions/0004-engine-split.md)). The mod uses it only through its API.

## Current phase

- **Engine (SOSDET): implementation** (owner, 2026-10-22). Cards SOS-25 to SOS-32 under the epic SOS-22; the work happens in the SOSDET repository and follows its CLAUDE.md.
- **Mod (this repository): planning (Sprint 0)**, resumed after the engine is built (owner, 2026-10-22). The rest of this section applies to it.

Do not write feature code for the mod yet. Allowed work:

- Read the game source (path in AGENTS.md Project Facts) and the docs.
- Write plans, ADRs and reports under `docs/`.
- Write spikes under `spikes/<name>/`: small, throwaway experiments that answer one question. Spike code is never moved into `mod/` as-is; each spike ends with a short report in `docs/plan/spikes/<name>.md` and, if it settles a choice, an ADR.

Planning tasks, in rough order:

1. **Rebuild check:** build the untouched game source and confirm it runs exactly like the shipped game.
2. **Hook surface:** map where the multiplayer features need to hook into the game: the main update loop, world tick, faction/player handling, battle start and end, save/load, city boundary crossings (soldiers, goods, caravans), pause and speed. For each, say whether the game's script/mod API is enough or the game's code must change.
3. **Single-player assumptions:** find the places that assume exactly one player faction.
4. **Spikes:**
    - Battle streaming (ADR 0002): the same battlefield from the host's seed on two PCs, snapshot size, order latency at 300 ms ping, display-only mode for viewers.
    - Late-game save size and save time.
    - Two game processes on one PC (city viewer) and their RAM use.
    - Steam P2P under App ID 480 from inside the game process.
5. **Distribution:** how players get the changed game code (the engine's patches and the mod's) and the mod, without committing game files. SOS-25 covers the engine's half.
6. **Test plan:** how CI gets the game's source and libraries (self-hosted Windows runner or private storage), what runs headless, and how [CI] acceptance criteria in spec section 15 become automated tests.
7. **Walking skeleton plan:** two players connect over Steam P2P and see the same world map, with one army's movement synced.
8. **Board:** propose Jira cards (spikes, decisions, stories) with acceptance criteria. Don't create them until I approve the list.

## Working style

- Planning first: for anything larger than a small fix, propose a plan and wait for my approval before editing.
- One Jira card at a time. Link the card in the branch name and PR.
- If the spec, architecture and code disagree, stop and ask; don't pick one silently.
- Report at the end as AGENTS.md "Reporting" describes, plus which spec sections the work touched.
