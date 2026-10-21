# Spike: city determinism for siege assaults (SOS-23)

Date: 2026-10-21 · Card: [SOS-23](https://fighterx2500.atlassian.net/browse/SOS-23) · Epic: SOS-22 · Code: `spikes/determinism/` (the SOS-11 rig) · Feeds: [SOS-17](https://fighterx2500.atlassian.net/browse/SOS-17) (siege assaults: streaming or lockstep) · Follows: [SOS-11 report](determinism.md), [ADR 0003](../../decisions/0003-battle-lockstep.md)

## Question

A siege assault runs inside the defender's live city (spec §9). For lockstep, every PC would have to simulate the whole city, not only the soldiers. Can the city simulation be made deterministic, and is it fast enough? The test city is the owner's late-game save Mogus-72 (about 4,210 people, build 71.44).

## Answer

**Yes, on one PC.** Mogus-72 under an AI invasion of 1,000 men ran **identically in 4 game processes for 10 minutes** (36,000 steps of 1/60 s) at game speeds 3, 1, 5 and 2. Every per-step hash of every person and animal matched. It took five fixes beyond SOS-11: three from code review before the first run, and two found by the runs.

**Timing passes once the AI general runs on the host.** With the general's planning inside the step, the game froze for 1 to 4 s dozens of times in 10 minutes. With the general on the host's own thread (owner decision, 2026-10-21, patch 96), the city attack passes the owner's timing rule. The field battle passes once its single step-1 load stall is left out, as the owner decided. That stall is also in vanilla.

The card's exit criterion was: identical for 10 minutes with an invasion, reached within 5 rounds of fixes, at a median of 16 ms per step or less. It is met. Two rounds of fixes found causes, and the median is 2.1 ms per step.

Not covered: two PCs, the network, a player-controlled attacker, larger cities, mid-run resync, and the render-driven risks found by review (see "Risks between PCs").

## Setup

The SOS-11 rig (`matrix.sh`, harness mod `SosMpDetSpike`) with additions for cities:

- **City runs** (`-C <save> [-I men]`). Every copy loads the same save. The world, factions, events, raids, nobles and tourism are held still (spec §9: the world is paused during an assault), and autosave is off. Every copy seeds the simulation stream, then runs the city. `-I` starts an invasion at step 2. The invasion's prompt and messages are hidden, so the game never pauses or offers a field battle instead.
- **City hash**: FNV-1a over every live entity (people and animals) in entity order: index, class, id, position, speed, and injuries for people. It doesn't cover rooms, stock, jobs or stats. Two checks cover those:
  - a whole-save comparison at an exact step (`compare_saves.py`, `saveat`);
  - stat dumps (`statdump`).
- **Load trace**: every simulation draw made while a save loads (`LOADTRACE`).
- **Baseline build** (`-B`): the untouched source with only patch 05 (timing) and patch 50 (autoload), vanilla stepping and threads.
  - Its world is **not** held still and autosave stays on, since the driver skips those switches in baseline mode.
  - Its battle plays out differently: the city baseline ended at 5,147 v 959 men, against 4,509 v 381 in lockstep.
  - So baseline and lockstep timings are comparable, not like for like.
- **Timing evals** (`evals.py`, `evals-run.sh`): one copy per build, 10 minutes, speed 1. They report game update time per frame, divided by the game time the frame advanced (in lockstep: time per step), and CPU per game second by thread group. A stall sampler records the update thread's stack for every step over 100 ms. The sampler was added after H1–H5; its first run was S1, a field battle.
- **AI on the host** (`-A`, `-P log`, `-S battleSave`): copy 1 runs first as the host and writes the AI log; copies 2 to 4 then replay it with the general switched off.

## Patches

New and changed since SOS-11. Each game file belongs to exactly one patch.

| Patch | Change | Found by |
| --- | --- | --- |
| 91-random-tile-tables | `RANMAP` / `GUTIL` random tile tables filled from a fixed seed instead of the unseeded start-up generator | Code review |
| 92-ice-setting | Ice on water (`TWater`) no longer depends on the weather-effects setting | Code review |
| 93-serial-killer-camera | `SerialKiller` no longer checks whether the victim is on the local screen | Code review |
| 94-load-in-sim | Loading a save runs on the simulation stream, seeded (prisoner crime picks are drawn during load) | Runs C4–C7 |
| 95-gl-jobs-in-sim | Graphics-thread jobs started by the simulation draw from the simulation stream (speaker cheer table) | Runs C8–D6 |
| 96-ai-host | AI general on the host's own thread, with its changes logged and applied at a stamped step (see below). Replaces 90-trace-orders: its trace lines now live here, since each file belongs to one patch. | Owner decision |
| 30-fixed-step (changed) | Applies the AI log at the start of each step; no autosave while the world is held still; afterTick once per step, behind a switch (`frameAfterTick` restores vanilla per-frame) | — |
| 40-sync-threads (changed) | The general's jobs run inside the step only in AI mode 0; the general's real thread may start on the host | — |
| 10-switches (changed) | Step counter, AI mode, AI delay, AI thread, afterTick switch | — |

## How the result was reached

Quiet city first, then an invasion of 1,000 men. "Identical for" is the worst copy.

| Stage | Runs | Identical for | First difference | Cause and fix |
| --- | --- | --- | --- | --- |
| Patches 91–93 from review, world held still | C0–C2 | 171–1,291 steps | A citizen picks `M_PlanSpectator` instead of walking | — |
| + afterTick per step | C3 | 79 | Same | No effect; not the cause |
| Save at step 0 compared | C4 | 171 (hashes); the step-0 saves already differ | `ENTETIES`: person records | — |
| Stat dumps right after load | C5, C6 | 13 | `LAW_PRISONERT`: 517 v 525 prisoners | Prisoner crime picks drawn during load (`CrimesData.addPrivate`); patch 94 |
| + loads in the simulation | C7–C9 | 13–467 | Step-0 saves identical; step-11 saves differ only in the game-speed byte | State that isn't saved |
| Soldier dumps, speaker check | D1–D6 | — | Speaker cheer table (64 entries, not saved) differs in all 4 copies; the load trace shows no speaker draw | Drawn inside graphics-thread jobs, so on the cosmetic stream; patch 95 |
| + GL jobs in the simulation | E1 | **10,800 (3 min), 4 of 4** | — | — |
| Invasion | F1 | No result | The invasion prompt paused the game | Harness: launch at step 2, messages hidden |
| Invasion, 2 copies | F2 | 5,400 (90 s) | — | — |
| Invasion, 10 minutes | F3 | 36,000 in 3 of 4 copies | Copy 2 was identical up to step 21,134, where the driver's 900 s wait ran out. Whether that copy was only slow at speed 1 or had stopped is unknown; H1 copy 2 needed about 12–13 minutes. | — |
| Invasion, 10 minutes | H1 | **36,000, 4 of 4** | — | — |
| AI on the host | AIcity, AIfield | **36,000, 4 of 4** (city and field) | — | — |
| AI on the host, final patch 96 | EVcityH → EVcityP, EVfieldH → EVfieldP (host and one peer) | **36,000** (city and field) | — | — |
| afterTick per frame (vanilla) | ATframe, ATfield | **36,000, 4 of 4** (city and field) | — | afterTick per step not needed |

AIcity ran on an earlier version of patch 96: its logs end at 17:22, and the patch last changed at 17:24. That version had no start hash in the log header and no artillery counter, and it may or may not have had the projectile vector fix. The final patch 96 is covered by these runs:
- EVcityH/P: city, afterTick per step, host and one peer;
- AIfield and EVfieldH/P: field battle;
- ATframe and ATfield: city and field battle, afterTick per frame, 4 copies.

## Causes found

| Cause | Would differ between two PCs because | Fix |
| --- | --- | --- |
| Random tile tables filled at start-up from an unseeded generator | Every process draws its own | Fixed seed (91) |
| Ice on water depends on the weather-effects setting | Graphics settings differ | Setting removed from the simulation value (92) |
| Serial killer only strikes off-screen | Every PC's camera differs | Camera check removed (93) |
| Prisoner crime picks drawn while loading the save | Load draws were outside the seeded stream | Loads run on the seeded simulation stream (94) |
| Speaker cheer table drawn inside graphics-thread jobs | Those draws were on the cosmetic stream | Graphics jobs started by the simulation draw from the simulation stream (95) |

Not a cause: **afterTick per frame.** Vanilla runs it once per drawn frame (`VIEW.java:193`), and patch 30 moved it into the step. Runs ATframe and ATfield put back per-frame afterTick while keeping fixed steps. One copy ran up to 10 steps between afterTick calls; another ran it several times per step. Both stayed identical for 36,000 steps.

Recommendation: drop the per-step afterTick from patch 30 and keep vanilla's call. That's one change fewer. But afterTick's world-side work (world-map battle polling, `RD.buildings.update`) hasn't been tested with the world moving.

## The AI general on the host (patch 96)

Owner decision (2026-10-21): the host runs Strategos2000 on its own thread as vanilla does. Every change it makes is applied at a later step by every PC, the host included, and never immediately. The other PCs have the general switched off. Orders, centres, trajectories and status jobs stay inside the step.

- **What is captured.** Only changes made from the general's own thread (`SosMpDeterminism.aiThread`):
  - the three locked orders (`BattleOrder.Locked.set` for path, destination and task);
  - the settings the general writes directly (guard, formation, running, fire at will; 11 call sites);
  - artillery targets.

  An audit of every caller of `Locked.set` and every write the general makes found no other writes. UI callers (`BattlePlacer*`, `UISelection`, `DivDeployerUser`) are player orders, which need their own channel.
- **What an entry holds.** Stamp, sequence number, kind, division index, and the payload. For orders, the payload is the order's own `save()` data. That holds every field `Locked.set` copies, plus some it doesn't: for a destination, `save()` writes all the formation's positions, while the copy takes only the deployed ones. So the log is complete, but larger than it needs to be.
- **Timing.** The simulation thread drains the general's queue at the start of step N, stamps each entry N + 30 (0.5 s), and logs it. At the start of step N + 30, every PC applies that step's entries in sequence order. A host or peer stops with an error if an entry's step has passed.
- **Log header.** It carries a format version, the delay and the start hash, so a peer refuses a log made from a different start.
- **Data race fixed.** The general's projectile checks (`testTarget` → `Updater.test`) shared a static vector with the trajectories job inside the step. That would split host from peers whenever the AI side had artillery. The general's thread now has its own vector.

| Run (4 copies, 10 min) | Result | Entries (logged = applied) | Settings share | Log |
| --- | --- | --- | --- | --- |
| City attack, Mogus-72, 1,000 men | Identical, 36,000 steps | 3,383 | 41% | 1.37 MB (about 18 kbit/s) |
| Field battle, 2,000 v 2,000 | Identical, 36,000 steps | 4,664 | 71% | 273 KB (about 3.6 kbit/s) |

The settings the general writes directly are 41% (city) to 71% (field) of all entries, so an order-only log would almost certainly have split the copies. That is reasoning; no run tried an order-only log. No test battle produced an AI artillery order, so that path is untested.

The general's decisions now depend on its thread timing, so two host runs of the same battle end differently: the city logs hold 3,383, 3,430 and 3,169 entries for the same start. That's harmless, since peers only need to match their host's log.

## Measurements

### Timing evals

One copy alone, 10 minutes of game time, speed 1. Times are ms of update per 1/60 s of game time. Owner's pass rule (2026-10-21): p99.9 at most 20 ms, no step over 1 s, and no more steps over 100 ms than the baseline for the same run. The baseline has its world running and its own course of battle (see Setup), so it is a reference, not a twin.

| Build | Battle | p50 | p95 | p99 | p99.9 | Mean | Over 100 ms | Over 1 s | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Vanilla (EVcityB) | City attack | 3.54 | 7.78 | 10.77 | 17.4 | 3.96 | 12 | 4 | — |
| Lockstep, AI in the step (H3) | City attack | 2.13 | 6.23 | 10.96 | 911 | 5.93 | 151 | 31 | Fail |
| **AI on host: host (EVcityH)** | City attack | 2.08 | 5.48 | 7.91 | 16.3 | 2.37 | 7 | 0 | **Pass** |
| **AI on host: peer (EVcityP)** | City attack | 2.03 | 5.28 | 7.62 | 14.6 | 2.27 | 2 | 0 | **Pass** |
| Vanilla (EVfieldB) | Field battle | 2.21 | 4.36 | 5.81 | 8.4 | 2.30 | 10 | 0 | — |
| Lockstep, AI in the step (H5) | Field battle | 1.60 | 6.17 | 12.11 | 1,156 | 6.16 | 150 | 45 | Fail |
| **AI on host: host (EVfieldH)** | Field battle | 1.67 | 5.88 | 11.76 | 17.3 | 2.08 | 1 | 1* | **Pass*** |
| **AI on host: peer (EVfieldP)** | Field battle | 1.59 | 5.69 | 11.43 | 16.5 | 1.98 | 1 | 1* | **Pass*** |

\* `evals.py` prints FAIL for these two runs. The single step over 1 s, which is also the single step over 100 ms, is the vanilla load stall at step 1. On the first step after a battle save loads, the city's guard patrols run their route search (`Patrol.find`), which takes about 1.1–1.2 s. Vanilla's first frame takes the same 1,208 ms. The evals divide each frame by the game time it advanced, and vanilla's first frame advanced almost two steps, so it shows as 644 ms. The owner set this stall aside (2026-10-21), so it isn't counted here.

H3 and H5 are from the earlier rerun with the AI inside the step. Their vanilla runs (H2, H4) were separate from EVcityB and EVfieldB, but gave similar figures.

What the stalls are:
- **AI inside the step:** frames of 1.0–2.8 s (H3, city) and 1.0–3.7 s (H5, field), about every 11 s of game time for the first 5–6 minutes, then rarely. H3 and H5 had no stall sampler. In S1 (a field battle), the sampled stacks over 0.5 s were all in the general's line planner (`StepLinesMoveTo.fillLines`, `StepLinesBacker.init`), inside the step. That the city's stalls have the same cause is inferred.
- **Vanilla city:** autosave (`AutoSaver` → `GameSaver.save` → `System.gc`), frames of 1.4–1.7 s at game times 179, 357 and 536 s, about every 3 minutes. Lockstep runs don't autosave while the world is held still.
- **Host, at each invasion wave:** a wait of up to 220 ms for the general's thread to finish its current pass (`BattleThreads.pause` → `waitUntilStopped`). Peers don't wait.
- **Otherwise, single stalls:**
  - invasion launch (`ArtilleryPlacer.find`, about 200 ms at step 2);
  - citizen AI during a collision on the host (`AISUB.poll`, 241 ms);
  - a guard patrol search mid-run on a peer (`Patrol.find`, 108 ms), so that search isn't only a load-time cost;
  - one render stall on a peer (`JOBS.render`, 264 ms).

CPU per game second, by thread. The simulation step runs on the `updater` thread. `main` is the game's graphics thread: its CPU varies from 0.1 to 215 s between the four copies of a single run, so it isn't simulation work.

| Run | Whole game | `updater` (simulation) | `main` (graphics) | General's thread |
| --- | --- | --- | --- | --- |
| City, vanilla | 1,155 ms | 296 | 202 | 517 |
| City, host | 654 ms | 200 | 153 | 196 |
| City, peer | 403 ms | 177 | 134 | — |
| Field, vanilla | 747 ms | 250 | 104 | 258 |
| Field, host | 1,023 ms | 243 | 441 | 229 |
| Field, peer | 741 ms | 243 | 398 | — |

The whole game used less CPU in the city attack than vanilla, but vanilla's world was running and its invasion went differently (more invaders alive). So that comparison is indicative only.

The field battle's p99 doubled (11.8 v 5.8 ms) while the simulation thread's CPU stayed the same (243 v 250 ms). The cause isn't established. Likely candidates are the battle jobs running in one burst inside the step instead of spread over their own threads, and the status job every 30 steps. At speed 3 a step has about 5.6 ms, so heavy moments may run below the requested speed. That is an estimate; higher speeds weren't measured.

### Other measurements

- **Paths:**
  - quiet city: 0.44–0.6 ms of path work per step, about 1.2–1.5 worker path jobs per step;
  - city attack: 0.21–0.32 ms per step, 0.87 jobs per step (31,297 in 10 minutes);
  - field battle: 0.006 ms per step.
- **Path option 2** (paths computed between steps and delivered at the next step) was not built. With paths at 0.2–0.6 ms per step it no longer matters.
- The battle jobs' share of step time was not measured separately; only whole-step time, path time and CPU per thread exist.
- **Sizes:**
  - Mogus-72 save: 3.47 MB on disk, 31.6 MB uncompressed in 4,429 sections. This is the whole world save; the city's share wasn't measured.
  - Battle save, 2,000 v 2,000: 1.9–2.0 MB.

## Risks between PCs (review, not tested)

A one-PC test can't catch these, because every copy shows the same view with the same settings. Two read-only reviews of the city code looked for them. Confirmed by reading the source:

1. **House rendering writes simulation state.** House sprites (`house/Sprites.java:358–382`) clear or place floor tiles when they find a mismatch, which also resets the floor's wear. A house drawn on one PC and not on another changes the city.
2. **First-caller caches.** The archer volley timing cache (`EquipRange.drawInter`) is filled by whoever calls it first, the battle division card in the UI included (`UIDivCardBattle`).
   Similar caches: `RoomEmploymentIns`, `StatsMultipliers`, `BoostCompound`, and the `GuardPower` cursor advanced by the UI.
3. **Head-on-spike decorations** (`SpriteHead`) spawn real gore objects at a rate set by the frame rate (`fps() * 4`).
4. **Battle-screen and speed checks** in simulation code (`VIEW.b().isActive()` about 8 times, and `GameSpeed` reads). They're harmless while every PC shows the same screen; they need one simulation flag.
5. **Player actions between steps.** The defender's UI actions during an assault (building, jobs, stock) change the city at once on one PC. They would have to become orders applied at a step.
6. **Floating-point intrinsics.** There are about 67–83 `Math.sin/cos/pow` and similar calls in simulation code, and `StrictMath` may be needed across CPUs (SOS-15).
7. **Unsaved state.** Some state isn't saved, such as the entities' half-update parity and the speaker table. A resync load has to set it deterministically.

Clean: no `Math.random` or identity hashing in city simulation code.

Found by the adversarial review of patches 94–96, not fixed in the spike:

- **Atlas cache.** When the graphics atlas cache is missing, `Initer` builds `GAME` twice inside the seeded load, which shifts the stream on that PC only. Fix: reseed per construction. Test: delete one copy's cache.
- **Status beat on save.** `BattleThreads.save` pauses and unpauses, which resets the status beat (`syncStep`) on the PC that saved. The beat should come from the global step.
- **Resync state.** The step number and pending AI log entries aren't in the save. Host and peer saves also differ in the battle threads' state.
- **Atomic batches.** A destination and a task the general sets together can land in different steps' batches. Every PC still applies the same entries at the same steps.
- `Quote.set` draws from the generator on a 10 s wall-clock timer during save and generation.

## Recommendation for SOS-17

**Lockstep looks realistic for siege assaults**, as far as the simulation and the CPU are concerned.
- The whole city simulates identically under an assault.
- With the AI general on the host, the step stays within the owner's timing rule.
- The frozen world is already what spec §9 asks for.

The evidence has limits:
- **A vanilla AI invasion was tested**, of a city of about 4,210 people. A spec §9 assault has a player attacker sending orders.
- **Cities of 10,000 people** (spec §13) weren't measured in lockstep.

Lockstep for assaults still needs four things that field battles don't:

1. **The city reaches every PC at assault start.** The defender's city save goes to every PC: Mogus-72's whole save is 3.5 MB, the city part is smaller and wasn't measured. The save size spike decides whether that transfer is acceptable.
2. **Render-driven writes become simulation-only.** Risks 1–3 above need hooks, so that only simulation code changes the city.
3. **The defender's actions during an assault are orders at a step.** This is a scope decision: either the defender can't build or manage during an assault, or each action is sent as an order.
4. **A two-PC test.** Different CPUs, cameras and settings, and a larger city, before the ADR is final.

Streaming would avoid points 2 and 3, but not points 1 and 4:
- viewers still need the city's layout to draw it;
- any model needs a two-PC test.

Streaming also has its own costs:
- a display-only mode for every viewer, for the whole city (people, rooms, fire, projectiles), not only soldiers; SOS-10 measured about 1.8 Mbit/s per viewer for 4,000 soldiers alone;
- the defender's PC carries the assault alone.

**Proposed for SOS-17:** lockstep for siege assaults, subject to the save size spike and a test with a player attacker on a larger city. The work in points 2–4 goes onto the siege cards.

## ADR 0003 amendment (approved by the owner, 2026-10-21; applied in this PR)

The text below is what was proposed and applied. Replace "only orders cross the network" in the Decision's lead sentence with "only orders, the host's AI log, and hashes cross the network". Replace the "Battle jobs" bullet with:

> **Battle jobs.** Four of the five battle worker jobs (orders, division centres, trajectories, status) and path finding run inside the step, in a fixed order and beat, on the simulation thread. **The AI general (Strategos2000) runs only on the host, on its own thread as in vanilla.** Every change it makes is captured instead of applied:
> - orders through `BattleOrder.Locked.set`: path, destination and task;
> - the division settings it writes directly: guard, formation, running, fire at will;
> - artillery targets.
>
> The host's simulation thread stamps each change for step N + 30 (0.5 s). Every PC, the host included, applies the changes at that step in sequence order. The other PCs have the general switched off and apply the host's AI log, which travels like orders.

Replace the Consequences bullet "Cost" with:

> - **Cost (SOS-23).** With the general inside the step, its planning froze the game for 1–4 s, dozens of times in 10 minutes. With the general on the host, a city assault (Mogus-72, 1,000 attackers) and a 2,000 v 2,000 field battle meet the owner's timing rule at 1× on the test PC: p99.9 at most 20 ms, no step over 1 s, and stalls no more often than vanilla. The field battle passes once its vanilla load stall at step 1 is left out. Its p99 is about twice vanilla's (11.8 v 5.8 ms), so high battle speeds are untested and may be limited by the slowest PC.

Add to Consequences:

> - **AI log traffic:** about 18 kbit/s in a city assault and 3.6 kbit/s in a 2,000 v 2,000 field battle (SOS-23, before any size optimisation).
> - **Resync and host change:** both must carry the step number and the AI log entries not yet applied. A new host starts its own general.
> - **The AI's decisions depend on the host's thread timing.** Two hosts would fight the same battle differently. Every PC still sees the same battle.

Hook changes (patch 96, plus parts of 10, 30 and 40):
> - **Hooks:**
>   - B8 changes: the general's jobs stay off the step, and its real thread starts on the host only (`BattleThreads`, `BattleThread`, `SlaveThread`).
>   - B7: the step applies the AI log entries due at it (`GAME`).
>   - A new point captures the general's writes:
>     - `BattleOrder` and `BattleOrders`;
>     - five offence steps and `Strategos2000`;
>     - its own vector in projectile `Updater`;
>     - a new log class.

The ADR also gained:
- an SOS-23 context paragraph;
- "AI general inside the step" under the options rejected.

The same approval updated:
- `.claude/rules/determinism.md`: one exception to "no multithreaded updates", for the host's general, whose writes go only through the log;
- `docs/plan/hook-surface.md`: B7, B8 and the new capture point B15 (now 55 points, 22 of them hooks);
- `docs/architecture.md`: the AI general bullet under field battles;
- spec §8 (version 0.9): orders and the host's AI orders cross the network.

Siege assaults stay open in ADR 0003 until SOS-17.

## Not covered

Deferred by the owner at the wrap-up (2026-10-21), to be swept later:

- **Mid-run save and reload (resync).** It needs rig work first:
  - the harness loses its run state when a save loads;
  - the status beat resets on save;
  - the step number and pending AI entries aren't saved.
- **Render time in the evals, higher game speeds, a slower PC.**
- **AI artillery orders**, which never occurred in a test battle.
- **The render-driven risks**, which need copies with different camera views.
- **The vanilla load stall** at step 1 (guard patrol search, about 1.2 s).

Also not covered:
- two PCs with different CPUs;
- player orders, a player attacker, and the network (order delay at 300 ms ping);
- cities larger than Mogus-72;
- the world moving during a lockstep run (world-side afterTick work);
- whether F3 copy 2 was slow or had stopped.

## Spec sections touched

- **§8, changed (spec 0.9):** "only orders cross the network" now includes the host's AI orders, and the battle AI runs on the host (ADR 0003 amendment, owner approval 2026-10-21).
- **§9 (sieges) and the §16 open question:** this report feeds them through SOS-17; neither changed.
- **§13 and §15:** the timing results bear on the performance and sync targets, and on the battle and siege acceptance criteria; neither changed.
