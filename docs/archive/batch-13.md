# Batch 13: the 2026-11-17 playtest backlog, worked by parallel agents

Date: 2026-11-18. Status: **planned; the owner answered the questions on 2026-11-18** (section 5). No cards, branches or code yet. The plan was written in a planning session; a separate orchestration session carries it out.

The session: 3 players over Steam (host FighterX2500, Fray2, Adam Smasher), 2026-11-17 21:55 to 2026-11-18 03:27, pack 2026-11-17. Host logs: `%APPDATA%\songsofsyx\sosgsgmp\logs\20261117-*-host-*.log` and `20261118-*-host-*.log`; the joiners' reports and saves reached the host at 02:00 (`sosgsgmp\reports\`). Campaign saves of that night: `sosgsgmp\saves\MP FighterX2500s campaign*.save`.

Nothing below has been run yet. The causes come from the logs and the source and are marked **probable** where they aren't proven.

## 1. Batches and agents

| Batch | Items | Agent per item | Repos |
|---|---|---|---|
| **A** desyncs | 11 + 12 (one agent: same symptom chain), 7 | 2 | SOSDET first, then the mod |
| **B** economy and diplomacy | 8, 1, 2, 9 | 4 | SOSDET mostly |
| **C** small changes | 4, 5, 6 | 3 | the mod |
| **D** features | 3, 10 | 2 (10's agent uses subagents for 10a, 10b and 10c) | both |

11 agents in all. The orchestration session is the **lead**: it starts the agents, holds the one test queue, merges, relays decisions, and reports to the owner.

## 2. How the agents work (owner, 2026-11-18)

**Code, in parallel.**
- Each agent has its own worktree and branch in each repository it touches: `b13-<item>` (for example `b13-08`), cut from `main`. It may start subagents for searching, reading and unit tests.
- Agents never run a game, the rig or twopc. Unit and loopback tests (`gradlew.bat compileJava compileTestJava test`) are theirs to run.
- Each agent follows AGENTS.md (reproduce first, round-trip tests for saved state, hooks only with approval) and writes its findings and fix under its item in this file's work section, in its own branch.
- **Shared numbers are the lead's:** an agent that needs a new network protocol version, campaign record format, save section version or SOSDET patch number asks the lead, which hands them out in merge order. Several items change the protocol (3, 8, 10), and a clash there costs a full test cycle.
- **Decisions go through the lead (owner, 2026-11-18).** An agent that needs a decision (a rule, a spec change, a new hook, a trade-off between two fixes) never picks one silently and never asks the owner itself. It sends the question to the lead with what it found and its recommendation, and works on what doesn't depend on the answer meanwhile. The lead collects the questions, asks the owner, and passes each answer back word for word.
- Known overlaps: items 2 and 9 both touch SOSDET's diplomacy and opinion code (`HumanDeals`, `GamePassed`, `StanceBook`, the `14-report-reads` patch to `OpsStance`). Item 3 and item 10a both touch the battle result and prompt code. The lead merges these pairs first and tells the second agent to rebase.

**Tests, one batch at a time.**
- When its code is ready, an agent writes a **test request**: the setup (save, instances, tooling), the steps, the pass criteria, and which logs to keep. It then waits.
- When every agent in a batch has its request in, the lead merges the batch's branches into `b13-<batch>` (both repositories), builds, installs, and runs one test cycle for the whole batch with the `sos-test-run` rules: one game set at a time, the one-minute monitor, short runs. Results go back to each agent: logs, screenshots, pass or fail.
- A failed item is fixed by its agent and retested in the next slot of the same stage. Only the requested tests are run.
- Only one batch tests at a time, because of this PC's limited computing resources. A batch that's ready waits in the queue.

**Stages (owner, 2026-11-18).**
1. A, B, C and D each tested alone, in the order they're ready.
2. **AB** together, then **CD** together: the same automated tests again on the merged build.
3. **ABCD** together: every automated test again, plus a manual session for the owner (`sos-manual-test`) for what only eyes can check: items 3, 4, 6 and 10.
4. Then `sos-ship-batch`: one PR per repository (engine first), the cards, the pack.

## 3. The items

### Batch A

**11 + 12: the view of Fray2's city closes at once; a desync loop after it.**
- Found: a view of Fray2's city broke twice, about 35 steps after it loaded: `viewdesync ... [divisions]`, then "lost step".
  - 23:18 (log `20261117-223957`): a world desync loop started at once, 12 desyncs.
  - 01:59 (log `20261118-012021`): a loop started at 02:02, 8 desyncs, ended by Save and exit. Reloading the save cleared it (02:04), after one more desync that a resync fixed.
  - In every loop only the `clock` section differs (the count of world random draws); the world, soldiers and divisions match, and each resync holds for about 100 steps.
- Views of Adam Smasher's city ran fine. So did the view of Fray2's *battle* at 22:11. Fray2 plays on Windows, so a platform difference is ruled out.
- Probable: something in Fray2's city, in the city save or its load, isn't restored the same way on the viewer, and after the failed view the viewer's world part keeps an extra random draw that a resync doesn't reset (the clock isn't in the resync save, or the drawing state lives outside it).
- Work: reproduce on 3 instances from the campaign save of 01:45 (`...-b0b.save`) or the save before the 23:18 view, with the host viewing Fray2's city. Find what differs in `divisions` (SOSDET `View`, the view save), then what keeps drawing after the view ends. Engine first (SOSDET `View.java`), mod side `MpViews`, `ViewRequests`.
- Test: view Fray2's city 3 times from that save. Each view holds 2 minutes with no `viewdesync`, and the world runs 5 minutes after it with no desync.

**7: an NPC's help against raiders, then a desync.**
- Found: Fray2 used vanilla's **Request Assistance** (`game.raiding.MessAlly`). The raiders destroyed the NPC kingdom, which became a neutral rebel city, and a desync followed. The time isn't known; a lone `clock` desync at 23:49:37 (step 138150, log `20261117-232014`), which a resync cleared, fits it.
- The request itself is a lockstep command (SOSDET `Events.RAIDER_ALLY_HELP` → `RaidingCurrent.setAllyFight`). What runs after it is vanilla code inside the step, and these are the probable sources of a random draw on one PC only:
  - `MessAlly.fight`: `RND.rFloat()` decides the fight; check that it's the step's random source on every PC, for the right human (`currentHumanSwap`).
  - Message texts picked at random (`raiderMess.allyHelp.rnd()`, `allyFight.rnd()`) while the message is built. If the message is built on one PC only, as a player's own messages are, the draw happens there only, which matches a `clock`-only desync.
  - `FACTIONS.remove(npc, true)`: the NPC's land going to rebels, its armies and requests; check for per-PC state.
- Work: a rig scenario that makes the raider ask a human's NPC neighbour for help and win (`raid3` plus a test command), then the fix. Engine.
- Test: the raider destroys the helping NPC with 3 instances; no desync for 5 minutes after; every PC shows the rebel city.

### Batch B

**8: goods duplicated through deals between players.**
- Found:
  - Proposal 66 (00:28, step 301877) gave 1000 Furniture from Adam Smasher to FighterX2500; it arrived at 00:30.
  - After a resume, proposal 67 (00:46, step 5767) gave 1000 more, and it arrived **twice** (steps 11660 and 17350).
  - 3000 Furniture left a city that held 1300–1400.
  - Proposal ID 67 was used twice, before and after the resume.
- Probable, two bugs:
  - **No stock check that holds.** `HumanDeals.sideRefusal` checks the giver's goods from the city's report (`getAvailable`), which is taken every 30 steps. `give` removes the goods through `seller(res).remove`, but the report doesn't see that until the next report, so a second deal right after passes the check. Nothing reserves the goods.
  - **Delivered twice.** The step count starts again at 0 at every resume, and transaction IDs come from the step, as proposal IDs seem to. A delivery's "applied once" check keyed by those IDs can let a second delivery through, or confuse two deals.
- Work: reserve the goods at acceptance, so a deal that the giver can't fill is refused on every PC; IDs that stay unique across resumes (saved in the campaign); a test that a repeated delivery changes nothing, and a save/load round trip. Engine plus the mod's transfer log.
- Test: 2 instances; two deals in a row that together ask for more than the stock (the second is refused); one deal, Save Campaign, resume, check that it's delivered exactly once.

**1: a losing player gets spoils from an NPC battle.**
- Found (owner): after a battle against NPCs that the player lost, a caravan with spoils went to the losing player's city. No window showed.
- Probable: vanilla sends a battle's spoils to `FACTIONS.player()`, assuming the player won. Where an NPC won, the caravan goes to the human instead of to nobody. To check in SOSDET `WorldBattles`, `BattleOutcome` and `ResultShares`.
- Rule (owner): as in vanilla, survivors keep their gear, the dead lose it, and a loser gets nothing back.
- Test: `battles3`, a player loses against an NPC army (auto-resolve and real time): no caravan to the loser, the same on every PC.

**2: vassals and players' friends.**
- Owner, 2026-11-18:
  - The bug: Colleagues isn't passed from a player's own NPC vassals to that player's player friends.
  - Vassals also still demand money to keep relations and break treaties with them.
  - Passed stances stay **locked**: the vassal's court can't cancel them.
  - An alliance between the players passes Colleagues to each one's vassals (the overlord excepted, who already has the vassal stance).
- Spec §6 changes: "An alliance never passes" becomes "an alliance passes Colleagues". "A passed stance that the vassal ... changes ... is theirs from then on" becomes "a passed stance is locked while it's passed". The demands are already forbidden (SOS-146): a regression.
- Work: SOSDET `GamePassed`, `StanceBook`, `GameAi`.
- Test: `battles3 prep ALLIES` with vassals (`givevassal`): a trade agreement, Colleagues and an alliance each pass their stance; the vassal's court doesn't cancel it or demand money for 3 in-game years at high speed; it goes back when the agreement ends.

**9: betrayal after an NPC broke the treaty.**
- Vanilla (`OpsStance.setNewStance(npc, stance, playerDidIt)`): `playerDidIt` is never read. The betrayal from a war declaration is scaled by "treaty freshness", the time since the last stance change, whoever made it. When an NPC breaks its treaties, the stance that follows is new, so a war the player then declares counts as fresh betrayal.
- Rule (owner, 2026-11-18): breaking a treaty yourself, then declaring war, still costs betrayal. A war on an NPC that broke its treaties with you costs none. Like SOS-141, this changes vanilla in every game with the mods, single player too.
- Work: extend SOSDET's existing patch of `OpsStance` (`14-report-reads`, SOS-141) and record who made the last stance change, per human. That's saved state: a save section version and a round trip. A new hook use, which the owner approved on 2026-11-18. Update `hooks.md`.
- Test: a unit test of the rule, plus a rig run: the NPC cancels → war → no betrayal; the player cancels → war → betrayal as in vanilla.

### Batch C

**4: the resume lobby's saves list doesn't scroll.**
- It stops at about 10 saves. Make it scroll, with the newest at the top. Mod: `MpScreenResume`, `MpSaves`.
- Test: an install with 30 saves; a screenshot run scrolls to the oldest and resumes it.

**5: autosaves while paused, and broken autosaves (critical).**
- Found (a player's report, passed on by the owner 2026-11-18): their group paused for several hours while away from the keyboard. The autosave kept going, every autosave slot filled with broken saves, and the campaign couldn't continue. No logs or saves from them yet; the agent asks the lead whether the owner can get them.
- The host's autosave counts **real** minutes (the Auto-Saving interval in Settings), not game time, so 5× or 25× doesn't change how often it saves. It's meant to stop counting while `GAME.SPEED.speed() == 0` (`MpSaves.autosave`), so that check misses the session's pause somewhere. Every autosave in the owner's logs of 17–18 Nov was ordered while unpaused. Candidates:
  - the session's pause (the agreed pause, or a joiner's pause) leaves the host's `GAME.SPEED` above 0;
  - an autosave that was waiting (for a view, as at 01:56:55) fires during a pause;
  - the save before a battle, which is also named "Auto".
- Two bugs, both fixed:
  - **No autosave while paused.** Count only while the session runs unpaused, by the agreed pause rather than the local speed, and let a waiting autosave wait for the unpause.
  - **Why the saves were broken.** Find what a save taken during a long pause holds that a resume can't use (a held step, a pending order, a stale peer or Steam state), and fix it.
- A safeguard (approved by the owner, 2026-11-18): skip an autosave when the world hasn't moved since the last campaign save (same step), so one bad state can never fill every autosave slot.
- Test: 2 instances. Joiner pauses, host pauses, a pause with an autosave waiting: no save is ordered while paused. A pause of 15 real minutes with a short interval: no new autosaves, and the last one resumes. A campaign saved during a pause by Save Campaign resumes and runs on with no desync.

**6: the Goods tab's price.**
- The column is named **Price**, with two subcolumns:
  - **Buy:** what this player would pay the other for one unit (their export price + toll + this player's tariff).
  - **Sell:** what this player would get selling to them.
- Spec §7 (Traded goods). Mod: `MpPlayerWindow`.
- A GUI flow report for the owner: screenshots of the tab, its hovers and narrow window sizes (`sos-test-run` screenshot run), with the figures checked against the depot.

### Batch D

**3: restart a battle by vote.**
- Owner, 2026-11-18:
  - Every battle can be restarted: between players, against NPCs, sieges.
  - Every player in the session votes, those not in the battle and spectators too; a majority restarts it.
  - A tie fails, and no answer in 30 s counts as No (owner). Assumed unless the agent's work shows a reason to ask: any player can call the vote, one vote at a time, no limit on restarts.
- Spec §8's "No restart" (SOS-93) is replaced.
- Work:
  - Engine: every PC running the battle reloads the battle's start save at one confirmed step: lockstep battles, the NPC battle on its own clock with its watchers, the assault.
  - Mod: the vote popup (like the speed vote) and its protocol message.
- Test: `battles3`, a restart vote in a battle between players, an NPC battle with a watcher, and an assault; Yes restarts it on every PC with the same hash, No and a tie don't.

**10: helping another player, and troops in another player's city.**
- **10a, the raider sally with allies.** When a raider besieges a player's capital and the player sallies, every allied player with an army within **4 tiles** (owner, 2026-11-18, as the battles-with-allies rule) is asked Join or Stay out, as in SOS-160. The raider's stash goes to the defender. Spoils and captives are split **by power** (owner, 2026-11-18), unlike the existing battles-with-allies rule, which splits by men brought; the spec says which power (the army's strength at the battle's start, as the game measures it) and where the remainder goes.
- **10c, stationing or parading troops in another player's city, for v1** (owner). An ally's army enters another player's city and stays there, with no raid.
  - This is new: today soldiers never cross into another player's city.
  - It needs an ADR before code: whose PC runs those soldiers, their upkeep and orders, what happens at war, a resume, or a player leaving.
- **10b, fighting inside the city.**
  - The owner is right that the city is already lockstep while viewed: watching Adam Smasher's raid from start to end was a view (ADR 0006), with the viewer running the city in lockstep.
  - 10b builds on 10c and the view: the ally's stationed soldiers fight the raiders, or an attacking player, in the defender's city, and the ally commands them during the view.
- Order: 10a code; the 10c ADR and a spike for the owner's approval; then 10c and 10b. All of it stays in batch D (owner, 2026-11-18), so D tests last and waits for the ADR's approval.

## 4. Spec sections this batch touches

§6 (2, 9), §7 (6, 8), §8 (1, 3, 10a), §9 (10b, 10c), §10 (11), §11 (4, 5), §13 Sync and desync (7, 12). Rule changes go into the spec in the same PR, worded for the owner's approval first: §6 alliance passes Colleagues and passed stances are locked, §6 betrayal, §7 Price column, §8 restart vote, §8/§9 the raider sally with allies and stationed troops.

## 5. The owner's answers (2026-11-18)

1. **10a spoils:** by power.
2. **3, the vote:** a tie and no answer both count as No.
3. **10b and 10c:** in batch D.
4. **5:** a player's report: the game autosaved for hours while paused and every autosave slot was broken (item 5 above).

5. **The name:** "batch 13", branches `b13-<item>`, is fine.
6. **The autosave safeguard** (item 5): approved.

Still open, for the lead to ask: whether the player's logs and saves from item 5 can be had.

## 6. State on 2026-11-18 (end of the first orchestration session)

Cards SOS-222..234 (222 items 11+12, 223 item 7, 224 item 8, 225 item 1, 226 item 2, 227 item 9, 228 item 4, 229 item 5, 230 item 6, 231 item 3, 232 10a, 233 10c, 234 10b). Worktrees `SOS-wt/b13-<item>` and `SOSDET-wt/b13-<item>`; each item's findings, owner answers and test request are in `docs/archive/batch-13/<item>.md` on its mod branch. Agent brief: `C:/ClaudeFolder/SOS-wt/b13-brief.md`. Test logs: `C:/ClaudeFolder/SOS-wt/b13-results/`. Scratch data folders: `C:/ClaudeFolder/b13-appdata` (A), `-B`, `-C`.

Shared numbers: protocol 33 (item 3 only), campaign record format unchanged (5). Mod message types 57-59 (item 3). Engine: AiEntry 22 and Notice 13 RESTART (item 3) clash with item 10's Notices 13-15: **renumber at the D merge**. Engine commands 8006 (item 9, test), 9005 (item 7, test), 7980-7982 (10c); sections SOSDET_BREAKS (9), SOSDET_STATIONS (10c), SOSDET_STATIONED_DIVS (10b, city only); TxnKind STATION_SUPPLY, STATION_DIVS; InputKind 11 SALLY_SPOILS, 12 STATION_RESOLVE.

**Batch A (b13-A: 07 + 11): done.** Item 7 PASSED (`raid3.py ally`, 0 desyncs). Items 11+12: view11 passed on b13-A and on b13-11 (contrast, no item 7); owner: ship with the new logging, "not reproduced, causes probable".

**Batch B (b13-B: 02, 09, 08, 01):** item 9 rig B227 PASSED (rerun, 12000 steps). Item 1 A2, A4, A7 PASSED. Item 2 V1: start check passed; full 3-year run stopped at 0.70 years for the session end (no failures until then): **rerun V1** (~70 min). Item 8 T1: step 1 PASSED; step 2 found a second gap; owner chose (a) spare = available − outgoing; the agent committed it on b13-08 (mod afc1d90, engine 4dd9f86, at the wrap-up; check its report / 08.md for what's tested): **merge b13-08 into b13-B (both repos), rebuild, rerun T1** (it runs T1-resume itself).

**Batch C (b13-C: 05, 06, 04):** merged, built against SOSDET-wt/pack-1117 (330 tests, 0 failures), installed in b13-appdata-C; raid3 snapshot copied in. Not tested yet: `battles3.py prep BASE/WAR/ALLIES`, then `pause5.py run`, item 4 `twopc.py --here --campaign ID:N --copies 29`, item 6 `twopc.py --resume --gui-preview` at 3 sizes (see 04/05/06.md).

**Batch D (03, 10):** all built, not merged. Item 3: RV1-RV3. Item 10: R1-R5 (10a, 10c, 10b with the owner's answers; SP1 probe done). Owner: D tests as one cycle including 10b.

Then stages 2-4 of section 2: AB and CD together, ABCD plus the owner's manual session (items 3, 4, 6, 10), then sos-ship-batch.

## 7. State on 2026-11-19: stage E (10b second version, items 13-17, R3)

The owner held the batch after a manual session on save 94 and asked for 10b's second version (stationed soldiers always in the host's city) and items 13-17 (screenshots in `C:\ClaudeFolder\Batch 13 New Updates`); all approved with their hooks. Plans: `docs/archive/batch-13/10.md` ("10b second version") and `docs/archive/batch-13/13-17.md` on mod branch b13-E.

Worktrees `SOS-wt/b13-E` and `SOSDET-wt/b13-E`, both from b13-ABCD. Build the mod with `SOSDET_DIR=C:\ClaudeFolder\SOSDET-wt13-E`. Scratch data folder `C:\ClaudeFolder13-appdata-E` (both mods installed).

- Engine: 7fcd934 R3 (a viewing ally's Join, 7903, passes the watch-only filter); 5e47591 10b second version (StationBook v2 with the grant and refresh period, the refresh every 120 game s, STATION_LEAVE, command 7983, Notice 17 STATION_GRANT, patch `99-stationed`, item 13's Restart prompt and the muster lock in `95-player-orders`); babbc02 item 16 (report field CAPITAL_WALLS, old reports read 0, patch `99-capital-walls`); fb8e8c1 docs. Tests: 453, 0 failures.
- Mod: ec8b2ad items 14, 15; e6c1260 10b's mod side (Give command / Take command back, the Watch/Decline window, hook M15 for item 17, autopilot `stationgrant`, `stationmarch`); 93cf8af docs (spec 7, 8, 9 and section 15, ADR 0011, hooks) and battles3 S1. `test build`: 345 tests, 0 failures (one earlier run had a LoopbackSetupTest timeout that passed 4 times after; a timing flake).
- Smoke: `APPDATA=C:\ClaudeFolder13-appdata-E python tools/battles3.py run S1` (position ALLIES copied from b13-ABCD). Result: **S1 PASS** (16:04). J's army stationed through `stationmarch` (M15's path); its 80 soldiers in H's city with no invasion, mustered, not selectable on H; the grant on and off on every game; a refresh kept 80; a raid: J's chat line, no automatic view, H commands them after the unanswered 30 s; 1 dead came off J's army (79); the send away took the soldiers out; same hashes, no desync, no crash. Log `C:/ClaudeFolder/SOS-wt/b13-E-S1.log`.
- Owner's manual session (save 94, 17:10-17:40): a sally with Fray2's stationed army found the army twice (as the host's garrison and as Fray2's joining army), swapped control, Fray2's Start starting the battle, troops nobody commanded after Fray2 left the view, Restart keeping the speed, "Watch B..." cut short, no Restart prompt on the panel, no chat or multiplayer buttons in the battle view.
- Fixed (engine 9858d17, mod 37e1e73): stationed divisions aren't garrison (patch 98); the stationing registry is off inside local battles; Ready at deployment, counted by the battle PC alone before its tape (patch 99-map-battles; a first build counted on every PC and desynced the ally's view, found with a temporary log); a restart goes back to 1x; an ally out of the battle's view leaves their divisions to the fighter; the panel's Restart asks first; Watch battle fits; chat and multiplayer buttons in the battle view. Engine 454 tests, mod 345, 0 failures.
- Smoke: battles3 **S2 PASS** (18:20): the sally's divisions match its layout once each, each player selects only their own; the ally's Ready (the last) starts the battle on both PCs with no view desync; restart vote back at 1x; the ally's Ready alone doesn't start the restarted battle, button Waiting; after the ally leaves the host selects every division; same hashes, no crash. Log `C:/ClaudeFolder/SOS-wt/b13-E-S2d.log`.
- Gap: a local battle with allies has no 2-minute deploy timeout (battles between players do); an ally who stays in the view and never presses Ready holds the start.
- Next: the owner's manual session again, then sos-ship-batch.

## 8. Plan: stationing, third version (owner, 2026-11-19 evening) - APPROVED 2026-11-19 with the hooks; open points 1-4 as proposed

The owner's request after the save 94 session (screenshots in `C:\ClaudeFolder\Batch 13 New Updates`: `DivisionTacTab.png`, `ConscriptWindow.png`):

- A stationed army can't be attacked directly. It may still help, as in vanilla when you sally out.
- Stationed soldiers appear at one of the city's entry points.
- The only way to move a stationed army on the world map: its soldiers leave the city through an entry point, as merchants do. The exit is easy: a "Leave for the camp" button in the division popup, above the "?". It is greyed out until a soldier of that division is within 3 tiles of an entry point.
- In the army window, the stationed divisions are darkened with a left arrow, as vanilla's conscripts window shows divisions out on the world map. Trying to move the army shows a popup: the troops are stationed, move them out to the camp first.
- The army can't move while any of its divisions is stationed (in the city).
- Recall from the conscripts window never calls back a stationed division. Divisions in the camp can be recalled, although the army as a whole is stationed.

### State: which divisions are in the city

- The station book (world, saved, hashed) keeps, per stationed army, the divisions in the host's city. A division is named by its world identity: its type and its index (`ADDiv.index`, saved with the world). Its place in the army isn't used, since a recall shifts the places.
- At stationing, every division with men is in the city. A division that joins the army later starts in the camp.
- Book version 4. Versions 1 to 3 load with every division in the city, as they are today.
- The city's registry (`StationedDivs`) keys its divisions by the same identity instead of the place.

### Flow

1. **Arrival.** The world sends the in-city divisions only (`STATION_DIVS`, as now, plus the instant update from 5e93634). Soldiers appear mustered on the chosen entry point's tile instead of beside the throne. Reinforcements from a refresh appear there too. With no entry point, they appear beside the throne as now.
2. **Leave for the camp.**
   - The button sits in `UISelection` above the "?". It is active when one division is selected, this PC's player commands it as a stationed division (its player, or the host while command is granted), and one of its soldiers stands within 3 tiles of any active entry point.
   - The click is an order through the log, like the stationed orders now. Every PC that runs the city applies it at its step and checks the 3 tiles again. The division's soldiers leave the city, and the dead are reported first, as `leaveDiv` does now.
   - The city tells the world through a new input `STATION_CAMP` (army, iteration, division identity). The world part takes that division out of the in-city list.
3. **Moving.** The army moves only when no division is in the city. Then a move ends the stationing (`MOVED`, as now). While divisions are in the city:
   - the move tool refuses the click;
   - hovering says "Stationed in X's city: bring its divisions out to the camp first (Leave for the camp)";
   - a click opens that popup.
4. **The army window** (the stationing player's PC): each division in the host's city is darkened with the left arrow, as `UIDivCardSett` draws it. The hover says it is in X's city.
5. **Recall.** These refuse a division in the host's city, with the reason, both in the UI and when the command applies (the same on every PC):
   - the conscripts window's Recall;
   - the army window's per-division disband (which for a city division is a recall);
   - Reassign;
   - Disband army (while any division is in the city).

   Camp divisions are recalled as vanilla does.
6. **Other ends** (sent away by the host, agreement lost, war, a player away, capital lost): the soldiers leave the city at once and the army is free, as now.
7. **Can't be attacked directly** (helpers untouched, `Util.fill` stays vanilla):
   - two armies meet (`PFieldBattle`): a stationed army is never the target (`enemy()`) and never starts a fight itself (`create()`);
   - a region's garrison attacks an army (`PRegAttack.create`, and `PlayToolAttack.isPlacable` with the reason);
   - NPC AI picks threats (`Defender`): a stationed army isn't one;
   - the player's move tool on a stationed army: refused, "Stationed at X's capital: it can't be attacked".

   It still joins sallies (already built), sieges of the capital and field battles nearby as a helper.

### Hooks (each needs the owner's approval)

| Repo | Patch (already owns the file) | Game file | Hook |
|---|---|---|---|
| engine | `15-humans` | `world/battle/PFieldBattle.java` | `create`, `enemy`: skip a stationed army |
| engine | `15-humans` | `world/battle/PRegAttack.java` | `create`: refuse a stationed target |
| engine | new file in `99-world-battles` | `world/army/ai/Defender.java` | threat list: skip a stationed army |
| engine | `96-cmd-world` | `view/world/ui/region/PlayToolAttack.java` | `isPlacable`: the reason |
| engine | `96-cmd-world` | `view/sett/ui/army/Actions.java` | Recall: refuse divisions in the city |
| engine | `96-cmd-world` | `view/world/ui/army/Army.java` | darkened cards with arrow; disband, reassign and Disband army refused |
| engine | `95-player-orders` | `view/battle/UISelection.java` | the Leave for the camp button |
| mod | `M15-station-on-map` | `view/world/ui/army/ToolMove.java` | move refused with popup; attack on a stationed army refused |

The refusals at command apply live in the engine's world commands (recall, disband, reassign), not only in the UI.

### Open points for the owner

1. **Which entry point:** I propose the reachable one nearest the army's tile on the world map (`EntryPoints.reachable(wx, wy)`).
2. **Exit within 3 tiles of any entry point,** or only the one they came in by? I propose any.
3. **Back into the city:** how do camp divisions (and new ones) go back in? I propose pressing Station again on the stationed army (Armies tab or the move tool), which sends every camp division in through the entry point.
4. **The host with command granted** may press Leave for the camp too? I propose yes.

### Tests

- Unit:
  - book v4 round trip and loading v1 to v3;
  - the move, recall and leave rules;
  - the registry by identity, including a recall that shifts the places.
- battles3 S1 extended:
  - soldiers arrive on the entry tile;
  - Leave is refused far from it and works within 3 tiles (an autopilot command moves the division there);
  - the move is refused while one division is in the city, then allowed and the stationing ends;
  - Recall is refused for a city division and works for a camp one;
  - an NPC army next to the stationed army starts no battle with it;
  - same hashes, no desync.
- S2 again (the sally with a stationed army).

### Spec and docs

- Spec §9 (stationing) rewritten for the camp, and §15 [AT] lines.
- ADR 0011, third version.
- `docs/hooks.md` rows for each hook.
- The engine's `api.md`.

### State on 2026-11-19 evening: built and smoke-tested

- Engine 770cf30, mod e12a5f0 (worktrees `SOSDET-wt/b13-E`, `SOS-wt/b13-E`). Engine 459 tests, mod 345, 0 failures.
- battles3 **S3 PASS**:
  - J's soldiers come in at an entry point;
  - move, Call back and Recall are refused while they are in the city;
  - Leave for the camp is refused 15 tiles off and works at the entry point;
  - the army may move; Station again brings them back in at the entry point;
  - an NPC army at war with J, set beside the stationed army for 40 s, starts no battle;
  - J walks off and the stationing ends; same hashes, no crash.
- **S2 PASS** and **S1 PASS** again on this build.
- Also decided while building: the army's own player can't Call back while divisions are in the city (the host's Send away still empties it at once).
- Not tested by the smoke:
  - the button's look in the division popup;
  - the darkened cards in the army window;
  - the popup itself (the test reads the refusals through the autopilot);
  - J pressing Leave for the camp from a view (the smoke used the host with command granted; both go through command 7984).
- Next: the owner's manual session on save 94, then sos-ship-batch.

## 9. Owner's save 94 session, 2026-11-19 night: two bugs and division banners (PROPOSED)

### 9.1 Desync: the viewer edits the viewed player's name and banner (bug)

- **Evidence:** both desyncs (steps 96630 and 101340) came while Fray2 viewed Xitter's city. Each time the world hash part "factions and stances" differed; that part holds each human's name, ruler name and banner (SOS-78). At step 101400 Fray2's PC had a different banner shape for slot 0 than every other PC.
- **Cause:** in a view the game's player is the viewed city's owner (`Session.humanNow`, SOS-82), so the viewer's player window (`UIProfile`) is the owner's.
  - A banner shape, colour or pixel change goes through the draft hooks against the owner's banner. The hash counts a draft only for this PC's own human, so the owner's changed banner is hashed on the viewer's PC alone, and that is a desync.
  - A typed name is sent as this PC's human's command: it renames the viewer, not the owner. No desync, but wrong.
- **Fix (engine):** while this PC views another player's city, the player window can't change anything.
  - The name and ruler inputs are refused (`Names.factionName`, `rulerName` and `profileLoad` swallow the edit).
  - The banner editor can't open: the profile's banner button is refused, a hook line in the file that opens it, owned by `96-cmd-names`.
  - The colour, shape and pixel hooks refuse too, as a backstop.

### 9.2 An allowed view that never opens (bug in the telling)

- **Evidence:** at 21:15:58 an autosave came due and waited for the views to end, while Fray2 viewed Xitter's city. Spec section 5 says no new view opens meanwhile, so Adam's allowed view at 21:17:31 waited, and nobody was told. The button stayed "Asked...". The desync closed Fray2's view, the autosave ran, and Adam's view began at once (21:21:11).
- **Options:**
  - (a) Keep the rule and say so: the asker's button reads "Waits for the save" and both players get a line saying the view opens after the autosave, which waits for X's view to end.
  - (b) An autosave waiting for views doesn't hold new views; a Save Campaign still does. A spec section 5 change.
  - (c) An autosave due while someone views a city is skipped, its clock starting again, and never waits. A spec section 5 change.

### 9.3 Division banners (feature, new hooks)

- **What:** a division that belongs to another player than this PC's shows that player's banner in its top right corner. It shows:
  - on the city map zoomed out (`UIDivCardBattle.miniDiv`, which `BattleRenderer` draws);
  - on the battle panel's unit cards (`UIDivCardBattle.render`);
  - in the conscripts window (`UIDivCardSett.render`).
- **Which divisions:**
  - In your city: allies' stationed divisions and allies' divisions in a battle.
  - Viewing another player's city: that player's own divisions and any third player's, never yours.
  - NPC divisions get none.
- **How:** one engine query, `Hooks.divBanner(Div)`. It returns the owning player's banner sprite, or null for this PC's own divisions and for NPCs. It reads the engine's stationed registry, the battle's allies and the viewed city's owner: per PC, render only, never in the step or the hash.
- **New hooks (engine, render only):** `view/ui/div/UIDivCardBattle.java` (render and miniDiv) and `view/ui/div/UIDivCardSett.java` (render). No patch owns these files yet; they would go into `95-player-orders`.
- The conscripts card's guard-duty shield sits in the same corner. A foreign division never has guard duty, so they don't clash.

**State 2026-11-19 late night:** all three approved as recommended and built.
- 9.1: engine 2465dc6 (`Names.identityLocked`, patch 96 UIProfile).
- 9.2: mod 5a1835d (`MpSaves.holdBegins`: only a Save Campaign holds new views; spec section 5).
- 9.3: engine 2465dc6 (`Hooks.divBanner`, patch 95 UIDivCardBattle and UIDivCardSett).
- Engine 459 tests, mod 345, no failures. No automated runs, as the owner asked; save 94 started for the owner's check.

## 10. Owner's additions, 2026-11-19 night (PROPOSED)

### 10.1 Player cursors (mod, `MpCursors`)
- Instead of the "+": an arrow like the Windows cursor, drawn pixel by pixel (a black outline, the inside filled).
- The fill is the player's banner foreground (`banner().colorFG()`); on the setup screen, before factions exist, it is the lobby colour as now.
- The name is drawn in font M instead of S, made bold by drawing it twice one pixel apart, over a dark shadow so it reads on any ground.
- The minimap crosses stay crosses, in the same banner colour.

### 10.2 Chat and Multiplayer buttons in battle (mod, `MpPanelButton`)
- Today they are pinned right of the top centre, over the battle panel's throne timer.
- Instead: the same size as the "?" (Tome of Knowledge) and Menu buttons at the top right, in a row directly left of the "?".

### 10.3 No throne in a battle between players (engine)
- Vanilla's field battle puts a throne on the battle map: the timer in the battle panel (crown icon), and enemies by the throne for 5 minutes lose the battle (`BattleState`).
- In a field battle between players it should not exist; a player's throne matters only in an assault on their capital (section 9).
- Proposal: in a field battle where both sides have a human, the throne rule never counts down and the panel hides the timer; NPC battles stay vanilla.
- The rule runs in the step, so the check is world state (the battle's roster), the same on every PC.
- New hooks: `game/battle/state/BattleState.java` (the countdown) and `view/battle/BattlePanel.java` (the timer stat; that file is in `95-player-orders` already).
- Open: whether the throne building itself also comes off the battle map. Removing it touches the battle map generator and everything that centres on `THRONE.coo()`; proposed to keep it as scenery.

### 10.4 A deal from a player, as vanilla's deal window (mod)
- The live notice (Inspect / Later) goes. In its place, a message in vanilla's message folder (`VIEW.messages()`), opened at once as vanilla opens an NPC's deal (`UIDipMessDeal`).
- Title "Deal from <faction>".
- At the top the player's faction banner, big (HUGE), in place of the king's portrait and the two side banners.
- Below it, the faction name, then "Player <player name>" in place of the king's name; no message text.
- "We give you" / "You give us" from the proposal, as vanilla's list.
- Accept and Decline, which answer the proposal through the existing commands, active while the proposal is open. No Inspect, no Later, no "inform us within a day".
- The message stays in the folder after it is answered or expires, as vanilla's: it then says accepted, declined or no longer valid.
- Persistence: vanilla saves its messages in the city save with the game's own Java serialization, and loads them with `object(true)`: a message whose class is missing or changed is dropped and the save still loads. Our message class keeps only plain values (the proposal id, the proposer's slot, the names, the two lists as text) and reads the live proposal from `PlayerDeals` by id. AGENTS.md forbids Java serialization for our saves, so this needs the owner's OK as an exception: the game, not the mod, writes it, and a failure only drops the message.

**§10 APPROVED 2026-11-19:**
- 10.3: the rule and the timer go, and the throne building comes off the battle map too, in battles between players.
- 10.4: the deal message is kept in vanilla's message folder (the Java serialization exception approved).
- The rest as planned. No automated runs; save 94 for the owner.

**§10 state, 2026-11-19 night:** built.
- Engine 7ceaf5c (no throne: 99-allies, 99-map-battles, 95 BattlePanel; `GameThrone`).
- Mod 60e6dc1 (`MpCursors`, `MpPanelButton` battle row, `MpDealMessage` replacing `MpDealNotice`).
- Engine 459 tests, mod 345, no failures. No automated runs; save 94 started for the owner.
