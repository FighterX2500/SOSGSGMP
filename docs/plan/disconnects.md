# Disconnects and removing a player (SOS-65)

Spec 0.18 §11: Disconnects, Removing a player, Resuming; §15 Disconnects. It calls the engine's removal, SOS-66 (SOSDET PR #42, plan 0007). Status: proposed, waiting for the owner.

## What the player gets

- **A player drops** (the network, a crash, quitting): within 2 s every PC stops at the same step and shows "*Name* is gone". Nothing runs, the cities included, until the host chooses. Only the host sees the buttons; the others see "Waiting for the host".
- **Return to the last save:** the session ends on every PC, as at Save and exit but with no save. Each player goes back to the multiplayer menu, where Load Saved Lobby resumes the campaign, the dropped player included.
- **Continue without them:** the player is removed, and play goes on at once. Every region they held goes free and their armies disband; a battle they were in ends with no result. Right after the removal, the host saves the campaign (an autosave), so the removal stays in the campaign. A later resume refuses that player.
- **Resume with a missing player:** in a Load Saved Lobby, the host gets **Start without them** once every other player is in. Whoever is missing is removed at the start, the same way. A player already removed is not waited for.
- **Continue is refused** in two cases the engine can't handle, a battle waiting for the gone PC's battle save and a battle the gone player engaged that hasn't begun. Continue is then greyed out with the reason, and only Return to the last save is left.

## How it runs

1. **The host sees the drop.** `Node.Event.Disconnected` for a joined player during play no longer closes the session (today's `closedByPeer`). The host holds at `S`, its current step plus 30 (`AiLog.DELAY_STEPS`: no peer can be past it), with `EnginePlay.runUntil(S)`. It sends `PlayerGone(playerId, S)` to the others and stops relaying anything from that PC (the PC is gone, so nothing more comes).
2. **Every PC holds at `S`** and shows the window (`menu/MpGone`, built like `MpDesync`). The engine would stall on its own anyway, since the world waits for the gone PC's reports; the hold makes the stop step the same everywhere.
3. **Continue without them:**
   - The host calls `Engine.removeHuman(slot)` between steps, then sends `GoneChoice(CONTINUE)`, and every PC runs on (`runFreely`).
   - The REMOVE entry travels in the host's AI log, so every PC applies it at the same step. The mod sends no step of its own for it.
   - Once the human is retired (`Engine.retired(slot)`), the host orders a campaign save (`SaveOrder`, kind AUTO), in which that player is marked removed.
4. **Return to the last save:** the host sends `GoneChoice(RETURN)`, every PC ends its session, and the menu says the campaign can be resumed from its last save. The host gets the same message when its own connection breaks while it decides.
5. **A resume with missing players:** `Lobby.canStart` counts the campaign's players not marked removed. With some still missing, the host's Start becomes Start without them. The start order lists the missing slots, and every PC calls `Engine.removeHumanAtStart(slot)` for each at step 0, before the first step. The first save after it marks them removed.

**Engine change needed** (SOSDET, a small addition to PR #42 or its own card): `Engine.removeRefusal(slot)`, which returns null or the reason `removeHuman` would refuse. That lets the window grey out Continue without triggering the exception. `removeHuman` uses the same check.

## Data and protocol

- **Messages** (protocol 10 → 11; each carries the version like every other message): `PLAYER_GONE = 29` (player id, hold step), `GONE_CHOICE = 30` (CONTINUE or RETURN, the hold step it answers). The start order for a resume gets the list of removed slots.
- **Campaign record, format 1 → 2:** each player gets a `removed` flag (`player.i.removed=true|false`). Format 1 loads with every player not removed. `Campaign.Player` gets the field, and `SaveOrder` carries it to every PC, so every PC's records agree.
- **Exactly once:** a repeated `PlayerGone` or `GoneChoice` changes nothing, because each names its hold step. A second drop during the hold is added to the same window ("*A* and *B* are gone"); Continue then removes them all.

## Tests

- **Unit (plain Java):**
  - the hold and choice logic (a `Departures` class in `sosgsgmp.session`: who is gone, the hold step, what the host may choose);
  - the two messages' round trips and a repeat changing nothing;
  - the campaign record format 2 round trip and a format 1 load;
  - `Lobby.canStart` with missing and removed players.
- **twopc.py** (this PC as host, the laptop as joiner), with a new `--kill-joiner STEP` (the runner kills the joiner's game) and `--on-gone return|continue`:
  - D65A: Return, then `--resume` with both players: identical world hashes;
  - D65B: Continue: the host plays on to STEP + 1,800, its save marks the joiner removed, and a resume refuses them.
- **Three PCs on this PC** (twopc with `--joiners 2` over loopback, new): D65C kills one joiner, chooses Continue, and the other joiner stays identical to the host for 1,800 steps.
- **Resume without a player:** D65D resumes D65A's save with one joiner missing and Start without them.

## Rejected

- **Holding at the step where the host noticed the drop.** A peer can already be up to 30 steps past it, and the hold must be a step no PC has passed.
- **No save after Continue.** A crash before the next save would bring the removed player back on resume, against "they can't rejoin".
- **A mod-side step for the removal.** The engine's REMOVE entry already applies at one step on every PC; a second step would be a second source of truth.

## Open questions for the owner

1. The automatic campaign save right after Continue: OK? (Recommended; otherwise a removal lasts only until the next save.)
2. When Continue is refused (a battle waiting for the gone PC), only Return to the last save is offered: OK?
3. The engine's `removeRefusal`: add it to SOSDET PR #42 before you merge it, or as its own small card?

The card's line "Continue forfeits their side" is out of date: spec 0.18 says the battle ends with no result, and the engine does that.
