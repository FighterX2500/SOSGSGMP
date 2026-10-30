# Sieges of player cities (SOS-92)

Spec §9 Sieges of player cities, §15 Sieges, §4 (an assault pauses the world), §11 (the save before an assault; a disconnect in an assault), §7 (no trade into a besieged city). Card: [SOS-92](https://fighterx2500.atlassian.net/browse/SOS-92). It builds on [ADR 0006](../decisions/0006-lockstep-city-viewing-and-assaults.md) (assaults in lockstep), city viewing ([city-viewing.md](city-viewing.md), SOSDET plan 0013, SOS-82), battles between players ([battles-between-players.md](battles-between-players.md), SOSDET plan 0012 M2, SOS-83), the save before a battle ([battle-saves.md](battle-saves.md), SOS-67) and the SOS-23 spike ([city-determinism.md](spikes/city-determinism.md)). Status: **approved (owner, 2026-10-29)**: decisions 1a, 3a and 6a as recommended; 2, 4 and 5 as the owner set them (section 3). Spec 0.29 and ADR 0006 record decisions 2 and 5.

Status: approved by the owner (2026-10-29). Step A merged (SOSGSGMP #64, SOSDET #60, 2026-10-30); steps B and C in part are in review (SOSGSGMP #67, SOSDET #63).

Game paths are relative to `<game>/info/SongsOfSyx-sources/`. Engine paths are SOSDET's (`src/main/java/sosdet/...`, `build/patched-src/...`). Line numbers are from the audits of 2026-10-29.

## 1. What exists, and what's missing

### Vanilla

**The siege.**
- `WArmy.besiege(Region)` (`world/entity/army/WArmy.java:263-281`) sends the army to a siege tile and sets `WArmyState.besieging`.
- `PSiege` (`world/battle/PSiege.java`) keeps each region's `besigeTime`. It adds 128 every 128 s of game time while the region is besieged and removes 512 when it isn't (`:101-136`).
- While `besigeTime > 0`, the garrison shrinks after a day (`RDMilitary.besigeMul`, `world/region/RDMilitary.java:141`).
- Both are in the world save.

**The blockade of a capital.**
- `EntryUpdater.update` (`settlement/entry/EntryUpdater.java:64-115`) closes the city's entry when an enemy army besieges `FACTIONS.player().capitolRegion()` within 3 tiles.
- A closed entry (`SENTRY.isClosed`) stops the city's market trade, immigration, tourists, supply runs and army reinforcement.
- Tax caravans skip besieged regions (`Shipper.ship:31`).

**The assault is a real-time invasion of the city.**
- `Resolver.besige` asks a defending player to sally on the first poll (`iplayer.sallyOut`), and returns while the defender is stronger. Otherwise, for the player's capital, it calls `ResolverPlayer.invadeCapitol(a)` (`world/battle/ResolverPlayer.java:205-240`).
- `invadeCapitol` builds an `InvasionSpec`: the attacker's divisions, artillery, loot and world position. It then calls `SETT.INVADOR().invade(spec, army)` and disbands the world armies.
- `Invasion` (`settlement/battle/invasion/Invasion.java:75-84`) runs `WARNING → BOMBARD → PLACEART → DEPLOYING → FIGHTING → WAIT_FOR_REPLY → DONE`. The WARNING prompt offers a field battle with the garrison first (`Attack.java`).
- The enemy enters from the map edge facing the attacker's world position (`SpotMaker.get`).
- **Defender wins:** every enemy division is dead or inactive (`fight()`, `:294-354`), and routed enemies may become prisoners (`:324-341`).
- **Attacker wins:** an enemy soldier stands next to the throne (`:355-370`).
- **Time limit:** after 5 days of fighting the enemy retreats (`:212-222`).
- **When the player loses** (`Invasion.loose`, `:415-444`):
  - 75% of the credits are taken;
  - 25-50% of the warehouse stock is taken;
  - an NPC invader makes the player its vassal;
  - nothing else happens: there's no game over and no change of owner.

**Captives and mercy.**
- `Util.ship` (`world/battle/Util.java:296-367`) sends captives as a spoils caravan. They arrive as prisoners (`PBuyerSlave.deliver`, then `SETT.ENTRY().add(race, PRISONER, n)`).
- The city's prisoner law then pardons or enslaves them (`AIModule_Prisoner`).
- Mercy is one number per faction (`AD.stats().mercy()`). The field battle's result panel moves it as follows (`view/world/ui/battle/Res.java:395-415`): release +n, capture −n/2, execute −n, each divided by (1 + population).
- Nobles read mercy as Chivalry and Cruelty (`OpsStance.java:148-186`).

**Time.**
- 1 day is 1,152 game seconds and 1 season is 4 days (4,608 s, `Sett.txt`, `Time.txt`).
- So the waiting period is about 1.3 hours of play per season at 1×, 5.1 hours at 4 seasons, and about 12 minutes at 25×.

**One-player assumptions on these paths** (vanilla's Resolver, PSiege, EntryUpdater, Invasion, Conquer, mercy): `ResolverSide.player`, `PSiege.besige` notifying `FACTIONS.player()` only, `EntryUpdater` watching the player's capital only, `Invasion.loose` and `Attack` taking `FACTIONS.player()`'s credits, and mercy read for `FACTIONS.player()`. `Invador` updates one invasion at a time.

### Engine and mod today

**A player's capital can't be besieged by another player.**
- `ARMY_BESIEGE` refuses it (`game/cmd/World.java:95-102`, `otherHumanRefused`, `:366-374`), with the reason "A player's capital can't be besieged yet" (war.md).
- Patch 15's `Util.conquer` never conquers a human's capital (`patches/15-humans.patch:1391`).

**Other regions** work, with the sally (SOS-83): `Resolver.besige:147-163`, `Hooks.worldPromptSallyAgainst`, Fall Back re-polling the besieger (`ResolverPlayer.java:98-102`).

**An NPC army at a player's capital** goes through vanilla's `invadeCapitol`, then `Hooks.invade` (`Hooks.java:1182-1191`), a `TxnKind.INVADE` transaction, and `GameTxns.java:111-113`.
- **The invasion runs only on the defender's PC,** as part of its own city. The other PCs keep playing, nobody watches, and nothing about it is in lockstep.
- Its outcome reaches the world through report inputs (`INVASION_TELL`, `VASSAL`).
- It works, but it's the path spec §9 "Invasions by NPC armies" replaces. No rig run of it with several humans is recorded.

**What the assault can reuse:**
- **City viewing (SOS-82)** carries one player's city to another PC at a step and runs it there in lockstep:
  - the owner's save (`Engine.viewOut`, `GameView.saveForView`, `ViewCity.cityState`) and the load on the other PC (`Engine.viewIn`, `GameView.loadView`, which keeps the session);
  - the city stream seeded as the owner's PC;
  - a hash over the city's sections (`viewHashOut/In`);
  - the thaw that brings the viewer's own city back (`__beforeView`, `heldTxns`).
  - **Measured on two PCs:** the city's load took 7.9-8.4 s and the owner's pause 6.6 s.
- **Battles (plan 0006, SOS-83)** provide the rest:
  - Engage at a step (`Hooks.ownCityEngage`) and `__beforeBattle` on every PC;
  - battle mode, where every PC runs one shared stream, city commands apply on every PC and the hash covers the battle;
  - the commanders table, which gives each division to one PC (`Engine.commanders`), and the host's AI log;
  - Start when both sides are ready, or after 2 min (`deployReady`);
  - the return, where every PC loads its `__beforeBattle`, the outcome goes to the winner (`BattleOutcome`), and the mod shows `MpBattleOutcome` and the Defeat window;
  - the save before a battle (`Engine.battleHold/battleAhead/battleGo`, `MpSaves`). battle-saves.md says assaults follow it.
- **Exactly-once transfers (ADR 0005):** credits and goods between players move as in `HumanDeals.give` (`game/cmd/HumanDeals.java:404-429`). Vassalage with a tribute rate is in `HumanDeals` (SOS-73).

**Missing:**
- the waiting period;
- the assault from the defender's city save on every PC;
- the attacker commanding divisions inside a city;
- the outcomes Sack, Eliminate and Vassalage;
- removed people as moved, killed or freed;
- allied attackers;
- a city that becomes a neutral NPC city;
- the eliminated player as a spectator;
- no trade between players into a besieged city;
- resync inside a battle or an assault (plan 0006 M3, not built).

## 2. Plan: seven steps, engine first

Each step is one PR in SOSDET, plus one in this repository where marked. Each ends with a short rig run (about 3 copies, at most 10 minutes) and, from step C on, a twopc run.

### A. The siege of a player's capital and the waiting period (engine)

- At war, `ARMY_BESIEGE` accepts another human's capital (`otherHumanRefused` keeps the peace check only).
- The siege works as vanilla's for both sides:
  - `PSiege` counts the time;
  - the defender's sally prompt comes first (SOS-83's sally, now for the capital);
  - **the sally at any time** (decision 2): while their capital is besieged, the defender can sally out in every phase before the assault. A "Sally out" button on the besieged capital's region panel (hook M9; also in the multiplayer panel and the popup, owner 2026-10-29) starts the sally battle as an engine command at a step, through SOS-83's sally path. This works for a besieger that is a player or an NPC;
  - **the popup at the siege's start** (mod): "Your capital is besieged by X. You can sally out at any time before the assault: Sally out on the region panel." It only informs; it has no timer;
  - the defender's city closes its entry: `EntryUpdater` watches this PC's human's capital, which is `FACTIONS.player()` in own-city mode (checked in the step's audit).
- **The waiting period:**
  - The engine keeps each besieged human capital's **siege start step** and the defender's **population at the start**, from the defender's city report. This is world state: in the world save and in the hash.
  - The assault is allowed after `seasons = min(4, ceil(pop / 1000))` seasons of game time (spec §9's table).
  - If the siege lifts (no besieger left on a siege tile for a whole `PSiege` visit), the record clears and the wait starts over.
- **The besieger's prompt** for a human capital: vanilla's `BattleSiege`, with Storm greyed out and "Storm in N days" until the wait ends, then enabled. Wait and Lift work as in vanilla. Storm is an engine command at a step.
- `Resolver.besige` no longer calls `invadeCapitol` for a human capital besieged by a human: only Storm starts the assault (step B).
- **No trade between players into a besieged capital:**
  - the human market (SOS-77) and `HumanDeals` refuse goods into or out of a besieged human capital, with a reason;
  - money still moves;
  - NPC trade is already stopped by the closed entry.
- **Tests:**
  - unit tests for the wait table, the start record and its save/load round trip;
  - a rig run with `--make-humans` and a scripted siege at a raised speed, checking that Storm is refused before the wait and accepted after it;
  - a test that a trade between players into a besieged capital is refused.

### B. The assault in lockstep (engine)

At Storm's step S:

1. **The save before the assault.** The host holds and saves the campaign as before a battle (SOS-67's `battleHold`, with Storm as the due command).
2. **Engage.** `Hooks.assaultEngage(defender, attackers)` works like `ownCityEngage`:
   - every view ends;
   - every PC saves `__beforeBattle`;
   - the world pauses (§4) and battle mode starts.
3. **The city.**
   - The defender's PC saves its city with city viewing's `saveForView`/`cityState` and sends it, with the mod's existing city stream (`VIEW_CITY`, protocol 14, under a new purpose).
   - Every other PC loads it with `loadView`'s path: the session is kept, and the stream is seeded as the defender's PC.
   - The defender's PC doesn't reload: it keeps running its own city.
4. **The invasion.** Every PC starts the same `Invasion` at the first step after the load, from an `InvasionSpec` built from lockstep world data: the besieging armies' divisions, artillery and supplies, and their world position for the entry edge.
   - The attacker's divisions take the attacker's boosts, equipment, training and experience from the attacker's city report, as army 1 does in SOS-83 (`BValue`).
   - The world armies are taken out of the world as vanilla does. Survivors return after the assault (step C).
   - Vanilla's WARNING prompt and its "Attack first" field battle are skipped: the defender already had the sally, and can attack the besiegers during the whole siege (§9). The assault starts at BOMBARD.
5. **Commanders.**
   - The defender's PC commands the city's soldiers and artillery. The attacker's PC commands the invading divisions and artillery (the commanders table, `Session.commandBattle`).
   - The host's AI log runs everything else: the city's people, and any division that has no commander.
   - Deployment ends when both press Start, or after 2 minutes (SOS-83's `deployReady`).
   - Every other PC watches. The defender can't build or change the city during the assault: city commands other than military orders are refused, with a reason.
6. **The hash** covers the city's sections and the battle, as in a view. The assault ends with vanilla's win, loss or 5-day retreat, at the same step on every PC.

- **Tests:**
  - rig: Mogus-72 as the defender, a scripted human attacker and an assault of about 6,000 steps, every copy identical;
  - the defender's city must be identical on the defender's copy and on every loading copy at every check;
  - twopc: a two-PC assault with `--battles auto`.

**Status (2026-10-30, overnight, decided as recommended; the owner reviews):** built in SOSDET PR (SOS-92 step B) and this repository's step B PR, with three differences from the plan above:
- The vehicle is a view, not battle mode: every other human views the defender's city from Storm's next report step, and the world is held until every view thaws. City viewing already loads the defender's city on every PC and checks the hash, so no new city stream was needed.
- No commanders yet: the invaders and the defenders run under the game's own `Invasion` AI on every PC. Player commands for the attacker's divisions are a gap for a later step.
- No save before the assault: saves are refused while it runs ("No saves during an assault."), and the last autosave is the restart point.

Rig run S92M (Mogus-72, slot 2 storms slot 0's capital): Storm at step 16,625, the view from 16,650 (7.5 s load), the defender held at 28,882, the view ended at 28,890, no desync. A twopc run is still to do. Rig run S92N repeated it with probes: held at 32,884; afterwards the attacker's army is gone and the siege record cleared on both copies, no desync.

**Step C, in part (same night):** the world's outcome reaches the mod once (`Engine.assaultOutcomeTake`): the attacker and the defender get an Assault window (victory, defeat or withdrawal, with the men the attacker committed), every other player a notice, and the playtest log a line. Not done: vanilla's prisoners prompt for the defender; on a withdrawal the attacker's world army keeps the men it lost in the city; the save before an assault has no name (there is none: saves are refused).

### C. The return, and the results for each side (engine and mod)

- At the end step E, every PC except the defender's loads its `__beforeBattle`, as after a battle.
- The defender's PC keeps the city as the assault left it: its own simulation is the same city every other PC ran, so nothing is sent back.
- The world resumes from the step before S on every PC, then the outcome applies at a confirmed step.
- **The defender wins:**
  - vanilla's prisoners prompt goes to the defender, and their answer is a command at a step;
  - the attacker's survivors are lost, as in vanilla, where the armies were disbanded at the start;
  - the attacker gets the Defeat window with the losses;
  - the siege record clears.
- **The attacker wins:** the outcome window (step D) opens on the attacker's PC and the Defeat window on the defender's. Vanilla's `Invasion.loose` (75% of credits, goods, vassal) is not applied between players.
- **The 5-day retreat:** the attacker's survivors go back to the world army at its siege tile, and the siege goes on with a new wait.
- **Losses:** the defender's dead are already in the defender's city. The attacker's dead are taken from the attacker's city through the division owner's `REL_DEATH` transactions, as for B in SOS-83.
- **Mod:**
  - `MpBattleOutcome` and the Defeat window read "Assault";
  - the assault goes into the playtest log;
  - the save before an assault gets its name;
  - a disconnect during an assault: Continue without them ends it with no result, as for a battle (§15 Disconnects).

### D. Sack, Eliminate and Vassalage (engine and mod)

The attacker's outcome window has three choices. Each is a command at a step, which the engine checks against the end state of the assault. That state is known on every PC, because every PC ran the city.

- **Sack:** the attacker picks goods, an amount of money and the people removed, up to 80% of the population.
  - Money and goods move as in `HumanDeals.give`. The defender's city removes them (two-phase withdrawal, ADR 0005), and the attacker's capital receives them by a spoils caravan.
- **Eliminate:**
  - 90% of the resources and money, and 90% of the population, are removed;
  - the capital and every region of the player then become a neutral NPC faction's, as vanilla's `Util.conquer` gives a region to a faction (decision 4);
  - the player becomes a spectator: their PC stays in the session and watches the world, with no city;
  - this needs a small part of spec §11's elimination now; the spectators' view comes with the chat and notifications card.
- **Vassalage:** the defender becomes the attacker's vassal, with SOS-73's terms: the tribute rate set in the window, default 10%.
- **Removed people** (Sack and Eliminate): the attacker splits the count between Moved, Killed and Freed, by race.
  - **Moved:** they go by vanilla's `Util.ship` to the attacker's capital and arrive as prisoners. The attacker's prison law then makes them citizens or slaves, as in vanilla.
  - **Killed and Freed:** they are taken from the defender's city.
  - The defender's city removes people through a new transaction kind, `REMOVE_PEOPLE` (race and count, applied exactly once). It picks which people itself: only the defender's PC runs that city from then on.
  - **Mercy** follows vanilla's field-battle panel: freed +n, moved −n/2, killed −n, divided by (1 + the attacker's population), on `AD.stats().mercy()` of the attacker's faction.
- **Mod:**
  - the outcome window, with sliders for money and each good, and the people's split by race;
  - the defender's Defeat window, with what was taken;
  - each outcome in the playtest log.
- **Tests:**
  - unit tests for the limits (80%, 90%, mercy) and the commands' refusals;
  - a repeated transaction changes nothing;
  - a rig run of each outcome, with every copy identical and each city's sums as expected;
  - twopc: one Sack.

**Status (2026-10-30, overnight, decided as recommended; the owner reviews):** Sack and Vassalage are built (SOSDET plan 0014, "Step D").
- After a taken throne, the attacker's PC opens `MpAssaultTerms`: Sack takes 0% to 80% of the defender's money and of each good, by caravan, and makes peace; Vassalage takes a tribute of 0% to 100%.
- With no choice in two minutes, Sack takes 50%.
- The defender and every other player get a notice, and the playtest log a line. Saves wait while the choice is open.
- Simplification: one share for the money and every good, not a slider per good.
- **The removed people are built for Sack (2026-10-30):** a row per race with Moved, Killed and Freed, up to 80% of the citizens and slaves in the city in all, by `REMOVE_PEOPLE`, with mercy. Nobles stay. With no choice in time, no one is removed. Details in SOSDET plan 0014.
- Not built: Eliminate. Its spectator is split off to SOS-111.

### E. Allied attackers (engine)

- Every human at war with the defender who has a besieging army on the capital's siege tiles joins the assault. NPC allies' armies join under the host's AI.
- Each human commands their own divisions (the commanders table, one PC per division).
- The human with the most soldiers at Engage chooses the outcome. Money, goods and removed people are split by the soldiers each human brought at Engage, rounded down, with the remainder going to the chooser.
- **Tests:** a rig run with 3 humans, two attackers and a defender.

### F. NPC invasions of a player's capital stay local, and can be viewed (engine and mod)

Decision 5: they keep today's path. An NPC faction's or a rebel army's `invadeCapitol` becomes a `TxnKind.INVADE`, and a raider's `RaiderArmy.invade` runs in the defender's city. The invasion runs in that city's own time, on the defender's PC only, and the world doesn't pause.

- **Check today's path with several humans.** No rig run of it is recorded. The checks:
  - the invasion's outcome reaches the world exactly once (`INVASION_TELL` and `VASSAL` inputs);
  - the world army is gone on every PC at the same step;
  - nothing in it reads another human's state.
- **Notification:** every PC learns that the city is invaded from a lockstep event: the `INVADE` transaction's step, or the raider's attack in the defender's report. The mod shows "X's capital is invaded by Y" on every PC: a popup now, and a chat line once chat exists.
- **Viewing an invaded city:** a view request during an invasion works as at any other time, and needs the owner's Allow.
  - Today `Session.viewRefusal` cancels a view while a battle or a prompt is open. The audit checks that an invasion in the owner's city counts as neither.
  - It also checks that the invasion runs the same on the viewer's PC: its enemies, the entry point and its AI read only lockstep state, as the view's hash already checks.
- **Not in this step:** the owner's answer to the view request pops up in the middle of their fight. That stays as it is.
- **Tests:**
  - a rig run of an NPC army against a human capital: every copy's world identical, the outcome applied once;
  - a rig view of the invaded city: the view hash holds.

### G. Resync during an assault (engine)

- §15 asks that a forced assault desync be detected within 2 s and recovered from the host's assault state within 10 s.
- This is battle resync (plan 0006 M3), not built yet. The host's save of the battle-mode game is loaded on every PC, keeping the session, the way the co-op resync does (`Engine.resyncSave/resyncLoad`).
- One mechanism serves battles and assaults, and it closes the field-battle gap too.
- **Tests:** rig `--corrupt` during an assault and during a battle.

## 3. Decisions (owner, 2026-10-29)

1. **The population for the waiting period:** the population at the siege's start, so the attacker knows the wait from the start and the defender can't stretch it by moving people in. Rejected: the current population, read from each report.
2. **The sally, and vanilla's WARNING phase:** the defender can sally out at any time during the siege, in every phase before the assault, and a popup at the siege's start reminds them. Vanilla's WARNING phase and its "Attack first" field battle are skipped, and the assault starts at BOMBARD (step A, step B).
3. **The attacker's survivors:**
   - **after a win:** they return to the world at the siege tile;
   - **after a loss:** they are lost, as in vanilla;
   - **after the 5-day retreat:** they return, and the siege goes on with a new wait.

   Rejected: vanilla's disbanding at the start in every case.
4. **Who owns an eliminated city:** a neutral city. It goes to rebels, the way vanilla's Abandon does it (`Util.conquer` with `null`), with the player's other regions. Rejected: a new NPC faction made the way vanilla's Puppet makes one.
5. **NPC invasions of a player's capital** (an NPC faction's, rebels' or raiders'): no lockstep and no pause. The invasion stays in the defender's city, every player gets a notification, and any player can ask to view the city, which its owner allows or refuses (step F; spec §9, §10, §15; ADR 0006). Rejected: the lockstep assault with the world paused.
6. **Order and tests:** three rounds, each tested before the next:
   1. A to D, a playable siege between two players, then tests;
   2. F, then tests;
   3. G and E, then tests.

   Each step keeps its own unit tests and its short rig run. Each round ends with a combined run of at most 10 minutes (rig and twopc), and the laptop joins when it's reachable.

## 4. Risks

- **City size.** The 10,000-person city has never been run in lockstep. Mogus-72 (about 4,200 people) held identical for 36,000 steps in SOS-23. Step B's rig run should use the largest test save at hand, and the load time should be measured (viewing: 7.9-8.4 s).
- **Defender's PC as the only city.** After the return, the defender's PC goes on with the assault's city, while every other PC loads its own save. A divergence there would be a desync in the defender's next report, not a crash, so the world hash catches it. Step C checks the report hash right after the return.
- **The invasion's own AI.** `Invasion` drives the enemy with its own code, not Strategos. Step B's audit checks that it reads only lockstep state (time, rand streams) and that no part of it is render-driven (SOS-23 risks: house sprites, first-caller caches).
- **Untested:** AI artillery orders (SOS-23 line 111), and a city question open at Engage (plan 0013 §5).

## 5. Estimate

About 3 days at the current pace: A 0.5, B 1, C 0.5, D 0.5, E 0.25, F 0.25, plus G (resync) about 0.5, which also closes plan 0006 M3 for battles. Decision 5 moved F's lockstep work out and left an audit and a notification.
