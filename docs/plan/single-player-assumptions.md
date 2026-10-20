# Single-player assumptions (V71)

Card: [SOS-9](https://fighterx2500.atlassian.net/browse/SOS-9) · Date: 2026-10-20 · Game: 0.71.44

This document lists the places where the game assumes exactly one player faction and groups them into the changes multiplayer needs. It's planning only: no mod code, and no hook is approved. Hook IDs refer to [hook-surface.md](hook-surface.md); new ones added by this work are F8–F10, B6 and W5.

**Two terms used below:**
- **Human:** any player in the campaign.
- **Local player:** the human sitting at the PC the code is running on.

On every PC, `FACTIONS.player()` is that PC's local player. Other humans appear as puppet `FactionNPC`s (architecture §Core rule).

## 1. Summary

- **Most call sites can stay as they are.** `FACTIONS.player()` / `GAME.player()` appear on **1,075 lines in 310 files**. About 700 of them (UI, the city, setup) are correct as written, because on each PC they mean the local player.
- **The host-run world is where the work is: about 363 call sites in about 140 files.** That's the world, faction, diplomacy, trade, raiding and event code that only the host runs, where "the player" has to mean "a human", "this particular human" or "the human who owns this".
- **Six deeper assumptions, outside those call sites, carry most of the design risk:**
  - **NPC relations exist toward one player only:** `ROPINION.get(npc)` (152 uses), one-argument `DIP.get(npc)` (62), `DipWarPlayer`, `Deal` (one `player` side, one `npc` side).
  - **The battle engine has exactly two sides,** "player" and "enemy" (`ARMIES().player()/enemy()`, 165 uses), each with one fixed faction.
  - **One store of city soldiers,** `AD.cityDivs()` (53 uses), for the local player only.
  - **Player-only progression** (credits, levels, titles, tech, emissaries, boosts) exists only in the `Player` class, which remote humans don't have on the host.
  - **Events and raids aim at "the" player.**
  - **Local-view code runs inside the world loop:** fog of war, minimap, overlays.
- **Four of these need a decision from you** (§7).

## 2. Kinds of single-player assumption

| Kind | Count | Where | Multiplayer needs |
| --- | --- | --- | --- |
| `FACTIONS.player()` and its alias `GAME.player()` | 1,075 lines, 310 files | everywhere | Classified in §3 and §4. |
| Battle engine with two sides, one faction each: `ARMIES().player()` → `FACTIONS.player()`, `ARMIES().enemy()` → `FACTIONS.otherFaction()` | 165 uses | `game/battle/Armies.java:216-220`, `Army.faction()` (`Army.java:100`), `Div.faction()`, `DivInfo.faction()` | The host runs battles between other humans (ADR 0002). Each side's faction must be the real human, and with allies (spec §8) one side holds several humans whose divisions each player moves separately. New hook **B6**. |
| NPC opinion of "the" player: `ROPINION.get(FactionNPC)`, `OpsStance`, `OpsEmi`, `OpsOther` | 152 uses, 44 files | `game/faction/royalty/opinion/` | One opinion per NPC, toward one player. With several humans, each NPC needs an opinion of each human (question SP1). New hook **F8**. |
| Stance between an NPC and "the" player: one-argument `DIP.get(npc)`, `DipStance.is(npc)` / `set(npc)`, `DipStance.player()` | 62 one-argument `DIP.get`, plus the `DipStance` helpers | `game/faction/diplomacy/DIP.java:162`, `DipStance.java:52-61` | The general table `DIP.get(a, b)` already exists (F6). Each one-argument call must name the human it means. Part of **F8**. |
| NPC war AI against "the" player: `DipWarPlayer`, `DIP.WAR_PLAYER()` | 1 class, 9 uses | `game/faction/diplomacy/DipWarPlayer.java` | NPCs plan wars against one player (`playerPower`, `coalitionAdvantage`). Needs to be per human. Part of **F8**. |
| Deals: `Deal` has one `player` side and one `npc` side | 3 classes | `game/faction/diplomacy/deal/` | NPC ↔ human deals for every human; human ↔ human through the mod's own UI (F7). Part of **F8**. |
| City soldiers stored in world armies: `AD.cityDivs()` | 53 uses, 15 files | `world/army/WDivStoredAll.java`, `PlanJoinArmy` | One store, for the local player's city. On the host, the armies of remote humans need their own stores (C5). |
| Player-only state: `Player` and `game/faction/player/*` (`PCredits`, `PLevels`, `PTitles`, `PTech`, `Emissaries`, `PBonusSetting`, `PTrade`) | 249 call sites read it | | The host's world logic reads credits, level, titles, race, offensive power and emissaries of "the" player. For a remote human the values live on the owner's PC. New hook **F10**. |
| NPC-only state: `instanceof FactionNPC` (48), `(FactionNPC)` casts (85) | 133 uses | | Puppets *are* `FactionNPC`s, so these branches treat other humans as NPCs (court, stockpile, opinion). Covered by F2 (AI off) and F8. |
| Special region for the player: `WORLD.REGIONS().player` | | `world/region/RD.java:238-240` | Setup of the local player's capital. Each human's capital comes from the host's world. Lobby work (spec §5), not a hook. |
| Events and raids aimed at "the" player | `game/events/faction/player/*`, `EventFactionWar`, `EventFactionExpand`, `EventWorldRebellion`, `game/raiding/*` | | Which human does an NPC war event, a rebellion or a raider army target? New hook **F9**, question SP2. |
| Local-view code inside the world loop: fog of war (`world/map/fow/FOW.java`), `WorldMinimap`, `COLOR_MAP`, overlays | | | On clients the world loop doesn't run (A2), but these must still run for the local player. New hook point **W5**. |

## 3. How the call sites were classified

The script is `spikes/single-player/classify_player_refs.py`. It's rerunnable, and its output is [single-player-callsites.csv](single-player-callsites.csv): file, line, enclosing method, class and use. The CSV has no game source code in it (AGENTS.md); the script can print the code for local use with `--with-code`.

1. **Call sites.** Every source line matching `FACTIONS.player()` or `GAME.player()`, found by plain text search, so the list is complete. Each line is mapped to the innermost method containing it, using the codegraph index.
2. **Reachability.** The script walks the codegraph call graph, including its dispatch heuristics, and treats methods of anonymous classes as reachable together with the method that contains them. It starts from three sets of roots:
   - **world:** `update` / `afterTick` of `WORLD`, `FACTIONS`, `EVENTS`, `NOBLES`, `RAIDING`, `TOURISM`;
   - **city:** `SETT`, `ARMIES`, `TIME`, `INTER`, `TEMP_EVENTS`, `ENGINE`, `COUNTS`;
   - **UI:** `VIEW.update` / `render`.

   From those roots it reaches 5,628 world methods, 6,095 city methods and 10,592 UI methods.
3. **Class**, one per call site:
   - **ui:** anything in `view/` or `util/gui/`;
   - **setup:** `init/`, `menu/`, `launcher/`, `cutscene/` and generators;
   - **host-world:** reached from the world roots;
   - **city:** reached only from the city roots;
   - **unreached:** no path from any root (listeners, save/load, constructors).
4. **Use**, read from the line itself:
   - **is-human:** a comparison with the player;
   - **counterpart:** a diplomacy or opinion call;
   - **player-state:** credits, level, titles, race and similar;
   - **realm:** the player's regions, armies or capital;
   - **goods:** the player's `seller` / `buyer`;
   - **argument:** the player is passed on to another method.

**Limits:**
- **Too broad in places.** The call graph's dispatch links over-approximate: 13 `settlement/` lines classed host-world are really city bookkeeping (for example `StatsHome`, `Equip`).
- **Too narrow in places.** World code that runs through listeners (`RDOwnerChanger.change` and others) shows up as **unreached**. For planning, the 156 unreached lines in `world/` and `game/` count as host-side.
- **Use is a regex reading of one line.** It's a guide for estimating, not a verdict.

### Results

| Runs in | is-human | counterpart | player-state | realm | goods | argument | Total |
| --- | --- | --- | --- | --- | --- | --- | --- |
| host-world | 90 | 25 | 35 | 25 | 5 | 40 | 220 |
| unreached | 59 | 15 | 25 | 9 | 0 | 66 | 174 |
| city | 4 | 1 | 85 | 9 | 3 | 49 | 151 |
| ui | 34 | 14 | 100 | 71 | 42 | 233 | 494 |
| setup | 5 | 0 | 4 | 1 | 1 | 25 | 36 |
| **Total** | 192 | 55 | 249 | 115 | 51 | 413 | 1,075 |

**What each class means for multiplayer:**
- **city (151), ui (494), setup (36):** correct as written on every PC: the local player's city, screens and game creation. A few UI screens will need other humans to look like humans rather than NPCs, for example the diplomacy list; that is UI work in the feature stories, not a hook.
- **host-world (220, of which 13 are false positives) and unreached world/game (156): about 363 lines in about 140 files.** These are the change groups in §4.
- **unreached elsewhere (18):** `util/text/Insert*`, text placeholders such as "{PLAYER}". They mean the local player and stay as they are.

## 4. Change groups on the host side

Each group lists typical call sites (V71 line numbers) and the hook points it maps to.

| Group | What "the player" means there | Typical call sites | Hook points |
| --- | --- | --- | --- |
| **G1. Is this faction a human?** | "Any human", not "the local player" | `TradeManager.tarif/toll` (`:49-88`), `PSiege.besige/poll/create` (`:153-244`), `PRegAttack` (`:75-159`), `Side.power` 0.8 multiplier (`:196-197`), `world/army/ai/War` (`:50-85`), `ADUpdaterDiv:34`, `WRegFinder:364`, `RDMilitary:152-180`, `RDHealth:164`, `RDBuildPoints:237` | **F5**: a one-line `isHuman(f)` check at each site (139 lines in 68 files, mechanical). |
| **G2. NPC relations toward a human** | "The human this NPC is dealing with" | `DIP.get(npc)` (`DIP.java:163`), `DipStance.is/set(npc)`, `DipWarPlayer.init/validEnemy`, `OpsStance.battle/setNewStance/signPeace`, `ROPINION.*`, `Deal.setFactionAndClear` (`:37-38`), `DealBools`, `DealParty`, `UpVassal.update` (`:69-76`) | **F8** (new): relations per (NPC, human). Needs SP1. |
| **G3. The player's own empire** | "Each human, for their own regions and armies" | `RDUpdater:53`, `RDOwner:70-74`, `Builder:71`, `RDMilitaryGar:74-133`, `RDDistance:198-227`, `RDBuildPoints:105-122`, `Shipper:40`, `WArmy.update/checkForResources` (`:193-215`), `ADStats:213`, `WDivMercenaries:47-65` (mercenary pay from the player's credits) | **F5** (loop over each human instead of one player), **F10** (credits and similar from the owner), **C5** (soldiers). |
| **G4. Goods and money for the player** | "The human who owns this caravan, sale or payment" | `TradeManager.update/sellPlayer/ship` (`:119-215`), `TradeSorter.sellPlayer/buy` (`:41-309`), `WArmy.returnResources` (`:230`, spoils caravan), `MessDemand.pay` (`:184-188`), `UpVassal:241` (tribute), `_CREDITS`, `_RESOURCES` event actions, `NPCRes.addReserve/remove` (`:240-267`) | **C1–C4**, **F4**: each becomes a ledger transaction to the right human. |
| **G5. Battle and siege dispatch** | "The humans in this battle" | `ResolverPlayer.battle` (`:72`), `ResolverSide.init` (`:64` `player` flag), `Util.conquer` (`:218-257`), `ResolverPlayer.occupy/puppet` (`:534-554`) | **B1**, **S1**, plus **B6** (battle sides). |
| **G6. Events and raids aimed at the player** | "Which human?" | `game/events/faction/player/War` (`:88-276`), `Stance` (`:58`), `EventFactionWar` / `EventFactionExpand` (timers scaled by the player's region count), `EventWorldRebellion` (`:102-164`), `game/raiding/RaidingMap/RaidingUtil/RaiderArmy/MessDemand`, `EventUprising:215` | **F9** (new). Needs SP2. |
| **G7. Player-only state read by world rules** | "This human's credits, level, titles, race, tech" | `Player.offensivePower` (`:333`), `PLevels/PTitles.getD` (lockables), `PTech:272`, `ResourcePrices:35`, `RaidingUtil.ransomCurrent` (`:60`), `NPCCourt.race` (`:222`), `DivInfo.clear` (`:178`) | **F10** (new): the host reads a remote human's values from that human's latest city report. |
| **G8. Local view in the world loop** | "The local player", correctly | `FOW.update/is` (`:64-158`), `COLOR_MAP.get` (`:48-52`), `WorldMinimap`, `WorldOverlays`, `OverlayRegnames`, `WArmy.renderAboveTerrain/handleFow` | **W5** (new): keep these running on clients even though the rest of the world loop stops (A2). |

## 5. New hook points (added to hook-surface.md)

| ID | Game location | Multiplayer needs | Route |
| --- | --- | --- | --- |
| W5 | `world/map/fow/FOW.java`, `world/WorldMinimap.java`, overlays: world resources that are really display for the local player | Keep running on clients while the rest of the world loop is off (A2). | Hook or reflection, decided together with A2 |
| F8 | `game/faction/royalty/opinion/*` (`ROPINION`), `DIP.get(npc)` and the one-argument `DipStance` helpers, `DipWarPlayer`, `Deal`, `UpVassal` | Each NPC keeps its opinion, stance history, war plans and tribute **per human**, not toward one player. | Hook (data per human, and the one-argument calls take the human) |
| F9 | `game/events/faction/player/*`, `EventFactionWar`, `EventFactionExpand`, `EventWorldRebellion`, `game/raiding/*` | World events and raiders pick a target human (SP2). Raids that enter a city run on that city's owner's PC. | Hook |
| F10 | Reads of `Player` state from world code (`credits()`, `level()`, `titles`, `race()`, `offensivePower()`, `emissaries`) | For a remote human, return the values from that human's latest city report. | Hook |
| B6 | `game/battle/Armies.player()/enemy()`, `Army.faction()`, `Div.faction()`, `DivInfo.faction()` | The host runs battles between other humans: each side's faction is the real human; with allies, several humans per side, and each division belongs to one human, who alone can order it (spec §8). | Hook; scope set by the battle streaming spike |

## 6. Risks

- **G1 is large but mechanical.** 139 one-line changes spread over 68 files. That's many small hooks to carry. The ADR on hook distribution (planning task 5) should decide whether hooks are kept as a source patch or as a separate set of replaced classes; either way, F5 dominates the hook count.
- **G2 changes data, not just checks.** Opinion and stance history are saved per NPC today. Keeping them per (NPC, human) changes the save format of those sections, and the host's save carries them (AGENTS.md "Persistence": a version number and old-save handling).
- **Puppet humans run NPC code paths.** Other humans are `FactionNPC`s on the host (architecture). Every `instanceof FactionNPC` branch (48) and cast (85) treats them as NPCs: court, stockpile, opinion. F2 (AI off) and F8 cover the known ones; the rest need a review per feature.
- **Battle sides (B6) are a structural limit,** not a one-line check: the engine has one "player" army and one "enemy" army. Allied players on one side and per-player control of divisions need a design inside the battle streaming spike.

## 7. Questions for you

- **SP1: How do NPCs relate to several humans?** Vanilla first (spec §1) suggests each NPC keeps a separate opinion, stance history and war plan **for each human**, as if each human were the vanilla player. My recommendation: yes, per human. The alternative, one shared opinion of "the humans", is simpler but not vanilla.
- **SP2: Who do world events and raids target?** For example NPC war declarations, rebellions, raider armies and ransom demands. Recommendation: each human gets their own event timers and raiders, as if alone (vanilla first). Events that happen inside a city (uprisings, killers) run on that city's owner's PC.
- **SP3: Other humans as puppet NPC factions (architecture).** This keeps the faction list unchanged but sends them down NPC code paths (§6). The alternative, several `Player` instances, would touch the 64-slot `FACTIONS` layout and the save format. Recommendation: keep the puppets, with F2 and F8. Please confirm, or ask for a short spike first.
- **SP4: Allied humans in one battle (spec §8).** The battle engine has one faction per side. Recommendation: let the battle streaming spike measure what per-division ownership needs, before deciding whether allies stay in v1 as written.
