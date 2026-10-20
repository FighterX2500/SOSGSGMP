# Hook surface map (V71)

Card: [SOS-3](https://fighterx2500.atlassian.net/browse/SOS-3) · Date: 2026-10-20 · Game: 0.71.44, source in `%SOS_GAME_DIR%\info\SongsOfSyx-sources`

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
4. **Vanilla battles are not deterministic.** They run on five worker threads that tick on wall-clock time, with an AI general that reads `System.currentTimeMillis()` and a global, randomly seeded RNG. This ruled out the lockstep plan: battles are now simulated on the host and streamed ([ADR 0002](../decisions/0002-battle-streaming.md), §7 Q1).
5. **A vanilla field battle replaces the city in memory.** It saves the whole game, generates the battlefield inside the city engine, and reloads the save when the battle ends. That fits spec §4 ("battles pause the world and every city"), but it costs a full save and a full load on every PC in the battle (§11, question Q2).

Tally of the 41 points in §4:

| Route | Count | Notes |
| --- | --- | --- |
| API | 19 | Several work only once a hook elsewhere exists, for example B5 after P1. |
| Hook | 12 | |
| Hook or reflection | 4 | Decided per point. |
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
| One global RNG, `snake2d/util/rnd/RND`: a static `java.util.Random`, randomly seeded at startup, shared by every thread. | `RND.java` | Nothing that uses it is reproducible across PCs (see Q1). |
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

### Faction and player handling (spec §5, §6, §11)

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| F1 | `game/faction/FACTIONS.java`: 64 slots, 1 `Player` and 63 `FactionNPC`; `activateNext`, `remove` | Each other player appears as a puppet `FactionNPC`. Up to 16 players fit (1 local and 15 puppets), leaving 48 NPC slots. | **API** (`activateNext`, `remove`, `getByIndex`) | — |
| F2 | `FactionNPC.update` (`game/faction/npc/FactionNPC.java:179`), `FACTIONS` updater (`FACTIONS.java:40`), `TradeManager.buy` (`game/faction/trade/TradeManager.java:194`) | A puppet runs **no** NPC economy, AI requests or trading on its own. | **Hook**: early return when the faction is a remote player | These are protected or package-private methods called inside the game loop. Nothing can skip them from outside. |
| F3 | `world/army/ai/WArmyAI.update` (`planForWar`), `world/army/ai/Recruiter` | No NPC war planning or army recruiting for puppet factions. | **Hook** | Same as F2. |
| F4 | `Faction.buyer(res)` / `seller(res)` for a `FactionNPC` (NPC stockpile) | On the host, trade with a puppet must go to the remote player's real city through the ledger, not to an NPC stockpile. | **Hook** (return a ledger-backed `FBUYER`/`FSELLER`), or **reflection** if the backing field can be replaced | Needs a closer look at `FactionNPC`'s buyer/seller fields. |
| F5 | 1,047 × `FACTIONS.player()` (`world/` 51 files, `game/` 73, `settlement/` 68, `view/` 87) | Host world logic must treat every human as "a player". Examples: `Resolver.init` checks `looser.player`; `ResolverPlayer.battle` shows the battle UI only for `FACTIONS.player()`; `TradeManager.sellPlayer`; `WArmy.java:230` sends spoils to `FACTIONS.player()`. | **Hook** (many) | **Planning task 3** will sort them into "local player is right" (most of `settlement/` and `view/`) and "means any human" (most of `world/` and `game/`). |
| F6 | `game/faction/diplomacy/DipStance.set(instigator, accepter)`, `DIP.get(a, b)` | Treaties between players, applied by the host (spec §6). | **API**: stances are a general faction-to-faction table | — |
| F7 | `game/faction/diplomacy/deal/Deal`: one `player` side and one `npc` side; `DealParty.execute` | Player-to-player offers and acceptance (spec §6: no opinion requirement). | **Mod UI + API** to apply the result (stance, goods, regions) | The vanilla deal screen is player-to-NPC only. Reusing it for player-to-player would need **hooks in `view/`**; drawing the mod's own screen through `render`/`mouseClick` avoids that. To be decided in the diplomacy story. |

### Battle start and end (spec §8)

| ID | Game location | Multiplayer needs | Route | Why not API or reflection |
| --- | --- | --- | --- | --- |
| B1 | `world/battle/WBattles.poll` → `PFieldBattle.create` → `Resolver.init` (private) → `ResolverPlayer.battle/sallyOut/besige` | When human armies meet, the host must (a) force a real-time battle (no auto-resolve or retreat for player against player), (b) send the invite and spectate prompts to every player, and (c) leave NPC-only battles to vanilla. | **Hook** at the dispatch in `Resolver.init` / `ResolverPlayer.battle` | Private flow. `BattleListener` fires only after a battle is resolved, too late to change how it starts. |
| B2 | `game/battle/state/BattleState.setGenerate(BattleStateExiter, BattleStateSpec)` (public static); `snake2d/util/rnd/RND.setSeed(int)` (public static) | The host starts the battle. Every viewer builds the same battlefield to display it on. | **API**: call `RND.setSeed(hostSeed)`, then `setGenerate` | — (The battle streaming spike confirms that the same seed gives the same map on two PCs.) |
| B3 | `game/battle/Armies` (`ARMIES`) and `game/battle/thread/BattleThreads`: five `SlaveThread`s (orders, centres, status, trajectories, `Strategos2000`) | Streaming ([ADR 0002](../decisions/0002-battle-streaming.md)). **Host:** human orders are applied on arrival; a human-controlled army gets **no** AI general; the battle state is read every ~100 ms for snapshots. **Viewers:** display-only, so no battle simulation or threads run, and units are positioned from snapshots. | **Hook** (several) | The battle update and threads are internal, and nothing can stop them or set unit state from outside. |
| B4 | `BattleStateExiter.exit` (public abstract) → reloads `__beforeBattle` → `afterExit(result)` | The host decides the result; each PC reloads its own pre-battle save; the host applies losses to the world. | **API**: the mod passes its own `BattleStateExiter` | — |
| B5 | `BattleState.view()` sets `SPEED.speedSet(0)`; `GAME.SPEED` during the battle | Battle speed by commanders' majority vote (spec §8). | **API** once P1 exists | — |

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
| `game/GAME.java` | A1 (and A2 if not done by reflection) | 1–2 calls |
| `game/GameSpeed.java` | P1 | 2 calls |
| `view/main/VIEW.java` | P4 | 1 call |
| `game/GAME.java` (`Notify`, `Warn`) | P5 | 2 calls |
| `game/faction/npc/FactionNPC.java`, `game/faction/FACTIONS.java`, `game/faction/trade/TradeManager.java` | F2, F4 | 3–4 calls |
| `world/army/ai/WArmyAI.java`, `world/army/ai/Recruiter.java` | F3 | 2 calls |
| `world/battle/Resolver.java`, `world/battle/ResolverPlayer.java` | B1, F5 (part) | 2–3 calls |
| `world/battle/PSiege.java` | S1 | 1–2 calls |
| `game/battle/**`, `settlement/**` invader AI | B3, S3 | unknown until the battle streaming spike |
| `world/army/WDivStoredAll.java`, `PlanJoinArmy.java` | C5 | unknown; task 3 |
| Others from the `FACTIONS.player()` review | F5 | task 3 |

## 7. Open questions and risks (raised, not decided)

**Q1. The lockstep battle plan vs the vanilla battle engine. Decided 2026-10-20:** option B, host-authoritative streaming, for now ([ADR 0002](../decisions/0002-battle-streaming.md)). Background: `docs/architecture.md` §Field battles planned deterministic lockstep. The vanilla engine works against it in four ways:

- **Wall-clock worker threads.** Battles run five `SlaveThread`s (`game/battle/thread/BattleThreads.java`). Each ticks on its own wall-clock interval, alongside the update thread.
- **Wall-clock AI general.** `Strategos2000UpdaterOffense.java:67` reads `System.currentTimeMillis()`.
- **Shared random numbers.** `RND` is one randomly seeded global `Random` shared by all threads. It's used 36 times in `game/battle/` and 33 times in `settlement/entity/humanoid/ai/battle/`.
- **Float timesteps.** Sub-steps use variable `double` timesteps.

Making this deterministic would mean reworking the battle's threading, time and random numbers: a large set of hooks across `game/battle/**` and the soldier AI. Streaming avoids that; lockstep can be revisited if streaming bandwidth turns out too high.

**Q2. Cost of a field battle.** Each battle saves the whole game twice (`__beforeBattle`, `__battle`) and reloads it when the battle ends, on every PC in the battle. Spectators would do the same. The late-game save-time spike (4.2) should time this; spec §15 has no battle-start time limit today.

**Q3. Campaign save layout.** Spec §11 and the architecture describe "world save plus every player's city save". Vanilla saves world and city together in one file, and their sections refer to each other. Simpler option: each player keeps a normal full save (own city plus their mirror of the world), and on resume the host's world sections are pushed as a snapshot (W3). To be settled in an ADR after the save spike.

**Q4. Steam App ID inside the game process.** The game calls `SteamAPI.init()` for its own App ID (1162750) when started from Steam, and one process can initialize Steam only once. The working copy also has no `steam_appid.txt`. **Owner direction 2026-10-20:** during development the game process itself may have to run under App ID 480 (Spacewar), for example by starting it outside Steam with a `steam_appid.txt` of 480. Spike 4.4 should test this.

**Q5. A local menu slows everyone down. Decided 2026-10-20:** menus don't pause in multiplayer (spec §4, version 0.4). Enforced by hook P4. Notification popups (`GAME.Notify` / `GAME.Warn`) don't pause either (owner, 2026-10-20; spec §4 version 0.6), enforced by hook P5.

**Q6. Steam updates the game. Decided 2026-10-20:** no compatibility with game updates is expected (spec §2 and §3: the version is pinned, and updating the mod for new game versions is out of scope). The distribution task (planning task 5) only needs to make sure every player runs V71; nothing is planned for other versions.

**Q7. `keyPush` is dead.** `SCRIPT_INSTANCE.keyPush` is never called in V71. Chat input can't rely on it; the mod has to read keys another way (polling `KEYS`, or a hook).

## 8. What this needs next

- **Planning task 3 (single-player assumptions):** go through F5 and C5 one by one.
- **Planning task 4 spikes:**
  - 4.1 battle streaming (replaces battle determinism, [ADR 0002](../decisions/0002-battle-streaming.md)) answers B2/B3: snapshot size, bandwidth, latency, the same battlefield on every PC, display-only mode;
  - 4.2 save size and time answers Q2 and Q3;
  - 4.3 two processes answers V1;
  - 4.4 Steam P2P answers Q4.
- **ADR on hook distribution (task 5):** decides A2 (reflection or hook) and how the hooks in §6 reach players.
- **Questions for the owner:** none open from this map.
