# Batch 6: playtest 2026-11-04 fixes and features

Cards SOS-137 to SOS-144, from the playtest of 2026-11-04 (host FighterX2500, Adam Smith, Limitless Gregg). Batch style, as batch 5: one engine branch, one mod branch, a commit per card, one shared test cycle, one playtest pack.

## Order

1. **SOS-137, the desync loop** (engine). It breaks a session after any view with a market sale between players, so it goes first, with a pack of its own if the owner wants to play before the rest is done.
2. **SOS-139 and SOS-138, the depot rows** (mod). Same rows and the same hover, so one after the other.
3. **SOS-144, the battle notice** (mod).
4. **SOS-141, no trespassing for colleagues** (engine patch).
5. **SOS-143, the raider** (engine, a repro from save 49, then the fix; the clickable notice with SOS-144).
6. **SOS-142, the Technology window's points** (a repro from Gregg's save first).
7. **SOS-140, stances to vassals** (engine, then the mod's display). The largest, so it comes last.

## SOS-137: the trade tally is missing from the world save

**Found.** All three PCs' desync reports agree on every world part up to part 7, "deals and tariffs between humans". The two joiners agree with each other there; the host, who viewed Gregg's city, differs. Part 7 has included the SOS-135 trade tally (`GameTraded`, section `SOSDET_TRADED`) since batch 5. Of the engine's sections, it's the only one missing from `GameWorldLoad.KEYS`, the list of sections the world save carries. So:

- **At the thaw** the viewer loads its own game from the freeze, then the world it ran during the view, without the tally. The host's tally went back to its value at step 74,160 and lost the Tool sales to Adam at step 75,366.
- **A world resync** doesn't carry the tally either. The joiners kept their own, so each resync desynced again within 10 to 30 steps.

Later views in the same playtest (20:28, 20:55, 21:27) had no market sale between players while they ran, and didn't desync.

**Fix.**

- Add `GameTraded.KEY` to `KEYS`, after `GameSieges.KEY`, the order the sections are registered in.
- Add a unit test over the sections `GameHumans` registers: each must be in `KEYS` or on a named list of city-only sections. A new section then can't be left out silently.

**Checks.**

- Unit test: the world save round-trips the tally.
- Rig smoke: two humans, one views the other's city, a market sale between them during the view, then the thaw. No desync.
- Rig smoke: force a different tally on one PC, then resync. It heals.

**Save format.** None of the save formats change. The world save gains a section, and both PCs always run the same build. Campaign save 35 (step 78,550) holds the host's wrong tally, so don't resume from it; the autosaves before 18:26 are fine.

## SOS-139: depots list only players who trade the item

- **Export depot:** lists another player for an item only while their city imports it. That means their report's import cap for it (`IMPORT_CAP` or `BUY_LIMIT`, whichever their import depot sets) is above 0.
- **Import depot:** lists another player only while their city exports the item (`EXPORT_AMOUNT` or `SELL_LIMIT`).
- **Each row** shows the other player's request: how much they import, or how much they offer.
- **A player whose import price cap rules out your price** still shows, marked "won't buy at your price", instead of today's 0.
- **Where it reads:** a new `Engine` read of one report field per slot and tradable, if `reportedPrice` can't be widened.

## SOS-138: the price's parts

The market's rule (patched `TradeSorter.buy`):

- the buyer pays the seller's price plus vanilla's fee (the toll);
- the buyer's tariff is cut from what reaches the seller and paid to the buyer's treasury.

**What the hover shows.**

- **Export depot:** your export price, the toll, their tariff on you (percent and denari), and what reaches you per item.
- **Import depot:** their price, the toll, your tariff (back to your treasury), and what you pay in the end.

**Check during the work:** who gets the toll. The patched code passes `sellPrice - cut` to the seller, and `sellPrice` includes the fee. If the seller keeps the toll, that's a market bug against spec section 7, and I'll bring it to you before changing it.

## SOS-144: notice of a battle against NPCs

- **When it starts:** every other player gets a chat notice: "<player> is fighting <enemy> on the world map. Open their window and click Watch battle to watch." Clicking the notice opens that player's window.
- **When it ends:** a notice with the outcome.
- **Spec:** section 12 lists event notifications, and this adds one. Section 8 already says watching is optional.

## SOS-141: no trespassing for colleagues

- **The change:** vanilla spares only allies (`WArmy.update` and `OpsStance.tresPass` check `DIP.ally`). Both checks change to `DIP.transit`, which is true for colleagues, allies and vassals.
- **Where it applies:** in the engine's patches, so every PC and single player alike.
- **Docs:** a row in the engine's hooks docs and a line in spec section 6, in the same PR. You asked for this change to vanilla, so that is the approval.

## SOS-140: stances pass to a player's vassals

Example: player A has vassal V, and A and player B are trade partners or colleagues. Then B and V get at least that stance with each other. An alliance never passes.

**Engine.** In the world step, on every PC alike, once a day:

- each human pair's stance is passed to the other human's vassals;
- a stance that came only from passing is kept in a new book, saved, hashed and in the world save;
- the world save carries it, so SOS-137 can't repeat.

**Mod.** The faction window marks a passed stance as "through <overlord>".

**Rules (owner, 2026-11-04):**

1. When the source ends (the agreement ends, or V stops being A's vassal), a stance that was only passed on goes back to neutral. One that B and V had on their own before stays.
2. It passes both ways between the humans (B gets A's vassals, A gets B's), never between two vassals.
3. A war between B and V blocks it until they make peace (recommended, plan approved 2026-11-04).

## SOS-143: the raider that left at once

**What the log shows.** At step 686,616 (21:11:33) the host's log has "Ukorina's capital is invaded by Rebels". That was the raider, named "Rebels" because the engine's notice gives faction -1 for both rebels and raiders.

**Likely cause** (to confirm by a repro):

- Vanilla's raid (`RaidingCurrent`, state INVADING) leaves with its "going away" message as soon as `SETT.INVADOR().invading()` is false.
- In the world part, the engine answers that from the city's last report (`Field.INVADING`).
- `Hooks.invade` only queues the invasion for the city tail. It updates the report's invasion ref at once, but not its INVADING flag.
- So until the city's next report, the world reads "not invading", and the raider leaves at once.

**Fix:** `Hooks.invade` sets INVADING in the report's ledger at once, as it does for the ref.

**Repro:**

- Load campaign save 49 (step 685,479, 21:11:19, 1,100 steps before the invasion). The host's copy is in its saves; Adam's and Gregg's copies are in the reports folder.
- Run past step 686,616 and check that the raider stays and attacks.

**Also in this card:**

- The notice names a raider as the raider, not "Rebels". Raids come only through `RaiderArmy.invade`, rebels through the world battle (`ResolverPlayer`), so the engine can tell them apart there.
- Other players already get the notice, with "You can ask to view the city from their window". The notice becomes clickable: it opens the player's window on View city. It uses the same clickable notice as SOS-144.
- The playtest log notes the raid's end and why it ended.

## SOS-142: the Technology window's points

**The fault** (owner, 2026-11-04): the numbers at the top left, next to Innovation (and maybe Administration and Knowledge), showed far more than Gregg had. He didn't have enough to buy a tech.

**Where the numbers come from.**

- Each number is vanilla's `TechCurr.available()`, which is `total() - frozen() - allocated()`.
- `total()` is the currency's boostable read for the player's class, `cu.bo.get(HCLASS_RACE.clP())`.
- Buying a tech checks the same pool through another path.

So on a joiner's PC, the header and the purchase check read different values. The likely cause is a boost that counts another human's sources, or a report value the window reads outside the step.

**Repro:**

- Load Gregg's copy of the latest autosave from the reports folder, as Gregg (slot 2), in the GUI preview.
- Compare the window's number and its hover breakdown with what a purchase allows.

**Then fix** whichever side is wrong.

## Tests and pack

- **Engine:** unit tests, then one short combined rig smoke covering SOS-137, SOS-141 and SOS-140.
- **Mod:** unit tests, a GUI preview covering SOS-138, SOS-139 and SOS-144, then a short twopc run with a battle against NPCs.
- **Then:** pushes and PRs on your word, and the playtest pack.

## Spec sections

- 6, Diplomacy: SOS-140 and SOS-141.
- 7, Trade: SOS-138 and SOS-139.
- 8 and 12, Military and Chat: SOS-144.
- 13, Sync and desync: SOS-137.

## As built (2026-11-04)

- **SOS-137:** `SOSDET_TRADED` is in `GameWorldLoad.KEYS`. `GameHumans` now adds its world sections through one method, which refuses to start a game when a section is missing from `KEYS`. The plan's unit test became that start-up check, because `GameHumans` can't be built outside the game.
- **SOS-139, SOS-138:**
  - New reads: `Engine.reportedDepot` and `Engine.reportedTollDivisor`; the arithmetic is in the mod's `DepotPrice`, with unit tests.
  - Hook M12 gains `playerHover`.
  - A player whose import price cap rules out your price stays hidden, as before, and isn't marked "won't buy". That keeps the list to players who would buy.
  - **The toll is lost** (owner, 2026-11-04: it pays the caravan). Between humans the seller now gets its export price less the buyer's tariff, through `Hooks.sellerGets` in `TradeSorter.buy` (patch 15-humans); before, the seller got the toll too. Sales to and from NPCs are as vanilla.
- **SOS-144:**
  - Chat lines can carry a player's slot. The running game's chat shows them in yellow, and a click opens that player's window.
  - The battle notice doesn't name the enemy: the engine's `LocalBattle` exposes no enemy faction.
- **SOS-141:** `Hooks.noTrespass` asks the stance's `transit`, in patches 96-cmd-world and 14-report-reads.
- **SOS-143:**
  - `Hooks.invade` sets the report's `INVADING` through the ledger, and gets the invading army. `Engine.RAIDERS` marks a raider in the invasion notice.
  - The mod names raiders and logs when an invasion ends.
  - Not repeated in the rig: the rig has no action that sends a raider at a capital.
- **SOS-140:**
  - `StanceBook`, `GamePassed` (`SOSDET_PASSED`) and `HumanDeals.passStances`, which runs each world part after `expire`. `Notice.STANCE_PASSED` feeds the chat.
  - Added rule: a passed stance that anyone else changes stays theirs until its source ends. Without it, a vassal's court that cancels the agreement (vanilla's low-opinion rule) would be overridden every day, and the cancel message would repeat.
  - Not shown in the faction window yet; the chat tells the player instead.
- **SOS-142:** not changed.
  - The header's number and vanilla's purchase check read the same pool, `TechCurr.available()`, so they can't disagree on one PC.
  - Likely explanation: Gregg was viewing Adam's city (20:28 to 20:31). A view loads the owner's city with its tech, so the window shows Adam's points, and nothing can be bought while viewing.
  - To confirm with the owner.
