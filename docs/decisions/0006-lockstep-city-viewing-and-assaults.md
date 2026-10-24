# 0006: City viewing and siege assaults run in lockstep

Status: Accepted (owner decision, 2026-10-24); amended the same day after the spec review ([SOS-44](https://fighterx2500.atlassian.net/browse/SOS-44), see the end) · Card: [SOS-43](https://fighterx2500.atlassian.net/browse/SOS-43) · Supersedes [ADR 0002](0002-battle-streaming.md) for siege assaults; amends the city viewing line of [ADR 0005](0005-world-lockstep-and-city-world-link.md)

## Context

- **What the docs said.** Spec 0.10 §10: viewing another player's city shows a snapshot the viewer can refresh; [architecture.md](../architecture.md) opened it in a separate read-only game process. Spec §9 and §16: the defender's PC runs a siege assault and streams it to every other PC (ADR 0002), until the late-game save size spike decides between streaming and lockstep ([ADR 0003](0003-battle-lockstep.md)).
- **The owner's direction on 2026-10-22:** city viewing becomes lockstep. The viewer's own city freezes, and the viewer loads the watched city and runs it in step with its owner's actions. Running both cities on one PC would be too expensive. It was not written into the spec then, and ADR 0005 kept the snapshot.
- **What the engine has shown since.**
  - Spike SOS-23: a city assault (Mogus-72, 1,000 attackers) stayed identical in 4 game processes for 10 minutes and met the owner's timing rule.
  - SOSDET co-op mode (V0.1 onward): every PC runs the same city and world in lockstep from the players' commands, and stays identical across two PCs (rig run P2: 4 copies on the dev PC and a laptop, 60,000 steps).
  - SOSDET own-city mode (SOS-38): each PC runs its own city beside the lockstep world; a city part reads the world only through transactions that every PC computes.

  So a whole city can run identically on two PCs, given its save at a step, the world, and its owner's actions with their steps.

## Decision

**City viewing (spec §10).**
- A viewer's own city freezes while they view another player's city: it is not simulated, and they can't act in it. It resumes where it stopped when the view closes.
- The viewer's PC loads the watched city from its owner's save at a step and runs it in lockstep: the same world part every PC already runs, plus the watched city's part instead of its own.
- The owner's actions in their city still apply at once, with zero latency (spec §1). Each one is recorded with the step it applied at and sent to the viewers, whose copy runs far enough behind the owner that every action arrives before its step.
- Nothing in a viewed city can be changed by the viewer.
- The viewer's copy is compared with the owner's city by state hash. On a mismatch the viewer reloads the owner's city save.
- This replaces the snapshot view, the "refresh" action, the later ghost sim, and the separate read-only process.

**Siege assaults (spec §9).**
- An assault runs in deterministic lockstep on every PC, like a field battle (ADR 0003).
- At the assault's start, the defender's PC sends its city save at the start step; every PC, the host and the defender included, loads it.
- Both commanders' orders carry the step they apply at. The battle AI runs on the host, and its log is applied at the stamped step on every PC.
- Desyncs: every PC hashes the assault state; on a mismatch every PC reloads the host's assault state, as in field battles.
- The world and every city stay paused during the assault (spec §4). Players' own cities don't run, so no PC runs two cities at once.

## Options rejected

- **Keep snapshots for viewing.** Simple, but a snapshot is a still picture, and the defender's city would still need a second mechanism for the assault. The owner chose one mechanism, lockstep, for viewing and assaults.
- **Snapshots in v1, lockstep later.** Offered; the owner chose lockstep now.
- **Run both cities on the viewer's PC.** The viewer keeps playing while watching, but two cities on one PC are too expensive (owner, 2026-10-22).
- **Keep streaming assaults (ADR 0002).** It needs a display-only mode for every viewer (hooks into `ARMIES` and the battle threads), about 1.8 Mbit/s per viewer, and the defender's PC carries the assault alone. Lockstep reuses the field battle mechanism the engine already proves.
- **Wait for the save size spike before deciding.** The save size now decides how long opening a view or starting an assault takes, not which mechanism is used.

## Consequences

- **Spec 0.11.** §9 (assault), §10 (viewing), §15 (siege and city viewing criteria) and §16 (the assault question closes; the viewing questions open) change in the same PR.
- **Architecture.** The city row, the siege assault row and section, and the city viewing bullet in [architecture.md](../architecture.md) change in the same PR.
- **The save size and save time** still matter (measured in playtests since the amendment, not a spike): the save is sent when a view opens and when an assault starts, so its size and save time set how long both take (spec §15: the owner's stutter stays under 1 s; since spec 0.12, a view catches up within 1 minute, the save, download and load not counted, see the amendment).
- **The "two game processes on one PC" spike** (CLAUDE.md planning task 4) is no longer needed for viewing.
- **Engine work (SOSDET), not yet planned:**
  - record the owner's city actions with the step they applied at, and apply them on a viewer's copy at that step (own-city actions are not lockstep commands today, ADR 0005);
  - load a city save into a PC that runs its own city, freeze and resume that own city;
  - a city hash for viewing;
  - an assault started from a player's city save, with the host's AI log, as SOS-23 ran it on one PC.
- **Open (spec §16):** whether a viewer's frozen city holds up the world clock, and how a viewer catches up with the owner when a view opens. Both settled by the amendment below; only its numbers stay open.

## Amendment (2026-10-24, owner, spec review)

Spec 0.12 settles how viewing runs ([SOS-44](https://fighterx2500.atlassian.net/browse/SOS-44)):

- **The world runs on while a city is frozen.** The viewer's city loses that time. The world goes on reading its last city report, and the world's transactions for that city wait and are applied when the view closes. Rejected: holding the world for everyone while anyone views, which would make viewing a pause button for one player.
- **Watch only.** The viewer can only watch and chat. Their armies and regions are world state, so they keep running on their last orders. A battle, or a prompt that needs the viewer's answer, ends the view. Rejected: world-map orders while viewing (simpler rule: nothing goes in or out of the viewer's empire's controls), and freezing the viewer's armies like a disconnected player's (viewing would become a way to make armies safe).
- **Catching up.** After loading the owner's save, the viewer's PC runs the city fast until it is a fixed delay behind the owner. Target: a 5-second gap closes in about 2 seconds.
- **A slow viewer slows everyone, down to 1×.** If the viewer can't meet that target, or falls behind later, the game speed drops for everyone, as for any slow PC (spec §4), never below 1×. If the viewer still hasn't caught up 1 minute after catching up started (the owner's save, the download and the load don't count), or can't run the city at 1×, the view ends and the speed returns to the host's setting. Rejected: letting the view lag without limit (the viewer would have to store ever more owner actions and transactions), and restarting the view from a new save (each restart costs the owner another save stutter).
- **Still open:** the catch-up target and how far behind a view may fall before the speed drops are first guesses, to be confirmed in playtests, not a separate spike (owner, 2026-10-24).
