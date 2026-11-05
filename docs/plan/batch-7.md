# Batch 7: playtest 2026-11-04 (evening) fixes

Three cards: two from the session of 2026-11-04 23:57 to 2026-11-05 03:24 (host FighterX2500, Adam Smith, Limitless Gregg). Logs: the host's `sosgsgmp-logs\20261104-235702-host-FighterX2500.log` and the joiners' copies in `sosgsgmp-reports\84ed6e7b50de7fff`. Batch style, as batch 6: one engine branch, one mod branch, a commit per card, one shared test cycle, one playtest pack.

## Order

1. **SOS-145, the sally against an invasion** (engine, spec change). It breaks the session, so it goes first.
2. **SOS-146, vassals of a friendly player** (engine patch, spec line).
3. **SOS-147, inbound caravans missing from the world map's top-left corner** (owner, 2026-11-05; joined the batch the same day). Not investigated yet: find the panel, the cause and which PCs it hits, then fix.

## SOS-145: the sally against an invasion runs as a local battle

### Found

- Step 965709: raiders invade the host's capital. The host presses **Attack** in vanilla's invasion message, the sally that fights the invaders outside the walls with the capital's garrison and the host's armies near it.
- Step 965910: the first desync. The joiners' world has 49 more draws than the host's. From then on the host's world hash stays `3ea40829007fd66b` and its draw count stays 1,652,479, so the host's world doesn't run at all. Each resync gives the joiners the host's world, and they desync again 20 to 60 steps later. Eight resyncs, then the session ended at step 966510.
- **Cause.** The Attack button (`settlement/battle/invasion/Attack.java`) opens its battle with `BattleState.setGenerate`, straight from the UI, on the defender's PC only. Patch `10-fixed-step` skips the world part while a battle view is open, unless the battle is one the engine runs as a local battle (SOS-93, `localWorldPart`). World battles go through that path, but the sally never does. The sally's end was patched in SOS-38 (survivors and outcome through the reports), its start never was. The game's other `setGenerate` callers are the world battle (handled), the battle editor and a debug test.

### Rule (spec change, owner 2026-11-05)

Spec section 9, "Invasions by NPC armies", gains:

> Vanilla's Attack, which meets the invaders outside the city before their assault, is a real-time battle against NPC armies under section 8: it runs on the defender's PC only, the world runs on at 1×, the defender's city is frozen until it ends, and any other player can watch it. The armies near the capital that join it stand still on the world map until its result applies.

Acceptance criterion in section 15, Sieges: "A player can attack an NPC invasion of their capital before its assault; the world keeps running and stays in sync, and another player can watch the battle. [AT]"

### Design

The sally becomes a local battle, as the world-map Engage is one today.

1. **The click is a world command.** In a session, the Attack button sends a command with the invasion's world ref instead of running `new Attack(spec)`. Patch `Prompt.java`'s button, as patch 99 does with the world-battle prompts.
2. **Whether the sally can still start.** Only the defender's city knows when the invaders start their assault (`canBeAttacked`). The city's report carries the ref of the invasion that can still be attacked, so every PC reads the same answer in the world part. If the command arrives too late, it is skipped on every PC and the button greys out, as vanilla's does.
3. **Engage, every PC, in the world part.** `Session.localEngage` as for a field battle. The battle's armies are the defender's armies that vanilla's `Attack.armies()` collects near the capital, computed in the world part as the defender. They wait in place until the result applies. Views of the defender's city end, as for any local battle.
4. **Generation, defender's PC, at the freeze step.** It runs vanilla's `Attack` constructor on the battle's own stream (`localGenerate`). The enemy comes from the city's `InvasionSpec`, which only this PC has, and the battle is generated only here.
5. **Exit.** It is already patched (`localExit`): the result goes out as a command.
6. **After the battle.** Vanilla's `Attack.afterExit` is split in two:
   - **World half, every PC**, in `localAfter`: the armies' survivors, the garrison's (through the existing `garrisonResolve` queue to the defender's city), `AD.stats().report`, and the invasion listeners' victory with the world ref.
   - **City half, defender's PC only**, in its city tail once its city is back: loot and captives (`MVictory2`), `canBeAttacked`, the counts. On a defeat, the invaders' survivors rejoin the invasion and the spoils are lost.

   The SOS-38 report path for the sally's end is dropped, as the world half now applies at the result step.
7. **Safety net.** If a PC's step skips the world part for a battle view the engine doesn't know, the engine logs it once with the view's class. A future gap like this one then shows in the log at once instead of as a desync loop.

### Checks

- **Unit:** splitting the result between the world half and the city half, and the report field round trip.
- **Rig smoke (two humans):** `wraidcapital 200`, then a new rig action `wsally` presses Attack. No desync, the world keeps running on the defender's PC, the result applies once, the defender's city resumes where it stopped.
- **Rig smoke:** `wsally` after the invaders begin their assault is skipped on both PCs.
- **Rig smoke (three humans):** the third player watches the sally.
- **Playtest:** a raider invasion, Attack, with someone watching.

### Open points to settle during the work

- The defender's divisions inside the city (raised from the garrison) and the city's own invasion fight are unchanged. Only the sally is new.
- With an ally's army near the capital, vanilla's sally takes only the defender's own armies. It stays that way: no ally prompt.

## SOS-146: a player's NPC vassal isn't hostile to that player's friends

### Found

Vanilla's `War.updateAll` chooses an NPC from `DipWarPlayer.potential()` and sends **Demand**, the money request (`WarMessages.warn`). If no deal comes of it, the same NPC goes on to war, poisoned opinions, a region's loyalty cut, or bad teachings. `validEnemy` doesn't know the NPC is the vassal of a player who has a trade, colleague or alliance stance with this player.

### Rule (spec line, owner 2026-11-05)

Spec section 6, after "Agreements reach a player's vassals":

> **A vassal isn't hostile to its overlord's friends.** An NPC vassal of a player never demands tribute from, declares war on, or works against a player who is its overlord's trade partner, colleague or ally: no demands, wars, poisoned opinions, loyalty cuts or bad teachings.

### Design

- In the per-human `DipWarPlayer` (patch `98-world-fixes`), `validEnemy` and `validProxy` return false for an NPC whose overlord is another player with a stance above neutral (trade, colleague, ally) with this slice's human. The check goes in a small engine hook.
- A warner already chosen drops out by vanilla's own check (`!w.potential(warner)`).
- World state only, in the world part, so every PC decides alike.

### Checks

- **Unit:** the hook's rule over the stances (war, neutral, trade, colleague, ally, no human overlord).
- **Rig smoke:** human A trades with human B; B's NPC vassal, with low trust of A, isn't in A's potential list (a new rig probe `wwarpotential`), and a forced warn picks no one.

## As built (2026-11-05, before the tests)

- **SOS-146:** `Hooks.friendOfOverlord` (the rule in `StanceBook.friendOfOverlord`, unit-tested) in `DipWarPlayer.validEnemy` and `validProxy` (patch 98). No new probe: the existing `wwar` lists each human's potential enemies. New rig tooling `wnpcvassal <faction>` makes an NPC this human's vassal.
- **SOS-145**, simpler than the design above in two places:
  - **No report field.** The command checks only what the world knows: no local battle, view or battle between humans for the defender. Whether the invasion can still be attacked is checked by the defender's PC at the freeze step. If the invaders began their assault since the click, that PC sends a result with no battle, every PC ends the local battle, and the defender sees a notice. The spec says so.
  - **No split of the after-battle.** The SOS-38 patch already sends `Attack.afterExit`'s world effects (army survivors, the battle stats, the invasion listeners) through the city's report. So the result goes whole to the defender's city as a transaction (`SALLY_RESULT`), where vanilla's after-battle runs. The army survivors travel as a count and mean experience, a new report input (`DIV_RESOLVE_COUNT`), as a local battle's result carries no people.
  - Patch 99 gains `Prompt.java` (the click) and `Attack.sosdetArmies`, `sosdetGenerate`, `sosdetAfter`, called through `GameSally`. New rig action `wsally`.
  - **The safety-net log** isn't built: the engine has no log of its own, so it needs a mod change too. Left for later.

## Tests and findings (2026-11-05)

- **The sally's command id** collided with the AI log's `LOCAL_ORDER` (7966): the game stopped at the menu. It is 7968 now, and a unit test keeps it apart from its neighbours.
- **Rig B7A** (2 humans, Mogus-72, local battles on, the AI on the host) crashed one second after the sally: `already in use by StepMoveToThrone`. The rig's own invasion was assaulting the host's city, so the host's AI general ran on vanilla's thread, and once a local battle runs the engine runs the general in the step as well; both used one flooder. This came with SOS-93 and hits any local battle the host starts while its city is under assault. Fixed under SOS-145: as a local battle begins on the host, the battle threads are paused and started again, so the general's thread stays off while it runs.
- **Rig B7A2** (the same, with the fix; Retreat 1,500 steps after Start): the sally's battle, the retreat and the city's return; copies identical to step 7,000, no desync, no crash file.
- **Rig B7B** (a trade treaty between the humans, the sally fought on): identical to step 9,000. The battle didn't end within the run.
- **SOS-147 found:** patch 10 refused `GAME.Cache` refreshes outside the step (SOS-32). Only UI code uses `GAME.Cache` (seven caches under `view/`), and the UI reads between steps, so each kept its first value: the inbound caravan list counted 0 with 22 caravans on their way to the capital (probe `wcaravans`, B7A2 and B7B). The top panel's two big buttons and four notifications (Trespassing, sieges, affordable region buildings, region problems) were frozen the same way. Fixed by giving `GAME.Cache` vanilla's check back.
- **SOS-146** has no rig check yet: neither human had any potential enemies in `wwar` during these runs, so a vassal's absence from the list proves nothing. It rests on the unit test and the reasoning in the plan.

- **Rig B7C** (the SOS-147 fix): the inbound list counted 22, the caravans to human 0's capital, on human 0's copy, and 0 on human 1's, which had none on their way; identical to step 9,000 with every UI cache refreshing.
- **Rigs B7D and B7E** (`--no-invade`, a 60-man raider, the sally fought on; B7E gave the player's divisions orders): identical to step 8,000, no crash file. Neither battle ended within the run, so the victory and its after-battle (`SALLY_RESULT`, spoils) are seen in no run yet: a hand test in a game.
- Mod `gradlew.bat build` against the batch 7 engine: passes.
