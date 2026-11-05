# Batch 8: playtest 2026-11-05 (evening) fixes

Date: 2026-11-05. Status: **approved by the owner 2026-11-05** (decisions at the end); cards SOS-149 to SOS-153. B8F is for later, no card. Branch `batch-8-playtest-fixes` from `main` 5da42ed (mod) and, once approved, the same branch name from SOSDET `main` acaf338.

The session: 3 players over Steam, resumed from campaign save 77 (host FighterX2500, Adam Smith, Limitless Gregg), batch 7's pack. Raiders invaded the host's capital at step 10349; the host fought them with vanilla's **Attack** (the sally, SOS-145) from step 10528 to 30720. Adam watched from 11490 to 19650, Gregg was refused while Adam watched and then watched from 20640 to the end. Logs: host `sosgsgmp-logs\20261105-230854-host-FighterX2500.log`, joiners under `sosgsgmp-reports\84ed6e7b50de7fff\` (Gregg's clock is 2 h off).

Nothing below was run in a game or the rig yet: the causes come from the logs and the source.

## Order

1. SOS-149, the sally's result (critical: a won sally changes nothing, the raiders assault later).
2. SOS-150, the general in the step (freezes on every PC during any local battle).
3. SOS-152, cursors in a battle; SOS-153, the depot hover. Small.
4. SOS-151, several watchers, with its spec change (owner: in this batch).
5. B8F, the sally's generation out of the step: later (owner).

## SOS-149: the sally's result is never applied

### Found

- The owner: the raider was beaten, but no message said so, there was no loot or prisoners screen, and later the same raiders assaulted the city.
- No log line shows the result reaching the city.

### Cause (engine)

- At the battle's end the result command works; the watcher's view ends ("The battle is over", 30720) and the host's city loads back (30719). `localBackWorld` applies the city transactions held during the battle and clears them.
- On the next step `localAfter` runs the sally's after-battle, which queues `SALLY_RESULT` to the defender's city.
- `Session.ownTxns` holds any transaction for this PC's human while `frozen(localHuman)`. The local battle stays RUNNING until its thaw, and the thaw waits for `b.after` (`localsAt`), so it always comes later: `SALLY_RESULT` goes into `heldTxns`, and nothing drains them again.
- So vanilla's `Attack.afterExit` never runs: no `invasionTell(VICTORY)` (no "defeated" message, no stats), no `MVictory2` (spoils and captives), and no `canBeAttacked = false`, which is what ends the invasion after a won sally. The invaders kept their timer and assaulted. The armies near the capital also came back at full strength.
- It happens on every sally end (victory, defeat, retreat). Rig B7A2's retreat had it too: the run checked only that the city came back. The rig never saw a battle end otherwise.
- The same hole can hit any city transaction a world local battle's `localAfter` sends to its own human (SOS-93): to check during the work.

### What a won sally must do (vanilla, owner 2026-11-05)

- The Victory screen (`MVictory2`): spoils and captives.
- The invasion ends (`canBeAttacked = false`), and the invasion listeners hear the victory. Through the city's report the defender's raid (`RaidingCurrent`, one per human since SOS-46) goes DEFEATED: `RAIDING.defeat` marks the raider defeated, which takes it out of the raider list, and 20 s later vanilla's **Raider Defeated** message shows, adding the raider's treasury (`bounty`) to the defender's treasury.
- To check, as nothing has run this path in a session yet: the message shows on the defender's PC only, the bounty goes to the defender (the world part runs it as that human) exactly once, on every PC alike.

### Fix

- In `ownTxns`, a human whose local battle is back (`b.back` and its city load done) applies at once. A helper `ownCityBack(slot)`. `frozen()` stays as it is, as every PC's world reads it.
- Safety net: at the thaw of this PC's local battle, apply what is left in `heldTxns` in the city part.
- To check: `MVictory2`'s Accept runs from the UI, outside the step. Whether its delivery reaches the world's ledger (`Hooks.buyerReserve`) or needs a city command.

### Checks

- Unit (LocalBattleTest): a transaction after `back` applies once and leaves `heldTxns` empty; before `back` and while loading it is held; the real order (`localAfter` queues, the city tail applies) applies it exactly once; with a watcher still on, it applies once the watcher is out.
- Rig smoke, 2 humans, `--no-invade`, a tiny raider (`wraidcapital`): a new rig action `wbattlewin` ends the battle on the battle PC as vanilla's `Invasion.fastForward` does and presses Close. Probes: the invasion is gone within a few steps, the Victory message is there, the raider is DEFEATED, the sally's army lost its men, `heldTxns` is empty, no desync. Then Retreat: the Defeat message, the invasion goes on. Then the same with a watcher.
- Saves 79 and 80 need no repair: the city fight already beat the raiders; the spoils and the losses are simply gone.

## SOS-150: the general plans inside the step

### Found

- "Constant freezes after several seconds", on every PC. The PC running the battle has a step over 100 ms every 4 game seconds once the armies meet (host: 55 stalls in 13,200 steps; the watcher while watching: up to 1.5 s). Every other PC waits for it (Adam's waits grew from 9.7 s to 27.3 s after his view ended), as the host is also the AI log's source.

### Cause (engine)

- Vanilla's general (`Strategos2000`) plans its whole pass every 4 game seconds. In a local battle the engine runs it inside the step (plan 0016 O15) and the step waits for the whole pass.
- Field battles had exactly this and moved the general to its own thread in host mode, its changes logged and applied later (engine 0ea7a5c). Local battles left it in the step on purpose.

### Fix (recommended: A)

- **A. The fighter's general runs on its own thread, its changes go on the battle's tape** and apply at a later step on the fighter and every watcher from the same bytes, as host mode does for field battles. Watchers never run a general. The thread pause from SOS-145 (fd71cd1) becomes the handoff.
- B, simpler and weaker: a patched general runs a fixed number of its states per step. One state alone can still take over 100 ms, and the timing differs from vanilla.

### Checks

- Unit: the captures go on the tape and apply once, in order.
- Rig: a local battle with the AI on, two humans, one watching; the step profile (SOS-106) shows no general pass in the step; no desync between the fighter and the watcher.
- Playtest: no freeze every few seconds.

## SOS-151: several players watch one battle (spec change)

### Found

- Gregg was refused: "Another player is watching that battle." The owner wants several watchers at once.

### Rule today, and the change

- Spec §10: "**One viewer at a time.** A city has one viewer at a time … because the owner's reload for a new view would lose the open one." §8 says any other player can join an NPC battle while it runs.
- Proposed: any number of viewers. Each join reloads the owner and every viewer already watching for a few seconds. §8 "Joining reloads the battle on both PCs" becomes "on the fighter's PC and every watcher's PC". New [AT]: two players join a running NPC battle one after the other, and both copies stay equal with the fighter's.

### Design

- The limit is only the reload: the owner reloads its own save for each new viewer, and a viewer already watching would keep a copy that wasn't reloaded and drift. The engine keeps most view state per viewer already (`views[]`, hashes, the tape's streams).
- Engine: a `rejoinAt` step on every open view of that owner when a new one freezes; the viewer holds there until it has the new save; a `viewRejoin` load that keeps the viewer's own city; the refusals in `viewRefusal` go.
- Mod: the owner sends its save to every viewer, not only the new one; the viewer keeps incoming cities by step; a "X joined, reloading" note; the camera kept. Protocol version up.
- Cost: every viewer pauses on each join; the owner uploads 2 to 3 MB per viewer.

### Checks

- Unit: `rejoinAt` set alike on every PC; a lagging viewer gets two joins in order.
- Rig, 3 humans: one watches, the second joins, both hash equal with the fighter's to the end.

## SOS-152: cursors in a battle

- The cursors exist (SOS-58, SOS-124), but the mod sends none while a battle view is open (`MpSessionPanel.cursorPlace`). Spec §12 already asks for the cursor of every player looking at the same city, so this is a gap, not a change. Spec text to add: the battle on the city's map counts.
- Fix: a battle place per fighter's slot for a local battle and one shared place for battles between players and assaults; the battle view's window for drawing. Protocol version up. The battle minimap optionally.
- Check: GUI preview with two PCs, both cursors in the battle.

## SOS-153: the depot hover's long rows overlap their values

- `TradeGUIBug.png`: "Your import price to buy, at least" runs into its value, and "Your export price to sell, at most" in the Export depot does the same: the values sit at a fixed tab (`MpPlayers.depotHover`, tab 7) that is shorter than these labels.
- Fix: the hover's values one tab column further right, for all its rows so they stay aligned.
- Check: GUI screenshot of both depots' hovers.

## B8F (optional): the sally's battle generates outside the step

- The sally's first step took 11.7 s on the host: vanilla generates the battle inside the step (`localGenerate`). The other PCs don't wait for a fighter that isn't the host, but the host is the AI log's source, so here every PC waited. Plan 0016 meant to generate between steps and never did.
- Fix: generate between steps, as a view's load does, and mark the PC away meanwhile.

## Also seen

- The first attempt crashed on load (`IDeploy.update`, the battle view's state null): save 78, last session's quit save, taken during the old sally's desync loop under batch 6. Batch 7 holds saves until a local battle ends, so this can't happen again. Save 78 shouldn't be used.

## Owner decisions (2026-11-05)

1. SOS-150: option A, the general on its own thread.
2. SOS-151: in this batch, with the spec change above.
3. B8F: later.
4. Cards: SOS-149 to SOS-153 created.

## As built and checked (2026-11-06)

- **SOS-149** (engine a05109d): `Session.ownTxns` applies a transaction for this PC's human at once when its local battle is back and its city loaded (`localCityBack`); the thaw applies anything still held; the three drains of `heldTxns` share `applyHeldTxns`. New rig probe `wraidend`.
  - Unit: `LocalBattleTest` (the after-battle's transaction applies once; held while the city loads; the thaw drains). Both failed before the fix.
  - Rig, 2 humans, Mogus-72, `--no-invade`, `wraidcapital 60`, `wsally`, `wipeenemy 0`, the battle won and closed:
    - **B8A0, before the fix** (main acaf338 with the probe only): after the battle the invasion still ran (`canBeAttacked true`), the raid stayed INVADING, no Victory or Raider Defeated message.
    - **B8A1, with the fix:** no invasion, the raid DEFEATED, active raiders 18 to 17 and defeated 2 to 3, the treasury up 467,766, the `MVictory2` and `MessDefeated` messages on the defender's copy only. Both runs identical to step 9,000, no engine desync, no crash file.
- **SOS-150** (engine ec8dfd7): `LocalGeneral` keeps the fighter's general's changes from its own thread and puts them on the battle's tape as each step begins; watchers run no general; the battle threads restart as a local battle starts and ends on every PC; changes past the end are dropped. Tape version 3.
  - Unit: `LocalGeneralTest` (on the tape as the AI's, once, coalesced in place, artillery kept), `LocalBattleTest` (routing, dropped after the end).
  - **Rig B8B1:** a 310-man raider, the sally with the player's divisions given orders, copy 2 watching from step 960 to the battle's end at 3,000: 66 battle hashes compared, no desync; the general's time in the step 0.0001 ms a step on both copies (2.1 ms in B8A1); copy 1's only step over 1 s is the battle's generation (B8F).
- **SOS-152** (mod a1aca32): cursor places `Cursors.battle(slot)` and `Cursors.BATTLE`; protocol 23. Unit: `CursorsTest`, `ProtocolTest`. Not seen on screen yet: GUI preview pending.
- **SOS-153** (mod 2e47afe): the hover's values at tab 9. Not seen on screen yet: GUI screenshot pending.
- **SOS-151:** in progress.
