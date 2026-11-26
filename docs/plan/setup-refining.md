# Setup refining: titles, borders, regenerate, changing picks

Status: **built and tested (2026-11-27); PRs next.** Approved by the owner on 2026-11-26 with its spec changes ("include lobby gui work in this batch"; titles: "Yes, build titles with engine change"). Asked for by the owner after the lobby GUI review (2026-11-26). It changes spec section 5 (and section 11 for hot-join). State: section 8.

## 1. What the owner asked for

1. **Titles.** A lobby setting row the size of the World row: **No titles**, **Host titles** (every player gets the set the host picks), **Unique** (each player picks from the titles their own profile has unlocked). Titles are picked after the race, in a classic world, a living world and a hot-join.
2. **Borders while placing.** Other players' spots stay drawn when the mouse is over the Place Capital window or the minimap. A spot is drawn as a 6-pixel outer border in the banner's foreground colour, with the banner inside it, and the faction's name over it in a font three times today's. The same in a classic world, a living world and a hot-join.
3. **Regenerate with a vote.** The host has Regenerate in Place Capital (classic) and Place your city-state (living world). The host confirms or cancels; every other player gets a Yes/No vote; a majority regenerates; a tie is No.
4. **Living world, host panel.** Ready and Initialize World don't fit in Place your city-state.
5. **Living world, borders.** Lines between regions, and each neutral NPC state its own border, instead of one border around all of them.
6. **Changing picks after the world is built** (living world): race, banner, faction and titles can be changed as in vanilla until Ready.

## 2. What exists (research of 2026-11-26)

- **Titles in vanilla** (`game/faction/player/PTitles.java`): 34 titles, unlocked per profile in `profile/Titles2` (title key and the races it was earned with). Up to 5 are picked in `StagePickTitles` after Customize Faction. A title boosts the whole player faction: city stats, and some world ones (battle skill and morale, world loyalty, diplomacy, raiding, opinion). Its strength is 50% to 100%, by how many races the profile earned it with. The selection is one global state on `FACTIONS.player()`, saved in the save by title index.
- **The mod today** turns titles off: `MpMapStage.takeOver` sets `hasSelectedTitles = true` (spec 5: "No titles are given at this stage"), and `MinimapAdapter.zoomButtonsOnly` hides vanilla's race, faction, titles and terrain buttons.
- **The engine** already keeps each PC's player data in the world save (`GameWorldLoad`: `PTitles.saver`), and the world part reads a human's boosts from that human's city report (`Hooks.reportValue`, `reportUnlock`), not from the local game. So each PC applying only its own titles may already reach every PC's world correctly. This has to be checked (step T1) before it's relied on.
- **Why spots vanish:** the placers draw spots in `renderPlaceHolder`, which vanilla's `ToolManager` calls only while the tool is hovered: not over the side panel, and never over the minimap (the minimap takes the hover first). Player cursors don't vanish because the side panel draws them. Classic spots are filled red/green squares (`MpLocationStage.Placer`), living-world ones 2-pixel frames (`MpSpotPlacer`); names are the small font.
- **Living-world borders** are vanilla's: `WREGIONS.renderBorders` draws a line only where the faction changes, so regions of one realm have none, and all unowned land compares equal and forms one border.
- **The host panel overflow:** `MpSettle.Settle.info()` gives the status a fixed 9 lines; the host's text (Place plus the stand-in note, about 390 characters) wraps to more and runs over the Ready / Initialize World row. `MpFactions` measures its status height and doesn't have this problem.
- **Regenerate in vanilla:** before a capital is placed, Regenerate re-rolls the terrain with a new seed (`StageCapitol`). After placing, it keeps the terrain and the capital and re-rolls the political world (`StageCapitol.regenerate`).
- **Votes:** the battle restart vote (`RestartTally`, `RestartVotes`, `MpRestartAsk`; SOS-231) already does "the caller is Yes, majority of all players passes, a tie fails, 30 seconds". It's tied to a battle id.
- **Changing picks:** classic picks stay editable until Ready. In a living world race and faction freeze at Build World, and the location screen has no Back. Hot-join has Back.

## 3. Work, in order (each its own commit; one shared test cycle)

### A. Spots drawn always, in the new style (mod)

- Draw every player's spot from the side panel's render, as the cursors are, instead of the placer's hovered-only path; the placer keeps drawing only the local footprint under the mouse.
- One drawing for the three placers (classic, living world, hot-join): a 6-pixel outer border in the banner's foreground colour, the banner inside it, and the faction name above it in the small font at three times its scale (scaled with the zoom, as today).
- Check: screenshots in a classic and a living-world setup with the mouse over the map, the panel and the minimap.

### B. Living world: region borders, the host panel (mod)

- The status box measures its height from its longest text, as `MpFactions` does, so Ready and Initialize World fit.
- During placement only (owner, 2026-11-26), draw a thin line between every two regions and around every NPC state, in that state's colour (unowned land in vanilla's grey), from the mod's own render of the visible tiles, not a game-code change. Setup screens only; the game itself keeps vanilla's borders.

### C. Change picks after Build World (mod, engine check)

- The living world's location screen gets vanilla's minimap buttons back for race, Customize Faction and titles (not terrain), greyed while Ready.
- Check first whether `buildWorldForCarving` uses the host's race for the stand-in town; if it does, a host race change after the build keeps the stand-in's race (it becomes a neutral state anyway).

### D. Regenerate with a vote (mod, protocol)

- Generalise the restart vote: a vote has a kind (battle restart, regenerate) instead of always a battle id. Same rules: the host is Yes, a majority of all players passes, a tie or 30 seconds without a majority fails, a player who leaves drops out.
- Host: Regenerate in the panel, then Confirm/Cancel; every other PC: a Yes/No window naming the vote; the chat says the result.
- On a pass, the whole world goes: every PC returns to the map step, where the host generates a new terrain (and changes the options if they like) and confirms it as today; a living world is then built again with Build World. Races, factions and titles are kept; every spot and Ready are cleared (owner, 2026-11-26).
- Protocol version up; `ProtocolTest` round trip; `RestartTally` tests reused for the new kind.

### E. Titles (engine audit, mod, maybe engine)

- **T1, audit (first, reported before building):** every title boost and lock the world part or a lockstep battle reads: does it come from the owning human's report (same on every PC) or from the local `FACTIONS.player()`? List any that don't; those need an engine change (SOSDET) before titles are on.
- **T2, lobby:** `LobbySettings` gets the titles mode (No titles default), the Create screen a row like World, the lobby's settings line shows it. The campaign record keeps it for hot-join and resume (record format up). Protocol version up.
- **T3, picking:** after Customize Faction, vanilla's title screen (the mod's screens already reach the generator's package): Unique, each player from their own profile; Host titles, only the host picks, and the others see the host's set, read-only. The picks travel with the faction picks, by title key.
- **T4, applying:** each PC selects its own player's titles at the world's start (Host titles: the host's set). Strength is vanilla's, from each player's own profile: Unique at the picker's strength, Host titles at the host's (owner, 2026-11-26). The strength travels with the picks, so every PC applies the same number.
- **T5, hot-join:** a joining player picks titles after their race when the campaign's mode is Unique, gets the host's set with Host titles.
- Checks: unit (settings and record round trips, old record loads as No titles), twopc with Unique on both PCs and different titles, hashes equal; a battle between them.

## 3a. T1 audit result (2026-11-26)

- **Safe for sync as built:** SOSDET patch 15 gives each human its own title compound (`BoostCompound.of(Player)`), patch 98 reads each human's own `titles.bos`, and the world part reads every human's boosts from their city report (`reportBoost`, `BOOST` slots cover the title boosts). Lockstep battles load the battle PC's save, so its titles come along; army 1 and allies read their reports at Engage. Vanilla titles have no faction or region locks.
- **Strength:** in a session the engine values every title at vanilla's base, 0.5 (`simulationValue(raceValue, 0)`, patch 98), the same on every PC. The owner's decision 2 (each profile's own strength) needs an engine change: the strength travels with the picks and the engine uses it. To confirm with the owner before E.
- **When:** `PTitle.select` is no command. Picks apply before the session's first step (and before any view); a hot-joiner picks after the join's `addHuman`, before their first step. Otherwise a viewed city or a loaded battle could diverge.
- **To check in E:** a run with titles on two PCs not in slot 0, `Stats.reportMisses` staying 0 (the one live fallback, `Hooks.reportBoost`'s miss).
- **Known gaps, same on every PC:** a siege assault's invaders are NPC-typed, so the attacker's titles don't apply there; other players' titles show empty or stale in their title text on another PC.

## 3b. E as built (2026-11-26)

- **Engine (SOSDET `setup-refining`):** `HumanStart` carries the titles (indices) and each one's race count; whatever builds or adds a human (build, carve, `addHuman`) gives it exactly those (`GameTitles.apply`, through `PTitles.saver`). The race counts are world state, section `SOSDET_TITLES` (`TitleTable`), and patch 98 reads them through `Hooks.titleRaces`: this PC's profile without a session, the holder's count with one, 0 (vanilla's base, as before) for a human with none set. `Engine.titleRaces`, `heldTitles`, `heldTitleRaces`.
- **Mod:** `TitlesMode` (No titles, Host-Titles, Unique) in `LobbySettings`, the Welcome and the campaign (record format 6, an older record reads No titles); `FactionPick` carries titles and race counts (protocol 37). The host's build applies the mode (`TitlesMode.apply`); a hot-join's newcomer gets it on the host (`WorldSetupAdapter.newcomerTitles`: the host's held titles in Host-Titles). The title screen comes after the race (owner: "after race"), for the host in Host-Titles and everyone in Unique; the other players see none. Titles travel as the game's title indices, as vanilla's save keeps them; the lobby's same-files check makes them mean the same on every PC.
- **Autopilot:** `titles=NONE|HOST|UNIQUE` on the host (`--titles` in twopc); a picking player takes its first 5 unlocked titles; every PC's `loaded` event lists each human's titles and race counts.

## 4. Decisions (owner, 2026-11-26)

1. **Regenerate:** back to the map step, a whole new world; races, factions and titles stay.
2. **Title strength:** vanilla's, from each player's own profile (Host titles: the host's).
3. **Region borders:** only while placing.

## 5. Not in this plan

- Changing titles once the game runs (vanilla has none).
- Steam Deck and macOS checks beyond the usual.

## 6. Order and size

A and B are small (mod only). C and D are medium. E is the largest and the only one that may need SOSDET; T1 decides. Proposed: A, B, C, D in one batch; E after its audit, as its own batch.

## 7. Spec changes (need approval)

- §5 Lobby 2: the host also chooses titles: No titles, Host titles or Unique.
- §5 Map 2 and Factions: after the map is confirmed, the host can propose Regenerate in Place Capital (and Place your city-state); every other player votes; a majority sends everyone back to the map step for a new world, a tie doesn't.
- §5 Factions 1: replace "No titles are given at this stage" with the titles rule.
- §5 Living world 1 and 3: race, faction and titles can be changed until Ready, after Build World too; Regenerate as above; spots drawn with the border, banner and name.
- §11 hot-join: titles after the race, by the campaign's mode.
- §15: acceptance criteria for the vote (tie fails), titles (every PC's hash equal with different titles), spots visible with the mouse over the panel.

## 8. State on 2026-11-27

**Commits.** Mod `lobby-gui-refining`: 7c548fb lobby GUI (players count, tooltips, Vanilla world, Direct IP, no transfer-log switch), 3c904e4 spots, region lines, picks after Build World, 0971a9b the vote for a new world (protocol 36), 447fd59 titles (protocol 37, campaign record 6, spec 0.76), 0b18ac7 smaller spot names and borders edged in black, then the review's fixes. SOSDET `setup-refining`: 1f6889b `Engine.loadBanner`, 422d45c titles (`HumanStart` titles, section `SOSDET_TITLES`, `Hooks.titleRaces` in patch 98), c677d45 the title menu shows this PC's player's titles (patch 96-cmd-names, `view/ui/profile/Titles.java`), then the review's fixes.

**Tests.**
- twopc titles-U1 (`--here --mods-from ../SOSDET --steps 6000 --titles UNIQUE --window 1280x768`), 2026-11-26: PASS, 10 hash steps, 0 differ; both PCs listed the same titles and strengths for each human.
- The owner's manual test, three games on this PC, 2026-11-26 and 2026-11-27: the lobby GUI, spots, the title menu after its fix, the marks after theirs; the owner: "it's all fine".
- After the review's fixes: SOSDET `test assemble` 478 tests, 0 failures; mod `test build` 393 tests, 0 failures.
- twopc titles-U2 after the review's fixes (title table now in the hash), same options, 2026-11-27: PASS, 10 common hash steps, 0 differ; both PCs `0:0/2;1:0/2`.

**The review (2026-11-27), fixed:**
- A passed vote for a new world takes the factions step away while Place Capital and Place your city-state still draw (a joiner until the host's build arrives): their lock checks and buttons read it. They now treat a missing step as locked, the side panel draws nothing until the map step, and the vote's result is taken before the check, so the "passed" line shows and an open question closes.
- A joiner's pick or Ready sent just before it heard the vote pass made the host drop that player; the host now drops the message (`aPickSentAsTheVoteForANewWorldPassesKeepsThePlayer`, which failed before the fix).
- The host's ready notices after a new world compared against the old world's picks.
- Engine: the title table is in the world hash (part 7; a world with no titles set hashes as before); `GameTitles.apply` writes the table after the selection loads; `Engine.loadBanner` names a null argument.
- The autopilot reads held titles through `WorldSetupAdapter`.

**The review, kept as they are:**
- Title indices are not checked against the host's title count: the lobby's same-files check gives every PC the same titles.
- A title's race count is bounded only by the mod's 255: a real profile's is at most the playable races; a modified client could send more, as it could send any other pick.
- `GameTitles.apply` sets the selection through vanilla's saver, not `PTitle.select`: `select` clears `FACTIONS.player()`'s boost cache, not the spare Player's. `raceValue` is read by reflection because vanilla's formula uses exactly it.
- The region lines walk the visible tiles each frame, without allocating, on the setup screens only.
- `MpSettle.syncPick` and `MpFactions.syncPick` stay two: the settle screen keeps the placed spot from its own picks, the factions step from its placer.

**Open for the owner:** a vote for a new world and hot-join titles were checked by the loopback tests and reasoning, not yet in a played game with several PCs; real Steam was not used.
