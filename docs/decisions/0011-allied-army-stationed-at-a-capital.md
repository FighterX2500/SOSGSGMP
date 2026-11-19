# 0011: An allied player's army stationed at another player's capital

Status: Accepted (owner, 2026-11-18) · Batch 13, item 10c (SOS-233); item 10b (SOS-234) builds on it · Builds on [ADR 0005](0005-world-lockstep-and-city-world-link.md) (own cities), [ADR 0006](0006-lockstep-city-viewing-and-assaults.md) (views and assaults), [ADR 0009](0009-several-humans-in-a-battle.md) (allies in battles)

## Context

- **The request (owner, 2026-11-18).** An ally's army enters another player's city and stays there, with no raid, for v1. Today soldiers never cross into another player's city. Item 10b follows: the stationed soldiers fight raiders, or an attacking player, in that city, and the ally commands them during a view.
- **A city has two armies and no third side.** `Armies.ARMIES = 2` (`game/battle/Armies.java:33`): `player()` is army 0, `enemy()` army 1 (`:216-222`). Army 0 is always `FACTIONS.player()` (`game/battle/Army.java:100-104`). A person who isn't hostile belongs to army 0 (`settlement/stats/Induvidual.java:235-236`). Vanilla has no friendly soldier of another faction inside a city.
- **A player's soldiers are city people.** In the city they are citizens (`SUBJECT`) in one of army 0's division slots (`Config.DIVISIONS_PER_ARMY`, 1 to 126). Sent out, they become a world division (`WDivStored`) that keeps each soldier as a stored person (`world/army/WDivStored.java:43`) and uses its city division's slot and settings (`:82-84`). Coming home, a soldier is made a citizen again at the city's entry and put back in his slot (`world/army/WDivStoredAll.java:117-154`).
- **Under own cities, only the owner's PC runs a city** (ADR 0005). Soldiers cross the city boundary only to and from their own city: a soldier coming home is a transaction only his own city applies (SOSDET `TxnKind.SOLDIER_RETURNS`, `followerApplies` false, `TxnKind.java:16, 53-55`); a soldier leaving is a city-to-world input, the costliest kind in SOSDET's link scan (B1). Every human's stored soldiers are world state, the same on every PC, with their division settings read from the owner's city report (SOSDET patch `14-report-reads`, `WDivStored`; section `SOSDET_CITY_DIVS`, `GameCityDivs`).
- **The one case of another player's soldiers in a city is the assault.** At Storm, in the world part, each joining army's divisions are turned into an invasion (`WDIV.generate`, SOSDET patch `15-humans`, `sosdetAssault`). Every PC runs the defender's city through a view, the invaders fight there as army 1, and the survivors go back to the world armies as counts, each division keeping the same share (SOSDET `GameAssault.java:121-169`). Nothing of the attacker lives in the city before or after.
- **Upkeep in vanilla.** A player's world army eats its own supplies every day (`world/army/ADSupplies.java:154-171`). They are taken from its city's stockpiles when a division is sent out (`WDivStoredAll.java:75-90`, SOSDET's two-phase `ARMY_SUPPLY_DIV`); a surplus goes home by caravan (`world/entity/army/WArmy.java:214-243`). City divisions cost no money (`WDIV.costPerMan` is 0, `world/army/WDIV.java:43-45`); only mercenaries do (`ADSupplies.java:97-98`).
- **Armies near a capital already defend it, outside the walls.** Vanilla's Attack against invaders takes the player's own armies within 4 tiles of the capital (`settlement/battle/invasion/Attack.java:208-243`, filter `:227`). Item 10a adds allies there.

## Decision

**A stationed army stays a world army (option A; owner, 2026-11-18: "Option A, map").** Stationing is world state, in lockstep on every PC. Nothing of the stationed player lives in the host's city until a fight there (10b). Nobody sees it inside the city in peacetime: no parading in v1.

- **Who may station** (owner: all four). A player's army may be stationed at another player's capital when the two are allies, colleagues (a pact), or one is the other's overlord (an overlord in its vassal's capital, a vassal in its overlord's), and they aren't at war.
- **Asking** (owner: "Alliance is enough"). No prompt: the agreement that allows stationing is enough. The host can send the army away at any time.
- **Stationing.** The army must stand still (fortifying or fortified) within 3 tiles of the host's capital centre along either axis, the allied-attacker distance of spec §9. Its player stations it with a world command, applied at one step on every PC (7980). The engine keeps a record per army: the army (index and iteration), its player, the host, the tile it stands on and the step (`StationBook`).
- **How many** (owner: "Limit by free slots"). Stationing is refused once the stationed divisions plus the host's own would exceed the city's army slots (`Config.battle().DIVISIONS_PER_ARMY`, 120 in V71). The host's own are its capital's garrison divisions and the divisions of its armies, both world state; the stationed ones are those of the armies already stationed there. The limit is checked when an army is stationed: an army that grows afterwards, or a host that recruits, ends nothing. 10b, which puts the divisions into army 0's slots, has to fit what is there at the fight.
- **Whose PC.** None runs anything new. The army is world state like any other.
- **Orders.** The army's player's, on the world map. Moving it ends the record. Its player can also call it back, and the host can send it away (7981); either way the army stays where it stands.
- **Upkeep** (owner: "Host may supply it"). As any field army, the stationed army eats its own supplies, and its own city's supply depots refill it as today (vanilla's depot delivers to all of a player's armies, wherever they stand). The host may also supply it from the host's own city:
  - **The order.** The host presses Supply for that army: a world command (7982), applied at one step on every PC, from the host only, for an army stationed at the host's capital.
  - **The path.** The two-phase supply path the engine already uses when a division is sent out (`ARMY_SUPPLY_DIV`, scan C1 then B2): the world part queues a transaction to the host's city (`TxnKind.STATION_SUPPLY`); the host's city tail fills each of the army's supplies from its stockpiles, as much as they hold, and its supply counter sends what it took to the world as a `SUPPLY` input in the city's next report, which the world part adds to the army.
  - **Exactly once.** The transaction has its id from the world's transaction log and the host's city applies it once, as every transaction. The goods leave the stockpiles and enter the report's input in the same city step, and the world applies each report's inputs once, in report order. The city counts what it sent and the world hasn't applied yet as already there (the supply ledger), so a second Supply before the report fills only what is still missing. An army gone in between loses the goods, as vanilla's supply does.
  - **Limits.** One Supply fills the army; nothing is automatic. The host's supply depots don't deliver to it on their own: that would need a hook in vanilla's depot (`settlement/room/military/supply/Cache.java`), which v1 doesn't add.
- **In the host's city.** Nothing is simulated. The host sees who is stationed, with their men and supplies, in the Armies tab of that player's window, from world state (UI only).
- **A fight in the host's city (10b).** The stationed divisions enter the city as temporary divisions on the defender's side, built in the world part from the world divisions, as the assault builds its attackers; the survivors go back to the world army as counts, as the assault's do. A stationed player may view the city and command their divisions in that fight without the host's Allow (owner: "Automatic"). In an assault by a player the stationed army's player isn't at war with, the army stays out (owner: "Stays out").
- **The record ends** at one step on every PC (the world part's end check, every step outside a battle) when: the two players' agreement ends, or they go to war; the army moves, is beaten or disbands; the host sends it away or its player calls it back; either player is frozen, removed or eliminated; the capital changes hands. The chat says why. The army was never inside the city, so nothing has to leave it.
  - At war (owner: "Stays where it stands"), the army is an ordinary enemy army next to the capital. It must still besiege and wait the full period of spec §9, so stationing gives no shortcut into a city.
  - A frozen host (owner: "It ends"): every record at its capital ends, as do the frozen player's own. A frozen player's armies already go home (SOSDET `freezeHuman`); a removed player's disband.
- **Resume.** The record is world state: saved in the world save (a new versioned SOSDET section, `SOSDET_STATIONS`), carried by a world resync and a world load, and in the world hash, as the siege book is (SOSDET plan 0014). A save without the section has no stationed armies, and a game with none hashes as before. No city save and no campaign record changes.
- **Determinism.** Only lockstep world state and world commands. The end rules read stances, freezes, removals, army tiles and capital owners, which are already the same on every PC.

## Options rejected

- **B. Real soldiers inside the host's city, run by the host's PC.**
  - Who runs them: the host's PC, the only one that runs that city. The ally commands them only through it.
  - The game has no place for them. They would take army 0's division slots, which the host's own divisions use in the city and on the map. As citizens they would count in the host's population and need its food and homes; a new kind of person or a new army means patches deep in `Armies`, `Induvidual` and the population counts.
  - The crossing is new both ways: in, a transaction carrying people to another human's city; out, the people back to the world through the report, up to 30 steps late (B1).
  - Upkeep moves to the host's city, or needs a new charge back to the ally.
  - At war or when the alliance ends, the soldiers are inside an enemy city: they must be pulled out late through the report, or fight there at once with no siege.
  - Resume: the soldiers live in the host's city save and the army record in the world save, and the two must agree. A frozen host locks them in a frozen city; a removed host's city is gone with them.
  - Desync: the host's city is compared only during a view, so a fault in the crossing shows late.
- **C. Real soldiers run by the ally's PC.** The ally's PC never runs the host's city, except during a view (ADR 0005). Running two cities on one PC was rejected in ADR 0006.
- **D. Soldiers in the city only while the ally views it.** A view is optional and ends often; the city would still need them when nobody views it, which is option B.
- **E. Handing the soldiers to the host.** They would join the host's garrison as the host's citizens. That is a gift of soldiers, not stationing: the ally loses command and the army.

## Consequences

- **Spec.** §9 gets "Allied armies at a capital" (stationing, upkeep, sending away, the end rules, the fight in the city); §11's freeze and removal lines name the stationed armies. §10's watch-only rule changes with 10b.
- **Engine (SOSDET).** The station, end and supply commands (world commands 7980 to 7982), the record with its save section, world-load key and hash part, the end rules in the world part, the `STATION_SUPPLY` transaction and three notices. The numbers are provisional, for the batch lead.
- **Mod.** The Armies tab of another player's window, with Station, Call back, Send away and Supply. It is the mod's own window, so no game hook is needed. No new network message: commands travel in the engine's log.
- **Item 10a.** A stationed army is within 4 tiles of the capital, so it is asked to join an Attack against an invasion when its player is the defender's ally. A colleague's, overlord's or vassal's stationed army isn't asked: 10a's rule is allies only.
- **What 10b builds on.**
  - The record says which divisions take part. At a fight they are generated in the world part, the same on every PC, and handed to the host's city at a step (a transaction, as `INVADE` is). They need free slots in army 0, which the stationing limit keeps, and a kind of person that isn't a citizen: vanilla's `HTYPES.SOLDIER` has only the battle AI, as the invaders do (`settlement/entity/humanoid/ai/main/AIModules.java:109-117`), and is what the battle map uses for the player's side (`game/battle/state/BattleStateGenArmy.java:159`).
  - A player's assault already runs on every PC with a commanders table (SOSDET plan 0014): the stationed divisions go to their player's PC there.
  - An NPC invasion runs on the host's PC only (spec §9). The stationed player views it without asking, and their orders go to the host's PC, which applies them at a step and sends them on as its own actions, as an ally's orders in an NPC battle go to the fighter's PC (SOSDET plan 0019). That adds the view's delay to the order delay.
- **Not in v1 with this option:** soldiers walking or parading in the host's city in peacetime; the host's supply depots delivering to a stationed army by themselves.

## Spikes that need no game (reading the source)

1. **`SOLDIER` people in a city.** Do they stay out of the population, needs, homes and work counts, and does `Invasion` treat army 0 divisions made of them as defenders? Decides 10b's kind of person, and confirms what option B would cost.
2. **Where a stationed army can stand.** Pathing to an ally's capital, the 3-tile ring, and `WArmy.stop` to fortified (`WArmy.java:411-421`).
3. **What reacts to an army at a capital.** World battles by proximity (`WORLD.BATTLES().report`) with an enemy besieging the capital, the NPC AI's reading of armies near a capital, and trespass.
4. **Army 0's slots.** The value of `DIVISIONS_PER_ARMY` in the game's data. How many slots a late-game city uses (Mogus-72) needs the rig's probe, so a game.

## Needs a game

- The order delay of an ally commanding through the host's PC during a viewed NPC invasion, against the 1-second limit of spec §8.
- A three-player test: station, war, send away, freeze, resume, with every world hash equal.
- Whether a garrison seen only on the map feels like "stationed" to players.

## The owner's answers (2026-11-18)

1. **Seen in the city?** "Option A, map": a stationed army stays a world army beside the capital; no parading in v1.
2. **Who may station:** all four: allies, an overlord in its vassal player's capital, a vassal in its overlord player's capital, and colleagues.
3. **Asking:** "Alliance is enough": no prompt; the agreement that allows stationing is enough, and the host can send the army away.
4. **At war or when the agreement ends:** "Stays where it stands".
5. **A frozen host:** "It ends".
6. **Upkeep:** "Host may supply it": the army eats its own supplies, refilled from its own city as any field army's, and the host may also supply it from the host's city (see Decision, Upkeep).
7. **Someone else's war:** "Stays out".
8. **How many:** "Limit by free slots": stationing is refused once the stationed divisions plus the host's own would exceed the city's army slots.
9. **10b's view:** "Automatic": a stationed player may view and command their divisions in a fight in that city without the host's prompt.

## The owner's second version (2026-11-19, batch 13)

After playing the first version (save 94), the owner changed answers 1 and 9 and added the rest. The plan is [batch-13/10.md](../plan/batch-13/10.md), "10b second version".

1. **Seen in the city:** a stationed army's soldiers stand in the host's city for as long as it is stationed, not only in a fight; the army stays a world army beside the capital too. The engine's world part sends the army's divisions as it is stationed and every 120 game seconds after (the refresh), and the city brings its stationed divisions in line: men, equipment and training as the world army has them, so the army's own supplies keep them up. The city's dead come off the world army in its next report. The soldiers leave when the stationing ends.
2. **Command:** their player commands them while viewing the city, asked as any view. The host commands them only while their player gives command (Give command / Take command back in the Armies tab; engine command 7983, a flag in the stationing's record). No demuster.
3. **Population (point A):** the soldiers don't count in the host's population (engine hook `99-stationed`).
4. **Invasion (point B):** the stationed player gets a window, "the city is being invaded and you have troops stationed there": Watch asks the view; Decline gives the host command; no answer in 30 seconds declines (point C).
5. **Stationing from the map (item 17):** the world map's move tool says Station over a partner's capital (mod hook M15); a click marches the army beside it and stations it once it stands still there.
6. **The end:** as before (frozen, disconnected through the existing pause and the host's choice, removed or eliminated, war or agreement end), and the soldiers leave the city.

Rejected: picking the soldiers in the world part from the city's last report (the registry in the city, as in the first version, keeps the hosts' own divisions out); a refresh on the step clock (it runs on game time, so a speed change doesn't change how often).

## The owner's third version (2026-11-19 evening, batch 13): the camp

After the second save 94 session the owner added the camp. The plan is [batch-13.md](../plan/batch-13.md) section 8, approved with its hooks and the four proposed answers.

1. **In the city or in the camp:** the station book (world, saved, hashed; version 4) keeps, per stationed army, the keys of its divisions in the host's city. A key is the world division's type and index (`ADDiv.index`), never its place in the army, which a recall shifts; the city's registry keys its divisions the same way (version 2). At the stationing every division is in the city; one that joins later starts in the camp. Books before version 4 load with every division in the city.
2. **Arrival:** soldiers come in at the city's reachable entry point nearest the army on the world map (`EntryPoints.reachable(wx, wy)`); reinforcements too. The throne for a city with none.
3. **Leave for the camp:** a button above the "?" of the division popup (engine patch `95-player-orders`), active when a soldier of the division stands within 3 tiles of any active entry point. Its player presses it, or the host while given command. It is a world command (7984): the world takes the division off the city list and the host's city lets its soldiers go (transaction `STATION_CAMP`), the dead reported as at any leave. The 3 tiles are the button's check, on the presser's copy of the city, which every PC that runs it shares.
4. **Moving:** while any division is in the city, the army's move, intercept, siege and disband commands are refused, its player can't call it back, and the mod's move tool (hook M15) answers a click with a popup saying why. Once every division is in the camp the army moves and the stationing ends, as before. The host's send away, and every other end, still empties the city at once.
5. **Recall:** the conscripts window's Recall, the army window's disband and reassign leave a division in the city where it is, refused when the command applies; a division in the camp is recalled as in vanilla. The army window darkens the divisions in the city with a left arrow (engine patch `96-cmd-world`).
6. **Station again** on a stationed army sends every division with men in the camp back into the city.
7. **Not a target:** a stationed army is never the target of a field battle (`PFieldBattle.enemy`) nor starts one (`create`), never a garrison's target (`PRegAttack.create`, and the garrison tool says why), never an NPC's threat (`Defender`), and the player's move tool and the intercept command refuse it. It still helps through `Util.fill`, which stays vanilla.

Rejected: soldiers walking out by vanilla's leave plan (`PlanJoinArmy` walks to any map edge, and the owner asked for an easy exit); a division keyed by its place in the army (a recall from the camp would move the others'); checking the 3 tiles again when the command applies (the city isn't the world part's to read, and the button's check is enough for an easy exit).
