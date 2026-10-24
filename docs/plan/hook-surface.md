# Hook surface map (V71)

Card: [SOS-3](https://fighterx2500.atlassian.net/browse/SOS-3) · Date: 2026-10-20, battle points updated 2026-10-21 for lockstep ([SOS-11](https://fighterx2500.atlassian.net/browse/SOS-11)) · Game: 0.71.44, source in `%SOS_GAME_DIR%\info\SongsOfSyx-sources`

This document maps where the multiplayer features meet the game's code. For each point it names the route:

- **API**: the game's script API or public game classes, called from the mod.
- **Reflection**: reflection through the adapter layer, one place per member (AGENTS.md "Adapter Boundary").
- **Hook**: a change to the game's own code. AGENTS.md requires approval for each hook and an entry in `docs/hooks.md`. This document only maps candidates; it adds no hook and approves none.

Source references use paths relative to the source folder, and line numbers are V71's.

## 1. Summary

1. **Most of the plumbing exists as public API.** This covers lifecycle callbacks, a per-tick update, save/load listeners, extra save sections, end-of-frame actions, game speed, army commands, diplomatic stances, caravans, money and starting a battle. The mod can do a lot without touching game code.
2. **Notification only, no veto.** Four public listener classes (§2) report:
   - battle results,
   - diplomatic stance changes,
   - region owner changes,
   - factions becoming active or inactive.

   Together they give the host a source of world changes to send to clients. Nothing lets the mod intercept or skip what the game does next, though. Every point where multiplayer has to *stop the game doing something locally* is a hook. That covers:
   - NPC AI for other players' factions,
   - world simulation on clients,
   - the city running ahead of world time,
   - local speed changes,
   - battle AI for human-controlled armies.
3. **The game assumes one player in more than 1,000 places** (`FACTIONS.player()` appears 1,047 times in 292 files, and `AD.cityDivs()` 53 times). On the host, world code that branches on "is this the player?" would treat every other human as an NPC. This is the biggest source of hooks and is planning task 3.
4. **Vanilla battles are not deterministic, but can be made so.** They run on five worker threads that tick on wall-clock time, with variable time steps and one global, randomly seeded RNG. ADR 0002 first chose streaming for that reason. Spike SOS-11 then made battles deterministic with spike patches, and field battles now run in lockstep ([ADR 0003](../decisions/0003-battle-lockstep.md), §7 Q1). That costs nine battle points, B7–B15 (B15 added in SOS-23 for the AI general on the host). Since [ADR 0004](../decisions/0004-engine-split.md) B7–B15 belong to the deterministic engine (SOSDET), which lists its hooks (B7–B11, B15) in its own hooks file as it builds them; B1–B6 are not assigned yet, and the other points stay with the mod.
5. **A vanilla field battle replaces the city in memory.** It saves the whole game, generates the battlefield inside the city engine, and reloads the save when the battle ends. That fits spec §4 ("battles pause the world and every city"), but it costs a full save and a full load on every PC in the battle (§11, question Q2).

Tally of the 55 points in §4:

| Route | Count | Notes |
| --- | --- | --- |
| API | 22 | Several work only once a hook elsewhere exists, for example B5 after P1, or B12 after B10. |
| Hook | 22 | |
| Hook or reflection | 5 | Decided per point. |
| Reflection | 2 | |
| API plus reflection | 2 | |
| Mod UI plus API | 1 | |
| No hook (a constraint) | 1 | |

## 2. What the script API gives us

Scripts are loaded from jars on the mod classpath (`init/paths/PATHS.SCRIPT().modClasspaths()`, `init/Main.java:78`). They run in the game process with full access to public game classes.

| Capability | Where | Notes |
| --- | --- | --- |
| `initBeforeGameCreated()`, `initBeforeGameInited()`, `createInstance()` | `script/SCRIPT.java` | `createInstance()` runs once per new game and once per load, so per-session state belongs there (AGENTS.md "State and Lifecycle"). |
| `update(double ds)` | `SCRIPT_INSTANCE`; `script/ScriptEngine.java:216` | Runs **inside** the loop, once per sub-step, as a `GameResource`. The script engine is created first (`game/GAME.java:119`), so it runs **before** TIME, SETT and WORLD in each sub-step, not "after each tick" as its Javadoc says. It runs during battles (`isBattle = true`). |
| `save(FilePutter)` / `load(FileGetter)` | `ScriptEngine.save/load` | Length-prefixed block inside the normal save. A size mismatch is caught and `handleBrokenSavedState()` is offered. |
| `render`, `hover`, `hoverTimer`, `keyPush`, `mouseClick` | `ScriptEngine.callback` | Enough for a chat box, a "who paused" banner and popups drawn by the mod. **`keyPush` is never called in V71**: no caller exists. |
| End-of-frame action | `GAME.addAfterUpdate(ACTION)`, `GAME.java:520` | Runs once per frame, after all sub-steps. This is the natural place to drain the network queue (AGENTS.md "networking thread"). |
| Init and start actions | `GAME.addOnInit`, `addOnViewInit`, `addBeforeGameStarts` | One-shot setup. |
| Save/load listeners | `GAME.saver().onBeforeSave/onAfterSave/onBeforeLoad/onAfterLoad` (`game/save/GameSaver.java:232-246`) | Called with the save path. |
| Extra save section | `GAME.saver().add(Savable)` | Unknown keys are skipped on load, so older saves still load (`GameSaver.load`, line 140). |
| Save on demand | `GAME.saver().save(Path, name, minified)` | Synchronous, on the update thread; calls `CORE.checkIn()` so the engine's kill switch doesn't fire. |
| Game speed | `GAME.SPEED` (`game/GameSpeed.java`): `speedSet`, `togglePause`, `tmpPause`, `updateOnce`, `speed()`, `isPaused()` | Public, but anyone can call it (see P1). |
| Start a child JVM | `snake2d/util/process/Proccesser.exec(Class, jvmArgs, args, classPaths)` | Usable for the city viewer process. |
| Game listeners: subclass the class and the constructor registers it | `world/battle/BattleListener` (battle results, regions taken), `game/faction/diplomacy/DIP.DipActivityListener` (`change(faction, other, old, new)`), `world/region/RD.RDOwnerChanger` (`change(region, oldOwner, newOwner)`), `game/faction/Faction.FactionActivityListener` (`add` / `remove`) | Notification after the fact. The game clears them when a session ends (`GameDisposable`), so the mod registers them again in `createInstance()`. |

**What it can't do:**
- **Few events.** Only the four listeners above exist. Nothing fires for an army meeting another army, a caravan arriving, a speed change and so on; the mod has to poll for those.
- **No veto or replacement.** A script can't cancel or skip a game method.
- **No hold on time.** It can't stop the remaining sub-steps of a frame.

## 3. Engine facts that shape every hook

| Fact | Where | Consequence |
| --- | --- | --- |
| The simulation runs on its own updater thread; rendering and GL run on another. | `snake2d/CORE.java:151-277`, `snake2d/Updater.java` | All game-state access must be on the updater thread. The networking thread only queues messages (AGENTS.md). |
| **Kill switch:** if the updater doesn't hand over a frame for 5 s the engine logs a warning; after 8 of those (about 40 s) the game exits. | `CORE.java:253-274` | Never block the updater thread waiting for the network. Wait by running ticks with `ds = 0`. |
| Frame time × speed is cut into sub-steps of at most 1/16 s. Each sub-step updates every `GameResource` in creation order. | `GAME.java:241-294` | There's no fixed world tick in vanilla. A "world tick" (architecture §Clocks) has to be defined by the mod on top of game time. |
| Built-in local slowdown: `slowDown` = frame time ÷ 1/40 s; `GameSpeed.update` lowers the actual speed when frames run long. | `Updater.java:61-66`, `GameSpeed.java:33-58` | "The slowest PC sets the speed" can reuse this signal, by reporting `SPEED.speed()` to the host. |
| Resources marked `isBattle = true` (`ARMIES`, `BATTLE_THREADS`, `TEMP_EVENTS`, `ENGINE`, `INTER`, `TIME`, `COUNTS`, `SCRIPTS`, `SETT`) keep updating while the battle view is open. World resources (`WORLD`, `FACTIONS`, `EVENTS`, `NOBLES`, `RAIDING`, `TOURISM`) stop. | `GAME.java:259-282`; constructors | This matches the architecture's split: the second group is the host-owned world. |
| One save file holds everything: game spec, special savables, then each `GameResource` as a keyed, length-prefixed section. | `GameSaver.java:101-125` | A campaign save of "world from the host plus a city from each player" doesn't exist in vanilla (see L4). |
| One global RNG, `snake2d/util/rnd/RND`: a static `java.util.Random`, randomly seeded at startup, shared by every thread. | `RND.java` | Nothing that uses it is reproducible across PCs as it stands. Lockstep splits it into a simulation and a cosmetic stream (B10). |
| The game starts Steam with `SteamAPI.init()` and runs callbacks every 5 s on its own thread. steamworks4j 1.9.0 ships inside `SongsOfSyx.jar`. | `src/integrations/SteamClient.java` | P2P can use the bundled steamworks4j with no new dependency. The App ID question is in Q4. |

## 4. Hook points

### Main update loop (spec §4, §13)

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| A1 | `game/GAME.java:241` `update(ds, slowDown)`, the sub-step loop | (a) Never let a city run past the world time the host has granted. (b) Stop **exactly** at world time T for the save barrier. | **Hook**: clamp `ds` to the granted budget before splitting into sub-steps | A script can only call `SPEED.tmpPause()`, which applies from the *next* frame. At speed 250 a single 1/60 s frame is about 4 game-seconds, so (b) can't be exact without a hook. |
| A2 | Same loop, `GameResource.all` (private static list, `GAME.java:456`) | On clients, don't update the host-owned world resources (`WORLD`, `FACTIONS`, `EVENTS`, `NOBLES`, `RAIDING`, `TOURISM`); keep `SETT`, `TIME` and the others local. | **Reflection** (remove them from `all` on clients) or a **hook** (`shouldUpdate(resource)`) | Reflection is possible: one private static field, and the saver keeps its own list, so save/load is unaffected. It edits a list the game owns, so a one-line hook is safer. Decide in the ADR on hook distribution. |
| A3 | `GAME.addAfterUpdate(ACTION)` | Drain the network queue once per frame on the update thread, and apply host deltas and ledger entries there. | **API** | — |
| A4 | Updater kill switch, `CORE.java:253` | Waiting on the network must never block. | **None** (a constraint) | — |

### World tick (spec §4, §13 "World map", §15 "World sync")

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| W1 | `world/WORLD.java:307` `update`, `afterTick` (`WBattles.poll`) | The host runs the world as in vanilla. Clients don't run it (A2), but mirror it. | **API + A2** | — |
| W2 | World state setters: `WArmy.setDestination/teleport/stop`, `WORLD.ENTITIES().caravans.create`, `DipStance.set`, region owner changes (`RD`). Change sources on the host: the four listeners in §2. | Collect changes on the host; apply them on clients. | **API** where a setter or listener exists; **reflection** for fields with no setter | Coverage isn't known yet: army movement and caravan progress have no listener and must be polled or snapshotted. A spike should list every field a delta must carry. |
| W3 | `WORLD.save/load(FilePutter/FileGetter)` (protected) | Full world snapshot for resync (spec §13: "recovers automatically") and reconnect. | **Reflection** (call the protected `save/load` of the `WORLD` and `FACTIONS` sections) | — |
| W4 | Same | World hash every few seconds (architecture §World map). | **Reflection** (hash the serialized `WORLD` and `FACTIONS` sections), or **API** getters for a smaller key-state hash | — |
| W5 | `world/map/fow/FOW.java`, `world/WorldMinimap.java`, world overlays: world resources that are really display for the local player | Keep running on clients for the local player while the rest of the world loop is off (A2). | **Hook or reflection**, decided with A2 | Same mechanism as A2. Found in [single-player-assumptions.md](single-player-assumptions.md) G8. |

### Faction and player handling (spec §5, §6, §11)

**Moved to the engine (2026-10-25).** F1–F5 and F8–F10 were written for the puppet model, where other humans are `FactionNPC`s on the host. Since ADR 0005 every PC runs the world in lockstep, and SOSDET [SOS-46](https://fighterx2500.atlassian.net/browse/SOS-46) gives each human a vanilla `Player` faction in a fixed slot on every PC instead. These points become engine work under SOS-46; the mod's requirements for it are in [player-slots.md](player-slots.md). F6 and F7 stay with the mod. The rows below are kept as the record of what the world code does.

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| F1 | `game/faction/FACTIONS.java`: 64 slots, 1 `Player` and 63 `FactionNPC`; `activateNext`, `remove` | Each other player appears as a puppet `FactionNPC`. Up to 16 players fit (1 local and 15 puppets), leaving 48 NPC slots. | **API** (`activateNext`, `remove`, `getByIndex`) | — |
| F2 | `FactionNPC.update` (`game/faction/npc/FactionNPC.java:179`), `FACTIONS` updater (`FACTIONS.java:40`), `TradeManager.buy` (`game/faction/trade/TradeManager.java:194`) | A puppet runs **no** NPC economy, AI requests or trading on its own. | **Hook**: early return when the faction is a remote player | These are protected or package-private methods called inside the game loop. Nothing can skip them from outside. |
| F3 | `world/army/ai/WArmyAI.update` (`planForWar`), `world/army/ai/Recruiter` | No NPC war planning or army recruiting for puppet factions. | **Hook** | Same as F2. |
| F4 | `Faction.buyer(res)` / `seller(res)` for a `FactionNPC` (NPC stockpile) | On the host, trade with a puppet must go to the remote player's real city through the ledger, not to an NPC stockpile. | **Hook** (return a ledger-backed `FBUYER`/`FSELLER`), or **reflection** if the backing field can be replaced | Needs a closer look at `FactionNPC`'s buyer/seller fields. |
| F5 | 1,075 call sites of `FACTIONS.player()` / `GAME.player()` in 310 files | Host world logic must treat every human as "a player". | **Hook** (many) | Classified in [single-player-assumptions.md](single-player-assumptions.md): about 363 host-side lines in about 140 files. The largest group, "is this faction a human?", is 139 one-line checks in 68 files. |
| F6 | `game/faction/diplomacy/DipStance.set(instigator, accepter)`, `DIP.get(a, b)` | Treaties between players, applied by the host (spec §6). | **API**: stances are a general faction-to-faction table | — |
| F7 | `game/faction/diplomacy/deal/Deal`: one `player` side and one `npc` side; `DealParty.execute` | Player-to-player offers and acceptance (spec §6: no opinion requirement). | **Mod UI + API** to apply the result (stance, goods, regions) | The vanilla deal screen is player-to-NPC only. Reusing it for player-to-player would need **hooks in `view/`**; drawing the mod's own screen through `render`/`mouseClick` avoids that. To be decided in the diplomacy story. |
| F8 | `game/faction/royalty/opinion/*` (`ROPINION`), one-argument `DIP.get(npc)` / `DipStance.is/set(npc)`, `DipWarPlayer`, `Deal`, `UpVassal` | Each NPC keeps opinion, stance history, war plans and tribute **per human**. | **Hook** | The data is stored per NPC toward one player; nothing outside can split it. [single-player-assumptions.md](single-player-assumptions.md) G2; SP1 decided: per human. |
| F9 | `game/events/faction/player/*`, `EventFactionWar`, `EventFactionExpand`, `EventWorldRebellion`, `game/raiding/*` | World events and raiders target a chosen human; raids into a city run on its owner's PC. | **Hook** | Targets are hard-wired to `FACTIONS.player()`. [single-player-assumptions.md](single-player-assumptions.md) G6; SP2 decided: each human's own timers and raiders. |
| F10 | Reads of `Player` state from world code (`credits()`, `level()`, `titles`, `race()`, `offensivePower()`, `emissaries`) | For a remote human, the host uses values from that human's latest city report. | **Hook** | Remote humans are `FactionNPC` puppets on the host and have no `Player` state. [single-player-assumptions.md](single-player-assumptions.md) G7. |

### Battle start and end (spec §8)

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| B1 | `world/battle/WBattles.poll` → `PFieldBattle.create` → `Resolver.init` (private) → `ResolverPlayer.battle/sallyOut/besige` | When human armies meet, the host must (a) force a real-time battle (no auto-resolve or retreat for player against player), (b) send the invite and spectate prompts to every player, and (c) leave NPC-only battles to vanilla. | **Hook** at the dispatch in `Resolver.init` / `ResolverPlayer.battle` | Private flow. `BattleListener` fires only after a battle is resolved, too late to change how it starts. |
| B2 | `game/battle/state/BattleState.setGenerate(BattleStateExiter, BattleStateSpec)`; `snake2d/util/rnd/RND.setSeed(int)` | Only the host generates the battle (vanilla `setGenerate`) and sends the battle save; peers load it (B14) and seed the simulation stream after loading. | **API** | Was a hook while every PC generated from a seed (saving draws from `RND`, SOS-10). With peers loading the host's save ([ADR 0003](../decisions/0003-battle-lockstep.md)) no hook is needed. |
| B3 | `game/battle/Armies` (`ARMIES`) and `game/battle/thread/BattleThreads`: five `SlaveThread`s (orders, centres, status, trajectories, `Strategos2000`) | Lockstep ([ADR 0003](../decisions/0003-battle-lockstep.md)). On every PC, human orders are applied at the step the host confirmed, and a human-controlled army gets **no** AI general. No display-only mode is needed. | **Hook** (several) | The AI general runs inside the battle jobs for both sides, and orders have no way in at a chosen step. |
| B4 | `BattleStateExiter.exit` (public abstract) → reloads `__beforeBattle` → `afterExit(result)` | The host decides the result; each PC reloads its own pre-battle save; the host applies losses to the world. | **API**: the mod passes its own `BattleStateExiter` | — |
| B5 | `BattleState.view()` sets `SPEED.speedSet(0)`; `GAME.SPEED` during the battle | Battle speed by commanders' majority vote (spec §8). | **API** once P1 exists | — |
| B6 | `game/battle/Armies.player()/enemy()` (165 uses), `Army.faction()` (`Army.java:100`), `Div.faction()`, `DivInfo.faction()` | Battles between humans: each side's faction is the real human; with allies, several humans per side and per-division ownership (spec §8). | **Hook** | The engine has two sides with one fixed faction each (`FACTIONS.player()` / `FACTIONS.otherFaction()`). [single-player-assumptions.md](single-player-assumptions.md) §2, question SP4. |
| B7 | `game/GAME.java:241` `update(ds, slowDown)`, sub-step loop; `view/battle/BattleView.update` (runs `BattleState.update`) | Battles advance in fixed 1/60 s steps from an accumulator, numbered from deploy; `BattleState.update` runs inside the step; nothing runs while paused. Each step first applies the AI log entries due at it (B15). | **Hook** (spike patch 30) | The sub-step length comes from frame time inside a private loop. Shares the loop with A1. |
| B8 | `game/battle/thread/BattleThreads.update` (empty in vanilla), `BattleThread.start`, `snake2d/SlaveThread.working` | Four battle jobs run inside the step in a fixed order and beat (orders, centres, trajectories, status every 30 steps); their real threads never start. The AI general's thread starts on the host only, and is off on peers (B15). | **Hook** (spike patch 40) | The jobs are protected and run on their own wall-clock threads. `working()` must be true only during a job, or `BattleOrders.init` restarts the real thread (found in SOS-11). |
| B9 | `settlement/path/thread/FinderThread.prep` | Paths requested during a battle are computed at once, on the simulation thread. | **Hook** (spike patch 40) | Answers otherwise arrive from a worker thread a varying number of frames later. |
| B10 | `snake2d/util/rnd/RND`, plus cosmetic call sites: `TForest.update`, `game/audio/*` (6 files), `game/time/Light.update` | Two random streams: simulation draws only inside the step on the simulation thread, everything else cosmetic. Visual and audio code always draws cosmetic. | **Hook** (spike patches 20, 60, 80, 85) | `RND` is a static class used everywhere. Tree sway, sound picks (skipped at zero volume) and night light flicker (only with the light-cycle setting on) draw from it inside the step. |
| B11 | `util/updating/TileUpdater` static initializer | The city-map tile visiting order is the same in every game process. | **Hook** (spike patch 70) | The order is shuffled once per process at class load, from an unseeded generator. |
| B12 | `BattleState.deploy()` → `GAME.setGameStart` → `BattleThreads.unpause(true)` → `Strategos2000.init` | Battle start is step 0: deploy runs inside the simulation context, so AI start-up (`Context.flanking`) draws from the simulation stream. | **API** once B10 exists | `deploy()` is public; the engine sets the simulation context around it (ADR 0004). |
| B13 | `SETT.ENTITIES().getAllEnts()`, soldier fields, `GAME.ARMIES().divisions()` | A battle state hash every few steps for desync detection, and a desync report. | **API** | All read through public accessors (the spike harness does this). |
| B14 | `game/save/GameLoader` with `doAfterSet` → `BattleState.setLoaded(exiter, path, true)` | Every PC loads the host's battle save at battle start and on resync. | **API** | Both public. AI state that isn't saved (`flanking`) still needs B12-style handling after a resync load. |
| B15 | `game/battle/thread/general/Strategos2000` and its offence steps (`StepAttackEnemyNear`, `StepAttackOthers`, `StepLinesMoveTo`, `Strategos2000UpdaterOffense`, `StepArtilleryBombard`), `game/battle/thread/order/BattleOrder.Locked.set`, `BattleOrders`, `settlement/thing/projectiles/Updater.test` | The AI general runs on the host only ([ADR 0003](../decisions/0003-battle-lockstep.md) amendment, SOS-23). Each of its writes is captured into the AI log instead of applied: locked orders (path, destination, task), division settings (guard, formation, running, fire at will) and artillery targets. Entries are stamped for step N + 30, and every PC applies them at that step (B7). | **Hook** (spike patch 96) | The general writes orders and settings directly from its own thread, at 11 call sites plus `Locked.set`. Its projectile checks share a static vector with the trajectories job, so its thread needs its own. |

### Sieges (spec §9)

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| S1 | `world/battle/PSiege` (`besige`, `besigedTime`, `poll`) | Spec §9.2 waiting period (1 season per started 1,000 people, at most 4) before an assault on a **player** city; blockade, so no trade. | **Hook** in `PSiege` where the assault becomes possible | The vanilla siege timing is internal. Needs a closer read of `PSiege`. |
| S2 | `ResolverPlayer.invadeCapitol` → `SETT.INVADOR().invade(InvasionSpec, WArmy)` | The assault runs in the defender's live city (matches spec §9.3); the defender's PC starts it from the host's command. | **API** to start | — |
| S3 | Invader AI in `settlement/` (the `INVADOR` divisions) and battle threads | Attacker's orders drive the invading divisions instead of AI; the city is streamed to viewers (architecture §Siege assault). | **Hook** | Same reasons as B3. |
| S4 | Outcome: sack, eliminate, vassalage (spec §9) | Take chosen goods, money and up to 80% of the population; make the city a neutral NPC; or make the defender a vassal. | **API** (`FCredits.inc`, the city's resource removal, `DipStance.VASSAL().set`, `FACTIONS.remove`) | "Move population to the attacker's city" has no vanilla equivalent and needs a design. |

### Save and load (spec §11)

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| L1 | `game/save/AutoSaver` reads the `autoSaveInterval` setting | No local autosaves; only the host triggers them. | **API**: set the setting to 0 (**reflection** if it isn't publicly writable) | — |
| L2 | `GameSaver.save(Path, name, minified)` | The save barrier: save at host time T. | **API** (combined with A1 for an exact T) | — |
| L3 | `GameSaver.add(Savable)` / script `save/load` | Mod state: peer IDs, ledger, campaign ID, world tick, protocol version. | **API** | — |
| L4 | `GameLoader` loads a whole file; `GameSaver.load(f, list)` is private and keyed | Campaign save = host world plus each player's city. Load your own city sections and the host's world sections. | **Reflection** (per-section load through the private keyed loader), or a **hook** | Sections refer to each other (`SETT` ↔ `WORLD`; `WORLD.load` calls `SETT.WORLD_AREA()`), so mixing sections from two saves may not work. **Q3.** |
| L5 | `BattleState.setGenerate` writes `__beforeBattle` and `__battle` into the normal save folder | Battles must not overwrite each other or clash with campaign saves. | **API** (file names are fixed; acceptable) | — |

### City boundary: goods, money, soldiers, caravans (spec §7, §11)

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| C1 | Goods **in**: `world/entity/caravan/Shipment.arrive` → `faction.buyer(res).addDeliver(am, type)` | On the host, an arrival at a puppet becomes a ledger entry to its owner. On the owner's PC: `FACTIONS.player().buyer(res).addDeliver`. Exactly once, with a transaction ID. | Host side: **F4**. Owner side: **API**. | — |
| C2 | Goods **out**: `FSELLER.remove(amount, type, price, buyer)` (player side: `settlement/trade/PSeller*`) | The owner's PC removes goods when the host confirms a sale; two-phase (architecture §Core rule). | **API** on the owner side; host side through F4 | — |
| C3 | Caravans created by `TradeManager.ship:220`, `DealParty:115`, `RDOutputs:221`, `Shipper:56`, battle spoils | Host-owned entities, visible on every map (spec §7). Deleted with their goods when their city disconnects (spec §11). | **API** (`caravans.create`, entity removal) | — |
| C4 | Money: `FCredits.inc(amount, CTYPE)` (`game/faction/FCredits.java:121`; `PCredits` for the player) | Trade payments, tribute, sack. | **API** | — |
| C5 | Soldiers **out**: `settlement/entity/humanoid/ai/subject/PlanJoinArmy.java:64` → `AD.cityDivs().add(humanoid, div)`; supplies taken from the stockpile in `WDivStoredAll.attach`. Soldiers **back**: `WDivStoredAll` `popSoldier` at the city entry. | Leaving: a city → world transaction. Returning: world → city. The soldier records travel inside the host-owned army. | **Hook** | `AD.cityDivs()` exists once, for the local player. The host can't hold another player's stored city divisions without code changes. Part of task 3. |
| C6 | Regional taxes and tribute caravans to a capital (`RDOutputs`, `Shipper`) | Delivered to a remote player's city through C1. | **API** once F4 exists | — |

### Pause and speed (spec §4)

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| P1 | `game/GameSpeed` (`final` class). Writers: `UISpecials:149-221` (UI buttons, `poll` for keys), `UIMiniMapSettView:163`, `GAME.Notify/Warn` (`speedSet(0)` on popups), `BattleState`, `SETT.java:405` | Every local speed or pause change becomes a command to the host; the host's speed applies everywhere; show who paused. | **Hook** in `GameSpeed.speedSet/togglePause` (one file) | A script could detect changes and undo them the next frame, but that fights the game every frame and still lets one frame run at the wrong speed. |
| P2 | `Updater.slowDown` → `GameSpeed.update` | Each PC reports its achievable speed; the host caps the world speed. | **API** (`SPEED.speed()`), enforced by A1 | — |
| P3 | Priority pause, "who paused" banner | Host-only unpause (spec §4, §11). | **API** (mod UI through `render`) once P1 exists | — |
| P4 | `view/main/VIEW.java:221-227`: when the window manager or the current view's UI blocks, the frame's `ds` becomes 0 and `slowDown` 1.0 | Spec §4 "Menus don't pause": an open menu or window must not stop the city. | **Hook** (let `ds` through in multiplayer) | Local variables inside `VIEW.update`; no API or field to change. |
| P5 | `game/GAME.java:386-430` `Notify` / `Warn` call `SPEED.speedSet(0)` | Spec §4: notification popups don't pause in multiplayer. | **Hook** (skip `speedSet(0)` in multiplayer) | Inside the game's static helpers; with P1 in place it would otherwise become a pause request. |

### City viewing (spec §10)

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| V1 | `Proccesser.exec(...)`, `GameLoader` | Open a received city snapshot in a second, read-only game process. | **API** to launch; **reflection or hook** in the viewer process to block input and simulation | Covered by spike 4.3 (two processes, RAM use). |

## 5. Areas with nothing to hook

- **Inside a player's own city** (buildings, citizens, production): the city stays local and unchanged (spec §1, "zero latency"). The only touch points are the boundary hooks C1 to C6.
- **Rendering and the camera:** the mod draws its UI through `SCRIPT_INSTANCE.render`.

## 6. Hooks needed, by file (candidates, not approved)

| Game file | Hook IDs | Size estimate |
| --- | --- | --- |
| `game/GAME.java` | A1 (and A2 if not done by reflection), B7 | 1–2 calls, plus the fixed-step loop (about 20 lines in the spike) |
| `view/battle/BattleView.java` | B7 | 1 line |
| `game/battle/thread/BattleThreads.java`, `BattleThread.java`, `snake2d/SlaveThread.java` | B8 | about 25 lines |
| `settlement/path/thread/FinderThread.java` | B9 | about 10 lines |
| `game/battle/thread/general/Strategos2000.java` and 5 offence steps, `BattleOrder.java`, `BattleOrders.java`, `settlement/thing/projectiles/Updater.java` | B15 | 11 setting writes and 1 artillery call redirected, a capture check in `Locked.set`, thread start on the host; the log itself is engine code (`SosMpAiLog` in the spike; ADR 0004) |
| `snake2d/util/rnd/RND.java`, `settlement/tilemap/terrain/TForest.java`, `game/audio/*` (6 files), `game/time/Light.java` | B10 | about 40 lines in 10 files |
| `util/updating/TileUpdater.java` | B11 | 2 lines |
| `game/GameSpeed.java` | P1 | 2 calls |
| `view/main/VIEW.java` | P4 | 1 call |
| `game/GAME.java` (`Notify`, `Warn`) | P5 | 2 calls |
| `game/faction/royalty/opinion/**`, `game/faction/diplomacy/**` | F8 | data change per human (SP1, decided: per human) |
| `game/events/faction/**`, `game/events/world/**`, `game/raiding/**` | F9 | each human's own timers and raiders (SP2, decided); size unknown until designed |
| World code reading `Player` state | F10 | about 60 lines (G7) |
| `world/map/fow/FOW.java`, `world/WorldMinimap.java`, overlays | W5 | with A2 |
| `game/battle/Armies.java`, `Army.java`, `div/Div.java`, `div/DivInfo.java` | B6 | unknown; SP4 |
| `game/faction/npc/FactionNPC.java`, `game/faction/FACTIONS.java`, `game/faction/trade/TradeManager.java` | F2, F4 | 3–4 calls |
| `world/army/ai/WArmyAI.java`, `world/army/ai/Recruiter.java` | F3 | 2 calls |
| `world/battle/Resolver.java`, `world/battle/ResolverPlayer.java` | B1, F5 (part) | 2–3 calls |
| `world/battle/PSiege.java` | S1 | 1–2 calls |
| `game/battle/**`, `settlement/**` invader AI | B3, S3 | unknown; orders at a step and no AI general for humans |
| `world/army/WDivStoredAll.java`, `PlanJoinArmy.java` | C5 | unknown; task 3 |
| 68 files with "is this faction a human?" checks (G1) | F5 | 139 one-line calls |

## 7. Open questions and risks (raised, not decided)

**Q1. The lockstep battle plan vs the vanilla battle engine. Decided 2026-10-20:** option B, host-authoritative streaming ([ADR 0002](../decisions/0002-battle-streaming.md)). **Changed 2026-10-21:** lockstep after all ([ADR 0003](../decisions/0003-battle-lockstep.md)); spike SOS-11 made the engine deterministic, and siege assaults stay open. Background: `docs/architecture.md` §Field battles planned deterministic lockstep. The vanilla engine works against it in four ways:

- **Wall-clock worker threads.** Battles run five `SlaveThread`s (`game/battle/thread/BattleThreads.java`). Each ticks on its own wall-clock interval, alongside the update thread.
- **Wall-clock AI general.** `Strategos2000UpdaterOffense.java:67` reads `System.currentTimeMillis()`.
- **Shared random numbers.** `RND` is one randomly seeded global `Random` shared by all threads. It's used 36 times in `game/battle/` and 33 times in `settlement/entity/humanoid/ai/battle/`.
- **Float timesteps.** Sub-steps use variable `double` timesteps.

Making this deterministic needed more than these four. SOS-11 also found tree sway, sound and light flicker drawing from the shared generator, a per-process tile order, and AI start-up outside the simulation. All of them are fixed by points B7–B12 ([report](spikes/determinism.md)). The clock read at `Strategos2000UpdaterOffense.java:67` turned out to be timing-only and harmless.

**Q2. Cost of a field battle.** Each battle saves the whole game twice (`__beforeBattle`, `__battle`) and reloads it when the battle ends, on every PC in the battle. Spectators would do the same. The late-game save-time spike (4.2) should time this; spec §15 has no battle-start time limit today.

**Q3. Campaign save layout.** Spec §11 and the architecture describe "world save plus every player's city save". Vanilla saves world and city together in one file, and their sections refer to each other. Simpler option: each player keeps a normal full save (own city plus their mirror of the world), and on resume the host's world sections are pushed as a snapshot (W3). To be settled in an ADR after the save spike.

**Q4. Steam App ID inside the game process.** The game calls `SteamAPI.init()` for its own App ID (1162750) when started from Steam, and one process can initialize Steam only once. The working copy also has no `steam_appid.txt`. **Owner direction 2026-10-20:** during development the game process itself may have to run under App ID 480 (Spacewar), for example by starting it outside Steam with a `steam_appid.txt` of 480. Spike 4.4 should test this.

**Q5. A local menu slows everyone down. Decided 2026-10-20:** menus don't pause in multiplayer (spec §4, version 0.4). Enforced by hook P4. Notification popups (`GAME.Notify` / `GAME.Warn`) don't pause either (owner, 2026-10-20; spec §4 version 0.6), enforced by hook P5.

**Q6. Steam updates the game. Decided 2026-10-20:** no compatibility with game updates is expected (spec §2 and §3: the version is pinned, and updating the mod for new game versions is out of scope). The distribution task (planning task 5) only needs to make sure every player runs V71; nothing is planned for other versions.

**Q7. `keyPush` is dead.** `SCRIPT_INSTANCE.keyPush` is never called in V71. Chat input can't rely on it; the mod has to read keys another way (polling `KEYS`, or a hook).

## 8. What this needs next

- **Planning task 3 (single-player assumptions):** done in [single-player-assumptions.md](single-player-assumptions.md). It added W5, F8, F9, F10 and B6.
- **Several human factions:** F1–F5 and F8–F10 are engine work under SOS-46 (2026-10-25); the mod's requirements are in [player-slots.md](player-slots.md).
- **Planning task 4 spikes:**
  - 4.1 battle streaming ([SOS-10](spikes/battle-streaming.md)) and battle determinism ([SOS-11](spikes/determinism.md)): done; field battles use lockstep, points B7–B15 (B15 from SOS-23);
  - 4.2 save size and time answers Q2 and Q3;
  - 4.3 two processes answers V1;
  - 4.4 Steam P2P answers Q4.
- **ADR on hook distribution (task 5):** decides A2 (reflection or hook) and how the hooks in §6 reach players.
- **Questions for the owner:** none open from this map.
