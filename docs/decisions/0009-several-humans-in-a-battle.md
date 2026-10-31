# 0009: Several humans in one battle

Status: Accepted (owner, 2026-10-31) · Cards: [SOS-116](https://fighterx2500.atlassian.net/browse/SOS-116), [SOS-117](https://fighterx2500.atlassian.net/browse/SOS-117), [SOS-118](https://fighterx2500.atlassian.net/browse/SOS-118), [SOS-119](https://fighterx2500.atlassian.net/browse/SOS-119)

## Context

- **What the spec asked for.** Section 8 has allies joining a battle, side by side and each commanding their own army, but it left most of the rules open.
- **What the engine has.** A battle holds two humans. `OwnBattle` has `human` and `other`, `Session.commandBattle` gives all of army 0's divisions to one PC and all of army 1's to one other PC, and `FACTIONS.otherFaction()` gives army 1 one human's bonuses from one city report (`OwnBattle.otherReport`).
- **What vanilla does.** It has two armies in a battle. `Util` pulls every allied army within `WArmy.reinforceTiles` (4) of the battle into one of the two sides, at once and without asking.
- **What that means today, from reading the code, not from a run.** A third human's allied army in range goes into the battle, under the side's lead PC and with that human's bonuses. Its own player has no control of it.
- **A local battle (SOS-93)** runs on one PC, apart from the world. A watcher's PC (SOS-109) follows it from a tape of the fighter's steps and orders, with its own city frozen.

## Decision

**A battle's sides hold humans, not one human each.** Vanilla's two armies stay. Every division in a side gets an owner: a human or the AI. Commands, bonuses, retreat and results read the owner.

**Who joins (owner, 2026-10-31).**
- Vanilla's range of 4 tiles along the map, for an army allied with one side and at war with the other.
- An allied player is asked with Join and Stay out, 30 seconds, and no answer stays out.
- Only as the battle starts.
- An ally already fighting another battle isn't asked, and the battle doesn't wait.

**Own bonuses.** Every division fights with its owner's bonuses, because they depend on technology. The city report carries them, so it's the same on every PC. A battle keeps a report for each of its humans.

**Slots.** A side keeps vanilla's number of divisions. They're split evenly between its humans, and unused slots go to the others.

**Retreat (owner, 2026-10-31).** Each commander retreats only their own army, and the rest fight on. The army leaves at once.

The loss is new, and the owner left its size to this record. Vanilla's loss for a side is 40% of the enemy's men, spread over the side. An army pays its share by its men among the side's men still fighting, and half of that while an ally stays behind, who is covering it. The last army of a side pays its share in full. Divisions in melee still lose 75%, because they're in contact.

For example, in 100 + 100 men against 200, the side's loss is 80, the first army's share is 40, and it loses 20.

**Results.** Each winning player gets their own window, whichever of the two armies won. Captives and spoils are counted from the battle, split by the men brought, and what doesn't divide evenly goes to the player who brought the most. Only armies still in the battle at its end share. This also ends the night run's rule that a beaten sally gives nothing.

**Speed.** Each commander's speed button is a vote, and more than half of those still in the battle must pick the same speed. After a battle the world goes back to its speed from before.

**An ally in an NPC battle (owner, 2026-10-31: "try as separate").** The battle stays apart from the world and runs on the two players' PCs only. The ally's PC follows the fighter's tape, as a watcher's does, and the ally's orders go to the fighter's PC, which puts them on the tape. The speed vote applies, either can pause, and the rules above for retreat and results apply.

**Still open.** What happens to the divisions of a commander who drops out of a battle with allies, and to a slow ally in a joined NPC battle (spec section 16).

## Options rejected

- **Pulling allies in as vanilla does:** a player who is away, or busy in their city, would lose an army.
- **Joining a running battle:** vanilla has no such path, and it would need a load on every PC in the middle of a fight.
- **The side's lead's bonuses for everyone:** simple, but technology would stop counting for the ally, and section 15 asks for each player's own.
- **One retreat for the whole side, by vote:** the owner chose separate retreats, so one player can leave without a second one agreeing.
- **Vanilla's full loss for an army leaving alone:** with 100 men against 200 it would cost 80%, worse than the whole side retreating together, and leaving an ally behind should cost less, not more.
- **An ally's join turning an NPC battle into one on every PC:** it would stop the world and hold up every other player for a battle against an NPC.
