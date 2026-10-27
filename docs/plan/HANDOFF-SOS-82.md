# Handoff: SOS-82, city viewing

Written 2026-10-28 by the session that worked SOS-82, for the session that carries it on. The general handoff is [HANDOFF.md](HANDOFF.md); this file covers only SOS-82. Fold it into HANDOFF.md once the card's PRs are open.

## 1. Where the work is

| Repository | Worktree | Branch | Last commit |
|---|---|---|---|
| SOSDET (engine) | `C:\ClaudeFolder\SOSDET-wt\sos82` | `SOS-82-city-viewing` | `46dfff2`, on `409c5c7` (main after SOSDET #51) |
| SOSGSGMP (mod) | `C:\ClaudeFolder\SOS-wt\sos82` | `SOS-82-city-viewing` | on `830a530` (main after #55): the plan, spec §10 and §15, ADR 0006, this file |

Neither branch is pushed and no PR is open. Jira SOS-82 has not moved yet. The main checkouts (`C:\ClaudeFolder\SOS`, `C:\ClaudeFolder\SOSDET`) hold SOS-81's work; don't do SOS-82 work there.

Read first: the card, spec §10 (0.26) and §15's view rows, [ADR 0006](../decisions/0006-lockstep-city-viewing-and-assaults.md) (the bullets dated 2026-10-27 and 2026-10-28), [city-viewing.md](city-viewing.md) (the mod's plan, M3 and M4), and SOSDET's `docs/plan/0012-city-viewing.md` (the engine's plan, M1 and M2).

## 2. The owner's decisions

- Option A: the host can view too. Option C (moving the host's role) is for later.
- A view is a request; the owner allows or refuses it.
- Option E: the owner and the viewer both load only the city from the owner's save, keeping the world. The owner's stutter of a few seconds is fine.
- Every other PC reloads its world as a view opens and closes, like a world resync (2026-10-27). Then (2026-10-28) "that pause is too long" for a whole-game reload. So the other PCs reload only their world, and the engine carries what a whole-game load resets.

## 3. What works (engine, M1 and the thaw)

- The view entry, the freeze at F and the thaw at T (the next multiples of 30), `viewOut`/`viewIn`, the view load, the view hash, catching up.
- The thaw: the viewer saves the world it ran and loads its own `__beforeView` save. Then it loads that world back into its own city and applies the city transactions it missed.
- `Session.viewWorldReloads`: every PC except the viewer saves and reloads only its world, after F (not the owner, which reloads its whole game at F) and before T.
- `GameWorldLoad.unsaved`: the state no save holds, put back after every view load and world reload. That covers:
  - move remainders;
  - the entity pools (their sizes and remainders);
  - the war event's timer;
  - each NPC court's `addT`;
  - the slave recipes' `FBoost.mul`.

  Every raid map is reset to a new game's (`AUp`, `dirty`). How each was found is in plan 0012 §3 (rig runs V82B to V82AA).

**Evidence:** rig run V82AB (`--copies 3 --humans 3 --steps 3600 --view 2400:2:1:2900`) kept all copies identical to step 3600, with no engine desync.

| Who | Load | Time |
|---|---|---|
| The PC outside the view | world reload | 440 ms and 489 ms |
| The owner, at the thaw | world reload | 269 ms |
| The viewer | the owner's save | 8.4 s |

The 3 copies shared one PC, so the times are upper bounds. `gradlew.bat test` passes.

## 4. Next, in order

1. **The debugging tools.** Decide with the owner whether to keep or remove them before the PR:
   - in the rig: `StateDump`, and `Rig.java`/`rig.py`'s `--dump-at` and `--dump-reload`;
   - in the engine: `Engine.reloadOwnForDebug`, `Engine.debugAt`, `Session.viewLoadDone`/`debugAt`.

   They found every piece of unsaved state: dump two copies at one step, then diff them with the scratch script from the run (it groups the dump by path with the indices stripped and leaves out the city's paths). The recommendation: keep them in the rig, since the next "a load resets X" hunt needs them.
2. **M2, the engine's limits** (plan 0012 §4), none of it written yet:
   - the view ends if the viewer hasn't caught up within 60 s after the load, or falls more than 60 steps behind for 60 s;
   - a battle or a prompt for the viewer ends the view (the prompt part exists at the thaw: `worldPromptOrQuestion` puts it off);
   - the viewer's room plans across the view.
3. **M3, the mod** ([city-viewing.md](city-viewing.md) §1 to §4): the View city button, the request and the Allow/Refuse popup, `VIEW_ASK`/`VIEW_ANSWER`/`VIEW_CLOSE`, the `VIEW` and `VIEW_HASH` streams, protocol 13, the limits' messages, the playtest log lines. SOS-81 also takes protocol 13, so take the next free number when SOS-81 merges first.
4. **M4:** `tools/twopc.py --view` on two PCs (the laptop over Radmin, memory `laptop-rig`). Then the PRs (SOSDET first, then SOSGSGMP), Jira to In Review with a comment, and HANDOFF.md.

## 5. Known gaps

- A room still being drawn in the placer is lost on a reload (on the owner at F, on everyone at a reload). Noted in city-viewing.md §6.
- `RaidingMap.process()` also runs from the UI (hovering the raid map). That can move its `AUp` per PC and so at any time, view or not. This was here before SOS-82; nothing was seen in runs.
- Only the 3-copy rig has run a view; two real PCs haven't yet.
- The spec text needed no change for the 2026-10-28 decision: §10 and the §15 rows already say "a world reload, about a second". ADR 0006 and plan 0012 have the new state and the numbers.

## 6. Running the rig for SOS-82

From `C:\ClaudeFolder\SOSDET-wt\sos82`, after `gradlew.bat assemble` (run through PowerShell):

```bash
python rig/rig.py city --tag V82AC --save Mogus-72-19f86f1e048-47002c-0-121c.save --no-invade --copies 3 --humans 3 --steps 3600 --view 2400:2:1:2900
```

- `--view STEP:VIEWER:OWNER:THAW` uses copy numbers.
- Add `--dump-at STEP:K,K` for state dumps in `build/rig/inst/K/songsofsyx/sosdet-rig/`.
- Rig rules from memory:
  - list `java.exe` first; another session's rig runs outside `sos82` must finish before yours;
  - put a monitor on every run, every minute;
  - keep runs short and windowed.
- A view run takes about 4 minutes on this PC.
