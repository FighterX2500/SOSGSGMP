# 0002: Field battles are simulated on one PC and streamed

Status: Accepted (owner decision, 2026-10-20) · Card: [SOS-5](https://fighterx2500.atlassian.net/browse/SOS-5)

## Context

`docs/architecture.md` proposed deterministic lockstep for field battles: every PC simulates the battle, and only orders cross the network. The hook surface map ([hook-surface.md](../plan/hook-surface.md), Q1) found that the vanilla battle engine is not deterministic:

- **Wall-clock worker threads.** Five worker threads (`game/battle/thread/BattleThreads.java`) tick on wall-clock intervals, alongside the update thread.
- **Clock-based AI general.** It reads `System.currentTimeMillis()` (`Strategos2000UpdaterOffense.java:67`).
- **Shared random numbers.** One randomly seeded global `RND` is shared by every thread.
- **Uneven time steps.** Sub-steps have variable `double` lengths.

Making the engine deterministic would mean many hooks deep in `game/battle/**` and the soldier AI, with a high risk of hidden desyncs.

## Decision

For now, field battles use **host-authoritative streaming**:

- **The host's PC simulates** every field battle between players, with the vanilla battle engine unchanged in behaviour.
- **Commanders send orders** to the host; the host applies them on arrival.
- **Other PCs display only.** The host streams the battle state (units, health, projectiles, morale) to every PC, which renders it without simulating.
- **Battle speed** is the host's simulation speed: commanders vote (spec §8), and it drops if the host's PC can't keep up.
- **Siege assaults** already use the same model, with the defender's PC simulating (architecture §Siege assault). One streaming mechanism serves both.

The owner chose this because it's easier to change later than lockstep.

## Options rejected

- **Deterministic lockstep (A).** It needs the least bandwidth and suits high ping, but it means reworking the battle engine's threads, clock use and random numbers, and any missed spot desyncs the battle. It can be revisited if streaming bandwidth turns out too high.
- **A battle participant simulates instead of the host.** It would cut latency for that commander, but the result has to come back to the host anyway, and a participant may disconnect mid-battle (spec §11). It can be revisited after the streaming spike.

## Consequences

- **Spec changes** (same PR, owner approval). The lockstep-only rules in spec §8, §13 and §15 (desync detection and recovery, replay reports, "a slow spectator slows the battle") are rewritten for streaming.
- **New questions for the battle streaming spike** (replacing the battle determinism spike in planning task 4):
  1. How large is a battle snapshot, and what bandwidth does a 2,000-per-side battle need at 10 updates per second?
  2. Is the 1-second order latency (spec §8) met at 300 ms ping, with the latency budget below?
  3. How do viewers get the same battlefield? `BattleStateGenerator` uses the global `RND`, so the map is generated differently on each PC. The options are to reseed `RND` before generation (reflection) or to send the generated map once at the start.
  4. How do viewers display a battle without simulating it? This needs a "display only" mode for `ARMIES` and the battle threads: hooks.
- **Latency budget at 300 ms ping:** about 150 ms for the order to reach the host, then up to 100 ms until the next snapshot, about 150 ms back, and about 100 ms of interpolation. That's roughly 500 ms in total, inside the 1 s limit.
- **The host's PC must handle the largest battle**, even when the host is only spectating.
- **No lockstep code in v1.** `.claude/rules/determinism.md` doesn't apply to any v1 code. It stays for a future switch to lockstep.
