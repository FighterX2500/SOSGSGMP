# Diplomacy between players (SOS-72)

Spec 0.18 §6 Diplomacy and §15 Diplomacy; §7 Trade's direct contracts and trade discount; the proposal popup from §12. Card: [SOS-72](https://fighterx2500.atlassian.net/browse/SOS-72) (priority, owner 2026-10-26). Status: approved (owner, 2026-10-26), milestone 1 first. Milestone 1 built, in review (SOSDET `SOS-72-human-deals`, SOSGSGMP `SOS-72-diplomacy`).

Game paths are relative to `<game>/info/SongsOfSyx-sources/`.

## 1. What exists

- **Treaties are a table between any two factions.** `DIP.get(a, b)` and `DipStance.set(instigator, accepter)` cover neutral, war, trade, pact, ally, vassal and overlord. This is game API (hook map F6). The table is world state, so it is already in the world hash and the world save, and is the same on every PC.
- **Deals assume an NPC.** `game/faction/diplomacy/deal/Deal` has a `player` side and an `npc` side. The NPC prices goods from its stockpile, judges the deal by its opinion (`opinionChange`, `selfWorth`), and evaluates regions (`DealRegs`). Between two humans there is no NPC to do any of that (hook map F7; single-player assumptions, row "Deals").
- **The deal's terms** (`DealBools`): War, Peace, Cancel all, Trade, Pact, Ally, Vassal/Overlord and Absorb (vanilla's "Unite"), plus money, goods and regions. `DealParty.execute` moves the money (`CTYPE.DIPLOMACY`), sends the goods as a caravan of type `TRADE_TYPE.diplomacy`, and hands over regions with `RD.setFaction`.
- **The engine** (SOSDET) makes a committed NPC deal a command (`cmd.Diplomacy`, ids 8000-8999), applied in the step on every PC. It refuses a siege of another human's regions (`World.otherHumans`), and battles between humans aren't built (SOSDET plan 0006 puts them after M2 and M3).
- **Passage:** an army's path treaty in `WArmy` only blocks a foreign region's centre tile, and the trespass opinion penalty applies only in NPC regions. So crossing another human's land already costs nothing; a test has to confirm it.

## 2. Design

**A deal between humans is the engine's own command, built from vanilla's parts, with no NPC side.**

- **Engine (SOSDET), a new command group:**
  - **Propose:** the proposer's terms, kept in the world state as an open proposal: id, from, to, the step it expires at (30 in-game days), and the terms.
  - **Accept:** applies every term at the step the host confirms, on every PC, after rechecking that each term still holds. Terms are applied with the same calls `DealParty.execute` and `DealBools` use: `DipStance.set`, the credits, the caravan, `RD.setFaction`.
  - **Decline;** and expiry, which the step does on its own.
  - The open proposals go into the world save and the hash. An accept that arrives twice changes nothing, because the proposal is gone after the first.
- **Rules between humans** (spec §6):
  - No opinion check and no valuation: whatever both players agree applies.
  - **Breaking a treaty** changes no NPC's opinion: the betrayal and opinion calls are skipped when both sides are humans.
  - **Absorb** is never offered, and a capital can't be given.
  - **Emissaries:** against a human, only Gather support is offered.
- **Goods** travel as vanilla's diplomacy caravan, visible on the map. They leave the sender's city and reach the receiver's through the existing city↔world transactions, which carry an id and apply exactly once.
- **Mod (this repository), the UI:**
  - a diplomacy screen for another player: their treaties with you, the terms to offer, Propose;
  - the popup on the receiver's PC (Accept or Decline), plus a note to the proposer about the answer;
  - this reuses vanilla's widgets where they don't assume an NPC, and adds no new network messages, because proposals and answers are engine commands.

## 3. Milestones

1. **Treaties, money and goods.**
   - Engine: propose, accept, decline and expiry; peace, trade, pact, ally and cancel all; money; goods.
   - Mod: the screen and the popup.
   - Checks: unit tests of the proposal store, including a save round trip and a repeated accept; a rig run with 3 humans (a proposal from copy 2 to copy 3, accepted, the treaty and goods identical on every copy, one left to expire); then a two-PC twopc run.
2. **Regions, vassalage, emissaries, passage.**
   - Regions other than capitals.
   - Vassal and overlord between humans, after checking what vanilla's tribute and shared wars do with a `Player` as the vassal. Today only NPCs are vassals, so this needs an audit before any code.
   - Emissaries limited to Gather support.
   - The passage test.
3. **War between players** (needs your decision 1 below).

Each milestone ends with its own PRs (SOSDET first, then the mod) and short smoke runs, each with a monitor every minute.

## 4. Decisions (owner, 2026-10-26: approved as recommended)

Milestone 1 is the priority: SOS-72 covers it alone. Milestones 2 and 3 get cards of their own.


1. **War between players before battles between players exist.** Declaring war is just a stance change, but war between humans can't be fought yet: sieges of a human's regions are refused, and armies meeting have no battle (spec §8 needs it to be real time). Options:
   - **(a) recommended:** offer war only once battles between humans are built. Until then, War is greyed out against a player, with the reason.
   - **(b):** allow the war stance now, with no fighting; this mostly just blocks trade.
2. **Where the deal lives:** in SOSDET, as above, since it changes world state in lockstep (ADR 0004). The mod only draws the UI. OK?
3. **Proposals while a player is offline or removed:** a proposal to a retired human (SOS-66) is refused, and open ones to or from them are dropped at the removal. OK?

## 5. Results, milestone 1 (2026-10-26)

- **Unit tests:** SOSDET's `DealBookTest` (7: propose, answer, expiry, removal, limits, the save round trip, the hash) and the mod's `DealInboxTest` (3: each proposal pops up once, each answer shows once, a resume's open proposal still pops up).
- **D72A, rig, 3 humans: PASS.** Copy 2 proposed trade to copy 3, which accepted; the TRADE treaty showed on every copy, and all copies were identical to step 14,000. Not covered there: the 30-day expiry (unit tests only), and the money and the caravan on their own.
- **D72B, two PCs: FAIL by setup.** At step 600 the joiner's new city had less than 100 denari, so the engine refused the proposal before it was sent ("Mogus doesn't have 100 denari."). The refusal works. The autopilot now offers at most what the joiner has.
- **D72B2, two PCs: PASS.** Proposed at step 600, open on both PCs at 660, accepted at 691 with the stance TRADE on both; the campaign saved at 1,200; hashes agree to 1,800.
- **D72C, two PCs, resume of D72B2's save: PASS.** Both loaded hash `c6edd695b1bb8326`, which includes the deal book; both showed the saved answer (deal 1, accepted, TRADE) after the load; hashes agree at 600.
- **After D72B2** vanilla's "No active entry points" error window opened on the laptop again (known, section 4 of HANDOFF). It isn't caused by the deals.
- **Not checked in a run:** goods through the caravan between two PCs, Decline, and the offer window and popup clicked by hand. These need the owner's playtest.
