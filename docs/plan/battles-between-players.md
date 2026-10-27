# Battles between players (SOS-83)

Spec §8 Military (battles between players), §4 (battles pause the world), §9 (a sally from a besieged region), §11 (the save before a battle). Card: [SOS-83](https://fighterx2500.atlassian.net/browse/SOS-83). Milestone 2 of [war.md](war.md) (SOS-74). It builds on SOSDET plan 0006 (own-city battles) and the two-PC commanders table proven in stage E, run V3. Status: **proposed**.

Owner decisions already made (2026-10-27, war.md):
- War is offered in the Diplomacy tab only when this card is merged.
- When two players' armies meet, both get the prompt with Fight only. The battle starts when both press it or after 30 s, and the PC of the player whose army moved in builds it.
- Sieges of other regions follow vanilla rules, and the defender is asked to sally.
- Capitals wait for spec §9.

Game paths are relative to `<game>/info/SongsOfSyx-sources/`. Line numbers are from SOSDET's `build/patched-src` (audit of 2026-10-27).

## 1. What exists, and what's missing

Below, **A** is the player whose PC builds the battle and **B** the other player.

**The prompt.** `Resolver.init` asks only the weaker side's human (`Resolver.java:36-53`). The engine keeps one asked human per prompt (`Hooks.worldPromptOpened`, `worldPromptAnswerer`). Fight, Auto and Retreat are all offered, and on a timeout the default is Auto (`WorldBattles.java:154`).
- **Missing:** both humans asked, Fight only, waiting for both answers, Fight as the timeout default, and A building the battle.
- **The subtle part:** the save before a battle (SOS-70) predicts the battle step from the due Engage command (`Hooks.battleAhead`). With two answers, the first one isn't the battle step, and a timeout has no command at all.

**The battle itself** works with two PCs in it (plan 0006). A's PC builds it and every PC loads it at one step. Army 0 is A's army (the battle's human), army 1 is B's.

**Commanders.** The engine's commanders table (SOS-15, `Engine.commanders`) gives each division and each army's artillery to one PC, and skips orders from any other PC. That includes the host's AI general, so giving army 1 to B's PC switches the general off for it. The battle UI already shows the commanded army as the PC's own (`OwnArmy`, patch 94). The mod never sets the table today, so anyone can order anything.

**Army 1 is "an NPC".**
- `Army.faction()`, `Div.faction()` and a soldier's `faction()` (by its type, ENEMY) all give `FACTIONS.otherFaction()`, some NPC. So B's army fights with an NPC's bonuses, not B's tech, levels, titles and nobles.
- **Already there to fix it:** each human's city report carries every boost value of that human (`Field.BOOST`, `Hooks.reportBoost`). It is lockstep data, the same on every PC. It also carries B's divisions' equipment, training and experience.
- **Also wrong:** army 1's division names and banners (A's own city's), and its formation (random).

**Deployment.** Army 1 may be placed anywhere outside army 0's zone (vanilla), and anyone can press Start (DEPLOY).

**Retreat.** Vanilla lets only army 0 retreat, and the engine's Retreat command doesn't check who sent it. Army 1 has no retreat at all.

**The result.** It opens only for the battle's human (A).
- When A wins, A chooses captives and spoils.
- When B wins, vanilla's defaults are shipped to B with no choice.
- Mercy, occupying and puppets go to `FACTIONS.player()`, which is the answerer.
- The world armies' losses are applied once, through the accept command. B's divisions are B's city's soldiers, so their losses must reach B's city exactly once (ADR 0005); this is checked in step C.

**A siege by another human.** Only the besieger is asked (`Resolver.besige:137-149`), and it returns before the sally branch. The defender is never asked.

## 2. Plan: five steps in the engine, then the mod

Each step is tested in the rig with 2 or 3 humans on Mogus-72, every copy identical, before the next one starts. A rig action sets the war, since SOS-74 gives the engine the war command.

### A. Both players asked, Fight only (engine)
1. In `Resolver.init`, a branch before the loser's: when both sides have a human, ask both.
   - The engine's prompt state holds the set of asked humans and a ready mark for each, changed only inside the step.
2. **The prompt on each PC:** Fight only. Auto and Retreat are hidden, and a hook refuses them in the command too.
   - On B's PC it says that B is defending.
3. **Engage:** each Fight marks its human ready, and the last mark engages. With no answer in 30 s, every PC engages at the same step.
4. **The battle's human is A,** the side whose army moved into contact: side A of `PFieldBattle`, the army that reported the contact.
   - That army is usually the one that moved, but a standing army's 16-second re-report can make it side A instead. To make it exact: side A only if its army moved into the tile this step, else side B (decision 1).
5. **The save before the battle:** `battleAhead` predicts the step that completes the ready marks, or the step the timer runs out. Unit tests cover both cases.
6. **Rig checks:**
   - two humans' armies meet: both answer, and one answers with the other timing out;
   - the battle loads on every copy at one step, and the campaign save comes just before.

### B. Each player commands their own army (engine and mod)
1. When the battle loads, the host stamps the commanders table:
   - army 0's divisions and artillery go to A's PC, army 1's to B's PC;
   - NPC divisions (allies, other factions) go to the host's AI.
   
   It goes through the host's AI log, at a step the host confirms, like any order.
2. **Deployment:** army 1 gets its own deployment zone, mirrored from army 0's (decision 2), and the rotate check in `BattlePlacerSpin` follows the commanders.
3. **Start** needs both commanders to press it (decision 2).
4. **Rig check:** each copy orders only its own army. The other's orders and the AI general's orders for army 1 are skipped, and copies stay identical.

### C. Army 1 is B's own army (engine)
1. **B's bonuses:** a hook in `BValue.vGet(Faction)`, which patch 14 already changes. When the faction asked about is army 1's in a battle between players, it reads B's reported value. That covers soldiers, divisions and the army in one place.
   - The values are the ones in B's last city report before the battle, the same on every PC.
   - An audit first: city-based boosts (rooms, religion) that a soldier reads from A's live city.
2. **B's divisions:** men, equipment and training come from B's report (`DIV_EQUIP`, `GAR_TRAINING`, `GAR_EXPERIENCE`), not from A's city. Generation may run where those reads don't go to the report yet.
3. **Names, banners and formation** from B's division data.
4. **Rig check:** a battle where B has a tech or level A lacks. A probe reads army 1's boost values on every copy and compares them with B's report. Copies stay identical.

### D. Retreat for either side, and the result for both (engine)
1. **Retreat:** the Retreat command carries the side, and only that side's commanders may send it.
   - When army 1 retreats, army 0 wins.
   - Army 1's losses follow vanilla's retreat penalty, mirrored: the enemy's men times `retreatPenalty`, plus 75% of any division that is fighting.
2. **The result:**
   - the **winner** gets vanilla's result prompt and chooses captives and spoils (spec §8);
   - the **loser** gets a "Defeat" window with the losses, information only (decision 3);
   - mercy, occupying and puppets go to the winner;
   - with no answer in 30 s, every PC accepts with no captives and no spoils, as today.
3. **Losses:** each side's losses reach its own world army and city exactly once. B's city gets its losses through a transaction, and a repeat changes nothing.
4. **Rig checks:**
   - army 0 retreats; army 1 retreats; a fight to the end;
   - after each, the result on both sides and the world armies' men are identical on every copy.

### E. The sally (engine)
1. When a human besieges another human's region with soldiers in it, the defender is asked "sally out?" first.
   - A sally is a battle between players, built by the defender's PC (decision 1).
   - Staying in, or no answer in 30 s, opens the besieger's siege prompt as today.
2. Auto is hidden in a sally between two players.
3. **Rig check:** a sally battle between two humans, and a sally declined.

### F. The mod, and War switched on
1. **Logs and tests:**
   - the playtest log notes both answers, the timeout, the commanders, the retreats and each result;
   - the autopilot answers the prompts on both PCs, and twopc gets a battle between players.
2. **War:** turn on War in the Diplomacy tab (`PlayerTerms`), with spec §6, §8, §15 and §17 updated.
3. **Checks:**
   - twopc: war, a battle between the two players with each commanding their own army, then the result;
   - then the playtest pack, and your playtest.

**Not in this card:**
- the battle speed vote (spec §8);
- allies joining a battle between players;
- resync during a battle (plan 0006 M3);
- several humans on one side;
- the capital's siege and assault (spec §9).

**PRs:**
- after steps A and B: the SOSDET PR, then the mod PR;
- after steps C to F: the SOSDET PR, then the mod PR.

War is switched on only in the last mod PR. Short smoke runs, each with a monitor every minute.

**Size:** about 2 to 2.5 days, more than the 1 to 1.5 days in war.md. The audit found three larger parts:
- the save before the battle with two answers;
- B's divisions read from B's report;
- the result for the winner, with B's losses reaching B's city.

## 3. Decisions for the owner

1. **Which PC builds the battle.**
   - **(a) recommended:**
     - **field battles:** the player whose army moved into the tile this step builds it. If both moved, the one whose army reported the contact.
     - **sallies:** the defender's PC builds it, since the defender's army moves out.
   - **(b):** always the stronger side, or always the defender.
2. **Deployment and Start.**
   - **(a) recommended:** army 1 gets its own deployment zone, mirrored from army 0's. The battle starts when both players press Start, or after 2 minutes, so an away player can't hold everyone.
   - **(b):** vanilla's zones (army 1 anywhere outside army 0's), and Start when both press it, with no time limit.
3. **What the loser sees.**
   - **(a) recommended:** a "Defeat" window with the losses, information only. The winner alone chooses captives and spoils.
   - **(b):** nothing; the loser reads it in the world.
4. **Retreat penalty for army 1.**
   - **(a) recommended:** vanilla's, mirrored, so both sides lose the same when they retreat.
   - **(b):** no penalty for army 1's retreat.
