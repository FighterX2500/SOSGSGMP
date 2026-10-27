# War between players (SOS-74)

Spec §6 Diplomacy (declaring war, breaking treaties), §8 Military (battles between players), §9 (sieges of player cities). Card: [SOS-74](https://fighterx2500.atlassian.net/browse/SOS-74). Diplomacy milestone 3, after [diplomacy.md](diplomacy.md) (SOS-72) and [diplomacy-2.md](diplomacy-2.md) (SOS-73). Status: **proposed**.

Game paths are relative to `<game>/info/SongsOfSyx-sources/`; line numbers are vanilla's, from the audit of 2026-10-27. SOSDET's patches already change some of these files, so each step starts from SOSDET's patched source.

## 1. What exists

**Declaring war in vanilla**
- It needs no answer. "Declare War" is a deal term worth 0 that is always accepted (`deal/DealBools.java:82-113`). The besiege question and the garrison's Attack tool also set war.
- **Penalties** all go through `OpsStance.setNewStance`, which takes a `FactionNPC`:
  - every NPC court's opinion drops (betrayal, doubled for war);
  - the target's court gets "war";
  - nearby NPCs get "aggression".
- **Allies:** `DIP.ally` pulls each side's allies into the war. SOSDET already keeps a human overlord out of its vassal's wars (SOS-73).
- **Trade:** war turns off trade and transit.
- **Peace:** there is no minimum war length. NPCs offer peace with money terms; a player's peace offer is a deal the NPC values.

**What blocks war between humans today**
- **Mod:** `PlayerTerms` greys out War: "No battles between players yet."
- **Engine:**
  - The deal book has no war treaty.
  - `World.java:98` refuses a besiege of another human's region.
  - `World.java:349` refuses a garrison attack on a human's army.
- **What's ready:**
  - `HumanDeals.treatyRefusal` already handles war: only peace can be agreed at war.
  - Cancel All (8102) applies at once and leaves the two neutral, with no NPC opinion change.

**Two humans' armies meeting** (if they were at war)
- **The prompt:** only the weaker side's human is asked (`Resolver.java:36-50`), and the other human gets nothing. Auto-resolve and Retreat are offered, and with no answer in 30 s the default is auto-resolve. Spec §8 says a battle between players is always real time.
- **The battle:** it is generated on the asked human's PC and loaded on every PC (plan 0006).
  - The soldiers, race and equipment of the other human's army are right: shared world data and city reports.
  - Wrong for the other human's army:
    - its boosts: `Army.faction()` for army 1 is `FACTIONS.otherFaction()`, some NPC;
    - its division names and banners: the battle PC's own;
    - its formation: random;
    - its soldier type: "enemy".
- **Commanders:**
  - Vanilla's army 1 is always the AI general.
  - SOSDET has the commanders table from SOS-15 (`Engine.commanders`, orders from a PC that doesn't command a division are skipped), but the mod never sets it. Today anyone can order anything (plan 0006 M1 gap).
- **Retreat and the result:**
  - Only army 0 can retreat in vanilla, and SOSDET's Retreat doesn't check who sent it.
  - The result prompt opens only for the asked human.
  - Both world armies take their losses. When the other human wins, the captives and spoils go to vanilla's defaults, with no choice.

**Sieges of a human's regions**
- **Other regions:**
  - Once the engine stops refusing them, the besieger is asked (auto, wait, retreat).
  - The defender is never asked, and its garrison never sallies on its own (`PRegAttack.create` is false for a `Player` region).
  - A capture goes through vanilla's Conquer.
  - Against NPC regions this works in the rig (plan 0008, H70A, H71A2).
- **A capital:** never falls this way (SOSDET's `Util.conquer`). Taking it needs the assault of spec §9, which isn't built.

**Crash risk found:** `OpsStance.signPeace` casts every war enemy of the current player to `FactionNPC` (`OpsStance.java:338-339`). A human at war with another human who makes peace with any NPC would crash every PC. It needs a hook before war between humans exists.

## 2. Plan: two milestones

War is offered in the Diplomacy tab only when milestone 2 is merged, so a war between players can always be fought as spec §8 says (your decision of 2026-10-26, kept). Milestone 1 is tested in the rig, where a test command sets the war.

### M1: the war itself (engine first, then the mod's UI behind a flag)

1. **Declare war:** a deal-book command, applied at once with no answer, like Cancel All:
   - it applies with the declarer swapped in as the current human, as `HumanDeals` does for every stance;
   - it uses `DIP.WAR().set`, not `setNewStance`, so no NPC opinion drops (spec §6);
   - NPC allies and vassals join as in vanilla (`DIP.ally`), and a human overlord stays out of its vassal's war (SOS-73);
   - from any stance, treaties included; a vassal declaring on its overlord ends the vassalage first.
2. **Peace:** the Peace treaty, already proposable at war, with money, goods and regions as any deal. No minimum war length.
3. **The `signPeace` hook:** skip human enemies in its loop. One line in a patch, with a `docs/hooks.md` row.
4. **Sieges of a human's other regions:**
   - remove the refusal in `World.java:98` for regions that aren't capitals;
   - the besiege question's "Yes" path declares war through step 1's command (vanilla's is NPC-only and silently does nothing);
   - the defender is asked to sally (decision 3);
   - a capital stays refused, with the reason "A player's capital can't be besieged yet" (spec §9 comes later).
5. **Garrison attack on a human's army:** remove the refusal in `World.java:349` when the two are at war.
6. **Field contact between two humans:** until M2, battles between two humans' armies **auto-resolve** (rig only, since the UI flag is off).
7. **Checks:**
   - a unit test of the war command in the deal book (refusals, the save round trip, the hash);
   - **rig, 3 humans, Mogus-72:**
     - copy 2 declares war on copy 3;
     - a besiege of one of copy 3's other regions;
     - a peace with money;
     - copy 2 makes peace with an NPC while at war with copy 3 (the crash path);
     - every copy identical;
   - `twopc` with the flag on, a short run.

### M2: real-time battles between players (engine plan 0006: M2 plus "human against human")

1. **The prompt:** when both sides have a human, no Auto and no Retreat in the prompt. With no answer in 30 s the default is to fight (spec §8). Who is asked: decision 2.
2. **Commanders:**
   - the host sets the commanders table right after the battle loads: each human's divisions to their PC, NPC divisions to the host's AI;
   - deploy and Retreat only for that side's commanders;
   - army 1's AI general is off when army 1 is a human's.
3. **The other human's army:**
   - **Boosts:** each PC's city report already carries values to the world part. It also carries its human's battle boosts, and army 1's faction is the other human, reading those reported values. Every PC then has the same numbers.
   - **Names, banners and formation:** the same report, or the world division's own data.
4. **Retreat for army 1:** a new path with its own losses (vanilla has none).
5. **The result for both humans:**
   - each winner chooses their own captives and spoils, as spec §8 says;
   - mercy goes to the right faction;
   - losses are applied to each world army.
6. **Turn on War in the Diplomacy tab** (remove the M1 flag), update spec §6 and §15, and the playtest pack.
7. **Checks:**
   - rig, 2 and 3 humans: a field battle between two humans' armies, each copy commanding only its own divisions, a retreat by each side in turn, and the result on both, with every copy identical;
   - then `twopc`, and the owner's playtest.

**Not in this card:** sieges and assaults of a player's capital (spec §9: the waiting period, the lockstep assault, sack, eliminate, vassalage), the speed vote, allies joining a battle between players, and resync during a battle (plan 0006 M3). These are cards of their own.

**Size:** M1 about half a day. M2 about 1.5 to 2 days; the other human's boosts and army 1's retreat are the largest parts.

**PRs:** each milestone ends with the SOSDET PR first, then the SOSGSGMP PR. Short smoke runs, each with a monitor every minute.

## 3. Decisions for the owner

1. **When War appears in the Diplomacy tab.**
   - **(a) recommended:** after M2, so every war between players is fought with real-time battles. Until then War stays greyed out.
   - **(b):** after M1. Field battles between players auto-resolve until M2. This is quicker to play but goes against spec §8 for a while.
2. **Who gets the battle prompt when two players' armies meet.**
   - **(a) recommended:** both players see "battle!" with Fight only; the battle starts when both press it, or after 30 s. The PC of the player whose army moved into the other generates it (spec §8, "Battle start").
   - **(b):** only the attacker sees it.
3. **The siege of a player's region other than the capital.**
   - **(a) recommended:** vanilla rules (spec §9): the besieger gets vanilla's siege prompt. The defender gets a "sally out?" prompt, and a sally is a real-time field battle between players. A garrison battle against the region itself auto-resolves as in vanilla.
   - **(b):** every battle over such a region is real time, garrison included.
4. **Capitals:** refused until the spec §9 cards, with the reason shown. OK?
5. **Cards:** M2 becomes its own card (for example, "Battles between players"), with SOS-74 for M1 and turning War on. OK?
