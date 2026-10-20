# Spike: deterministic battles for lockstep (SOS-11)

Date: 2026-10-21 · Card: [SOS-11](https://fighterx2500.atlassian.net/browse/SOS-11) · Code: `spikes/determinism/` · Decision record: [ADR 0003](../../decisions/0003-battle-lockstep.md)

## Question

Can the vanilla battle engine be made deterministic, so that every PC simulates a field battle and only orders cross the network (lockstep)? Owner request after SOS-10: rework the battle engine for lockstep instead of streaming, as spike patches only.

## Answer

**Yes, on one PC.** With nine spike patches on our own rebuild of the game (ADR 0001), a 2,000 vs 2,000 battle ran **identically in 4 separate game processes for 10 minutes** (36,000 steps of 1/60 s). The 4 processes ran at game speeds 3, 1, 5 and 2. Every per-step state hash matched, and every copy ended with exactly 1,594 vs 1,649 men (AC1).

Two PCs with different CPUs are not tested yet (see "Not covered").

## Setup

- **Patched game.** `build.py prepare` copies the pristine source and applies `patches/*.patch`; `build.py compile` builds it with ECJ (ADR 0001). The shipped game is never touched. Every change sits behind switches in a new class `snake2d/SosMpDeterminism`; with the switches off the game behaves as vanilla.
- **Harness.** A script mod (`harness/`, mod `SosMpDetSpike`) that takes commands from a file: load a save, set switches, generate, load a battle save, seed, run, hash, dump, trace.
- **Hash.** After every simulation step, FNV-1a over every soldier's index, id, position, speed, health and division.
- **Test rig** (`matrix.sh`). It starts N copies of the game, each with its own data folder (`APPDATA` set per process), and each loads the base save by itself (patch 50). Copy 1 generates the battle; every copy then loads that battle save, seeds the simulation stream and runs at its own game speed. `summary.py` compares every run with the first and diffs the traces. One command, unattended; the user's own settings are only read.
- **Tracing.**
  - The caller of every simulation random draw, for a window of steps (`-t A-B`).
  - Every AI order with its caller (patch 90).
  - Per-soldier and per-division dumps at chosen steps.
- **Battle.** 2,000 vs 2,000 (2 × 10 divisions), seed 12345, generated near the test city; both sides controlled by the battle AI.

## Patches

| Patch | Change |
| --- | --- |
| 10-switches | `SosMpDeterminism`: switches, simulation-thread check, draw counters, trace helpers |
| 20-rnd-split | `RND` gets two streams: the simulation stream (seeded) for draws made inside the simulation step on the simulation thread, and a cosmetic stream for everything else. Adds `cInt`/`cFloat`/`cFloat1` for code that is always cosmetic. |
| 30-fixed-step | `GAME.update` runs whole 1/60 s steps from an accumulator; `BattleState.update` moves into the step; no simulation while paused |
| 40-sync-threads | The five battle threads' jobs run inside the step in a fixed order (orders, centres, trajectories, AI twice, status every 30 steps); `working()` is true only while a job runs; real threads never start; paths are computed at once |
| 50-autoload | Load a named save at start-up (`SOSMP_AUTOLOAD`), so tests need no clicks |
| 60-cosmetic-trees | Tree sway updates draw cosmetic |
| 70-tile-order | `TileUpdater` shuffles its tile order with a fixed seed |
| 80-cosmetic-audio | All random draws in `game/audio` are cosmetic |
| 85-cosmetic-light | Night light flicker draws cosmetic |
| 90-trace-orders | Debugging only: logs every battle order with its caller |

The harness also runs `BattleState.deploy()` inside the simulation context (battle start as step 0).

## How the result was reached

Each stage removed the first difference and exposed the next one. "Identical for" is the number of steps two runs of the same battle matched.

| Stage | Identical for | First difference | Found by |
| --- | --- | --- | --- |
| Vanilla | 8 steps | Variable frame-time steps, threads on wall-clock timers | Per-step hash |
| Fixed 1/60 s step | 31 | Battle threads' timing | Hash, soldier dump: facing sign of standing soldiers |
| + jobs inside the step (first version) | 0 | **Our bug:** `working()` forced true, so `BattleOrders.init` started the real orders thread during generation | Generation hash differed between runs |
| Thread patch fixed | 31 | One random stream shared with rendering | Draw count |
| + split streams | 31 | Draw count differs from step 3 | Draw trace on 4 copies (T1): tree sway, grass growth, guard patrols |
| + one shared battle save, trees cosmetic, fixed tile order | 2,138 (35.6 s) | First archer volley: projectile sound picks | Trace window at steps 2,130–2,142 |
| + audio cosmetic | 10,800 (3 min) in some processes, 32 in others | Battle AI started differently per process | Division dumps (division 123 got another `MOVE`), then order trace: AI line placement at step 1 |
| + deploy inside the simulation | 10,800: 8 of 8 runs (4 copies × 2) | — | — |
| 10-minute battle | 23,332 (6:29) | Night light flicker | Trace window at steps 23,320–23,334 |
| + light flicker cosmetic | **36,000 (10 min): 4 of 4** | — | — |

## Causes found

| Cause | Kind | Would differ between two PCs because | Fix |
| --- | --- | --- | --- |
| Time step follows frame time | Timing | Frame rates differ | Fixed step (30) |
| Five battle worker threads on wall-clock timers | Threads | Scheduling differs | Jobs inside the step (40) |
| Path answers from a worker thread | Threads | Scheduling differs | Paths computed at once (40) |
| One random generator for simulation, rendering, UI and saving | Random | Frame counts differ | Two streams (20) |
| Tree sway draws from the simulation stream, with state from before the battle | Visual in simulation | Frame history differs | Cosmetic (60) |
| `TileUpdater` tile order shuffled at class load from an unseeded generator | Per process | Every process has its own order | Fixed seed (70) |
| Projectile sound picks: throttled by game speed, skipped at zero volume | Audio in simulation | Speed and **volume settings** differ | Cosmetic (80) |
| AI `flanking` value drawn when the AI is built and redrawn at deploy, outside the step, and not saved | Battle start outside the simulation | Each process draws its own | Deploy as step 0 (harness) |
| Night light flicker, only when the light-cycle setting is on | Visual in simulation | **`UI_LIGHT_CYCLE` setting** differs | Cosmetic (85) |

Not a cause, but found on the way:
- **Battle generation has two outcomes** (hashes `740710b8…` and `fd716bfa…`), in both vanilla-thread and sync modes, cause unknown. Lockstep sidesteps it: only the host generates, and every PC loads the host's battle save.
- **Withdrawn:** an earlier note that `Strategos2000.prevUpdate` leaks between battles. `GameLoader` rebuilds the whole `GAME` on every load, so it doesn't.
- `DivSettings.setBestAmmo` uses `ThreadLocalRandom`, but nothing calls it.

## Measurements

- **Battle save:** 1.9 MB (1,920–1,932 KB) for 2,000 vs 2,000. That's what the host sends to every PC at battle start, and again for a resync.
- **Cost of the jobs inside the step:** 7–10 ms per step with one game running, against about 1.3–1.6 ms in vanilla (where the jobs run on other cores). With 4 copies on one PC: 8.5–11 ms. At 60 steps per second, 1× speed needs under 16.7 ms per step, so a 2,000 vs 2,000 battle fits on this PC with room for about 1.5× to 2×. The jobs run every step; most could run less often.
- **Draws from other threads during a step:** 0 in every run (the thread check in patch 20).

## Consequences

- **ADR 0003:** field battles use lockstep; ADR 0002 is superseded for field battles. Siege assaults stay open until the save size spike (owner, 2026-10-21).
- **Spec 0.8:** §8, §13, §15 and §16 rewritten for lockstep.
- **Hook map:** new battle points B7–B14; B2 becomes API because peers load the host's battle save instead of generating.
- **Determinism rules** (`.claude/rules/determinism.md`) apply again, with what this spike learned added.
- **Debug test build:** parked as a proposal in spec §16.

## Not covered

- **Two PCs.** `Math.sin`, `cos`, `pow` and other intrinsics may differ between CPUs; `StrictMath` may be needed. No second PC was available.
- **Player orders.** Only AI-controlled battles ran. Orders from players, applied at a confirmed step, are untested.
- **Network.** Order delay, hash exchange and resync over Steam P2P (spike 4.4).
- **Other battle content.** Siege engines, cavalry-heavy armies, routing at scale, weather. The test battle had archers and infantry and reached heavy losses, but not every unit type.
- **Mid-battle save and load (resync).** AI state such as `flanking` is not in the save. On load it is drawn again when the AI object is built, outside the step, so each PC would get its own value and desync again right after a resync. A resync needs that state saved, or redrawn on the simulation stream after the load (a hook); not tested.
- **Faster jobs.** Running the jobs in parallel on a snapshot and applying results a fixed number of steps later wasn't tried.
