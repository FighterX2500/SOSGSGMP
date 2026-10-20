# 0003: Field battles run in deterministic lockstep

Status: Accepted (owner decision, 2026-10-21) · Card: [SOS-11](https://fighterx2500.atlassian.net/browse/SOS-11) · Supersedes [ADR 0002](0002-battle-streaming.md) for field battles; siege assaults are left open

## Context

ADR 0002 chose host-authoritative streaming because the vanilla battle engine is not deterministic: worker threads on wall-clock timers, one shared random generator, and variable time steps. After SOS-10 the owner asked to rework the battle engine for lockstep instead, if it can be made deterministic.

Spike SOS-11 ([report](../plan/spikes/determinism.md)) did that with nine patches on our own rebuild of the game. A 2,000 vs 2,000 battle ran identically in 4 game processes at different game speeds for 10 minutes (36,000 steps). On the way it found nine causes of divergence. Four of them would also differ between two PCs in ways a single PC can't show: the tile order shuffled per process, sound picks skipped at zero volume, night light flicker tied to a player setting, and AI state drawn at battle start outside the simulation.

## Decision

Field battles run in **deterministic lockstep**: every PC in the game simulates the battle, and only orders cross the network.

- **Start.** The host generates the battle with the vanilla generator and sends the battle save (about 1.9 MB for 2,000 vs 2,000) to every PC. Every PC, the host included, loads that save, seeds the simulation random stream from the host's seed, and runs deploy as step 0 inside the simulation. Nobody regenerates, so the generator itself need not be deterministic.
- **Step.** The battle advances in fixed steps of 1/60 s. The step number is the battle tick. Frames only decide how many steps run; nothing runs while paused.
- **Battle jobs.** The five battle worker jobs (orders, division centres, trajectories, AI general, status) and path finding run inside the step, in a fixed order and on a fixed beat, on the simulation thread.
- **Two random streams.** The simulation stream is used only inside the step, on the simulation thread. Everything visual or audible (rendering, tree sway, sound picks, light flicker, UI, saving) uses a separate cosmetic stream. No simulation draw may depend on a local setting (volume, light cycle, graphics).
- **Orders.** A commander's order carries the step it applies at. The host confirms that step, and every PC applies the order exactly then, never on arrival.
- **Pace.** Every PC in the game must keep up, so **the slowest PC sets the battle's pace** (owner, 2026-10-21). This matches the world speed rule in spec §4. Commanders still vote on the battle speed; the slowest PC caps it.
- **Desync detection.** Every PC hashes the battle state every few steps and sends the hash to the host.
- **Desync recovery: resync from the host** (owner, 2026-10-21). The battle pauses, the host sends its battle save at the current step, every PC loads it, and the battle continues. Every desync writes a report.
- **Siege assaults: open.** The defender's city exists only on the defender's PC, so lockstep would first send the city save to every PC. Decided after the late-game save size spike (owner, 2026-10-21). Until then ADR 0002's streaming model still describes siege assaults.

## Options rejected

- **Keep streaming (ADR 0002).** It needs no engine changes, but it means a display-only mode for every viewer (more hooks into `ARMIES` and the threads), about 1.8 Mbit/s per viewer, and the host's PC carries every battle alone. The spike showed the determinism work is bounded: nine causes, each fixed with a small change.
- **Commanders in lockstep, spectators streamed.** Spectators wouldn't slow the battle, but it means building both mechanisms.
- **Commanders set the pace; spectators fall behind and catch up.** Offered; the owner chose the simpler rule that every PC keeps up.
- **Every PC regenerates the battle from the seed.** Generation gives one of two results (cause unknown), and every PC would also pay about 17–20 s to generate. Loading the host's save avoids both.
- **Auto-resolve or report only on desync.** Auto-resolve throws away the real-time battle; report-only lets players see different battles.

## Consequences

- **Hooks.** Lockstep needs engine changes. They are listed as candidates B7–B14 in [hook-surface.md](../plan/hook-surface.md):
  - the fixed step;
  - battle jobs and path finding inside the step;
  - the split random stream, plus the cosmetic call sites (trees, audio, light);
  - a fixed tile order;
  - deploy as step 0;
  - the state hash;
  - loading a battle save for start and resync.

  How hooks reach players is still the distribution ADR (planning task 5). B2, setting the seed after the pre-battle save, becomes plain API, because peers no longer generate.
- **Determinism rules apply.** `.claude/rules/determinism.md` covers lockstep battle code again, extended with what the spike found.
- **Cost.** Running the jobs inside the step costs 7–10 ms per step on the test PC, against about 1.5 ms vanilla. A 2,000 vs 2,000 battle runs at 1× with room to spare, but the slowest PC limits high battle speeds. Running jobs less often, or in parallel with a fixed delay, is left for later.
- **Resync needs more state.** AI state that isn't saved (the AI's `flanking` value) is drawn again on load outside the step. Resync needs it saved, or redrawn on the simulation stream after loading.
- **Still to prove:**
  - two PCs with different CPUs (`StrictMath` may be needed);
  - player orders applied at a confirmed step;
  - order delay at 300 ms ping (Steam P2P spike);
  - battle content the test didn't reach.
- **Spec 0.8.** §8, §13, §15 and §16 are rewritten for lockstep in the same PR. ADR 0002's §9 wording for siege assaults stays until the siege decision.
