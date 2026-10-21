# Architecture direction (proposed)

Status: proposal from the design discussion, not yet validated. Sprint 0 spikes confirm or change it, and each confirmed choice becomes an ADR in `docs/decisions/`. Where this file and `docs/spec.md` disagree, the spec wins.

## Core rule

Whoever owns a piece of state simulates it; everyone else gets reports or streams of it. A city is too heavy to copy or simulate twice, so each city runs only on its owner's PC. The world map is small, so the host owns it and everyone mirrors it. Field battles are the exception: every PC simulates them in lockstep from the host's battle save ([ADR 0003](decisions/0003-battle-lockstep.md)).

| State | Model | Simulated by | Crosses the network |
| --- | --- | --- | --- |
| World map | Host-authoritative state sync | Host only; clients mirror | Commands up, state deltas down |
| A player's city | Local authority | Its owner only | City reports and ledger transactions |
| Field battle | Deterministic lockstep ([ADR 0003](decisions/0003-battle-lockstep.md)) | Every PC | Battle save at start; commander orders with their step; state hashes |
| Siege assault | Defender-authoritative streaming (ADR 0002; open, spec §16) | Defender's PC | Visible city state down, attacker orders up |
| Goods, soldiers, money crossing city ↔ world | Ledger with transaction IDs | Host | Two-phase transactions, applied exactly once |

Other players appear on each client as puppet NPC factions: AI off, state overwritten from host deltas.

## Clocks

- **World tick (host):** fixed rate, e.g. 10 per second at 1× speed. Everything shared is stamped with it: army moves, treaties, caravans, pauses, saves.
- **City clock (each player):** runs locally at full speed, reports the world tick it has reached, never runs ahead of the host. The host does not advance the world more than a small cap ahead of the slowest city; that is how "slowest PC sets the speed" works.
- **Battle clock:** fixed steps of 1/60 s, numbered from deploy (step 0). Orders carry the step they apply at. The slowest PC sets the pace.

## World map

- A client sends a command; the host validates it, applies it at world tick N and broadcasts the changes stamped with N.
- Clients don't run NPC AI or world logic.
- Every few seconds the host sends a hash of key world state. A client whose mirror differs requests a full world snapshot.
- Pause and priority pause are commands applied at a world tick, so everyone stops at the same moment.

## Field battles

Decided in [ADR 0003](decisions/0003-battle-lockstep.md): deterministic lockstep, proven on one PC by spike SOS-11 ([report](plan/spikes/determinism.md)).

- **Starting the battle.** The host generates the battlefield with the vanilla generator and sends the battle save (about 1.9 MB for 2,000 vs 2,000). Every PC, the host included, loads it, seeds the simulation random stream from the host's seed, and runs deploy as step 0.
- **Simulation.** Every PC runs fixed 1/60 s steps. The other battle worker jobs and path finding run inside the step in a fixed order; nothing runs while paused.
- **AI general.** Only the host runs it, on its own thread. Its orders, settings and artillery targets go into the AI log, stamped 30 steps ahead (0.5 s); every PC applies them at that step (ADR 0003 amendment, SOS-23).
- **Random numbers.** A simulation stream for the step, and a cosmetic stream for rendering, sound, light and UI. No simulation draw depends on a local setting.
- **Orders.** Commanders send orders stamped with a future step; the host confirms the step, and every PC applies them exactly then.
- **Pace and speed.** Commanders vote on the speed; the slowest PC caps it.
- **Desyncs.** Every PC sends a state hash every few steps. On a mismatch the host sends its battle save at the current step and every PC reloads it. Every desync writes a report.
- **Determinism.** Battle code follows `.claude/rules/determinism.md`.

## Siege assault

The defender's PC runs the city and the assault and streams the area around each viewer's camera at 5–10 updates per second. Attacker orders go through the host to the defender. This is ADR 0002's streaming model, which field battles no longer use. Whether assaults move to lockstep is decided after the save size spike (spec §16).

## Saves, disconnects, viewing

- **Save barrier:** host announces "save at world tick T"; every city runs to exactly T, saves, and reports a save hash; the host saves the world; play resumes. A campaign save = world save + every city save.
- **Save before battles:** the same save barrier runs before every battle or siege assault starts (spec §11).
- **Disconnect:** priority pause; the player's city, empire and armies freeze; reconnect = load own city save + full world snapshot.
- **Host disconnect:** every client stops all simulation and shows "host disconnected". Any player can host the resumed campaign, usually from the pre-battle save.
- **City viewing:** the owner uploads a city snapshot (vanilla save format); the viewer opens it in a separate read-only game process.

## Code layout

- `mod/` holds the multiplayer module: networking, world mirror, ledger, battle lockstep (orders, hashes, resync), siege streaming, snapshots, observer mode. Domain logic here does not import game classes (adapter boundary, AGENTS.md).
- The game's own code is changed only through small, named hooks. Each hook is listed in `docs/hooks.md` with the reason it exists.
