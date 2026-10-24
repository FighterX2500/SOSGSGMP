# Architecture direction (proposed)

Status: proposal from the design discussion, not yet validated. Sprint 0 spikes confirm or change it, and each confirmed choice becomes an ADR in `docs/decisions/`. Where this file and `docs/spec.md` disagree, the spec wins.

## Core rule

Whoever owns a piece of state simulates it; everyone else gets reports or streams of it. A city is too heavy to copy or simulate twice, so each city runs only on its owner's PC. The world map and field battles are the exceptions: every PC simulates them in lockstep ([ADR 0005](decisions/0005-world-lockstep-and-city-world-link.md), [ADR 0003](decisions/0003-battle-lockstep.md)).

| State | Model | Simulated by | Crosses the network |
| --- | --- | --- | --- |
| World map | Deterministic lockstep ([ADR 0005](decisions/0005-world-lockstep-and-city-world-link.md)) | Every PC | Commands and city reports with their step; the host's AI log; state hashes; the host's world save on a desync |
| A player's city | Local authority | Its owner only | City reports, every 30 steps |
| Field battle | Deterministic lockstep ([ADR 0003](decisions/0003-battle-lockstep.md)) | Every PC | Battle save at start; commander orders with their step; state hashes |
| Siege assault | Defender-authoritative streaming (ADR 0002; open, spec §16) | Defender's PC | Visible city state down, attacker orders up |
| Goods, soldiers, money crossing city ↔ world | Transactions with IDs | Every PC (part of the world step) | Nothing: each PC computes them; the city applies its own exactly once, and reports what a withdrawal actually took |

Other players appear in the world as factions whose city values come from their city reports.

## Clocks

- **Step (every PC):** one step counter for the city and the world. Each step runs the PC's own city part, then the lockstep world part. Everything shared is stamped with a step: commands, city reports, treaties, caravans, pauses, saves.
- **Pace:** a PC runs world step N only once every city report stamped for N has arrived, so the slowest PC sets the speed and no city runs ahead of world time.
- **Battle clock:** fixed steps, numbered from deploy (step 0): 1/60 s at 1x or slower, 1/16 s above (the engine's `StepClock`). Orders carry the step they apply at. The slowest PC sets the pace.

## World map

Decided in [ADR 0005](decisions/0005-world-lockstep-and-city-world-link.md); built by the engine (SOSDET stage E).

- Every PC runs the world in lockstep: the same steps, seed and commands, and the host's AI log.
- The world never reads a live city. Each city sends a report of the values the world reads every 30 steps, applied on every PC at its stamped step.
- The world's changes to a city are transactions with IDs that every PC computes; the city applies its own exactly once.
- Every PC hashes the world. A PC whose hash differs reloads the host's world; its city keeps running.
- Pause and priority pause are commands applied at a step, so everyone stops at the same moment.

## Field battles

Decided in [ADR 0003](decisions/0003-battle-lockstep.md): deterministic lockstep, proven on one PC by spike SOS-11 ([report](plan/spikes/determinism.md)). The deterministic part is a separate project, [SOSDET](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine) ([ADR 0004](decisions/0004-engine-split.md)): it owns the step, the random streams, the jobs, the AI log, the hash and save and load at a step. The mod carries the battle save, orders, the AI log and hashes between PCs.

- **Starting the battle.** The host generates the battlefield with the vanilla generator and sends the battle save (about 1.9 MB for 2,000 vs 2,000). Every PC, the host included, loads it, seeds the simulation random stream from the host's seed, and runs deploy as step 0.
- **Simulation.** Every PC runs the same fixed steps. The other battle worker jobs and path finding run inside the step in a fixed order; nothing runs while paused.
- **AI general.** Only the host runs it, on its own thread. Its orders, settings and artillery targets go into the AI log, stamped 30 steps ahead (0.5 s); every PC applies them at that step (ADR 0003 amendment, SOS-23).
- **Random numbers.** A simulation stream for the step, and a cosmetic stream for rendering, sound, light and UI. No simulation draw depends on a local setting.
- **Orders.** Commanders send orders stamped with a future step; the host confirms the step, and every PC applies them exactly then.
- **Pace and speed.** Commanders vote on the speed; the slowest PC caps it.
- **Desyncs.** Every PC sends a state hash every few steps. On a mismatch the host sends its battle save at the current step and every PC reloads it. Every desync writes a report.
- **Determinism.** Battle code lives in the engine and follows its determinism rules.

## Siege assault

The defender's PC runs the city and the assault and streams the area around each viewer's camera at 5–10 updates per second. Attacker orders go through the host to the defender. This is ADR 0002's streaming model, which field battles no longer use. Whether assaults move to lockstep is decided after the save size spike (spec §16).

## Saves, disconnects, viewing

- **Save barrier:** host announces "save at step T"; every PC runs to exactly T, saves, and reports a save hash; the host saves the world; play resumes. A campaign save = world save + every city save.
- **Save before battles:** the same save barrier runs before every battle or siege assault starts (spec §11).
- **Disconnect:** priority pause; the player's city, empire and armies freeze; reconnect = load own city save + full world snapshot.
- **Host disconnect:** every client stops all simulation and shows "host disconnected". Any player can host the resumed campaign, usually from the pre-battle save.
- **City viewing:** the owner uploads a city snapshot (vanilla save format); the viewer opens it in a separate read-only game process.

## Code layout

- `mod/` holds the multiplayer module: networking, lockstep transport for the world and battles (commands, orders, city reports, AI log, hashes, resync saves), siege streaming, snapshots, observer mode. Domain logic here does not import game classes (adapter boundary, AGENTS.md).
- The deterministic engine is in its own repository, SOSDET, and the mod pins one engine release ([ADR 0004](decisions/0004-engine-split.md)).
- The game's own code is changed only through small, named hooks. The mod's hooks are listed in `docs/hooks.md` and applied on top of the engine's patches; the engine lists its own.
