# SOS-93: NPC field battles on their own clock. Spike, spec drafts and plan

Draft, 2026-10-30. Read-only research: nothing was built or run. Paths are relative to each repository:

- `SOSDET/` is `C:\ClaudeFolder\SOSDET`.
- `SOS/` is `C:\ClaudeFolder\SOS`.
- `game/` is the game source, `C:\ClaudeFolder\Songs of Syx\info\SongsOfSyx-sources`.

Line numbers are from the files as they were on 2026-10-30 (SOSDET at `7218dd2`, SOS at `6aa955b`).

**Verdict in short:** it looks feasible. No obstacle found is a wall. Each one has a fix that already has a model in the engine, mostly from city views (SOS-82) and assaults (SOS-92). The biggest pieces are these:

- one game clock per JVM, shared by the battle and the world: the step loop, `GAME.updateI`, `TIME` and `GAME.SPEED`;
- the battle's return, which today reloads the whole game and so would roll back the world;
- the result, which today is a set of live game objects and must become a command.

The spike below tests the first piece, which is the riskiest.

---

## 1. Today: a player's field battle against an NPC army in own-city mode

This is plan 0006 M1 (SOS-61), `SOSDET/docs/plan/0006-own-city-battles.md`.

**Engage.** The prompt's Engage is a world command, `DECIDE` with `ENGAGE`, applied in the world part on every PC (`SOSDET/src/main/java/sosdet/game/cmd/WorldBattles.java:86`, `:118-120`). It calls `Hooks.ownCityEngage(asked, spec::engage)` (`SOSDET/src/main/java/sosdet/Hooks.java:2184`), which does three things:

- It records an `OwnBattle` (`SOSDET/src/main/java/sosdet/OwnBattle.java:8`) with the step, the human, that human's PC and the world draw count.
- It ends every city view (`battleEndsViews`).
- It runs `spec.engage()` on the battle stream, then puts the world stream back.

`spec.engage()` is vanilla's `ResolverPlayer.Manual` (`game/world/battle/ResolverPlayer.java:346-358`). It calls `BattleState.setGenerate` (`game/game/battle/state/BattleState.java:54-69`): this saves `__beforeBattle`, generates the battle map into SETT and saves `__battle`. So **every PC generates a battle from its own city**, and all but one are thrown away.

**The hold, and which PCs run the battle.** Every PC holds before step N + 1 while `battle.awaiting` is true (`Hooks.java:228`, `:233`). The battle PC's engine hands out its `__battle` save once (`Engine.battleOut`, `SOSDET/src/main/java/sosdet/Engine.java:368`). The mod sends it (`SOS/mod/src/main/java/sosgsgmp/game/EnginePlay.java:79-88`). **Every PC, the battle PC too, loads it** with `Engine.battleIn` (`Engine.java:382`, called from `EnginePlay.java:93-102`). From then on every PC runs the same battle in lockstep:

- **Session state.** `Session.battleLoaded` (`SOSDET/src/main/java/sosdet/Session.java:1255`) turns the hash from world-only to whole-game. The host stamps a commanders table (`commandBattle`, `Session.java:1278`).
- **The player.** `FACTIONS.player()` is the battle's human on every PC, even in the world part (`Session.humanNow`, `Session.java:1030-1033`).
- **City commands** apply on every PC (`Hooks.cityCommandHere`, `Hooks.java:522-526`).

**Is the world held? Yes, completely.**

- **No world updates.** Inside the step, `VIEW.b().isActive()` is true on every PC (patch `65-shared-ui-state`, `VIEW.java`, through `Hooks.battleView`, `Hooks.java:1823`). The step loop then updates only the `isBattle` resources (`SOSDET/patches/10-fixed-step.patch:52,56`, the `!battleView && !worldHeld()` gate). `WORLD`, `FACTIONS`, `RAIDING`, `EVENTS`, `NOBILITIES` and `TOURISTS` have `isBattle = false` (for example `game/world/WORLD.java:286`), so they don't run.
- **No reports.** `stepPart` takes and reads no city reports while a battle runs (`Hooks.java:461-488`).
- **Any change is undone.** Whatever the world did is thrown away at the end anyway.

**The end and the result.**

- Retreat or Close is a command (patch `99-map-battles`). Vanilla's `BattleStateExiter.exit` (`game/game/battle/state/BattleStateExiter.java:11-22`) builds a `BattleStateResult` from the live battle map. That result holds `Induvidual[]` survivors per division (`game/game/battle/state/BattleStateResult.java:28-87`).
- Then **every PC loads its own `__beforeBattle`**, so its city and the world come back as they were at step N.
- `afterExit` runs on a stream seeded from the step (`Hooks.battleReturned`, `Hooks.java:2154-2172`). That is `Manual.afterBattle` (`ResolverPlayer.java:360-435`), which opens the result prompt for the battle's human.
- **Accept is a world command** (`RESULT_ACCEPT` 7901, `WorldBattles.java:37`, `:55`). It resolves the world divisions with `WDIV.resolve(Induvidual[])`, ships the captives and loot, and teleports the loser.

**The mod's side.** `Engine.battleHold(true)` saves the campaign before every battle (`EnginePlay.java:63`, SOS-70). The mod ships and loads the battle save. Spec §4 and §8 describe exactly this.

---

## 2. The core question: can one PC run a battle map while every PC keeps stepping the world in lockstep?

In short: the engine has **one step clock**. Each step runs a city part, then a world part (ADR 0005, `SOSDET/src/main/java/sosdet/game/StepParts.java`). The battle now runs in place of the city part, on every PC, while the world part is switched off.

SOS-93 needs a second shape on the battle PC only:

- the world part runs every step, in lockstep, at the agreed speed (1x);
- the battle runs in place of the city, at the battle's own speed and pause.

Every other PC runs its own city and the world as usual, and sees the battling human **frozen**, as it sees a viewer (SOS-82).

### Recommended shape: battle ticks inside the world step

Run the battle as **0 to k battle ticks inside each world step**, on the battle PC only. k comes from the battle's own speed:

| Battle speed | Battle ticks |
|---|---|
| Paused | none |
| 1x | 1 per step |
| 3x | 3 per step |
| below 1x | 1 every few steps |

The battle's speed and pause change at a step, as small local entries.

This keeps one session clock, one log, one hash cadence and one pacer. It also makes watching possible later. A watcher loads the battle PC's game at a known step, as a viewer loads an owner's city (`Engine.viewOut` / `viewIn`, `Engine.java:520`, `:550`), and runs the same ticks at the same steps. That only works if the battle ticks are deterministic. They already are in a session: the battle jobs run in the step (hook B8, patch `30-battle-jobs`), and the AI general can run in the step (`AiMode.IN_STEP`).

The other shape was rejected: the vanilla battle per frame, outside the step. It is simpler for the battle PC alone, but its draws come from the cosmetic stream (`Streams.pick`, `SOSDET/src/main/java/sosdet/Streams.java:37-41`) and its jobs from wall-clock threads, so no watcher could ever follow it.

### Obstacles

Each row says what is shared or in the way, where it is, and whether it looks solvable. "Not checked" means no one has run it.

| # | Obstacle | Where | Solvable? How |
|---|---|---|---|
| O1 | **The step loop gates the world off whenever a battle view is open.** Inside the step, `battleView` is true when `VIEW.b().state() != null` on this PC, and then only `isBattle` resources update. | `patches/10-fixed-step.patch:47-66` (`GAME.java`); `patches/65-shared-ui-state.patch:172`; `Hooks.battleView`, `Hooks.java:1823-1826` | Yes. Split the gate by part. In the city part on the battle PC, run the battle ticks (the `isBattle` resources and `BattleState.update`, k times). In the world part, run the world resources whatever this PC's view is. The world part must see "no battle" so that it runs as on the other PCs. |
| O2 | **World code asks "is the battle view open?"** `WBattles.canPoll` returns `!VIEW.b().isActive()`. On the battle PC the world would stop polling world battles, but not on other PCs: a desync. | `game/world/battle/WBattles.java:104-106`; the other callers of `VIEW.b().isActive()` are city code (`EntryUpdater`, `SENTRY`, `StatsReproduction`, disease, weather, `GCOUNTS`) | Yes. In the world part, `Hooks.battleView` answers false on every PC (only a world-held battle, today's kind, answers true). Audit the 15 files that read `VIEW.b().isActive()` (listed by grep) for any that runs in the world part. Only `WBattles` is world code. |
| O3 | **`TIME` and `TEMP_EVENTS` are world state, but they update in the city part.** Both are `isBattle = true`, so battle ticks would also advance them, k times per step. Both are world sections (`GameWorldLoad.KEYS`), and the calendar is in the world hash (B13). | `game/game/time/TIME.java:58`, `:189-205`; `game/game/boosting/tmp/TmpBoosting.java:49`; `SOSDET/src/main/java/sosdet/game/GameWorldLoad.java:70` | Yes. In a local battle, these two update once per world step, not per battle tick. The battle sees the world's time of day; this is cosmetic, but not checked. Also not checked: `INTER` (`Intervals`, `isBattle` true), `COUNTS`, `SCRIPTS` (the mod and the rig are scripts) and `ENGINE`. Each must run either per world step or per battle tick, never both. The spike's world hash finds any mistake fast, because the calendar is hashed. |
| O4 | **One tick counter, `GAME.updateI`.** World caches key on it: `RDBoostCache` uses `(updateI>>5)&1`, and so do `RDMilitaryGar`, `WRegFinder`, `RDBuildPoints` and `RDRace`. The battle's Start calls `GAME.setGameStart()`, which sets it to -1 on the battle PC only. A battle save's load also sets it. | `game/game/GAME.java:80`, `:303-305`; `BattleState.deploy`, `BattleState.java:140-143`; `game/world/region/RDBoostCache.java:32` | Yes. Battle ticks don't count `updateI`; only world steps do (patch 10 counts it at `stepBegin`). Skip `setGameStart` in a local battle, or put the count back after it. Put it back after the battle's load and after the return load too. Not checked: whether any battle code needs `updateI` to start at -1. |
| O5 | **One `GAME.SPEED`.** The battle's pause and speed controls write it, and the step count reads it (`stepsFor(ds, SPEED.speedTarget())`). `BattleState.view()` sets it to 0, and `MapBattles.setsSpeed` gates that in a session. | `patches/10-fixed-step.patch:20-25`; `game/game/battle/state/BattleState.java:95-98`; `game/view/battle/BattleView.java:113`; `SOSDET/src/main/java/sosdet/game/cmd/MapBattles.java:203` | Yes. In a local battle, the world steps follow the agreed speed only (`Engine.followAgreedSpeed` already exists). The battle's speed and pause go to a battle speed that sets k. Where the battle UI writes `GAME.SPEED`, a hook sends the value there. Not checked: every place in the battle UI that writes the speed. |
| O6 | **`OwnBattle` means "the battle every PC runs".** Its checks are spread out: the hash (`hashes.worldOnly(false)`), `humanNow` (the battle's human, even in the world part), `cityCommandHere`, reports off, no step past N while `awaiting`. | `Session.java:1030-1033`, `:1255-1270`; `Hooks.java:228-233`, `:304`, `:315`, `:461-468`, `:522-526` | Yes. Make a separate record for a local battle (a new class, say `LocalBattle`) and keep `OwnBattle` for battles between humans, which still hold the world. No `OwnBattle` check may fire for a local battle. |
| O7 | **Engage generates the battle on every PC, inside the world part.** In the new design only the battle PC may generate, and the world keeps running, so generating must change no world state. In M1 that never mattered: everything rolled back at the end. Generating also takes seconds (5.9 s of the Engage step in MB12, before SOS-41's fix). | `WorldBattles.java:118-120`; `Hooks.java:2184-2198`; `BattleState.setGenerate` (`BattleState.java:54-69`), `SETT.CreateFromWorldMap` (`game/settlement/main/SETT.java:345-393`) | Likely yes. Engage at step N only records the battle on every PC: the armies, who fights, and the freeze step F (the next multiple of 30, as for a view). Between steps at F, the battle PC saves its own game and generates, as `viewIn` does. Other PCs don't generate. Not checked: whether `CreateFromWorldMap` or the battle generator writes world state (for example `FACTIONS.otherFaction`, region data, or world draws). The spike's world hash right after generating answers it. |
| O8 | **A whole game's load resets world state that no save holds.** The battle PC loads the battle (if watchers are to follow it, it must load its own `__battle`, as the owner loads its save for a view) and loads `__beforeBattle` at the end. Plan 0013 §3 lists what such a load resets: move remainders, entity pools, the war-event timer, NPC court days, slave boosts. | `SOSDET/docs/plan/0013-city-viewing.md` §3; `GameWorldLoad.unsaved`; `Session.viewWorldReloads`, `Session.java:394` | Yes, already solved for views. Carry `GameWorldLoad.unsaved()` across the loads, and every other PC reloads its world at the same step (`viewWorldReloads`). |
| O9 | **The return rolls the world back.** Vanilla's `exit` loads `__beforeBattle`, the whole game, so the battle PC's world would go back to step N. | `game/game/battle/state/BattleStateExiter.java:11-22`; patch `99-map-battles` (`Hooks.battleLeaving` / `battleReturned`) | Yes, as at a view's thaw. Save the world, load the own game, load the world into it, put back the city's engine state, then apply the held transactions (`Session.viewBackStart` / `viewBackWorld`, `Session.java:347-392`; SOS-97's `HeldTxn`). This happens at a thaw step every PC knows. |
| O10 | **The result is live game objects.** `BattleStateResult` holds `Induvidual[]` from the battle map. `Manual` holds copies of the two world `Side`s, made at Engage on the battle PC only. | `ResolverPlayer.java:340-435`; `BattleStateResult.java:19-87`; `WDivStored.resolve`, `game/world/army/WDivStored.java:196-229` | Yes. The battle PC sends a **result command** through the host's log: result kind, losses, enemy survivors per division, player survivors and mean experience per division, and captives by race. Every PC applies it in the world part at its stamped step, building the `Side`s from the armies recorded at Engage and calling `WDIV.resolve(int survivors, double exp)`. The prompt and Accept follow as today. One open point: for `WDivStored`, `resolve(int, double)` keeps the first N soldiers, not the ones who actually lived (owner question Q6). Exactly once: the command carries the battle's id, and a second one is refused. |
| O11 | **The armies must stand still, and nothing else may fight them.** `armyHeld` holds only the open prompt's armies. With the world running, a third army could attack a held army, or the battling human could get a new battle prompt. | `Hooks.armyHeld`, `Hooks.java:422-441`; `WBattles` polling (not checked in detail) | Probably. Hold the battle's armies (a world-state list, the same on every PC) and leave them out of `WBattles` polls until the result applies. Not checked: whether a held army can still be the target of an intercept or a siege. The spike watches it with `warmystate`. |
| O12 | **The city is frozen, and the step's city work must skip it.** City paths, `GameProduction.refresh`, the city tail's transactions, the assault step and `takeReport` all run each step. | `Hooks.java:287-312`, `:461-468`; `Session.ownTxns`, `Session.java:255-262`; `Session.frozen`, `Session.java:293` | Yes. `frozen(slot)` is tied to `View` today. Freeze the battling human the same way, at F: no reports, held transactions, nobody waits for its reports. The frozen human's city work is skipped on the battle PC. |
| O13 | **The RNG streams.** | `Streams.java`; `Hooks.stepBegin` / `stepPart` reseed | Yes. The world part already reseeds from `worldSeed(step)` and counts only its own draws, so the battle ticks can't shift it. Each battle tick reseeds from a battle seed: the step, the tick index and the battle id. Battle draws must never happen with `worldPart(true)`. |
| O14 | **The hash.** In own-city mode the hash is world-only and counts world draws. The battle needs its own hash later, for watchers. | `Hooks.stepEnd`, `Hooks.java:312-334`; `HashLog` | Yes. The world hash stays as it is. A battle hash log for watchers would follow the view hash (`viewHashes`). It isn't needed for the spike. |
| O15 | **The battle jobs and the AI general on a peer.** In a session the jobs run in the step, beat counted from `s.clock.step()`. The general runs on the host's own thread, or in the step, or not at all on a peer, so a peer's local battle would have no enemy AI. | `BattleJobs.java`; `Hooks.battleJobsNow` / `runBattleJobs` / `threadRunsInStep`, `Hooks.java:1582-1600` | Yes. Run the jobs once per battle tick, beat counted in battle ticks, with the general in the step for a local battle (deterministic, so a watcher could run it too). Cost not measured: the spike times it. |
| O16 | **Pacing.** A big battle at 3x plus the world at 1x may be too slow on the battle PC. The pacer would then lower the speed for everyone (spec §4, down to 0.5x). | `SOSDET/src/main/java/sosdet/Pacer.java` | Partly. World steps come first. If the battle PC can't keep up, the engine lowers k (the battle slows), not the world speed. The owner decides this (Q3). |
| O17 | **Views, prompts, saves and resyncs during a local battle.** | `Session.battleEndsViews`; `Engine.worldResyncLoad` (`Engine.java:687`); `Engine.resyncSave` refuses own-city mode (`Engine.java:198-203`) | Needs rules, not research. Should the battling human's views end and requests be refused? Should prompts for the battling human get their default? Should campaign saves be refused, as during an assault? A world resync while SETT holds a battle is not checked: `GameWorldLoad.load` keeps the player's city part (`GameWorldLoad.java:129-148`), and whether that works in a battle is unknown. Simplest: put a world resync off until the battle ends, or end the battle first. |

The case for the world part being safe while SETT holds a battle: in own-city mode every PC already has a different SETT (its own city), and the world hash agrees. So the world part already doesn't read the local SETT, except through reports (patches 14, 16 and 98). What was never tested is world code that reads the **battle's** state. Known examples: `VIEW.b()` (O2) and `GAME.ARMIES()` (`RDMilitaryGar`, `FWorth`, `WDivStoredAll`). Patch 98 routes these through reports when `reportRead()`. Not checked: whether every such read is covered. The spike's world hash is the test.

---

## 3. Spike design

**Question:** can the battle PC run a battle map, as battle ticks inside its steps at its own speed, while every copy, the battle PC included, steps the world in lockstep at 1x, so that the world hash stays identical through the battle, the return and a few thousand steps after?

**Where.** A SOSDET branch, `spike/sos-93-battle-clock`, with a folder `spikes/sos-93-battle-clock/` holding the rig commands and notes. The engine changes are patch and engine edits on that branch only. They are throwaway (SOS CLAUDE.md: spike code is never moved as-is). The report goes in `SOSDET/docs/plan/spikes/battle-clock.md`, plus a short note in `SOS/docs/plan/spikes/`.

**Smallest change set (spike only):**
1. A rig-only switch, `--local-battle`, so that an Engage with own cities records a local battle on every copy instead of an `OwnBattle`. It also holds the two armies and freezes the battling human at the next multiple of 30 (reusing the view freeze: no reports, held transactions).
2. Between steps at F, on the battle copy only: save the own game (`GameView.saveOwn`'s path), then `BattleState.setGenerate` as vanilla does. No battle save is shipped. Other copies do nothing.
3. The step loop on the battle copy (O1, O3, O4):
   - city part: k battle ticks (the `isBattle` resources except `TIME`/`TEMP_EVENTS`, then `BattleState.update`), each tick reseeded;
   - world part: the world resources, with `battleView` false in the world part;
   - `TIME` and `TEMP_EVENTS` once per step;
   - `updateI` counted per step only.
4. The battle jobs and general run in the step, beat counted per battle tick (O15).
5. The end, by the rig's Retreat N steps after Start: no result command yet. The battle copy does the view's thaw (save world, load own game, load world, held transactions), and the other copy reloads its world at the same step. The battle's result is only logged, not applied (phase 2 below).
6. A rig action `bspeed K` (k battle ticks per step; 0 = paused), for `--in-battle` (rig README, "Map battles").

**Run** (OB2's setup, `SOSDET/rig/README.md` "Map battles", "Own cities"):
```
python rig\rig.py city --tag B93A --save Mogus-72-19f86f1e048-47002c-0-121c.save --no-invade ^
  --humans 2 --copies 2 --speeds 1,1 --steps 18000 --engage 3000 --local-battle ^
  --cmds "wbesiegenear 0 stronger" ^
  --in-battle "600 bspeed 3" --in-battle "1200 bspeed 0" --in-battle "1800 bspeed 1"
```
Then B93B on two PCs (`--remote 2`) with the same arguments. The step counts are first guesses: OB2 engaged at about 11,830 at 5x. Notes:

- `--speeds 1,1` keeps the world at 1x, as the card asks. The rig normally runs faster; a 5x run is a cheaper first try. Whether the rig accepts two equal speeds is not checked.
- Whether the rig takes `--in-battle` more than once is not checked (README: "copy 1 does the player action once"). A list form may be needed.
- `warmystate` and `wstatus` probe the two armies every 300 steps.

**Pass:**
- **P1.** The battle copy generates and runs the battle (Start, fighting, Retreat). The other copy never loads a battle, and its city runs throughout (its city's steps and reports go on).
- **P2.** The world hash is identical on both copies at every step, from Engage through the battle, the return, and at least 3,000 steps after. The engine compares its hashes with no desync.
- **P3.** Both copies' step counters go on through the battle at the agreed speed, with no hold longer than the world reloads at F and at the end. The rig's `heldSeconds` reports it.
- **P4.** k = 0, 1 and 3 each run for at least 600 steps, with the world unaffected (P2 holds across each change).
- **P5.** The two armies don't move on the world map during the battle (`warmystate`), and no new battle involves them.
- **P6.** After the return, the battle copy's city is the one from F, the world is the live world, and the transactions held since F have applied (a `wstatus` treasury and stock check before and after).
- **Measure:** the battle copy's step time before and during the battle at k = 1 and 3 (`Engine.stats()`), how long generating takes, and the world reload times.

**Fail:** any world-hash difference whose cause isn't found and fixed inside the spike. The engine's desync report names the hash section. A failure in a known part (O3 `TIME`, O4 `updateI`, O7 generating) is a finding, not a dead end: fix it and run again. A failure we can't explain after two tries ends the spike with "not yet feasible" and the report.

**Phase 2 of the spike (optional, if time allows):** the result as a world command (O10) with Accept, run as B93C. Pass: the NPC army's and the human's world divisions show the battle's losses on both copies, applied once, and a repeated command changes nothing.

---

## 4. Draft spec changes (for the owner's approval; not applied)

### §4 Session and time (`SOS/docs/spec.md:66`)

**Current:**
> - **Manual battles pause the world.** While a real-time battle or siege assault runs, the world map and every city are paused, and every player watches it. Otherwise a single battle could let years pass outside, because real-time battles are slow compared to city time. An NPC army's invasion of a player's city is city time, not a manual battle: it doesn't pause the world (section 9).

**Proposed:**
> - **Battles between players pause the world.** While a real-time battle between players' armies or a siege assault on a player's capital runs, the world map and every city are paused, and every player watches it.
> - **Battles against NPC armies slow the world.** While any player fights a real-time battle against an NPC army, the world runs on at 1× for everyone (slowed, not paused), and the host can't raise the speed until the last such battle ends. The fighting player's city is frozen meanwhile, as a viewer's is (section 10). The other players keep playing their cities. Watching that battle is optional (section 8).
> - An NPC army's invasion of a player's city is city time, not a manual battle: it doesn't pause or slow the world (section 9).

### §8 Military

**Current** (`spec.md:130`, Battle size):
> Every PC simulates every field battle, and only orders, the host's AI orders and state checks cross the network …

**Proposed:**
> Every PC simulates every battle between players' armies, and only orders, the host's AI orders and state checks cross the network ([ADR 0003](decisions/0003-battle-lockstep.md)). A battle against NPC armies runs only on the fighting player's PC and on the PCs of players who watch it. …

**Current** (`spec.md:131`, Battle start):
> The PC of the player whose army starts the battle generates the battlefield and sends it to every PC, and every PC loads it at the same step …

**Proposed:** keep it for battles between players. Add:
> Against NPC armies, the fighting player's PC generates the battlefield and runs it alone. Nothing is sent unless another player watches.

**Current** (`spec.md:138`):
> - **Battles against NPC armies** can be fought in real time, as in vanilla. Like any real-time battle, they pause the world and every player watches (section 4).

**Proposed:**
> - **Battles against NPC armies** can be fought in real time, as in vanilla. Only the fighting player's PC runs the battle, with vanilla's battle AI and vanilla's battle speeds and pause, on the battle's own clock. The world doesn't pause; it runs at 1× for everyone while the battle lasts (section 4). Both armies stand still on the world map, and nothing else can attack them, until the result applies. The result applies to the world at one moment on every PC, exactly once. The fighting player's city is frozen until the battle ends, then resumes where it stopped and receives what the world sent it meanwhile.
> - **Watching an NPC battle** is optional. Any other player can join it while it runs: their PC loads the battle from the fighting player's PC and runs it in step, as a city view does (section 10). Their own city is frozen while they watch. Leaving ends their view only.

**Current** (`spec.md:145`):
> - **Spectating.** All other players watch the battle while the world is paused (section 4).

**Proposed:**
> - **Spectating.** In a battle between players, all other players watch while the world is paused (section 4). An NPC battle can be watched by choice (above).

A question for the owner: the "Battle speed" bullet (`spec.md:142`, a majority vote of commanders) would now apply to battles between players only. In an NPC battle the fighting player alone sets the speed and pauses.

### §15 Field battles (`spec.md:328-341`)

**Current:**
> - When player armies meet, a real-time battle always starts. Battles against NPC armies offer auto-resolve. [PT]

**Proposed:** unchanged.

**Add:**
> - While a player fights an NPC army in real time, every other player's city and the world keep running at 1×, and every PC's world hash matches the host's throughout the battle and after it. [AT]
> - During an NPC battle, both armies stay in place on the world map and no other battle involves them. [AT]
> - The result of an NPC battle applies on every PC at the same step, exactly once; a repeated result changes nothing. [AT]
> - The fighting player can pause and change the battle's speed as in vanilla without changing anyone else's game speed. [PT]
> - After an NPC battle, the fighting player's city resumes as it was when the battle began, and receives the deliveries and other changes the world sent it during the battle. [AT]
> - Another player can join a running NPC battle, sees the same battle as the fighting player (battle hash), and returns to their own city when they leave. [AT]

**Current:**
> - When any PC in the game is slow, the battle slows for everyone. [AT]

**Proposed:**
> - When any PC in a battle between players is slow, the battle slows for everyone. In an NPC battle, a slow fighting PC slows its battle, not the world. [AT]

Note: the ADR 0003 second amendment and ADR 0006 (city viewing) will need an amendment too: NPC battles are no longer lockstep on every PC. This is a new ADR, written after the spike.

---

## 5. Plan

Order: after SOS-92 (as the card says). Each step is an engine step in SOSDET with its own plan entry (a new plan 0015), plus a mod step where noted.

**A. The spike** (section 3).
- **Builds:** the throwaway branch.
- **Tests:** B93A on one PC, B93B on two PCs, optional B93C.
- **Risks:** a world read of battle state that the hash finds only late; `TIME` or `updateI` coupling we can't separate cleanly; generating being slow on the battle PC.
- **Output:** the spike report, the owner's decision on the spec drafts, and the new ADR.

**B. The local battle's start and clock** (engine).
- **Builds:**
  - the `LocalBattle` record (world state, saved with the world, in the world hash): battle id, human, PC, armies, freeze step F and thaw step;
  - the freeze at F (generalising `View`'s freeze);
  - the battle PC generating between steps at F;
  - battle ticks in the step;
  - the gates O1 to O5 and O12 to O15;
  - the armies held and left out of polls (O11);
  - the speed cap at 1x through the host's log while any local battle runs.
- **Tests:**
  - unit tests: the record's save/load round trip; the freeze and thaw step arithmetic; the cap refusing a speed change;
  - a battle-tick schedule test (k per step from a battle speed, including below 1x);
  - rig: B93A's checks as a suite scenario (`localbattle`).
- **Risks:** the battle's own threads (sounds, GL jobs) under per-tick runs; the battle UI writing `GAME.SPEED` in places not found.

**C. The end and the result** (engine).
- **Builds:**
  - the return as a thaw (O9), with the other PCs' world reloads (O8);
  - the result command (O10) with a battle id, exactly once;
  - `Manual.afterBattle`'s logic in the world part from the recorded armies;
  - the prompt and Accept as today;
  - what a removed or disconnected battle PC means (default: Retreat at the next step the host stamps).
- **Tests:**
  - unit tests: the result command's encoding round trip; a second result for the same battle refused; a result for an ended battle refused;
  - rig: a won battle, a lost one and a retreat, each with losses checked on both copies; `--remove 2:bN` with the battle on copy 2.
- **Risks:** `WDivStored` survivors (which soldiers live); captives and loot shipped to a frozen city (held transactions); generating and loading time on a big city.

**D. The mod** (SOSGSGMP).
- **Builds:**
  - no battle save shipped for NPC battles;
  - the 1x cap shown in the top bar ("A player is in a battle: 1×");
  - the battling player shown as busy;
  - the battle's speed and pause controls working locally;
  - saves refused or deferred during a local battle (Q5);
  - prompts for the battling player (Q4);
  - the playtest log entries.
- **Tests:**
  - unit tests for the new message, if any (protocol version);
  - two-PC GUI run: player A fights an NPC army while player B builds; then A wins and B sees the world result;
  - the [PT] criteria.
- **Risks:** the UI text; campaign saves during a battle.

**E. Watching** (engine and mod).
- **Builds:**
  - a watch request, modelled on a view request but with no Allow? (Q2);
  - the battle PC's `viewOut`-like save of its game in battle mode;
  - the watcher's view load;
  - the watcher running the same battle ticks;
  - a battle hash for the pair (as `viewHashes`);
  - leaving and the watcher's thaw.
- **Tests:**
  - rig: three copies, copy 3 watches from step X and leaves at Y, with the battle hash identical and the world hash identical on all;
  - the view limits (`--view-limits`).
- **Risks:**
  - the battle PC's own load at watch start (the owner's pause of plan 0013, about 7 s on Mogus-72, here mid-battle);
  - `GameResync`/B14 is refused in own-city mode (`Engine.java:198-203`), so this needs the view path, not B14 as the card says.
- **May be split** into its own card.

**F. Cleanup and docs.**
- **Builds:**
  - the spec (approved text);
  - the ADR;
  - SOSDET `docs/hooks.md` for each changed patch;
  - plan 0006 (M1 stays for battles between humans);
  - mod HANDOFF.

### Open questions for the owner

- **Q1.** Is the shape "battle ticks inside the world step" acceptable? It makes the battle's speed a whole number of ticks per world step. Below 1x the battle runs a tick every few steps. The battle's top speed is limited by the battle PC's CPU while the world runs at 1x.
- **Q2.** Watching: does the fighting player have to allow a watcher, as with a city view? Can enemies watch? Should watching be in this card or in a follow-up card?
- **Q3.** A slow battle PC: should the battle slow (recommended), or the world for everyone? The spec's 0.5x floor applies to the world either way.
- **Q4.** A prompt for the fighting player during the battle, from another of their armies or a war declaration: give the default answer at once, or keep it until the battle ends? For a view, a prompt ends the view, which can't apply here.
- **Q5.** Campaign saves (Save and exit, autosave) during a local battle: refuse until it ends, as during an assault? Also the SOS-70 save before every battle: keep it for NPC battles, which costs every player a short hold?
- **Q6.** Survivors of a player's stored divisions: the battle's actual survivors (the command carries each survivor's data, larger and more work) or a count with mean experience (simpler; vanilla's own `resolve(int, double)` path)?
- **Q7.** Several local battles at once, by different players: allowed? The design allows it, one record per human. The cap stays at 1x until the last ends.
- **Q8.** A battle between players starting while an NPC battle runs: hold it until the NPC battle ends, or end the NPC battle as a retreat first?
- **Q9.** Does an NPC battle need a time limit? With the world at 1x a long battle costs everyone speed (1x instead of 25x). Vanilla's battles have no limit.

### The owner's answers (2026-10-30)

The spec drafts in section 4 are approved and applied (spec 0.33), with these answers:

- **Q1.** Yes: battle ticks inside the world step.
- **Q2.** Anyone may watch, enemies included, without asking. Watching is a follow-up card; step E moves there.
- **Q3.** A slow battle PC slows its battle, not the world.
- **Q4.** Every prompt that needs the fighting player's answer waits until the battle ends, then appears with the 30-second rule from then. This covers a world battle of another of their armies, another player's army meeting theirs, a sally request and the capital siege's popups. Deal proposals, declarations of war and other notifications arrive as notifications without popups.
- **Q5.** Campaign saves wait until the battle ends. NPC battles don't get the SOS-70 save before battles.
- **Q6.** Survivors come back as a count with mean experience (`resolve(int, double)`).
- **Q7.** Several NPC battles at once are allowed; 1× until the last ends.
- **Q8.** A battle between players waits until the NPC battle ends.
- **Q9.** No time limit.

The engine plan for steps A to C is SOSDET plan 0016.

### Not checked (and what would settle it)

- Whether `BattleState.setGenerate` / `SETT.CreateFromWorldMap` change world state: the spike's world hash right after F.
- Which of `INTER`, `COUNTS`, `SCRIPTS` and `ENGINE` are world-relevant: read each `update`, or let the spike's hash show it.
- Every place where battle UI code writes `GAME.SPEED`: grep `SPEED` under `view/battle` and `view/ui` (only `BattleView.java:113` and `IDeploy` were seen).
- Whether `WBattles` can still target a held army: run B93A with an NPC army near the held ones.
- The cost of the in-step general on a peer for a large battle: B93A's step times.
- Whether a world resync works while SETT holds a battle: a rig run with `wcorrupt` during the battle.
