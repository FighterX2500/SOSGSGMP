# 0006: City viewing and siege assaults run in lockstep

Status: Accepted (owner decision, 2026-10-24) · Card: [SOS-43](https://fighterx2500.atlassian.net/browse/SOS-43) · Supersedes [ADR 0002](0002-battle-streaming.md) for siege assaults; amends the city viewing line of [ADR 0005](0005-world-lockstep-and-city-world-link.md)

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
- **The late-game save size spike** still matters: the save is sent when a view opens and when an assault starts, so its size and save time set how long both take (spec §15: a view opens within 30 s; the owner's stutter stays under 1 s).
- **The "two game processes on one PC" spike** (CLAUDE.md planning task 4) is no longer needed for viewing.
- **Engine work (SOSDET), not yet planned:**
  - record the owner's city actions with the step they applied at, and apply them on a viewer's copy at that step (own-city actions are not lockstep commands today, ADR 0005);
  - load a city save into a PC that runs its own city, freeze and resume that own city;
  - a city hash for viewing;
  - an assault started from a player's city save, with the host's AI log, as SOS-23 ran it on one PC.
- **Open (spec §16):** whether a viewer's frozen city holds up the world clock, and how a viewer catches up with the owner when a view opens.
