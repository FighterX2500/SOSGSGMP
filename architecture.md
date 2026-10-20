# Architecture direction (proposed)

Status: proposal from the design discussion, not yet validated. Sprint 0 spikes confirm or change it, and each confirmed choice becomes an ADR in `docs/decisions/`. Where this file and `docs/spec.md` disagree, the spec wins.

## Core rule

Whoever owns a piece of state simulates it; everyone else gets reports or streams of it. A city is too heavy to copy or simulate twice, so each city runs only on its owner's PC. The world map is small, so the host owns it and everyone mirrors it.

| State | Model | Simulated by | Crosses the network |
| --- | --- | --- | --- |
| World map | Host-authoritative state sync | Host only; clients mirror | Commands up, state deltas down |
| A player's city | Local authority | Its owner only | City reports and ledger transactions |
| Field battle | Deterministic lockstep, host as referee | Every player, from one start state | Orders only, plus state hashes |
| Siege assault | Defender-authoritative streaming (v1) | Defender's PC | Visible city state down, attacker orders up |
| Goods, soldiers, money crossing city ↔ world | Ledger with transaction IDs | Host | Two-phase transactions, applied exactly once |

Other players appear on each client as puppet NPC factions: AI off, state overwritten from host deltas.

## Clocks

- **World tick (host):** fixed rate, e.g. 10 per second at 1× speed. Everything shared is stamped with it: army moves, treaties, caravans, pauses, saves.
- **City clock (each player):** runs locally at full speed, reports the world tick it has reached, never runs ahead of the host. The host does not advance the world more than a small cap ahead of the slowest city; that is how "slowest PC sets the speed" works.
- **Battle tick:** fixed timestep, grouped into turns of about 100 ms.

## World map

- A client sends a command; the host validates it, applies it at world tick N and broadcasts the changes stamped with N.
- Clients don't run NPC AI or world logic.
- Every few seconds the host sends a hash of key world state. A client whose mirror differs requests a full world snapshot.
- Pause and priority pause are commands applied at a world tick, so everyone stops at the same moment.

## Field battles

- The host sends one start state: terrain seed, army records, deployment.
- Each commander sends its orders for every turn, even when empty, scheduled a few turns ahead (input delay from measured ping, capped at 1 s). The host bundles them and sends the confirmed bundle to everyone.
- No machine runs a turn before its bundle arrives. A late machine, spectators included, slows the battle for everyone.
- Every ~1 s each peer sends per-subsystem state hashes (positions, health, morale, projectiles, RNG). On mismatch the host's state is canonical: it sends a serialized battle state to the diverged peer, and a desync report (hashes, recent orders, start state) is written for replay.
- Determinism rules: `.claude/rules/determinism.md`.

## Siege assault

The defender's PC runs the city and the assault and streams the area around each viewer's camera at 5–10 updates per second. Attacker orders go through the host to the defender. Alternative to test in a spike: every player loads the defender's city snapshot at assault start and runs city + battle in lockstep (needs a deterministic city simulation).

## Saves, disconnects, viewing

- **Save barrier:** host announces "save at world tick T"; every city runs to exactly T, saves, and reports a save hash; the host saves the world; play resumes. A campaign save = world save + every city save.
- **Disconnect:** priority pause; the player's city, empire and armies freeze; reconnect = load own city save + full world snapshot.
- **City viewing:** the owner uploads a city snapshot (vanilla save format); the viewer opens it in a separate read-only game process.

## Code layout

- `mod/` holds the multiplayer module: networking, world mirror, ledger, lockstep, snapshots, observer mode. Domain logic here does not import game classes (adapter boundary, AGENTS.md).
- The game's own code is changed only through small, named hooks. Each hook is listed in `docs/hooks.md` with the reason it exists.
