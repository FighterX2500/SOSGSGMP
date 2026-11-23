# The joiner's city gets no roads (SOS-75)

Card: [SOS-75](https://fighterx2500.atlassian.net/browse/SOS-75). Spec §3 (each player's city as in single player) and §11 (setup). Status: approved (owner, 2026-10-26: "Make both. Run diagnosis and fix."); diagnosed and fixed, in review.

## 1. What we know

- **Where it happened:** in twopc runs S64A, D65D and D72B2. Each time, the joiner's own city was built with no active entry points.
- **What the game reports:** vanilla's `GeneratorRoads` warns "No active entry points exist", then prints "The city map did not generate any roads, and the city will be unplayable". The error window opens when the game quits, because vanilla shows its error output then.
- **Whose city:** only the joiner's. Joiners build their city after the setup save loads, with `MpWorldLoad.load` calling `Engine.ownCityAt`, then `GameOwnCity.make`, then `CreateFromWorldMap(x - 1, y - 1)`. The host's city comes from the world build, the way vanilla's `StageFinish` builds it. The host has never shown the warning.
- **The capitals:** the runs used the same seed, 12345. In D72B2 the autopilot placed the host at 91,122 and the joiner at 160,137, and the other runs most likely picked the same spots.
- **The check that fails:** for each entry point on the city map's edge, `GeneratorRoads` asks two things about its world tile, at the capital's 3x3 area plus the entry's offset:
  - whether the world's path map lets you leave in the entry's direction (`WORLD.PATH().map.can`);
  - whether the tile is good land (`WTRAV.isGoodLandTile`: not a mountain, not big water).

  It warns when no entry point passes both.
- **The path map's state:** it is saved with the world and loaded with it (`WPATHING` saver), so after the load it should be ready on the joiner.
- **Why the runs still passed:** each PC's own city is outside the shared hash. The bug only affects the joiner's own game.

## 2. Two possible causes

1. **The spot is bad:** any city at 160,137 would get no roads, in single player too. The location check (`WorldCentrePlacablity.terrain`) checks the 3x3 square, not the tiles the entry points look at. Players could still pick such a spot.
2. **The joiner's build is wrong:** something the check reads isn't right on the joining PC when its city is built. Candidates: the path map near its capital, or the capital area's tiles (`CapitolArea.init`). Then every joiner could be hit.

## 3. Plan

**Step 1: the diagnosis.** No fix yet; it settles which cause it is.
- **A one-shot diagnostic log** in the mod's adapter (`WorldSetupAdapter.ownCity`), just before the city is built. For each world tile the entry points look at, it logs `map.can` in the entry's direction and `isGoodLandTile`; afterwards, the number of active entry points. It runs once at setup, outside the step, and feeds nothing into the step or the hash.
- **A new autopilot option, `capital=X,Y`:** that PC places its capital there instead of searching. `twopc.py` gets `--host-capital` and `--join-capital`.
- **Three short twopc runs**, each about 600 steps, with a monitor every minute:
  - **R75A:** as before (host at 91,122, joiner at 160,137). It should reproduce the warning, with the log showing which check fails.
  - **R75B, the slots swapped:** the host at 160,137, the joiner at 91,122.
    - If the host's city at 160,137 has no roads, the spot is the cause (cause 1).
    - If the host's city is fine there but the joiner's at 91,122 has none, the joiner's build is the cause (cause 2).
  - **R75C:** the joiner at a third spot, open inland land, as a control.

I'll report the results and stop there for your choice of fix.

**Step 2: the fix, after your OK.** It depends on the cause:
- **Cause 1:** `Engine.locationProblem` also refuses a spot whose entry tiles have no way out, with a clear reason ("no road could leave the city here"). That way the setup map, and the autopilot, never offer it. The rule lives in SOSDET's `GameWorldSetup.terrainProblem`, beside vanilla's check, as an SOSDET PR. It is tested with a twopc run at the old spot, which must now be refused.
- **Cause 2:** fix what the joining PC has wrong before `ownCityAt`, in SOSDET. Which change that is depends on what step 1 shows. It is tested with a twopc run (no warning, active entry points > 0 in the log) and a resume.

Either way, the diagnostic log stays as one line per setup ("own city: N entry points active"), so a playtest log shows it.

## 4. Not in this card

- The warning's window on the laptop at the end of runs: it goes away with the cause.
- Other city-generation differences between the host and the joiners.

## 5. Results (2026-10-26)

**Cause: the spot (cause 1).** The joiner's build is not at fault.
- **Every world build differs.** The same seed gives the same terrain, but a different world: D72B2, R75A and R75A2 all have different world hashes. So the regions, roads and the world's path network change from run to run.
- **What 160,137 is.** It's an islet. Its only land tiles are the centre and its east neighbour, and every tile beyond the capital's square is water. It still passes vanilla's capital check, which wants only a land centre and one land neighbour.
- **What happens there.** The city map gets an exit only where that world's path network leaves the square. At this islet the network sometimes leaves only by water, and sometimes not at all.
- **R75B3 shows it.** The host built its own city at 160,137 the normal vanilla way, in the world build. It got 0 active exits and the same warning. In other worlds (R75A, R75B1, R75B2, R75C) one exit opened, each time a different one.
- **175,112 is another islet** (R75C).

**The fix:** SOSDET's `GameWorldSetup.terrainProblem` now adds a rule beside vanilla's check.
- **The rule:** the capital needs a good land tile on its square's edge whose neighbour straight out is good land too (`landExit`).
- **When it's refused:** "No road could leave a city here: it needs land beyond its square."
- **Where it applies:** the setup map, the autopilot and the host's world build all use it, through `Engine.locationProblem`.
- **What it checks:** terrain only, because the path network doesn't exist yet when a location is picked.
- **The spec:** 0.19 adds the rule to §5, rule 2.

**The mod:**
- One log line per setup: "own city: N entry points active".
- The autopilot gets `capital=X:Y`, which falls back to its search when the rules refuse the spot. It also reports the active exits.
- `twopc.py` gets `--host-capital` and `--join-capital`, and fails a run in which a PC's own city has no active exit.

**Runs, two PCs, 600 steps each unless noted:**
- **R75A, R75A2 (with a save at 300):** PASS, with roads. Joiner at 160,137, 1 exit each time.
- **R75B1 to R75B3 (300 steps):** host at 160,137. It got 1, 1 and then 0 exits: R75B3 reproduced the bug in vanilla's own build path. These runs fail on the hash check because they were too short to reach one.
- **R75C:** PASS. Host at 160,137 with 1 exit; joiner at 175,112, also an islet.
- **After the fix:**
  - **R75D:** PASS. Both islets were refused with the reason, each PC searched instead (91,122 and 185,148), and the cities got 1 and 3 exits.
  - **R75E1, R75E2:** PASS, with the default searches. Joiner at 185,148, every city with 1 to 2 exits, and no error window.

**Not covered:**
- A capital with land beyond its square could in principle still get no exit; no run showed one.
- Vanilla allows islet capitals, so this rule is stricter than vanilla.
