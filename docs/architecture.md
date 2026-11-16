# Architecture

Status: describes what is built (2026-11-16). The main choices are in the ADRs in `docs/decisions/`. `docs/spec.md` says what the mod must do; where this file, the spec and the code disagree, ask the owner (CLAUDE.md).

## Core rule

Whoever owns a piece of state simulates it; everyone else gets reports of it. A city is too heavy to simulate twice on one PC, so each PC runs one city: its owner's, or, while its player views another city, that city instead. The world map, field battles and siege assaults run in lockstep on every PC ([ADR 0005](decisions/0005-world-lockstep-and-city-world-link.md), [ADR 0003](decisions/0003-battle-lockstep.md), [ADR 0006](decisions/0006-lockstep-city-viewing-and-assaults.md)).

| State | Model | Simulated by | Crosses the network |
| --- | --- | --- | --- |
| World map | Deterministic lockstep ([ADR 0005](decisions/0005-world-lockstep-and-city-world-link.md)) | Every PC | Commands and city reports with their step; the host's AI log; state hashes; the host's world save on a desync |
| A player's city | Local authority | Its owner; a viewer's PC runs it in lockstep while viewing ([ADR 0006](decisions/0006-lockstep-city-viewing-and-assaults.md)) | City reports, every 30 steps; to viewers, the city save when a view opens and the owner's actions with the step they applied at |
| Field battle | Deterministic lockstep ([ADR 0003](decisions/0003-battle-lockstep.md)) | Every PC in a battle between players; in an NPC battle, the fighting player's PC and any ally's or watcher's | Battle save at start; commander orders with their step; state hashes |
| Siege assault | Deterministic lockstep ([ADR 0006](decisions/0006-lockstep-city-viewing-and-assaults.md)) | Every PC | Defender's city save at start; commander orders with their step; the host's AI log; state hashes |
| Goods, soldiers, money crossing city ↔ world | Transactions with IDs | Every PC (part of the world step) | Nothing: each PC computes them; the city applies its own exactly once, and reports what a withdrawal actually took |

Other players appear in the world as factions whose city values come from their city reports. Holding several human factions in the world is engine work (SOS-46, ADR 0005 amendment). Built: SOSDET [ADR 0004](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/docs/decisions/0004-several-humans.md) (several humans, accepted 2026-10-25), merged in SOSDET [PR #31](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/pull/31) (2026-10-25).

## Clocks

- **Step (every PC):** one step counter for the city and the world. Each step runs the PC's own city part, then the lockstep world part. Everything shared is stamped with a step: commands, city reports, treaties, caravans, pauses, saves.
- **Pace:** a PC runs world step N only once every city report stamped for N has arrived, so the slowest PC sets the speed and no city runs ahead of world time. A city frozen while its player views another keeps sending its last report, so it never holds the world up (spec §10).
- **Battle clock:** fixed steps, numbered from deploy (step 0): 1/60 s at an agreed speed of 1x or slower, 1/30 s above 1x and below 2x, 1/16 s from 2x (the engine's `StepClock`, SOS-42). Orders carry the step they apply at. The slowest PC sets the pace. Spec §4 says down to 0.5x; the engine's floor today is 0.25x (`Pacer.MIN_SPEED`, open in spec §17). A PC that can't hold the floor stops the game until it catches up.

## World map

Decided in [ADR 0005](decisions/0005-world-lockstep-and-city-world-link.md); built by the engine (SOSDET stage E).

- Every PC runs the world in lockstep: the same steps, seed and commands, and the host's AI log.
- The world never reads a live city. Each city sends a report of the values the world reads every 30 steps, applied on every PC at its stamped step.
- The world's changes to a city are transactions with IDs that every PC computes; the city applies its own exactly once.
- Every PC hashes the world. At a difference every PC holds at one step and the host chooses Resync or Save and exit (spec §11); there is no automatic resync.
- Pause and priority pause are commands applied at a step, so everyone stops at the same moment.
- Prompts (a world battle, a declaration of war) never hold the world in multiplayer (spec §4). While a world-battle prompt is open, only that battle's armies wait; with no answer within 30 s, every PC gives the default answer at the same step (SOSDET SOS-45, spec §8).

## Field battles

Decided in [ADR 0003](decisions/0003-battle-lockstep.md): deterministic lockstep, proven on one PC by spike SOS-11 ([report](plan/spikes/determinism.md)). The deterministic part is a separate project, [SOSDET](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine) ([ADR 0004](decisions/0004-engine-split.md)): it owns the step, the random streams, the jobs, the AI log, the hash and save and load at a step. The mod carries the battle save, orders, the AI log and hashes between PCs.

- **Starting the battle.** The PC of the player whose army starts the battle generates the battlefield and sends its battle save to every PC; every PC loads it at the same step and opens it at deployment (spec §8, ADR 0003 second amendment; SOSDET SOS-61, the mod's SOS-62). The battle's end loads the game saved just before it on every PC.
- **Simulation.** Every PC runs the same fixed steps. The other battle worker jobs and path finding run inside the step in a fixed order; nothing runs while paused.
- **AI general.** Only the host runs it, on its own thread. Its orders, settings and artillery targets go into the AI log, stamped 30 steps ahead (0.5 s at 1x), half a second of real time at higher speeds, up to 400 steps; every PC applies them at that step (ADR 0003 amendment, SOS-23).
- **Random numbers.** A simulation stream for the step, and a cosmetic stream for rendering, sound, light and UI. No simulation draw depends on a local setting.
- **Orders.** Commanders send orders stamped with a future step; the host confirms the step, and every PC applies them exactly then.
- **Pace and speed.** Commanders vote on the speed; the slowest PC caps it (the engine's governor, SOS-42).
- **Commanders.** The engine's commanders table says which PC (or the host's AI) commands each division and each army's artillery, and skips orders from anyone else (SOSDET SOS-15). The mod fills it from the campaign, and decides who may retreat: only that side's commanders. Restart is off in every battle.
- **Desyncs.** Every PC sends a state hash every few steps. On a mismatch the host sends its battle save at the current step and every PC reloads it. Every desync writes a report.
- **Determinism.** Battle code lives in the engine and follows its determinism rules.

## Siege assault

Decided in [ADR 0006](decisions/0006-lockstep-city-viewing-and-assaults.md): the assault runs like a field battle. The defender's PC sends its city save at the start step, and every PC, the host and the defender included, loads it. Commanders' orders carry their step, the battle AI runs on the host and its log is applied at the stamped step, and a desync reloads the host's assault state. The world and every city stay paused, so no PC runs two cities at once. An NPC army's invasion of a player's capital is not run this way: it stays vanilla's, in the defender's city on the defender's PC only, and the world doesn't pause (spec §9, owner 2026-10-29).

## Saves, disconnects, viewing

- **Save barrier:** host announces "save at step T"; every PC runs to exactly T, saves, and reports a save hash; the host saves the world; play resumes. A campaign save = world save + every city save.
- **Save before battles:** the same save barrier runs before every battle between players or siege assault starts (spec §11).
- **Lost connection** (SOS-177, SOS-178; [reconnect.md](plan/reconnect.md)): a player's PC silent for 5 s puts every PC in a priority pause at one step, with the lost-connection window. The transport (`TcpNode`, `SteamNode`) numbers every message and keeps it until the peer confirms it; a joining PC connects again after a break, and a `Resume` handshake resends what the other side lacks, so nothing is lost or applied twice. Above the nodes a broken connection looks like silence, and play goes on by itself when the PC is heard again.
- **Host's connection lost** (SOS-179): every other PC stops the whole simulation (world, cities, battles) and shows "Lost the connection to the host: waiting", and connects again by itself when the host is back. Each player can leave from that window.
- **Disconnect** (a crash or a quit): priority pause; the host chooses Return to the last save (end the session, resume from the last campaign save), Continue without them (the player is removed at the same step on every PC: every region they held becomes a free region with no owner, and their armies disband; SOS-65, SOSDET SOS-66) or Freeze them (below). A carved city-state of a removed player is un-carved while every PC holds (SOS-188). Spec §11.
- **Host disconnect** (the host quits or crashes): every other PC stops all simulation and shows "host disconnected". Any player can host the resumed campaign, usually from the pre-battle save.
- **Joining a running campaign** (SOS-189, SOS-190; [hot-join-restart.md](plan/hot-join-restart.md)): by restart, not inside the running session. After the host's Accept every PC makes a campaign save; the newcomer picks their spot on a copy of it while the others play on; then every PC saves again, starts a new engine session from its own copy of that save, and adds the newcomer before step 0 (`Engine.addHuman`, which carves their city-state). Every stream starts again at offset 0.
- **Freezing a player** (SOS-192 to SOS-196, SOS-204; [freeze.md](plan/freeze.md)): the host logs a freeze that applies at one step on every PC (`Engine.freezeHuman`): the player keeps their slot and capital, and nobody waits for their PC. Coming back is a restart like a join, with the returning PC loading its own freeze save (`Engine.rejoinLoad`, `thawHuman`).
- **Living world** (SOS-187, SOS-212 to SOS-214): the host builds a vanilla world around a stand-in capital (`Engine.buildWorldForCarving`), every PC loads it, every player picks a spot on it, and the host carves every city-state in before step 0 (`Engine.carveHumans`).
- **City viewing** ([ADR 0006](decisions/0006-lockstep-city-viewing-and-assaults.md)): the viewer's own city freezes; the owner sends a city save at a step, and the viewer's PC runs that city in place of its own, applying each owner action at the step it applied at on the owner's PC. A city hash mismatch reloads the owner's save. The viewer's frozen city keeps sending its last report, so the world never waits on it; the world's transactions for it queue until the view closes. A view is only between the viewer and the owner: the save and the owner's actions go straight from the owner to the viewer. The other PCs only reload their own world, keeping their city, for about a second as the view opens and as it closes, and while the host's own view opens every PC holds (spec §10; SOS-82). After loading, the viewer runs the watched city fast until it is a fixed delay behind the owner. A view never changes the game speed: if the viewer hasn't caught up after 1 minute, or can't keep up at the current speed, only that view closes (spec §10).

## Code layout

- `mod/` holds the multiplayer module: networking, lockstep transport for the world and battles (commands, orders, city reports, AI log, hashes, resync saves), city viewing, observer mode. Domain logic here does not import game classes (adapter boundary, AGENTS.md).
- The deterministic engine is in its own repository, SOSDET, ([ADR 0004](decisions/0004-engine-split.md)). The build compiles against the SOSDET jar it finds, with no pin ([ADR 0007](decisions/0007-mod-compiles-against-engine-jar.md)). The lobby compares every PC's game files and mods instead: the game's jar, its `assets/init` data and script jars, and each enabled mod's name, version, data and script jars, SOSDET's and this mod's included (`sosgsgmp.lobby.GameFiles`, SOS-159; spec §5).
- The game's own code is changed only through small, named hooks. The mod's hooks are listed in `docs/hooks.md` and applied to the game's source alone; no game file is patched by both. They ship as classes in the mod's script jar ([ADR 0010](decisions/0010-mod-hooks-ship-in-script-jar.md)). The engine lists its own.
