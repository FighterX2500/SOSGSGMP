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

- **Engine (SOSDET): implementation** (owner, 2026-10-22). Merged through SOSDET PR #62 (2026-10-30): several humans, own-city battles, removing a human, deals, the market and war between humans, battles between two humans, city viewing, the first step of sieges (SOS-92 step A) and the playtest fixes. Open: SOSDET PR #63 (SOS-92 steps B and C in part) and #64 (SOS-102, the unit tests). The work happens in the SOSDET repository and follows its CLAUDE.md.
- **Mod (this repository): implementation; the walking skeleton is done** (2026-10-25). Merged through PR #66 (2026-10-30):
  - the walking skeleton: the lobby, the setup, the game over the network, the desync window, Steam, battles, the playtest log (SOS-49 to SOS-63);
  - campaign saves and resume, the save before every battle, disconnects and removing a player (SOS-64, SOS-65, SOS-67, SOS-68);
  - diplomacy, trade and war between players, and battles between players (SOS-72 to SOS-77, SOS-83);
  - Save Campaign in the escape menu, names and banners, the transfer log (SOS-78, SOS-79, SOS-81);
  - city viewing (SOS-82);
  - sieges of a player's capital, step A: the popup and Sally out (SOS-92);
  - playtest fixes, among them cursors in cities (SOS-58), the governor's reasons (SOS-94), stream compression (SOS-96) and a Steam invite from the main menu (SOS-103).

  Open: PR #67 (SOS-92 steps B and C in part), #68 (SOS-93, a plan and draft spec changes), #69 (SOS-100) and #70 (SOS-105). Where things stand and what's next: [docs/plan/HANDOFF.md](docs/plan/HANDOFF.md). The rest of this section applies to the mod.

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
    - ~~Networking from inside the game process~~: built, direct IP (SOS-49) and Steam P2P under Songs of Syx's App ID 1162750 (SOS-60).
5. **Distribution:** how players get the changed game code (the engine's patches and the mod's) and the mod, without committing game files. SOS-25 covers the engine's half.
6. **Test plan:** what runs before every pull request, before a release and in play sessions, and how each [AT] criterion in spec section 15 becomes an automated test.
7. ~~**Walking skeleton plan:** two players connect over Steam P2P and see the same world map, with one army's movement synced.~~ Done: steps 1 to 5 are merged ([walking-skeleton.md](docs/plan/walking-skeleton.md)).
8. **Board:** propose Jira cards (spikes, decisions, stories) with acceptance criteria. Don't create them until I approve the list.

## Working style

- Planning first: for anything larger than a small fix, propose a plan and wait for my approval before editing.
- One Jira card at a time. Link the card in the branch name and PR.
- If the spec, architecture and code disagree, stop and ask; don't pick one silently.
- Report at the end as AGENTS.md "Reporting" describes, plus which spec sections the work touched.
