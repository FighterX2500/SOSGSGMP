# Setup: factions, starting locations and the world

Card: [SOS-56](https://fighterx2500.atlassian.net/browse/SOS-56) (walking skeleton step 4b) · Date: 2026-10-25 · Status: merged (SOSGSGMP PR #33); changes from the draft in §10 · Spec: §5 Factions and starting locations, §5 World rules

Game paths are relative to `<game>/info/SongsOfSyx-sources/`. All findings come from reading the V71 source and the engine's SOS-55 code; written before the build, which has since run on two PCs. It builds on the map step ([setup-map-step.md](setup-map-step.md), SOS-54, PR #32) and on SOSDET's `Engine.buildWorld` (SOS-55, merged).

## 1. Goal

After the host confirms the map, each player picks a race, customizes their faction and picks a world location, at their own pace. The host then initializes the world. Every PC gets the same world, plays its own human in it, and picks its own city's starting spot. When the last player has picked, the game starts on every PC at the same moment (spec §5, steps 1 to 5).

The card ends when two PCs (PC and laptop over Radmin, direct IP) go from Start Lobby to the start of the game, each with its own human and city, and their world hashes are equal at step 0. Running the game over the network from there is [SOS-57](https://fighterx2500.atlassian.net/browse/SOS-57).

## 2. What the player sees

1. **Faction** (one panel, replacing the map panel after Confirm):
   - vanilla's race buttons, description, pros and cons, as `StagePickRace`;
   - vanilla's Customize Faction section (`UIProfile.section(false)`, with Save as Default and Load Default), as `StageVisuals`;
   - a Next button;
   - every player's row: picking, location picked, or location refused (§10).
2. **Location:**
   - the world map with a placer, as vanilla's `StageCapitol` (`view/world/generator/StageCapitol.java:86-181`);
   - every other player's picked location drawn with their banner color;
   - a spot closer than 6 tiles to another player's, or on bad terrain, can't be placed, and the tooltip says why (`Engine.locationProblem`);
   - Back returns to the faction panel; picks can change until the host initializes the world (spec §5.1).
3. **Host only:** Initialize World, active when every player has a location. Pressing it locks every pick.
4. **Everyone:** "Building the world" while the host builds and sends it, then the game loads (vanilla's load screen).
5. **City start spot:** vanilla's Landing Party view (`view/sett/SettViewStart.java`). The game stays paused, and nobody can unpause it (spec §5.4). A line says how many players are still picking.
6. When the last player has placed their landing party, every PC starts together (spec §5.5).

No titles stage (spec §5.1): the map step already sets `hasSelectedTitles = true`.

## 3. Flow

1. **After `MapConfirm`,** `MpMapStage` switches to the faction panel on every PC. Nothing new is generated.
2. **Picks.** Each PC changes only its own `FACTIONS.player()` (race, name, ruler name, banner), as vanilla does, and sends a `Pick` whenever it changes:
   - race key;
   - faction name;
   - banner bytes (`Engine.bannerBytes()`);
   - location, or none.

   The host keeps every player's latest pick and sends the table to everyone (`Picks`), so all PCs draw the same locations and rows.
3. **The 6-tile rule** is checked on the picking PC (`Engine.locationProblem` with the others' locations from the latest `Picks`). The host checks again on receipt. If two players pick close spots at the same time, the host keeps the first it received, clears the other's location and says so in that player's row.
4. **Initialize World (host):**
   - The host turns the picks into `HumanStart`s: the host first, then the joiners in lobby join order, which is slot order (player-slots.md R1/R2).
   - It calls `Engine.buildWorld` (SOS-55), applies the lobby's difficulty to every human, and saves (`GAME.saver().save(path, name, false)`).
   - It sends `SlotMap` (player id → slot), then the save, compressed, in `WorldPart` pieces under one transfer id, as the map step sends its terrain (`sosgsgmp.net.Pieces`, 60 KB per piece; SOS-54). A 3 MB save is a few hundred KB compressed, so a few pieces; a repeated piece changes nothing (AGENTS.md "applied exactly once").
5. **Load (every PC, the host too):**
   - A new constructor, `menu.MpWorldLoad`, wraps vanilla's `GameLoader` (a public `Constructor`, `game/save/GameLoader.java:18-70`). It passes the session through, as `MpGameBuild` does today.
   - After the load it starts the engine session held at step 0 (`Engine.begin`, `ownCity`, `localHuman(slot)`, `pcHuman` for every PC) and attaches the mod's in-game session panel (section 5), which pumps the session once per frame.
   - Each joiner then makes its own city at its own capital, as `StageFinish` does for the player (`GAME.s().CreateFromWorldMap(cx-1, cy-1, false)`, `view/world/generator/StageFinish.java:157`). The save holds the host's city; each PC runs only its own (own-city mode, ADR 0005).
   - Each PC reports `Loaded(transfer id, world hash at step 0)`. The host compares the hashes; a mismatch stops the setup with a message on every PC, since nothing can be repaired before the game starts.
6. **Landing party:** each PC opens the settlement view, which shows vanilla's start view while no one lives in the city. The mod's panel keeps the speed at 0 every frame until the game starts. When this PC's landing party is placed (`POP.tot(null) > 0`, as `SettViewStart` checks at `:84`), it sends `StartSpot`.
7. **Start:** when every PC has sent `StartSpot`, the host sends `Go`. Every PC lets the engine run (SOS-57 carries the session from there); until SOS-57 merges, the card stops here with a "ready to start" line.

## 4. Messages (protocol version 4)

| Type | Message | Direction | Layout after version and type |
| --- | --- | --- | --- |
| 14 | `Pick` | joiner → host | race key UTF, name UTF, banner bytes (short length + bytes), location x, y shorts (−1 = none) |
| 15 | `Picks` | host → all | count byte, then per player: id int, a `Pick`'s fields, the reason the location was cleared (UTF, empty = none) |
| 16 | `SlotMap` | host → all | transfer id int, count byte, then per player: id int, slot byte |
| 17 | `WorldPart` | host → all | transfer id int, index short, count short, bytes (int length + bytes) |
| 18 | `Loaded` | joiner → host | transfer id int, world hash long |
| 19 | `StartSpot` | joiner → host | transfer id int |
| 20 | `Go` | host → all | transfer id int |
| 21 | `WorldPlayers` | host → all | transfer id int, count byte, then per player: id int, progress byte (loading, landing, mismatch, ready) |

Every decode checks its ranges (count 1–16, index < count, location in the map or −1, hash any), as the map messages do. The host's own pick, load and start spot go through the same state machine without the network.

## 5. Classes

**Plain Java, no game imports, unit-tested:**

| Class | Responsibility |
| --- | --- |
| `sosgsgmp.setup.FactionPick` | Record: race key, name, banner bytes, location or none. |
| `sosgsgmp.setup.FactionStep` | The state machine:<br>• **Host:** every player's latest pick; the 6-tile rule on receipt; `canInitialize()`; slot order; the transfer id; who has loaded with which hash; who has picked a start spot; `canGo()`.<br>• **Joiner:** the latest table, its own pick, the slot map and the save's pieces as they arrive.<br>A player who leaves is dropped before the world is built. After the build, a player leaving stops the setup, since their slot exists. |
| `sosgsgmp.net.Message`, `Protocol` | The seven messages, version 4. The save's pieces go through `sosgsgmp.net.Pieces`, made for the map step's terrain (SOS-54). |
| `HostedSetup`, `JoinedSetup` | Feed `FactionStep` after `MapConfirm`, as they feed `MapStep` before it. |

**Adapters and UI (game classes):**

| Class | Responsibility |
| --- | --- |
| `sosgsgmp.game.WorldSetupAdapter` | One place for the game and engine calls: `Engine.locationProblem`, `bannerBytes`, `buildWorld`; the difficulty on every human; save to a file; this PC's city at its capital; `POP.tot`; the speed held at 0. |
| `view.world.generator.MpFactionStage` | The faction panel: race buttons (the same calls as `StagePickRace`, which can't be reused because its Confirm runs vanilla's `set()`), `UIProfile.section(false)` and the player rows. |
| `view.world.generator.MpLocationStage` | The location placer and the other players' markers. Like `StageCapitol`'s placer, but `afterPlaced` sends a pick instead of generating the world. |
| `menu.MpWorldLoad` | The load constructor: `GameLoader`, then the engine session at step 0 and the session panel. |
| `view.MpSessionPanel` (package to be decided) | A small always-shown panel (players, "waiting for N players", setup messages) that pumps the session from its `render`, so the session needs no script and no static. |

**Why the vanilla stages aren't reused as they are:** `StagePickRace` and `StageVisuals` end with `stages.set()` (`StagePickRace.java:104`, `StageVisuals.java:32`). With the terrain generated, `set()` goes on to vanilla's `StageCapitol`, which builds a one-player world. The mod's panels use the same building blocks (`RACES.all()`, `FACTIONS.player().setRace`, `UIProfile.section`) without that call.

## 6. Engine

- **Uses:** `Engine.buildWorld`, `locationProblem` and `bannerBytes` (SOS-55); `begin`, `ownCity`, `localHuman`, `pcHuman`, `runUntil(0)` (SOS-46).
- **Needs one small addition, in its own SOSDET PR:** `Engine.worldHash()`, so the mod can compare the worlds at step 0 before any hash exchange runs. The engine's own hash log starts with the session; at step 0 nothing has been hashed yet.

## 7. Hooks

**None expected.** The new panels live in the game's packages for package-private access, as `MpMapStage` and the `menu/Mp*` screens do (a `docs/hooks.md` note, no game code changed). If holding the speed at 0 from the panel turns out not to be enough (vanilla's speed keys during the landing party), a hook goes into hooks.md with the reason, after asking.

## 8. Risks and open questions

1. **Difficulty on spare Players.** Vanilla sets the mode on the one player before the game (`menu/ScRandom`: `bonusesCustom.setMode`) and applies it when the landing party is placed (`SettViewStart.java:87`). The plan sets the lobby's mode on every human before the save, and each PC applies its own human's at its landing. Before coding, the engine has to confirm that applying it changes nothing the world reads (otherwise it must apply for every human at the same step); this is checked in milestone M1.
2. **ESC and quickload during setup** stay a known gap, as in the map step.
3. **The host leaves after the world is built:** every PC returns to the menu with "The host closed the game." Resuming needs campaign saves (spec §11): SOS-64, PR #39.
4. **Load time on the laptop:** a 3 MB save loads in a few seconds; the host waits for every `Loaded` with no timeout in v1, and shows who it is waiting for.

## 9. Milestones

| | What | Verified by |
|---|---|---|
| M1 | `FactionPick`, `FactionStep`, the seven messages; the difficulty check (risk 1) | Unit tests: rule on receipt, slot order, transfer pieces in any order, repeats, a leave before and after the build |
| M2 | `MpFactionStage`, `MpLocationStage`, picks over the network | Two PCs: picks shown on both, the 6-tile rule on both |
| M3 | Initialize World, transfer, `MpWorldLoad`, each joiner's city, `Engine.worldHash` (SOSDET) | Two PCs: both load, each with its own city, equal world hashes |
| M4 | Landing party, held speed, `StartSpot`, `Go`; docs (`hooks.md` note, walking-skeleton plan) | Two PCs from Start Lobby to "ready to start" (the card's end) |

Each milestone runs `gradlew.bat build`; the two-PC checks in M2–M4 are the owner's manual tests (PC host, laptop joiner), with what to look for listed in the PR.

## 10. Changes from the draft (M1 to M4)

- **No ready flag.** A placed location is the player's "ready": Initialize World needs every player's location, and picks stay open until it is pressed (spec §5.1). A separate flag added a state with no rule of its own.
- **`WorldPlayers` (type 21).** The joining PCs need every player's progress after the build (loading, landing, mismatch, ready), as the map step's `MapPlayers` gives it; the draft had no message for it.
- **Difficulty (risk 1):** in vanilla, `PBonusSetting.setMode` adds boosters whose value is 1 for any `Player`; with the engine, a booster counts only for the Player it belongs to (SOSDET patch 15, SOS-46). So the host sets the lobby's mode on every human's Player before saving (corrected after M4; M1 had set it on the host's only), and every PC loads them with the save. The difficulty files hold only boosts, so `apply()` at the landing changes nothing the world reads. A load into a running game (the world resync) stacked the boosters; SOSDET PR #36 fixes it in patch 15.
- **The engine at compile time** ([ADR 0007](../decisions/0007-mod-compiles-against-engine-jar.md), owner 2026-10-25): the mod compiles against SOSDET's built jar, `compileOnly`, so `Engine.locationProblem`, `bannerBytes` and `buildWorld` are plain calls from the adapters.
- **Banner cap 1 KB.** The game's banner is a 12×12 bitmap and four colors (`FBanner.save`), well under it; 16 picks then fit one 64 KB message.
- **The location check goes through the session** (`SetupSession.locationProblem`): it asks the engine's rule with the other players' locations as this PC knows them. The placer keeps the answer until the tile under the mouse or the session changes.
- **Screens switch from clicks.** After the confirm, the map panel shows Next; the factions step has three screens (race, Customize Faction, location), each with Back and Next, as vanilla's stages. Switching from inside a frame's render would clear the UI while it is drawn.
- **Picks are read back twice a second.** Vanilla's Customize Faction section edits `FACTIONS.player()` directly and reports nothing, so the factions step compares the game's race, name and banner with the last pick sent every 0.5 s and sends a new one when they differ.
- **Other players' capitals** are drawn on the map while the placer is on it (red; this PC's in green), with their faction names. The rows list every location in tiles.
- **Not carried:** the ruler name. `HumanStart` has no field for it; each PC keeps its own.
- **Two game ports.** `MapWorld` stays the map step's; `FactionWorld` (the location rule and the world build) is the factions step's, implemented by `WorldSetupAdapter`.
- **Each joiner's city through the engine** (`Engine.ownCityAt`, SOSDET PR #36). An audit of `SETT.CreateFromWorldMap` found one write the world reads: clearing the recipes re-rolls the NPCs' production boosts (`FBoost.mul`) on that PC only. The engine keeps them as loaded. Everything else the city's generation writes is the city's or the view's, and its random draws are outside the step, off the simulation's stream. The same PR adds `Engine.worldHash()`.
- **The host saves from the world view.** The save records the current view, and the generator's isn't one; a save taken in it would load into a new world generator (`VIEW.setFirstView`). The host switches to the world view first, as vanilla's finish stage does.
- **The save travels as the game wrote it:** the game's save is already compressed, so `WorldPart` carries its bytes as they are.
- **The engine session at the load,** in the order SOSDET's rig starts an own-city run: begin, AI host or peer (start id: the save's SHA-256), this PC's number (its slot), own city, the load seed (the map's seed), the load, then every PC's human and the seed. It holds at step 0.
- **The in-game panel** (`menu.MpSessionPanel`) is an interrupter on top of every view: every player's progress, the status line and Leave (which also ends the engine session). The game can't be saved while it shows.
- **`GAME.setGameStart()` at the landing** (`SettViewStart`) resets `GAME.updateI`, a cache key for world code, on that PC. In a session `updateI` counts steps (SOSDET patch 10) and stays put while the session holds, and every PC places its landing party once before Go, so every PC enters step 1 with the same value.
- **No held speed.** The engine session holds at step 0 until the game starts, so no PC's speed buttons run anything; the plan's "speed held at 0" isn't needed.
- **Go is automatic:** when the last PC's landing party is placed (`POP.tot() > 0`, as vanilla's landing view checks), the host sends `Go`, as spec §5.5 says. Until SOS-57 the game stays held there, with a "the game starts" line.
- **The host's landing view (owner's playtest, 2026-10-25).** A loaded save opens vanilla's landing view (`SettView`'s loader, while the city has no people), but the save's first view, the world, replaces it, so the host never got one and could unpause without a landing party. After the load, every PC whose city has no people opens it, as a city's generation does (`SettView.clear`); a joiner's own city already did.
- **Locked while the world initializes (owner, 2026-10-25).** From Initialize World (on a joiner, from the slot map) until the PC leaves for the load, the race, faction and location screens and their buttons are greyed and take no clicks; the placer says why. Leave stays, so a transfer that never ends can't trap a player.
- **Other players' cursors (spec §5, owner 2026-10-25).** On the location screen and on the game's world map every PC shows the other players' cursors, each with the player's name, in a colour fixed by the player's place in the lobby. A new message, `Cursor` (protocol 5), goes to the host at most 10 times a second and only when the cursor moved; the host relays it to every other PC. It carries no tick: a cursor is only shown, never an action. In a city they wait for city viewing (spec §10).
- **The landing view's Regenerate button** (`SettViewStart`: `SETT.reGenerate()`) rebuilds this PC's city with `CreateFromWorldMap`. It works as in vanilla: SOSDET's hook 18 (PR #36, owner 2026-10-25) keeps the NPCs' production boosts around every city generation in a session, and `Engine.ownCityAt` uses the same hook.
