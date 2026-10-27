# Handoff: SOS-82, city viewing

Written 2026-10-28 by the session that worked SOS-82, for the session that carries it on. The general handoff is [HANDOFF.md](HANDOFF.md); this file covers only SOS-82. Fold it into HANDOFF.md once the card's PRs are open.

## 1. Where the work is

| Repository | Worktree | Branch | Last commit |
|---|---|---|---|
| SOSDET (engine) | `C:\ClaudeFolder\SOSDET-wt\sos82` | `SOS-82-city-viewing` | M1 `46dfff2`, M2 `4beedf2`, `Engine.viewRefusal` `7e3460a`; on `409c5c7` (main after SOSDET #51) |
| SOSGSGMP (mod) | `C:\ClaudeFolder\SOS-wt\sos82` | `SOS-82-city-viewing` | rebased on `96de87a` (main after #56, SOS-81); the plan commit, then M3 |

**The mod needs SOS-81's engine.** SOSGSGMP main (since #56) uses SOSDET's `TransferLog`, which is on SOSDET's `SOS-81-transfer-log` (`503c061`, PR #52, not merged), not on SOSDET main or on `SOS-82-city-viewing`. So the mod doesn't compile against the sos82 engine as it is. `origin/SOS-81-transfer-log` merges into `SOS-82-city-viewing` with no conflict; M3 was built and tested against such a merge in a throwaway worktree. Before M4 (and `installMod`), merge it into the SOSDET branch, or wait for #52 and merge main.

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

### Built since (2026-10-28, second session)

- **The debugging tools** stay (main session's decision): `StateDump` and the rig's `--dump-at`/`--dump-reload` in the rig; `Engine.reloadOwnForDebug`, `Engine.debugAt`, `Engine.traceAllDraws` and the `Session` fields behind them are marked "test tooling" in their javadoc.
- **M2, the engine** (plan 0012 §2 "The ends", `ViewTest`): `ViewLimit` (catch-up and keep-up limits on the viewer's clock), `Engine.viewLimit()`, `Engine.viewEnd(viewer, why)` with `View.why()`/`reason()` on every PC, a battle ending every view at its Engage, the thaw put off while a battle runs or a world prompt is open (decided at the end of the step before it, `Session.viewsEnd`), a view ended in its own freeze step loading nothing (`View.loaded()`), `Engine.viewRefusal` for the host. Room plans: a room the viewer was still drawing is lost at the view's load (documented; built plans are in the save). Engine.java's `§` signs, mangled by the M1 commit, are fixed.
- **M3, the mod**, protocol 14 ([city-viewing.md](city-viewing.md) §2): `ViewRequests` (the flow, plain Java), the five messages, `MpViews` (host checks and begins and ends, the owner's popup `MpViewAsk` and its city and hashes going out, the viewer's loading screen, load, hashes, limits and bar `MpViewBar`, the messages `MpViewNotice`, the playtest log), the faction window's **View city** button, a campaign save skipped on a PC that views, the autopilot's `view=`/`viewanswer=` and `tools/twopc.py --view`, `--host-view`, `--view-answer`.
- Rig options for the limits: `--view-limits CATCHUP:KEEPUP:STEPS` (shorter limits, `Engine.viewLimits`), `--view-stall STEP:MS` (the viewer's frame stalls once); the viewer's copy asks copy 1 to end the view through `view.close`, as the mod's `VIEW_CLOSE` does.

## 4. Next, in order

1. Merge SOS-81's engine into the SOSDET branch (§1), then `gradlew.bat assemble` there and `installMod` in both repositories.
2. **M4, the rig** (§6): the view run again (M2 changed the thaw's timing rule), then each limit.
3. **M4, two PCs:** `tools/twopc.py --view`, `--host-view`, `--view-answer refuse` (§6). The owner checks the GUI in game: the button, the popup, the bar, the messages.
4. The PRs (SOSDET first, then SOSGSGMP), Jira to In Review with a comment, and HANDOFF.md.

## 5. Known gaps

- A room still being drawn in the placer is lost on a reload (on the owner at F, on everyone at a reload). Noted in city-viewing.md §6.
- `RaidingMap.process()` also runs from the UI (hovering the raid map). That can move its `AUp` per PC and so at any time, view or not. This was here before SOS-82; nothing was seen in runs.
- Only the 3-copy rig has run a view; two real PCs haven't yet. Nothing of M2 or M3 has run in a game yet: unit and loopback tests only.
- The owner leaving while the viewer loads leaves the viewer's PC held (city-viewing.md §6).
- A battle during a view: every PC loads the battle, the viewer's PC with the owner's city in it, and the view thaws after the battle. Covered by `ViewTest` only; no rig run had a battle in a view.
- The spec text needed no change for the 2026-10-28 decision: §10 and the §15 rows already say "a world reload, about a second". ADR 0006 and plan 0012 have the new state and the numbers.

## 6. Running the rig for SOS-82

From `C:\ClaudeFolder\SOSDET-wt\sos82`, after `gradlew.bat assemble` (run through PowerShell):

```bash
python rig/rig.py city --tag V82AC --save Mogus-72-19f86f1e048-47002c-0-121c.save --no-invade --copies 3 --humans 3 --steps 3600 --view 2400:2:1:2900
```

The limits (M2), each with every copy's world compared to the end; the run's `events` show `viewlimit` on the viewer, `viewend` with `why` on copy 1, and every copy's `viewstate` ending at one thaw step:

```bash
# not caught up: a 1-second catch-up limit after an 8-second load (why 1)
python rig/rig.py city --tag V82AD --save Mogus-72-19f86f1e048-47002c-0-121c.save --no-invade --copies 3 --humans 3 --steps 3600 --view 2400:2:1 --view-limits 1:60:60
# fell behind: catches up (check viewcaughtup comes before the stall), then one 8-second stall at step 3300, a 2-second keep-up limit (why 2)
python rig/rig.py city --tag V82AE --save Mogus-72-19f86f1e048-47002c-0-121c.save --no-invade --copies 3 --humans 3 --steps 4200 --view 2400:2:1 --view-limits 60:2:60 --view-stall 3300:8000
# the host views (option A): every copy holds while copy 1 loads
python rig/rig.py city --tag V82AF --save Mogus-72-19f86f1e048-47002c-0-121c.save --no-invade --copies 3 --humans 3 --steps 3600 --view 2400:1:2:2900
```

A battle or a prompt ending a view needs one to come in the view's window; without `--no-invade` the save's invasion may bring one (`--view 2400:2:1:3300`, or with `--engage 0` for a battle on the battle map). Not tried; `ViewTest` covers both.

Two PCs (from `C:\ClaudeFolder\SOS-wt\sos82`, after `installMod` in both repositories; the laptop as in memory `laptop-rig`):

```bash
python tools\twopc.py --tag V82T1 --steps 5400 --view 1800:3600              # the joiner views the host's city 30 s to 60 s
python tools\twopc.py --tag V82T2 --steps 5400 --host-view 1800:3600         # the host views the joiner's (every PC holds while it opens)
python tools\twopc.py --tag V82T3 --steps 3600 --view 1800:-1 --view-answer refuse   # refused: no view begins
python tools\twopc.py --tag V82T4 --steps 5400 --view 1800:-1 --view-answer none     # no answer: refused after 30 s
```

The verdict adds: no `viewdesync`, and both PCs end the view at the same freeze and thaw steps (or, refused, never begin one). `--here` runs both games on this PC.

- `--view STEP:VIEWER:OWNER:THAW` uses copy numbers.
- Add `--dump-at STEP:K,K` for state dumps in `build/rig/inst/K/songsofsyx/sosdet-rig/`.
- Rig rules from memory:
  - list `java.exe` first; another session's rig runs outside `sos82` must finish before yours;
  - put a monitor on every run, every minute;
  - keep runs short and windowed.
- A view run takes about 4 minutes on this PC.
