---
paths:
  - "mod/**/lockstep/**"
---

# Determinism rules (lockstep code)

Field battles run in lockstep ([ADR 0003](../../docs/decisions/0003-battle-lockstep.md)). Code that runs in lockstep must produce bit-identical results on every player's PC from the same start state and the same orders. Adjust the `paths` above when the real package layout is decided; the battle hooks in the game's own code (hook-surface B7–B14) follow these rules too. Spike SOS-11 ([report](../../docs/plan/spikes/determinism.md)) found each rule marked *SOS-11* breaking in practice.

## Time and threads

- Advance the simulation only with the fixed step (1/60 s). Never use frame time, `System.currentTimeMillis()` or `System.nanoTime()` in simulation logic.
- No multithreaded updates of battle state. Battle jobs and path finding run inside the step, in a fixed order. If work is ever split across threads, inputs are a snapshot and results are applied at a fixed later step.
- A switch that says "the job runs inside the step" must be true only while the job runs. *SOS-11:* a flag that was always true made `BattleOrders.init` restart the real thread.

## Random numbers

- Simulation code draws only from the simulation stream, only inside the step, and only on the simulation thread. No `Math.random()`, `ThreadLocalRandom`, or `new Random()` without the battle seed.
- Anything visual or audible draws from the cosmetic stream, even when it is called from inside the step: rendering, animation such as tree sway, sound picks and pitch, light flicker, UI. *SOS-11:* all three of those examples drew from the simulation stream.
- Nothing that runs outside the step may draw from the simulation stream: class initializers, field initializers, constructors run on load, UI actions. *SOS-11:* a tile order was shuffled at class load, and the AI's `flanking` value was drawn when the AI was built.
- Battle start (deploy and AI start-up) is step 0 and runs inside the simulation context. *SOS-11:* the AI drew its start-up values outside it.

## State

- Every peer starts from the host's battle save, never from its own generation. After a load, any state the save doesn't hold must be set deterministically before step 1.
- Never let iteration order depend on identity: no iterating `HashMap`/`HashSet` keyed by objects without a stable `hashCode`, no `IdentityHashMap`, no `System.identityHashCode`. Iterate arrays, lists or maps sorted by a stable ID.
- Use `StrictMath` instead of `Math` for `sin`, `cos`, `tan`, `atan2`, `exp`, `log`, `pow`, `sqrt`-dependent chains, and similar functions (not yet tested across CPUs).
- No simulation behavior, and no simulation random draw, may depend on local settings: graphics quality, resolution, language, camera position, sound volume, the light-cycle setting, game speed. *SOS-11:* sound picks were skipped at zero volume and throttled by game speed, and light flicker ran only with the light cycle on.
- Anything that feeds the state hash must be part of the simulated state, not a render-side copy.

## Orders and testing

- Every peer applies orders only at the step the host confirmed, never on receipt.
- When changing lockstep code, run the determinism test before declaring the change done. Use several game processes at different game speeds, the same battle save, seed and orders, and compare state hashes every step (`spikes/determinism/matrix.sh` until CI has its own).
