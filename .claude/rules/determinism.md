---
paths:
  - "mod/**/lockstep/**"
---

# Determinism rules (lockstep code)

Code that runs in lockstep must produce bit-identical results on every player's PC from the same start state and the same orders. Adjust the `paths` above when the real package layout is decided.

- Advance the simulation only with a fixed timestep. Never use frame time, `System.currentTimeMillis()` or `System.nanoTime()` in simulation logic.
- Use one seeded RNG owned by the battle. No `Math.random()`, no `new Random()` without the battle seed, no shared or static RNG.
- Never let iteration order depend on identity: no iterating `HashMap`/`HashSet` keyed by objects without stable `hashCode`, no `IdentityHashMap`, no `System.identityHashCode`. Iterate arrays, lists or maps sorted by a stable ID.
- Use `StrictMath` instead of `Math` for `sin`, `cos`, `tan`, `atan2`, `exp`, `log`, `pow`, `sqrt`-dependent chains, and similar functions.
- No multithreaded updates of battle state. If work is ever split, the merge order must be fixed.
- No simulation behavior may depend on local settings: graphics quality, resolution, language, camera position.
- Every peer applies orders only at the turn the host confirmed, never on receipt.
- Anything that feeds the state hash must be part of the simulated state, not a render-side copy.
- When changing lockstep code, run the determinism test (same seed + same orders on two runs, compare per-subsystem hashes every turn) before declaring the change done.
