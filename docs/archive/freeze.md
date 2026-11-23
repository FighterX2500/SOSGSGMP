# Freezing a player, and coming back later (proposal)

Status: approved by the owner on 2026-11-12, with the section 3 wording "up to half a minute". Cards SOS-192 (spike), SOS-193 (engine, SOSDET PR #92), SOS-194 (mod: freeze), SOS-195 (mod: rejoin), SOS-196 (tests and report). Builds on hot-join (SOS-189, SOS-190: the restart route) and on the view's frozen city (spec section 10).

## 1. The request

Players asked for a way to leave a running campaign and come back later without losing their city. The spec doesn't allow that today. A player who leaves is either removed for good (Continue without them), or everyone goes back to the last save. Section 11 says: "A disconnected player never rejoins a running session", and "No city is frozen because its player is gone".

## 2. Owner decisions (2026-11-12)

1. **When:** both ways.
   - A player can choose **Leave and freeze** from the escape menu.
   - At a crash or disconnect, the host gets a third choice, **Freeze them**, beside Continue without them and Return to the last save.
2. **Wars:** a player may freeze during a war. Every war with them ends in **neutral relations**: a peace, not a truce.
3. **Capital:** protected while frozen.
4. **Other regions:** every region they hold besides their capital becomes **free land with no owner**, as at a removal. Each NPC faction that is their vassal becomes **independent**. When they return, they have their capital only.
5. **Armies:** sent home and frozen, with their stats as they were.
6. **Everything else:**
   - No NPC faction makes war on them or raids them.
   - Treaties and trade routes stay.
   - Goods and anything else sent to their city wait for their return.
   - No new deals with them while they are frozen.
   - No time limit; the host can still remove a frozen player for good.
7. **Rejoin:** the player connects to the running game. The host Accepts, and every game reloads, as at a hot-join. Their city goes on where it stopped.
8. **Saves:** a freeze lasts across saves and resumes until the player returns. A resume doesn't wait for a frozen player, and they can rejoin it later.

## 3. Draft spec text (section 11, for approval)

**Freezing a player.** (owner, 2026-11-12) A player can leave a running campaign and come back later.

- **Leaving.** A player chooses **Leave and freeze** in the escape menu. Their game saves their city, and the session goes on without them. At a disconnect (section 11, Disconnects), the host can also choose **Freeze them**. That player's city then comes back from their last campaign save, so what happened in it after that save is lost.
- **While frozen:**
  - Their capital stays theirs and can't be besieged or attacked.
  - Every war with them ends, and their relations with everyone become neutral.
  - Every other region they held becomes free land with no owner, and their vassals become independent.
  - Their armies go home to their capital and stay as they are.
  - No NPC faction makes war on them or raids them.
  - Their treaties and trade routes stay. Goods and people sent to their city wait until they return.
  - Nobody can propose a deal to them.
  - The chat says who is frozen. Their city appears to everyone as it was when they left.
- **Coming back.** A frozen player joins the running game as a new player does (see Joining). The host Accepts, every game saves and reloads with them back in, which holds every player for up to half a minute, and their city goes on where it stopped.
- **Saves and resumes.** The campaign remembers who is frozen. A resume starts without waiting for a frozen player, and they can come back into it later. The host can remove a frozen player for good at any time (see Removing a player).

Changes elsewhere:
- **§11 Disconnects:** add the third choice. Replace "A disconnected player never rejoins a running session" with "...rejoins a running session only after a freeze".
- **§11 Disconnects:** replace "No city is frozen because its player is gone" with a pointer to this section.
- **§12:** chat lines "*Name* left and is frozen" and "*Name* is back".
- **§15 [AT]:**
  - a freeze and a rejoin on three PCs keep every world hash equal through and after;
  - a frozen player's capital can't be besieged;
  - a frozen player resumes later from a campaign save;
  - a freeze during a war leaves every stance with them neutral.

## 4. Design

**Engine (SOSDET):**
- `Engine.freezeHuman(slot)` runs in the step's world part on every PC, like `retire`, but the slot keeps its capital. It:
  - sets every stance with the slot to neutral and frees its vassals;
  - frees its other regions;
  - drops its open deals and the siege of its capital;
  - moves its armies to the capital and marks them frozen;
  - marks the slot frozen.
- The pacer drops the slot's PC, as at a removal. The world reads the slot's city as its last report, as for a viewer's frozen city. Transactions to it are held (`HeldTxn`, as for a view).
- Protection checks refuse a war declaration on a frozen human, a siege or assault of its capital, a raid on it and NPC war targeting of it. These sit where `sanctified` protects an NPC; a human slot needs its own check.
- On rejoin, `Engine.thawHuman(slot)` runs in the restart, before step 0, on every PC: the slot is unfrozen and its armies run again.
- The frozen flag is saved with the humans (`SOSDET_HUMANS`, a new format version).

**Mod (SOSGSGMP):**
- **Leave and freeze** in the escape menu. The host orders a freeze at a held step. The leaving PC saves its city as a campaign save of its own, then leaves.
- **Freeze them:** a third button in the disconnect window (`MpGone`).
- **Campaign record:** `player.N.frozen` (record format 4, protocol 30). A resume skips them. No save number is kept: the returning PC's own latest record of the campaign is the save its city returns from (its Leave and freeze save, or its last save before a crash).
- **Rejoin:** hot-join's Hello from a known, frozen key leads to "*Name* wants to come back", then Accept, then the restart.
  - The returning PC loads the host's restart save for the world.
  - Then it loads its own city from its freeze save. This step needs a spike.
  - Then `thawHuman`.

## 5. Risks and questions to settle in a spike

1. **Swapping in their city.** The returning PC needs the world from the host's save and its city from its own older save. The view code already loads cities on their own; the spike checks this works for a city that is weeks behind the world (calendar, seasons, ages).
2. **A freeze after a crash rolls the city back.** Their city returns from their last campaign save, but the world already took what the city sent out after that save (goods, soldiers, money): sent things could exist twice, and things sent to the city could be lost. Options:
   - keep what the world has and accept it;
   - drop the city-to-world transfers made after the save;
   - offer Freeze them only when the last save is recent.
   The spike measures how much is at stake.
3. **Frozen armies.** Vanilla's army update (upkeep, supplies, morale) must skip them, and other armies must path around or through them.
4. **Global events** (section 5) fire on every player's city. A frozen city needs them held or skipped.
5. **Frozen human slots on a crowded map.** A frozen player keeps a faction slot, so hot-join's "no free slot" can come sooner.

## 6. Cards (created 2026-11-12 as SOS-192 to SOS-196)

| Card | What | Size |
|---|---|---|
| Spike | City swap into a newer world; a rejoin in the rig; the crash-rollback size | 1 day |
| Engine | `freezeHuman` and `thawHuman`; protection checks; frozen armies; held transfers; save format | 2-3 days |
| Mod: freeze | Leave and freeze; Freeze them; campaign record; resume without them; chat; spec text | 2 days |
| Mod: rejoin | Known frozen key; "wants to come back"; restart with the city swap | 1-2 days |
| Tests | `twopc --freeze` and `--rejoin`; the real campaign; GUI review; report | 1 day |
