# Spike: battle streaming, phase A (SOS-10)

Date: 2026-10-20 · Card: [SOS-10](https://fighterx2500.atlassian.net/browse/SOS-10) · Code: `spikes/battle-streaming/` · Decision record: [ADR 0002](../../decisions/0002-battle-streaming.md)

## Questions

1. Does the same seed give the same battlefield on every PC (ADR 0002 amendment)?
2. How large is a battle snapshot, and what does capturing it cost the host?

Phase B (a display-only mode for viewers) is not part of this card.

## Answers

1. **Yes, but only if the seed is set after the pre-battle save.** Vanilla `BattleState.setGenerate` saves the whole game first, and **saving draws from the global `RND`**, by an amount that depends on the game state. Seeding before `setGenerate`, as ADR 0002 planned, therefore gives different battlefields. Seeding after the save gives the identical battlefield, soldier placement and RNG state on every run: 6 of 6 runs, across two game launches. Doing that needs a one-line hook in `setGenerate`, or reflection; the plain API isn't enough.
2. **About 22 KB per snapshot** (compressed) for 4,000 soldiers, which is **about 1.8 Mbit/s at 10 snapshots per second** sending the full state each time. Capturing costs the host **about 1.1 ms per snapshot**. Bandwidth has no limit in v1 (spec §13), so this only records the figure.

## Setup

- **Spike script mod** (`BattleSpike.java`). It's installed by `build.ps1` into `%APPDATA%\songsofsyx\mods\SosMpSpike\V71\script\`, compiled with JDK 21 against the pinned `SongsOfSyx.jar`, and loaded through `SCRIPT.forceInit()`. It reads commands from `%APPDATA%\songsofsyx\sosmp-spike\command.txt` and writes results to `results.jsonl`.
- **Game:** the shipped V71 game, with a fresh "Random Game" (world seed 1462982728) loaded from its `A New Beginning` save.
- **Battle:** 2,000 vs 2,000 soldiers (2 × 10 divisions of 200), built from the seed the same way the developer's "battle create" debug tool does (`world/battle/Tests.java`). The army generator draws only from `RND`.
- **Fingerprints** of the generated battlefield, each a CRC32:
  - terrain (`SETT.TERRAIN().get(tile).index()`) for every tile;
  - ground type (`SETT.GROUND().MAP.get(tile).index`) for every tile;
  - floors;
  - soldiers, as (division, tile), sorted;
  - one `RND.rInt()` drawn right after generation, the "probe", to see whether the RNG ended in the same state.
- **Snapshot format:** 11 bytes per soldier: entity id (4), x and y in pixels (2 + 2), heading (1), health (1), division (1). Compressed with `Deflater(BEST_SPEED)`, with no delta against the previous snapshot.

## Results: same seed, same battlefield?

| Run | Path | Terrain | Ground | Soldiers | RNG probe |
| --- | --- | --- | --- | --- | --- |
| Launch 1, #1 | vanilla `setGenerate`, seeded before | `b84d2ed2` | `67286d44` | `890f41ef` | 1353408573 |
| Launch 1, #2 | vanilla | `b3cd628a` | `dd88501f` | `890f41ef` | 408537598 |
| Launch 1, #3 | vanilla | `b84d2ed2` | `67286d44` | `890f41ef` | 1353408573 |
| Launch 1, #4 | vanilla | `b3cd628a` | `dd88501f` | `890f41ef` | 408537598 |
| Launch 1, control | vanilla, seed 777 | `36f9e73b` | `9f8645db` | `71a8f648` | −1783413602 |
| Launch 2, #1–#4 | seeded **after** the pre-battle save | `b3cd628a` | `dd88501f` | `890f41ef` | −865904143 (all four) |
| Launch 3, #1–#2 | seeded after the save | `b3cd628a` | `dd88501f` | `890f41ef` | −865904143 (both) |
| Launch 3 | vanilla | `b3cd628a` | `dd88501f` | `890f41ef` | 408537598 |

Launches 2 and 3 are the same save loaded in a new game process.

- **The vanilla path alternates** between two battlefields. Soldier placement stays the same, because the armies are built before the save.
- **A direct test** (`rndsave`) confirmed the cause: set the seed, save, draw a number; the number differs from the one drawn without a save.
- **Seeding after the save** (`generate-direct`, which repeats `setGenerate` step by step through reflection) gave identical results in every run. That held across different prior states (the landing screen, or a previous battlefield) and across two game launches.
- **The floor fingerprint was identical in every run**, including other seeds, so battlefields have no floors and that layer tells us nothing.
- **Not tested yet: two PCs.** No second PC was available. Different hardware shouldn't matter, because `java.util.Random` with a given seed produces the same numbers on every JVM. That's still reasoning, not a measurement.

## Results: snapshot size

| Capture | Battle seconds | Snapshots | Soldiers (start → end) | Compressed avg / max | Rate at 10/s | Capture time avg / max |
| --- | --- | --- | --- | --- | --- | --- |
| Before contact (1×) | 60 | 600 | 4,000 → 3,999 | 20.8 KB / 23.5 KB | 1.67 Mbit/s | 1.10 ms / 4.96 ms |
| Combat (fast-forward) | 240 | 2,400 | 3,999 → 3,800 | 22.4 KB / 24.5 KB | 1.79 Mbit/s | 1.11 ms / 2.13 ms |

- **Uncompressed:** about 44 KB per snapshot (11 bytes × 4,000 soldiers).
- **Compression:** halves it. Positions change every snapshot, so plain compression can't do more.
- **Delta encoding** (send only what changed since the last snapshot, with smaller position deltas) wasn't tried. It's the obvious next step if traffic ever matters.
- **Host cost:** about 1.1 ms per snapshot, which is 11 ms per second of battle at 10 snapshots per second.

## Other findings

- **A battle takes about 17–20 s to generate** for 2,000 vs 2,000 (`CreateFromWorldMap` on the battle tile), plus two full saves (`__beforeBattle`, `__battle`). Every PC pays this when a battle starts, as well as the campaign save barrier that spec §11 (0.7) now requires before battles. The spec has no limit on battle start time.
- **`BattleState.deploy()` alone doesn't start a battle.** After calling it, battle time didn't advance until the game's own Start button was pressed, which evidently does more (for example starting the battle threads). Phase B and hook B3 need the exact start sequence.
- **The game window minimized itself** when it lost focus (`WIN_AUTO_ICONIFY: 1` in the launcher settings). Whether a minimized game keeps simulating isn't established yet: the battle wasn't running then for the reason above. Spec §4 says menus don't pause; a minimizing window may need the same rule. This is an open question.

## Consequences

- **ADR 0002 amendment.** The battlefield seed must be applied after the pre-battle save. Hook point B2 changes from "API" to "Hook": one line in `BattleState.setGenerate` to set the host's seed after `save("__beforeBattle")`. The alternative is to repeat `setGenerate` by reflection on its private constructor, private fields and package-private generator, as the spike does; that's possible, but it's four private members instead of one hook.
- **Bandwidth.** About 1.8 Mbit/s per viewer for a 2,000 vs 2,000 battle with no delta encoding. The host sends one stream to each other player.
- **SP4 (several players on one side).** Not measured here. What this spike shows: the engine already handles each division separately (soldiers carry `division().index()`), so "who may order which division" can be a table kept by the mod. What still needs the engine (hook B6) is everything tied to a side's faction: `Army.faction()` is `FACTIONS.player()` or `FACTIONS.otherFaction()`, which drives bonuses and race. Phase B is the place to measure that.

## Not covered

- Order latency at 300 ms ping (the Steam P2P spike).
- Display-only mode (phase B).
- Two PCs.
- Delta encoding.
