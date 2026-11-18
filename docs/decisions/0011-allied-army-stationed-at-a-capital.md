# 0011: An allied player's army stationed at another player's capital

Status: Proposed, for the owner's approval (2026-11-18) · Batch 13, item 10c (SOS-233); item 10b (SOS-234) builds on it · Builds on [ADR 0005](0005-world-lockstep-and-city-world-link.md) (own cities), [ADR 0006](0006-lockstep-city-viewing-and-assaults.md) (views and assaults), [ADR 0009](0009-several-humans-in-a-battle.md) (allies in battles)

## Context

- **The request (owner, 2026-11-18).** An ally's army enters another player's city and stays there, with no raid, for v1. Today soldiers never cross into another player's city. Item 10b follows: the stationed soldiers fight raiders, or an attacking player, in that city, and the ally commands them during a view.
- **A city has two armies and no third side.** `Armies.ARMIES = 2` (`game/battle/Armies.java:33`): `player()` is army 0, `enemy()` army 1 (`:216-222`). Army 0 is always `FACTIONS.player()` (`game/battle/Army.java:100-104`). A person who isn't hostile belongs to army 0 (`settlement/stats/Induvidual.java:235-236`). Vanilla has no friendly soldier of another faction inside a city.
- **A player's soldiers are city people.** In the city they are citizens (`SUBJECT`) in one of army 0's division slots (`Config.DIVISIONS_PER_ARMY`, 1 to 126). Sent out, they become a world division (`WDivStored`) that keeps each soldier as a stored person (`world/army/WDivStored.java:43`) and uses its city division's slot and settings (`:82-84`). Coming home, a soldier is made a citizen again at the city's entry and put back in his slot (`world/army/WDivStoredAll.java:117-154`).
- **Under own cities, only the owner's PC runs a city** (ADR 0005). Soldiers cross the city boundary only to and from their own city: a soldier coming home is a transaction only his own city applies (SOSDET `TxnKind.SOLDIER_RETURNS`, `followerApplies` false, `TxnKind.java:16, 53-55`); a soldier leaving is a city-to-world input, the costliest kind in SOSDET's link scan (B1). Every human's stored soldiers are world state, the same on every PC, with their division settings read from the owner's city report (SOSDET patch `14-report-reads`, `WDivStored`; section `SOSDET_CITY_DIVS`, `GameCityDivs`).
- **The one case of another player's soldiers in a city is the assault.** At Storm, in the world part, each joining army's divisions are turned into an invasion (`WDIV.generate`, SOSDET patch `15-humans`, `sosdetAssault`). Every PC runs the defender's city through a view, the invaders fight there as army 1, and the survivors go back to the world armies as counts, each division keeping the same share (SOSDET `GameAssault.java:121-169`). Nothing of the attacker lives in the city before or after.
- **Upkeep in vanilla.** A player's world army eats its own supplies every day (`world/army/ADSupplies.java:154-171`). They are taken from its city's stockpiles when a division is sent out (`WDivStoredAll.java:75-90`, SOSDET's two-phase `ARMY_SUPPLY_DIV`); a surplus goes home by caravan (`world/entity/army/WArmy.java:214-243`). City divisions cost no money (`WDIV.costPerMan` is 0, `world/army/WDIV.java:43-45`); only mercenaries do (`ADSupplies.java:97-98`).
- **Armies near a capital already defend it, outside the walls.** Vanilla's Attack against invaders takes the player's own armies within 4 tiles of the capital (`settlement/battle/invasion/Attack.java:208-243`, filter `:227`). Item 10a adds allies there.

## Decision

**Proposed: a stationed army stays a world army (option A).** Stationing is world state, in lockstep on every PC. Nothing of the ally lives in the host's city until a fight there (10b).

- **Stationing.** An ally's army fortified within 3 tiles of the host's capital centre (the allied-attacker distance of spec §9) can be ordered to station there. It is a world command, applied at one step on every PC. The engine keeps a record per army: the army, the host, the step. Any move order ends it.
- **Whose PC.** None runs anything new. The army is world state like any other.
- **Upkeep.** The ally's, as for any army in the field: it eats its own supplies, refilled from the ally's city as today. The host pays nothing and feeds no one.
- **Orders.** The ally's, on the world map. The host can send a stationed army away; that ends the record, and the army stays where it stands.
- **In the host's city.** Nothing is simulated. The host sees who is stationed, and how many men, from world state (UI only).
- **A fight in the host's city (10b).** The stationed divisions enter the city as temporary divisions on the defender's side, built in the world part from the world divisions, as the assault builds its attackers. The survivors go back to the world army as counts, as the assault's do. 10b decides how (see Consequences).
- **The record ends** at one step on every PC when: the two players' alliance ends, or they go to war; the ally's army moves, is beaten or disbands; the host sends it away; either player is frozen, removed or eliminated; the capital changes hands. The army was never inside the city, so nothing has to leave it.
  - At war, the army is an ordinary enemy army next to the capital. It must still besiege and wait the full period of spec §9, so stationing gives no shortcut into a city.
  - A frozen ally's armies already go home (SOSDET `freezeHuman`); a removed ally's disband.
- **Resume.** The record is world state: saved in the world save (a new versioned SOSDET section), carried by a world resync and in the world hash, as the siege book is (SOSDET plan 0014). A save without the section has no stationed armies. No city save and no campaign record changes.
- **Determinism.** Only lockstep world state and world commands. The end rules read stances, freezes and removals, which are already the same on every PC.

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

- **Spec.** §9 gets "Allied armies at a capital" (stationing, upkeep, sending away, the end rules), worded for the owner's approval first; §11's freeze and removal lines name the stationed army. §10's watch-only rule changes with 10b, not here.
- **Engine (SOSDET).** The station and send-away commands (world commands, 7000s), the record with its save section, world-load key and hash part (the world hash format goes up by one), and the end rules. Numbers come from the batch lead.
- **Mod.** The Station and Send away buttons and the host's list. Each needs a hook approved by the owner and listed in `docs/hooks.md`, in a game file SOSDET doesn't patch (ADR 0010). No new network message: commands travel in the engine's log.
- **Item 10a.** A stationed army is within 4 tiles of the capital, so it is asked to join the sally like any allied army.
- **What 10b builds on.**
  - The record says which divisions take part. At a fight they are generated in the world part, the same on every PC, and handed to the host's city at a step (a transaction, as `INVADE` is). They need free slots in army 0 and a kind of person that isn't a citizen: vanilla's `HTYPES.SOLDIER` has only the battle AI, as the invaders do (`settlement/entity/humanoid/ai/main/AIModules.java:109-117`), and is what the battle map uses for the player's side (`game/battle/state/BattleStateGenArmy.java:159`).
  - A player's assault already runs on every PC with a commanders table (SOSDET plan 0014): the stationed divisions go to the ally's PC there.
  - An NPC invasion runs on the host's PC only (spec §9). The ally views it, and the ally's orders go to the host's PC, which applies them at a step and sends them on as its own actions, as an ally's orders in an NPC battle go to the fighter's PC (SOSDET plan 0019). That adds the view's delay to the order delay.
- **Not in v1 with this option:** soldiers walking or parading in the host's city in peacetime.

## Spikes that need no game (reading the source)

1. **`SOLDIER` people in a city.** Do they stay out of the population, needs, homes and work counts, and does `Invasion` treat army 0 divisions made of them as defenders? Decides 10b's kind of person, and confirms what option B would cost.
2. **Where a stationed army can stand.** Pathing to an ally's capital, the 3-tile ring, and `WArmy.stop` to fortified (`WArmy.java:411-421`).
3. **What reacts to an army at a capital.** World battles by proximity (`WORLD.BATTLES().report`) with an enemy besieging the capital, the NPC AI's reading of armies near a capital, and trespass.
4. **Army 0's slots.** The value of `DIVISIONS_PER_ARMY` in the game's data. How many slots a late-game city uses (Mogus-72) needs the rig's probe, so a game.

## Needs a game

- The order delay of an ally commanding through the host's PC during a viewed NPC invasion, against the 1-second limit of spec §8.
- A three-player test: station, war, send away, freeze, resume, with every world hash equal.
- Whether a garrison seen only on the map feels like "stationed" to players.

## Open questions for the owner

1. **Seen in the city?** Is a garrison on the map enough for v1 (option A), or must the ally's soldiers be seen in the host's city in peacetime ("parading")? The second is option B, with its cost.
2. **Who may station:** allies only, or also an overlord in its vassal's capital, a vassal in its overlord's, and colleagues?
3. **Asking:** does the host allow each stationing, as a view, or is the alliance enough, with Send away?
4. **At war or when the alliance ends:** the army stays where it stands (proposed), or is moved home or to the nearest region of its own?
5. **A frozen host:** spec §11 says relations become neutral but treaties stay. Does the stationing end (proposed), or stay while the capital can't be attacked anyway?
6. **Upkeep:** the ally pays (proposed), or may the host supply a stationed army from its own city?
7. **Someone else's war:** when the host is besieged or stormed by a player the ally isn't at war with, does the stationed army stay out (proposed), or join the defence anyway?
8. **How many:** a limit on stationed men or divisions? In 10b they share army 0's slots with the host's own divisions.
9. **10b's view:** does a stationed ally get the host's Allow for a view of an invasion without asking, so it can command?
