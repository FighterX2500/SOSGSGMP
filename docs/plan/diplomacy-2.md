# Diplomacy between players, part 2 (SOS-73)

Spec §6 Diplomacy and §7 Trade; the open question in §16 on passage and trade. Card: [SOS-73](https://fighterx2500.atlassian.net/browse/SOS-73). Part 1 is [diplomacy.md](diplomacy.md) (SOS-72); war between players is SOS-74. Status: **proposed, waiting for the owner's approval.**

Game paths are relative to `<game>/info/SongsOfSyx-sources/`. The vanilla line numbers come from the audit of 2026-10-27; SOSDET's patches already change some of these files (`build/patched-src/` in SOSDET), so each step starts by reading the patched file.

## 0. The owner's answers so far (2026-10-27)

- **Trade passes through land at peace:** players at peace cross each other's land with trade, as their armies do, with no transit treaty. This closes the §16 question; spec §7 changes to match.
- **No emissaries in player diplomacy:** no emissary mission targets another player, Gather support included. Spec §6 changes to match.

## 1. What exists

- **Deals between humans** (SOSDET plan 0009, `sosdet.game.deal.DealBook`, commands 8100-8103 in `cmd/HumanDeals`):
  - What a deal carries: one treaty (peace, trade, pact, ally, cancel all), money and goods each way.
  - How it applies: an accept rechecks every term, then applies them in the step on every PC.
  - What's missing: no region terms; `DealBook.VERSION` is 1.
- **The mod's Diplomacy tab** (`menu/MpPlayerDiplomacy`, `sosgsgmp/session/PlayerTerms`):
  - Offer and Demand regions are greyed out "Not between players yet".
  - Protector and Vassal are greyed out the same way.
  - The Realm tab already lists the other player's regions with vanilla's `UIPickerRegion`.
- **Regions in vanilla deals** (`deal/DealRegs`, `DealParty.execute`):
  - `RD.setFaction(region, receiver, true)` moves a region.
  - What can be offered: a giver region that isn't a capital and that neighbours the receiver's land, or a region already chosen in the same deal.
  - **Side effects that act only for `FACTIONS.player()`:** health reset (`RDHealth`), edicts reset (`RDEdicts`), building requirements (`RD.setFaction:491`), emissaries (`Emissaries:126`), the rebellion timer (`EventWorldRebellion`), support (`RDOwner:70`). In lockstep, which faction is `FACTIONS.player()` differs per PC, so the step must apply a transfer with the receiver swapped in as the current human (as `HumanDeals.execute` already does for the stance), or these listeners make PCs differ.
  - What a transfer leaves alone: population, and armies inside the region.
- **Vassalage in vanilla** assumes a player and an NPC throughout:
  - **Shared wars:** `DIP.ally` makes every ally-like partner (ally, vassal, overlord) an enemy of each enemy of the other, in both directions.
  - **Tribute:**
    - `UpVassal` makes the local player pay 10% of its worth each year to an NPC overlord, which is never credited.
    - An NPC vassal's tribute is offered to the player through a message.
    - SOSDET patch 15 runs it per human, and skips a vassal that isn't an NPC.
  - **Rebellion:** only NPC vassals rebel (`War`, `WarMessages`: a freedom request, then war).
  - **Becoming a vassal:** `DIP.set(VASSAL)` clears every stance of the vassal except trade, wars included.
- **Passage:** armies already cross any foreign land. Only a region's centre tile (its city) is closed unless allied (`WArmy` path treaty). The trespass penalty applies only in NPC land. No test proves it yet.
- **Trade reach:** a human's trade partners come from `RD.DIST().tradePartners`, which walks `Treaty.FACTION_REACHABLE` (`WRegFinder`, owned by SOSDET patch 15):
  - unclaimed land blocks;
  - another faction's land passes only with `transit` (pact, ally, vassal or overlord);
  - so two humans at peace with a third human's land between them don't trade today;
  - NPC trade already crosses any human's land (SOS-46).
- **Emissaries:** Gather support can target any region, a human's included (`Emissaries:225` only excludes the local player's; SOSDET command 8004 doesn't check either).

## 2. Design

### 2.1 Regions in a deal (engine, then mod)

- **Terms:** `DealBook.Terms` gains `fromRegions` and `toRegions`, as region indices. A region's index is fixed for a world's lifetime, so it is a stable key in a save. `VERSION` goes to 2, and `fromInts` still reads version 1 (no regions).
- **Command:** 8100's payload appends the two region lists. Old payloads aren't in circulation (commands live only within a session), so the command needs no second version.
- **Rule (`refusal`):** each region is the giver's and isn't a capital, and the receiver's realm has room. It also follows vanilla's reach: the region neighbours the receiver's land, or another region given in the same deal. These checks run again at accept.
- **Apply:** in the step, `RD.setFaction(reg, receiver, true)` for each region, with the receiver swapped in as the current human. Before the first smoke, audit every owner-change listener in the patched source for state that differs per PC (rule: audit before smoke).
- **Mod:** Offer and Demand open a region picker: vanilla's `UIPickerRegion` over the giver's eligible regions, with the engine's refusal as the hover text. The deal summary and the incoming notice list the regions by name.

### 2.2 Vassalage (engine, then mod)

- **Terms:** Protector (the proposer becomes overlord) and Vassal (the proposer becomes vassal) join the treaties, applied with `DIP.VASSAL().set(vassal, overlord)` and nothing else. The existing rule stays: a vassal agrees only trade with others.
- **Shared wars:** the vassal joins its overlord's wars, as vanilla does. The overlord does **not** join a war its vassal starts (spec §6). That needs a hook in `DIP.ally` (patch 15 owns `DIP.java`): when the faction that went to war is a human's vassal, propagation to the overlord is skipped.
- **Tribute:** once a year, in the step, the vassal pays its overlord a set share of its worth, and both get a notice (decision 2).
- **Right to rebel:** the vassal ends the vassalage at once, with Cancel all (command 8102, no answer needed), at no penalty, and the two are at peace. War comes with SOS-74. Check that `cancelRefusal` allows it.
- **Mod:** the two terms stop being greyed out, and the Diplomacy tab and the faction list show "Vassal of X" and "Overlord of X".

### 2.3 Trade through land at peace (engine)

- In `WRegFinder`, a new treaty for a human's trade walk: `FACTION_REACHABLE`, plus it passes through land owned by a human who isn't at war with the trader. `RDDistance.tradePartners` uses it for humans. `FACTION_REACHABLE` itself stays as it is, because it also gates vanilla's diplomacy reach and fog of war.
- Who a human trades with doesn't change: a partner still needs a trade treaty (`selTrade`). Only the way there opens up. This covers a human trading with an NPC across another human's land too (decision 3).
- Stance changes already mark every human's trade partners for recomputation (`RDDistance`'s listener), and the caravan route (`Shipment`, patch 15) follows the same reach. Check that in the patched source.

### 2.4 No emissaries against players (engine)

- `Emissaries:225`'s target gate also clears a region owned by any human, not only the local player's. The file is owned by SOSDET patch 98.
- Command 8004 refuses a region target owned by a human, so a modified client can't set one either.

### 2.5 Passage (test only)

A rig check: a human's army walks across another human's land at peace and arrives, nobody's opinion changes, and no siege prompt opens. No code unless it fails.

## 3. Order and checks

The owner's rules apply: short smoke runs, a monitor every minute, no GUI tests, and the owner checks the windows by hand.

1. **SOSDET PR** (branch `SOS-73-regions-vassals`):
   - code: 2.1, 2.2, 2.3, 2.4;
   - `DealBookTest`: region terms, the version-2 round trip and a version-1 save loading;
   - `docs/hooks.md` rows and the patch updates;
   - rig smoke: 3 humans in Mogus-72 (`--make-humans`). It covers:
     - a region deal accepted, with the owner and hash the same on every copy;
     - a vassalage accepted, a tribute year passing, then Cancel all;
     - a trade through a third human's land;
     - the passage walk.
2. **SOSGSGMP PR** (branch `SOS-73-diplomacy-2`, needs the SOSDET PR):
   - the region picker and the vassal terms in the Diplomacy tab;
   - spec §6, §7 and §16 updated;
   - one short twopc run with a region deal, then a save and a resume.

## 4. Decisions for the owner

1. **Which regions can be given:** **(a) recommended:** vanilla's reach, where each region neighbours the receiver's land or another region in the same deal. **(b)** Any region except the capital, even far away.
2. **Tribute between players:** **(a) recommended:** vanilla's own rate, 10% of the vassal's worth once a year, paid automatically to the overlord, with a notice to both. **(b)** No tribute between players. **(c)** A rate set in the deal.
3. **Trade through land at peace, scope:** **(a) recommended:** any human's trade crosses the land of a human it isn't at war with, whatever the destination (an NPC included). **(b)** Only trade from one human to another.
4. **Rebellion:** **(a) recommended:** the vassal leaves at once with Cancel all, ending at peace, until SOS-74 brings war between players. **(b)** Leaving waits for SOS-74 and is only possible by declaring war.
