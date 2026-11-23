# Carved city-states: setup by carving, hot-join, and removal

Date: 2026-11-11. Status: **spec change approved by the owner (2026-11-11); the decisions in section 9 are still under discussion**; no cards yet. Section 7's text goes into `docs/spec.md` with the first card's PR, worded to match the decisions. Spec §2 lists "New players joining a campaign in progress" as out of scope for v1, and §11 says "New players cannot join a campaign that is already in progress", so nothing here is built until the owner decides (section 9). Spec sections it would touch: §2, §4, §5, §9, §11, §12, §15.

Game paths are relative to `<game>/info/SongsOfSyx-sources/`; SOSDET paths to `SOSDET/src/main/java/sosdet/`; mod paths to `mod/src/main/java/`. Everything below comes from reading code (three audits on 2026-11-11: world pathing and roads, region-dependent world state, engine and mod). Nothing has been run. Claims not traced to a line are marked *inferred*.

## 1. The idea in one paragraph

A player's city-state is its own 3×3 region. Today the world generator reserves those squares before it grows the other regions around them. The proposal is one engine module that **carves** a 3×3 city-state out of a finished, living world, and **un-carves** it again. Three features then share it:

- **(A) Setup by carving:** the world is generated whole (countries, regions, borders, roads), and each player then carves their city-state into it. This is the cheapest place to prove carving: every PC is present from step 0.
- **(B) Hot-join:** a new player carves a city-state into a running campaign.
- **(C) Removal by un-carving:** an eliminated or removed player's square disappears from the map, instead of staying as an orphan 3×3 region.

(A) can ship alone. (B) adds the network and session work. (C) is the carve's undo.

## 2. How vanilla's world map works (the facts carving relies on)

### 2.1 Regions

- **The layout is plain data.** `RegionMap` stores a region index per tile (`WREGIONS.java:42-44`, `RegionMap.java`), and each `Region` has a `RegionInfo` (name, centre, bounds, area, fertility, climate and terrain shares, `RegionInfo.java:51-58`). Both are saved. `RegionMap.set` is public.
- **A player's capital is a 3×3 square.** `GenPlayer.gen` marks a `WCentre.TILE_DIM` (= 3) square for region 0. `GenAssign.expand` grows every other region and skips region 0 (`GenAssign.java:89`). SOSDET patch 17 does the same for every human's square (regions 1 to N−1).
- **A region is active** when its area is above 0 and it holds its own centre tile (`WREGIONS.java:166-177`).
- **Rebuilt at every load:** borders, the siege ring around each centre, the centre tiles, the active list (`WREGIONS.init`, `:119-150`).
- **Region indices:** `WREGIONS.MAX` = 1023. A small map leaves indices unused; a big map may use them all (*to check per map*).
- **Vanilla never re-partitions a live world.** The only code that paints regions after generation is the map editor's brush (`world/map/regions/Placer.java`), which leaves roads and paths to generation. Vanilla's **Resettle** isn't a precedent either: it starts a brand-new game (`GAME.create`, then the world generator) and carries over the level, race and a quarter of the stockpile (`UIAdvice.restart`, `WorldViewGenerator.setresettle`).

### 2.2 Roads and world paths

- **Armies move only along roads.** The path network is generated with `roaded = true`, and in that mode a land tile is passable only if it holds a road (`WTRAV` LAND `isPossible`, `road/WTRAV.java:211-222`). The world's path network is therefore the road network.
- **Roads** link neighbouring region centres (`road/GenRoad`), and `road/GenPlayer` joins any separate groups with `WTRAV.makeRoad`.
- **Paths** (`pathing/GenLand`): each region is flooded from its centre within its own tiles. Wherever two regions touch, one link is laid centre → border → centre, along roads. Then `GenPort` (sea routes) and `GenConnect` (joins any disconnected groups). A step between two regions must be orthogonal: `WTRAV.can` refuses a diagonal step between regions (`:15`).
- **The path map is saved** (`WDirMap`: per tile, the allowed directions; plus `portArea`).
- **The path graph is rebuilt at every load** (`Comps.generate`, `WPATHING.java:92-98`):
  - its nodes are region centre tiles, border crossings, and coast transitions;
  - any path tile not reached from a node is deleted.

  So after a carve, the new square's centre becomes a node by itself, **but with no road and path link to it, nothing can reach it.**
- **The region finder** (`WRegFinder`) is computed live, with no state of its own.
- **Determinism:** the road and path generators draw no random numbers (no `RND` in `pathing/Gen*`, `road/Gen*`), so the same map gives the same network.

### 2.3 How SOSDET loads a world mid-game

SOSDET's world resync doesn't start a new process. It loads the save **into the running game** (`GameWorldLoad.java:133`, through `SuperSaver.load`, which clears then loads each resource). An in-memory cache that a load doesn't reset stays stale on every PC that was already running, while a PC starting fresh computes it anew. **That is a desync, not just a wrong number.**

## 3. The carving module (engine)

### 3.1 Placement rules for a square

A spot is the centre tile of a 3×3 square. It must:

1. pass vanilla's capital terrain rule (`WorldCentrePlacablity.terrain`) and SOS-75's (land beyond the square on at least one side);
2. lie on land owned by an NPC, by nobody, by a player's vassal, or held directly by a player in a region other than their capital square; never on any player's capital square (decision 3);
3. keep the square **and a 1-tile ring** free of road and path tiles. Then no existing route is cut and nothing has to be re-routed;
4. keep the square and ring off big water, so no port or harbour logic applies (vanilla's own player-harbour code is switched off: `GenPlayer`, `pharbour = false`);
5. not touch any other region's centre 5×5 (the centre and its siege ring). Keep the new centre at least 5 tiles from every other centre (Chebyshev), so siege rings don't overlap and no old centre has to move;
6. not split the region(s) it is cut from: each remaining part must stay 4-connected to its own centre (a flood check);
7. hold no entity on its 9 tiles (army, caravan, camp/haven). Camps would change owner (`WHaven.java:111-130`);
8. be at least 6 tiles from every player's capital (spec §5's rule, `Engine.locationProblem`);
9. not involve a region in a siege, assault or battle at that moment (*inferred* safe rule).

### 3.2 Carving, step by step (on the host, between steps)

1. **Region index:** take an unused one. Its RD row must be zero and `factionI` −1, or `activateNext` throws (`FACTIONS.java:228`).
2. **Tiles:** set the 9 tiles to the new region in `RegionMap`, recording each tile's previous region in the **undo log** (3.5).
3. **The new region's info:** centre, bounds, area, climate, terrain, fertility. **Needs a patch:** `RegionInfo.init` accepts a 3×3 square only for region 0 (`RegionInfo.java:194-198`; see section 8), and `centreSet` is package-private.
4. **The shrunk region(s):** rerun `RegionInfo.init`. Rule 5 means their centres stay put.
5. **The new region's data (RD row):** what `RD.prime` does per region (`RD.java:249-291`): `RD.RAN().randomize(r)`, three rounds of `RD.UPDATER().BUILD(r)`, and `init(r)` on each region updater. `RD.init.upers` is private, so this needs a patch or reflection.
6. **Road links** (owner's design, matching `GenLand`'s rule of one link per pair of touching regions):
   - a square inside one region gets **one link to that region's town**;
   - a square on a border gets **one link to each touching region's town** (two, or three at a corner).

   Each link is a path search from the new centre to that town, over land only within the new square and that one region (as `road/GenPlayer.path` searches, with orthogonal steps across borders), then `WTRAV.makeRoad` along it. `makeRoad` and `GenPlayer` are package-private, so this needs a patch or a copy in the engine. Each road tile laid goes in the undo log.
7. **Path links:** add path edges along each new road, both ways, as `pathing/Gen.connect` does. Add the edges between neighbouring path tiles of the same region, as `Gen.generateAll`'s last loop does. Each edge goes in the undo log.
8. **Caches a load doesn't reset**, recomputed on every PC after the load:
   - `RDProspects.average` (`RDProspects.java:52-64`): computed once from the active list and never reset by a load, while NPC production boosts read it (`settlement/recipe/Creator.java:247`) and those feed hashed NPC rates. Call `RD.PROSPECT().tt()` after the load on every PC. **Without it, desync.**
   - Any other cache computed once from `active()`: the region-state audit found only `RegionInfo.ave`, which a load does reset. The spike must look for more (3.6).
9. **Realm totals:** `Realm.ferArea` is only recomputed inside `RD.setFaction`. `activateNext` on the new square calls it for the new realm; the realm(s) that lost tiles need it rerun.
10. **The new realm:** activate a **chosen** NPC slot on the square (section 5.1), then `GameHumans.add`.
11. **Save,** and every PC loads it. That rebuilds borders, the siege ring, the active list and the path graph, and marks `RDDistance` dirty (`RDDistance.java:123-136`).

Not changed and acceptable: `WorldBuildings` villages may be drawn under the new square (cosmetic); `RDProspects` data for the new region is empty (economic only); `EventFactionPopup.nextRegion` points at another region (same on every PC).

### 3.3 Alternative for links: regenerate the network

Instead of steps 6 and 7, rerun vanilla's own road and path generators over the whole map on the host. They draw no random numbers, so everything the carve didn't touch comes out as before. **Not recommended:**
- roads and paths are rewritten map-wide;
- armies already on the march hold paths that would need checking;
- a road that crossed the square would be routed differently.

Keep it as the fallback if the local links prove hard.

### 3.4 Un-carving

The reverse, from the undo log:
1. give the 9 tiles back to their previous regions;
2. remove the path edges and road tiles the carve added. A road tile that was already a road before stays;
3. clear the region's RD row and free its index;
4. rerun `RegionInfo.init` and the realm totals of the regions that got the tiles back;
5. save; every PC loads; `RDProspects.tt()` again.

Rules:
- it waits, as a save does, until no battle, assault or view involves that player;
- armies on the square are moved to the nearest tile outside it;
- references to the region are dropped: region deals in treaties (SOS-73), claims, vassal terms, AI army targets. The world log keeps its lines (*to audit in the spike*).

### 3.5 The undo log

A save section in the engine (for example `SOSDET_CARVES`), one entry per live carve:
- the region index and the human slot;
- the 9 tiles with their previous regions;
- the road tiles laid (only those that weren't roads before);
- the path edges added (tile + direction bits).

It has a version number, like every engine section.

### 3.6 The spike, before any card

In the SOSDET rig on Mogus-72, 3 copies:
1. carve a square inside an NPC region and one on a border;
2. save, then every copy loads;
3. check that an army can march to the new centre;
4. run 2 game days with the hash checked;
5. un-carve, save, load, and run again;
6. compare the world with an uncarved run: borders, path graph node counts, region info, `RDProspects.average`, and any cache that differs between a running copy and a fresh one.

That last check (a running copy against a fresh process after the same carve) is what finds caches like `RDProspects.average`.

## 4. (A) Setup by carving

1. The map step as now: the host generates terrain and confirms it.
2. The host generates the **whole world with no players placed**: vanilla's generator without the player square. *To check:* the generator assumes region 0 is the player's (`WREGIONS.player`), and path validation starts from its centre (`WPATHING.validateInit`). The likely way is to generate with region 0 as a placeholder square and carve the host like everyone else, or keep region 0 as a dummy. Part of the spike.
3. Every PC receives that world, and every player picks a spot on the **real political map**: kingdoms, borders, roads. The rules are 3.1's.
4. When all are ready, the host carves every square in slot order, makes each one a human (as `buildWorld` does today with `GameHumans.add`, at setup, before step 0), saves, and every PC loads at step 0, as today.

It gains:
- one code path, tested by every campaign;
- players choose with the real map in front of them;
- patch 17's multi-player region generation isn't needed for this mode.

It changes:
- **The start.** Vanilla protects a new player with a free neighbouring region (`handsOff`) and small `sanctified` NPC states around them (`world/region/Gen.java:37-68`). With carving, a square cut from an empire of 4 or more regions splits that region off as a new neutral, sanctified NPC state, which plays the same part (decision 3).
- **Direct links between players 6–15 tiles apart (SOS-120)** are kept (decision 4): a step after the carves lays a road and path link between the two new centres, by the same link code.
- **Slots:** the players are made before step 0 in slot order, as today, so none of hot-join's slot and session problems apply.

Shipping: a lobby option ("World: classic / players settle in a living world") until it is proven, then perhaps the default.

## 5. (B) Hot-join

### 5.1 Engine

- **A chosen NPC slot.** `activateNext` takes the first inactive NPC (`free()`, `FACTIONS.java:226-278`), which can be the game's "other faction" (refused by `add`) or a slot below a human's (refused by the slot-order rule, `GameHumans.java:252-258`). A new call activates a **chosen** inactive NPC slot above the last human that isn't the other faction (`activateNext`'s body without `free()`).
  - On a crowded map there may be none: the rings of small states fill all 63 NPC slots by about 7 humans (`player-slots.md` Q3). The join prompt must then say a join is impossible.
- **Neutral stances and starting credits:** reset the slot's old stances as `retire` does; credits from the campaign's starting value, not the host's (`GameWorldSetup.java:137` uses the host's).
- **A world load that grows by one human.** The world resync leaves `SOSDET_HUMANS` out on purpose and refuses any change in the human slots (`GameWorldLoad.java:72-74`, `Engine.java:878-881`). `FACTIONS.load` reads each slot in its own object's layout (`FACTIONS.java:143-149`). So every PC must swap the slot to a `Player` (`put(slot)`) **before** loading. This takes:
  - a host log entry at a confirmed step;
  - `WorldResync` v4 carrying the new slot;
  - the slot check accepting "the old slots plus one higher";
  - `Session.humans` cleared and `spectatorsChanged()` run after the load (`worldResyncLoad` skips `afterLoad`, `Session.java:2053`).
- **Audit:** objects outside the world sections that still hold the old `FactionNPC` (the city part, UI built at load). The risk is lower than at setup: on the other PCs the slot was an inactive NPC (*inferred*).
- **Per-human data is pre-sized** for 16 humans (`Hooks.HUMANS_MAX`, 33 uses) and keyed by a human's place among the human slots. A new human always takes the highest slot, so it appends the next unused slice. `SOSDET_RELATIONS` and `SOSDET_EVENTS` need no version bump, provided the slot is swapped before the load.
- **A "landing" human state.** Today the world part throws for a human with no city report (`Hooks.java:866-875`) and waits for every remote human that isn't frozen (`Session.java:2316-2333`). A frozen city reads its **last** report, and a newcomer has none.
  - From the founding step to their first report, the world neither waits for nor reads that human.
  - `SOSDET_CITY_DIVS` skips their store (`GameCityDivs.java:55-73`, the same crash plan 0007 met).
  - It ends at a host-confirmed step.
  - Carried in `SOSDET_HUMANS` v4 as a flag, so a save made during the landing resumes (or saves wait for the landing).
- **`ownCityAt`, `pcHuman` and `localHuman` after step 0**, applied on every PC at the same step through log entries (`Engine.java:982-986`, `:1012`, `:1031`; `Session.java:2430-2457`).

### 5.2 A new PC in a running session

There is no path for a fresh process today:
- reconnect replays the streams of a process that is still alive;
- views and battle watching serve PCs already in the session;
- spectators are retired humans whose PC has been there since step 0;
- the engine has no PC without a human (`Hooks.java:1062-1075`).

The closest precedent is the co-op whole-game `resyncSave`/`resyncLoad` (`Engine.java:163-201`, `AiLog.resyncOut/resyncIn`), which is refused in own-city mode.

A newcomer's PC needs:
- **a join state:** the world save, plus every human's current and pending reports, the AI log's resync part, the seed, speed, hash epoch and world draws;
- **a city of its own to load into.** The host's whole save would hand over the host's city without an Allow (spec §10). Options: a placeholder city, or a vanilla game generated at the same size (*inferred*);
- **a guest mode** (`localHuman = −1`), only if they watch before founding.

**Recommended cheaper flow:** the newcomer picks their spot on a **snapshot** of the host's world, loaded outside the session, as setup's location stage does. Their PC enters the session only at founding, already holding its slot. The host checks the spot again on the live world. This removes the guest mode; the join state is still needed.

### 5.3 Mod

- **Hello after the start** is refused at `sosgsgmp/session/HostedSetup.java:307` (`Refusal.STARTED`). It must instead run the builds and game-files check (SOS-159, lobby-only today: `HostedLobby.java:92-108`), then the host's Accept prompt.
- **Slot numbers.** The mod uses PC number = slot (`EngineLink.java:21-23`) and caps both at 16:
  - the protocol rejects `slot >= 16` (`Protocol.java:603, 774, 791, 862`);
  - `EngineLink`'s arrays and 48 uses in 18 files are sized by `LobbySettings.MAX_PLAYERS`.

  A newcomer's slot is usually above 15 (slots from N up are NPCs at setup). Either allow slots up to 63, or stop using the slot as the PC number.
- **Streams can't be joined midway.** `EngineLink` compresses each stream once for every receiver (one Deflate stream from offset 0, `:91-117`), and the AI log writes its header once (`AiLog.java:666-672`). At the founding pause every stream restarts: a stream-epoch message, new Deflaters and Inflaters, offsets back to 0, the AI-log header written again (protocol 28).
- **Runtime registration** in `joined`, `players`, `keys`, `FactionStep`, `Departures`, `ReportStore`, `Chat`, `SaveRound`; `Engine.pace` called again with the new PC (`EnginePlay.java:66-73`).
- **Steam:** the lobby's member limit is the player count, so it's full after the start (`MpScreenCreate.java:187`, `MpScreenResume.java:173`). Raise it (`setLobbyMemberLimit`, *inferred API*).
- **Campaign:** `Campaign.adding(key, name, slot)`, sent with the next save; a resume then treats the newcomer like anyone else.

### 5.4 Flow

1. The newcomer joins through IP or Steam; the host's game runs the builds and files check, and the host gets "*Name* wants to join: Accept / Refuse" (no pause, §4).
2. On Accept, the newcomer receives a snapshot of the world and picks race, faction and spot outside the session (3.1's rules, checked on the snapshot).
3. Ready: the host checks the spot again on the live world. At the next step with nothing in the way (no battle between players, assault, save, view or desync window), **every PC pauses for a few seconds**:
   - the host carves, activates the chosen slot, `add`s the human and saves;
   - every PC swaps the slot and loads (5.1);
   - the streams restart (5.3);
   - the newcomer's PC loads the join state.
4. The newcomer lands: makes their city and picks the starting spot, in the landing state (5.1), while everyone else plays on.
5. Their first report ends the landing at a host-confirmed step; a campaign save follows.

## 6. (C) Removal by un-carving

Applies to:
- removal after a disconnect or quit (Continue without them, or Start without them at a resume), which today leaves free regions (plan 0007);
- Eliminate, which today leaves an NPC city owned by a neutral faction (spec §9). Changing it is decision 6.

Only the capital square is un-carved; the player's other regions become free as today (or go to the conqueror at Eliminate). The human slot stays retired: every human's data is kept by its place among the slots, so reusing a slot is a separate engine question.

The test it makes possible: carve → save → load → un-carve → save → load. The region layout, the road and path maps, and the path graph must equal a world that was never carved.

## 7. Proposed spec text (draft for approval, not applied)

- **§2:** remove "New players joining a campaign in progress" from out of scope, or move it to a later version (decision 1).
- **§5, World setup (new option):** "World: classic, or players settle in a living world. In the second, the host generates the whole world first: NPC countries, regions, borders and roads. Each player then picks a spot for their city-state on that map: on NPC or unowned land, or in a region another player or their vassal holds (never a player's capital), at least 6 tiles from another player's capital, off roads and coasts. Their city-state is a small region carved there, linked by road to the town of each region it touches; players 6–15 tiles apart also get a road between them. When the region it is cut from belongs to an NPC realm of 4 or more regions and isn't its capital, that region breaks away as a new independent NPC state, neutral to everyone and protected as vanilla's starting neighbours are; a square on unowned land makes that region a new NPC state the same way. When no faction slot is free the region stays as it was, and the chat says why."
- **§11, Joining** (replaces "New players cannot join"):
  > A new player can join a running campaign. They join through the host's IP or Steam, after the same game-files check as a lobby, and the host Accepts or Refuses. The newcomer picks a race, a faction and a spot on a copy of the current world. When they press Ready, the game pauses for every player for a few seconds while their city-state is founded, then the newcomer picks their starting spot while everyone else plays on. They start with the campaign's starting resources and difficulty. No player joins during a battle between players, an assault, a save, a view or a desync window: the join waits until it ends. A join is refused when the world has no room for another faction, or the campaign has had 16 players (a limit that may be lifted later).
- **§11, Removing a player / §9 Eliminate** (Eliminate's "the city becomes an NPC city owned by a neutral faction" is replaced): "Their city-state is removed from the map: its land goes back to the regions around it."
- **§12:** chat lines: "wants to join", "founded *Faction*", "*Region* broke away from *Realm*", "*Region* became an independent state", "*Region* stays as it was: no room for another faction", "*Faction*'s city-state is gone".
- **§15 [AT]:**
  - a carve and an un-carve on 3 PCs keep every world hash equal through and 10 minutes after;
  - an army reaches a carved capital;
  - un-carve after carve restores the layout and the road and path maps;
  - a newcomer joins two playing PCs and every hash matches through the join and 10 minutes after;
  - a save and resume after a join brings every player back;
  - a refused join changes nothing.

## 8. Found along the way: a probable bug in today's setup

`RegionInfo.init` accepts a centre tile that fails `regionC` only for region 0 (`RegionInfo.java:194-198`: `if (backup == null || !WORLD.REGIONS().player.is(backup)) return false;`). That return comes after area and bounds are set, but before climate, terrain shares and fertility. The square of every human but the host (regions 1 to N−1, from patch 17) can never pass `regionC`, because the ring around its centre belongs to other regions. `GenInit` ignores the return value (`GenInit.java:41`).

So **every joining player's capital region probably has climate, terrain and fertility at 0** since setup. One known effect: a city's minerals at founding read the capital region's terrain (`settlement/tilemap/generator/GeneratorMinerals.java:71-78`), so a joining player's city may get only the base amount where the host's gets more.

*To confirm on a save:* read `info.terrain()` and `info.moisture()` of region 1 in a two-player campaign. If confirmed, it's a small engine card (patch line 196 to accept every human's square), separate from this proposal. Carving needs the same patch anyway.

## 9. Decisions (owner, 2026-11-11)

1. **Order:** both, one after the other. First carving (A, then C) and its tests, then the network half of hot-join (B) and its tests.
2. **Links:** local road links to the town of each region the square touches (3.2 steps 6 and 7). The map-wide regeneration (3.3) stays only as a fallback.
3. **The region the square is cut from:**
   - **An empire of 4 or more regions:** the region breaks away as a new independent NPC faction (`FACTIONS.activateNext` on that region, as vanilla's region release and `EventFactionPopup` do).
   - **The empire's capital region never breaks away:** the square is carved there with no split.
   - **Stances:** the breakaway state starts neutral to everyone: the empire it left, every player, every NPC.
   - **Vanilla's start-neighbour protection:** the breakaway state is marked `sanctified`, as the small states vanilla's generator puts around a new player (`world/region/Gen.java:78`). A sanctified state never starts an NPC war and is never chosen as a target by one (`EventFactionWar.java:51,69`), never expands (`EventFactionExpand.java:55`), and never collapses (`EventFactionCollapse.java:59`).
   - **No free faction slot** (all 64 in use): no split, the square is carved inside the owner's region, and the chat says why.
   - **A player's land:** a square may also be carved in a region another player holds directly, or in a region of a player's vassal. Never in a player's capital square (rule 3.1.2 changed to match).
   - **Unowned land:** the region the square is cut from becomes a new NPC faction, neutral and sanctified like a breakaway state. With no free faction slot it stays unowned, and the chat says why.
   - **No split** in an NPC realm of fewer than 4 regions, in a realm's capital region, or in a region a player holds directly: the square sits inside that region, and its owner keeps the rest.
   - **A player's vassal** is an NPC realm and splits like any other at 4 or more regions. The owner has never seen a player's vassal realm bigger than 2 regions, so in practice this rarely applies.
4. **Direct links between players 6–15 tiles apart (SOS-120):** kept in setup by carving: a road and path link between the new centres, by the same link code.
5. **A newcomer picks on a snapshot** of the world outside the session (5.2's recommended flow). No guest mode.
6. **Eliminate un-carves** the city-state, as removal does. Spec §9's "the city becomes an NPC city owned by a neutral faction" changes.
7. **Limits accepted for now:** at most 16 players over a campaign's life (retired slots aren't reused), and a join is refused when no NPC faction slot is free. **Remark:** the slot limit will probably be lifted later (reusing a retired human slot in the engine, and slots above 15 in the mod's protocol).
8. **A late joiner starts normally:** the campaign's starting resources and difficulty, no catch-up bonus.
9. **No protection years** for a newcomer.

## 10. Cards, in order (decision 1: carving first, then hot-join)

1. **Spike (engine):** carve and un-carve in the rig (3.6). Settles feasibility and finds the caches a load doesn't reset.
2. **Engine bug, if section 8 is confirmed:** every human's capital gets its region info.
3. **E-carve (engine):** the carving module: placement rules, carve, links, the undo log `SOSDET_CARVES`, un-carve, the `RDProspects` reset; rig tests.
4. **E-setup + M-setup:** setup by carving as a lobby option (engine generation without player squares, the mod's map screens on the real political map).
5. **E-remove + M-remove:** un-carving on removal and, per decision 6, Eliminate.
6. **Hot-join, engine:**
   - (a) the chosen slot, neutral stances, `SOSDET_HUMANS` v4 with the landing flag;
   - (b) the world load that grows by one human;
   - (c) the landing state;
   - (d) the join state for a fresh process.
7. **Hot-join, mod:**
   - (a) slots above 15 or PC numbers apart from slots, plus the stream restart (protocol 28);
   - (b) Hello mid-session, the files check, the prompt, the Steam limit, runtime registration;
   - (c) the newcomer's screens on a snapshot, the landing, `Campaign.adding`.
8. **Test tool:** a third game joins a running two-game session (battles3 or twopc).

Sizes, roughly: the spike is days. (A) and (C) are each the size of a normal feature card pair. (B) is the size of the reconnect work (SOS-175 to SOS-180) or larger.
