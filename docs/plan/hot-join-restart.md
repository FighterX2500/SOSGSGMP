# Hot-join by restart (SOS-189, SOS-190)

A design note for hot-join, replacing `hot-join.md` §5.1–5.3's in-session join (owner's go: "the rest of decisions on you", 2026-11-11; route decided 2026-11-12 after rig runs CE1, CE2 and AH2).

## Why a restart

`hot-join.md` §5 had a fresh PC enter a running engine session. That needs:
- a join state: the world save plus every human's reports, the AI log's resync part, the seed, speed, hash epoch and draws;
- stream epochs;
- a guest mode;
- a "landing" human state in the engine.

A **restart** has none of it:
1. Every PC makes a campaign save.
2. Every PC starts a new engine session from its own copy of that save, as a resume does.
3. Every PC adds the newcomer to the world before step 0.
4. The newcomer loads the host's copy of the save and lands as a setup joiner does.

Every stream starts at offset 0 in a new `EngineLink`, and the AI log has a new start id (the campaign id XOR the save number). The cost is one load on every PC, a few seconds, which spec §4's "every PC pauses for a few seconds" allows.

**What the engine needs, done on SOSDET branch `SOS-189-hot-join`:**
- `Engine.joinSlot()`: the first free NPC slot above the last human's.
- `Engine.addHuman(HumanStart, credits)`: the newcomer's city-state is carved and the slot made human, in a session, between steps, after a load.
- Carve, uncarve and `addHuman` draw from the game's generator seeded from the carve layout and the step, so every PC makes the same world.

**Rig evidence:**
- CE1 failed before the seed: copies differed at 2001, in the break-away NPC's court and stocks.
- CE1 and CE2 with the seed: identical to 6,000.
- AH1, adding with no session: copies differed from step 1. A load with no session isn't seeded.
- AH2, adding in a session: identical to 6,000, and the saved worlds were equal on all three copies.

## The flow

1. **Hello during play.** The newcomer connects by IP or Steam. Today the host refuses a Hello after the start (`HostedSetup` `Refusal.STARTED`). Instead it now:
   - runs the builds and game-files check (`Builds.mismatch`, as `HostedLobby` does);
   - refuses if the game is full (16 players, counting spectators), if `Engine.joinSlot()` is -1, or if a hot-join is already under way;
   - otherwise keeps the PC as the **candidate** and shows the host "*Name* wants to join: Accept / Refuse". The game doesn't pause.
2. **Accept.**
   - The host orders a campaign save, kind AUTO (every PC holds for a moment, as an autosave does).
   - When it is done, the host sends that save's game file to the candidate in pieces (`SettlePart`).
   - The candidate loads it outside any session, paused (`MpSettle`), and picks race, name and spot.
   - Spots go by `Engine.settleProblem` against every human's capital. The host checks again on its live world.
3. **Ready.**
   - The candidate sends `JoinPick` (race, faction name, ruler, banner, spot).
   - The host checks the spot on its live world and answers `JoinPicked(problem)`.
   - If the spot is fine, the host orders the **founding save**: a campaign save, kind AUTO, with `restart` set. When `SaveDone` is OK, the host sends every joined PC and the candidate `Restart(number, newcomer)`, with the newcomer's start, slot, key and name.
4. **Restart, on every PC.**
   - The old session ends: `game.endSession()`; the setup is replaced, not left, so the node stays open.
   - The host builds a new `HostedSetup` in resume mode over the same node, its players plus the newcomer, with `Resume(campaign + newcomer, number)`.
   - Every joiner builds a new `JoinedSetup` in resume mode when `Restart` arrives. Messages from the host arrive in order, so anything after `Restart` belongs to the new session.
   - Each joiner sends `Restarted` first. The host's new setup drops a peer's messages until that peer's `Restarted`, which clears the old session's stream pieces still in flight.
   - The usual resume follows: `SlotMap(1)`, now with the newcomer's slot, then `MpWorldLoad(setup, record)` on every old PC.
   - After the load and before `humans()`, every PC calls `Engine.addHuman(start, credits)`, saves and loads again.
   - Hashes go to the host (`Loaded`), as at a resume.
5. **The newcomer.**
   - Gets the host's founding save in pieces (`WorldPart(1, …)`, only to them) and writes it to its saves folder with a campaign record.
   - Runs the same load and `addHuman`, then its own city at `capitalOf(slot)` and the landing party, as a setup joiner does.
   - The others' cities stand already.
6. **Go** as at a resume. The next save writes the newcomer into the campaign (`Campaign.adding(key, name, slot)`), so a later resume treats them like anyone else.

## Choices

- **Slot:** `Engine.joinSlot()`, usually above 15. The mod allows slots and PC numbers up to 63 (`LobbySettings.MAX_SLOTS`, done).
- **Credits:** vanilla's starting credits for the campaign's difficulty, not the host's current credits. See what `GameWorldSetup` sets at a new game.
- **Not persisted until it succeeds:** the newcomer enters the campaign at the first save after the restart. If the restart fails, a resume of the founding save is the game without them.
- **Refusals during a hot-join:** battles between players, assaults, open views and saves already hold a campaign save back (`MpSaves.saveRefusal`); the founding save waits like any save.
- **Protocol 29:**
  - `Restart` and `Restarted`;
  - `JoinPick` and `JoinPicked`;
  - `JoinAsk(name)` and `JoinAnswer`, host to candidate, for the Accept prompt's outcome;
  - `SettlePart` reused for the snapshot.
- **Steam:** the lobby's member limit becomes the game's player limit, so a newcomer can enter the Steam lobby after the start.

## Cards

- **SOS-189 (engine):** done on the branch, with CE1, CE2 and AH2.
- **SOS-190 (mod):**
  1. Slots up to 63 (done).
  2. Protocol 29 and the host's candidate.
  3. The Accept prompt and the snapshot (MpSettle for the newcomer).
  4. The founding save and `Restart`.
  5. The restart load with `addHuman`.
  6. A twopc test in which a third game joins a running two-player game, plus a GUI review.
- **SOS-191:** the test pass.
