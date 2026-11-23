# The campaign save before every battle (SOS-67)

Spec §11 (Save before battles): before every real-time battle or siege assault starts, the campaign is saved the same way as a host save. With Return to the last save (spec 0.17), a drop during a battle then costs only that battle. Sieges of player cities (§9) aren't built yet, so this card covers field battles on the battle map (SOSDET plan 0006). Approved by the owner on 2026-10-26; the engine half is SOSDET SOS-70.

## Why the mod can't do it alone

- **When a battle starts.** A battle starts at Engage, when the world-battle prompt's decision applies at step N (SOSDET `WorldBattles.applyDecide`, then `Hooks.ownCityEngage`). Engage is always a player's command, applied 30 steps after the click. A prompt nobody answers resolves itself after 30 s, with no battle on the battle map (SOS-45).
- **When a save can be ordered.** A campaign save (SOS-64) is ordered for the host's step + 60, because a peer can run up to 30 steps past the host. The order has to reach every PC before it passes the save step.
- **The result.** The mod learns of nothing 60 steps ahead, so it can't order the save in time. Saving after Engage doesn't work either: the vanilla save would hold armies set for a battle that only the engine knows about (`OwnBattle` isn't in the game's save), and resuming it would break.

## As built

**Engine (SOSDET SOS-70): a hold before the battle.**
1. With `Engine.battleHold(true)`, every PC looks at its log before it runs a step. If an Engage command is due at step N, it holds before N. A PC may run a step only once it has the host's entries for it, so every PC holds at the same step, N - 1.
2. `Engine.battleAhead()` gives N while the PC holds there. `Engine.battleGo()` lets step N run, and the battle begins as before: the battle PC's battle save, `battleIn`, the battle.
3. Nothing in the step changes: the command applies at N as before, so the world stays identical.

**Mod (SOS-67): the save at the hold.**
1. **Host:** when `battleAhead()` appears and no save is open, it orders the campaign save for its current step, N - 1. Every PC is held there, so the order can't come too late.
2. **Every PC:** saves there as at a host save, an Auto save that rotates like the others. It reports, and once the host's SaveDone arrives it calls `battleGo()`.
3. **A save already open** when the hold begins, ordered for a later step, is taken at the hold instead: every PC is held at the same step, so it's consistent, and it counts as the battle's save.
4. **A failed save:** the battle still begins, and the panel shows which PC failed. An autosave that comes due during the hold or the battle waits.
5. `EnginePlay.start` turns the hold on for every session.

**On resume:** the click applies 30 steps after it's given, so the save is from while the prompt is still open. Resuming it opens the prompt again, and the battle can be fought again (rig H70B).

## Rejected

- **Postponing vanilla's engage until after the save** (the first version of this plan): it moves the battle's start after the rest of step N's world part, and it isn't needed once the hold can read the log ahead.
- **Saving when the prompt opens**, before any decision: the save order still needs 60 steps of lead, and a click 30 steps after the prompt opens would start the battle first.
- **Saving at the existing battle-save hold**, after Engage: the vanilla save holds armies set for a battle that isn't in the save (above).

## Tests

- **Engine, H70A:** rig `--humans 2 --engage 900 --battle-hold` (plan 0006's OB2, Mogus-72, `--steps 13500`): both copies held at 11,864 before the Engage at 11,865, saved, and went on. The battle, Retreat and Accept followed, identical to 13,500.
- **Engine, H70B, the resume check:** `--humans-saves` with each copy's save from H70A: the prompt opened again at step 1 and the battle ran again, identical to 1,500.
- **Mod:** 122 unit and loopback tests pass. The save at the hold runs in the game's menu classes, which can't run outside the game.
- **Mod, two PCs (this PC and the laptop):** with the hold on and no battle, normal saves still work. S67A (`twopc --steps 1200 --save 600`): saved on both at 600, identical to 1,200. S67B (`--resume --steps 600`): both loaded the same world hash, identical to 600. A two-PC run with a battle needs armies, and a new MP world has none; that waits for the owner's playtest.

## Later idea (owner, 2026-10-26)

Not for this card. It would change spec §4 (the world stands still during a battle), so it needs its own decision:

- Only the battle's combatants run the battle, in a time of its own, while the world outside goes on. The world gets only the battle's result.
- Anyone who wants to watch asks before the engagement, or a notification offers to watch or not.
- Meanwhile the combatants' city reports are frozen, and the world goes on.

## Gaps

- Only field battles on the battle map. Siege assaults follow when spec §9 is built.
- Each battle adds a save of about 3 s on every PC before it starts.
- The mod's save at the hold is checked by reasoning and the rig's equivalent, not yet in a two-PC battle.
