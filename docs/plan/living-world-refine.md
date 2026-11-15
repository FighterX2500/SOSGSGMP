# Living world: refining the settling (2026-11-15)

The owner's review of a living-world lobby (2026-11-15, pictures in `PTreport/LivingWorldPics`) asked for:

1. The picked spot drawn as one thick square, as thick as the placer's own edges, without the placer's nine cells.
2. The settling on the same screen as the world generator's Place Capital: its side panel, its hover window (climate,
   resources, moisture, fresh water, forest, open land), the notice about a guaranteed connection to another player,
   and a new line under it on the road links the spot gets to NPC states, so a player sees a spot on a border of two
   or three states.
3. The world already built while players pick, so they can look at the NPC states.
4. The host carves their city-state too, on the same screen; no host location before the world is built.
5. Hot-join gets the same screen.

The owner was away and allowed cards, decisions (logged here and in the report), implementation and tests.

## Cards

- **SOS-212** The picked spot as one thick square (living world, hot-join).
- **SOS-213** Settling on the world generator's screen, with the spot's hover window and the notices; the same on
  hot-join.
- **SOS-214** The host carves their city-state too (engine and mod).

## Decisions

**D1. One screen for every pick on a built world.** The living world's settling (host and players) and the
hot-join use the hot-join's location screen (SOS-198): vanilla's world generator view on the loaded world, the side
panel on the left ("Place your city-state": players, status, buttons, Leave), the minimap, no game bar, no fog. The
built world shows there with its towns, names, borders and the minimap's realm colours: the NPC states can be looked
at. The settling has no Back (race and faction were picked before Build World); the host's panel has Initialize
World next to Ready.

**D2. The spot's hover window** is vanilla's Place Capital window (`UIWorldToolCapitolPlaceInfo`: climate and its
boosts, resources, moisture, fresh water, forest, open land), with two lines above it: the land's region and who holds
it, and the regions the city-state would get a road link to, each with its state. The host's stand-in capital (D4)
says what it becomes.

**D3. The notice under the top of the screen** keeps the classic setup's first line, "Guaranteed connection to
&lt;player&gt;", for another player 6-15 tiles away (in a living world and on hot-join that guarantee is SOS-120's market
route, spec section 5 Living world 6). A second line under it says where the road links go: "Road links to Mystise
(Garthimi)" for one region; for two or more, "On the border of 2 states: Garthimi, Ruuvit" (or "Road links to 2
regions of Garthimi" when one state holds them all). Unowned land counts as "free land". The line follows the cursor
over a spot the rule takes, else this PC's picked spot.

**D4. The host carves too.** Vanilla's generator needs a player capital (region 0, `GenPlayer`, the roads and paths
start there), and the game's code assumes the player always has one (for example `WorldView`'s throne key), so the
host can't be without one while the world loads for the settling. So:

- Build World generates the world around a **stand-in capital** for the host at a spot vanilla's generator picks
  itself (`GenPlayer` with no location: random land where a capital fits). Every PC loads that world and the host
  picks a spot on it like every other player.
- Initialize World carves the host's city-state first (slot 0), then the others in slot order. The host's capital
  moves to their carved region, and the stand-in becomes a new neutral NPC state, protected as the carve rule's new
  states are (spec section 5 Living world 5); with no free faction slot it is left unowned. The host's city is then
  generated at their carved capital, before the world is saved, as Build World did at the stand-in.
- While the players pick, the stand-in shows as the host's town; its hover says it becomes a neutral state.

Rejected: generating the world with no player capital (a deep change to vanilla's generator, region 0 and the
pathing); a host without a capital while picking (crash risk in vanilla code that reads the player's capital); moving
region 0's tiles into a neighbour (leaves stray roads and links).

**D5. Thick square.** One frame, two of the game's sprite pixels thick (8 world pixels, `C.SCALE * 2`), the
thickness of the placer's edges, in the colour each screen used (the own spot OK, others BAD). Every other player's
spot is drawn the same way, with their name over it.

## Tests

- Engine unit tests where plain Java allows; the rest in the two-PC rig.
- `twopc --living --gui-preview`: shots of the host's and the joiner's settling screens, the hover window, the
  notices; Initialize World; both land; the host's capital at their own spot; the stand-in an NPC town.
- `twopc --hot-join` with `--gui-preview`: the newcomer's location screen with the hover window, notices and frame.

## Found while testing

- **LW5** stalled after Build World: the settling screen pumped the session from its side panel's render, which
  vanilla's "click to continue" after the load doesn't draw. The settling now pumps after every game update, as the
  factions step does (the old settling panel was an interrupter and drew under it).
- **Rig CS2** (engine, setup by carving with the host carved first) failed: "no road from 120,110 to the town of
  region 148". The carving rule's road check let the way cross the rest of the spot's old region, but the carve's
  link, laid once the square is its own region, crosses only the square and the region it links to. The check now
  walks the same land. A spot the rule takes can't fail its carve that way any more.
- **LW6** shot: the names over other players' spots were drawn in world pixels and shrank with the zoom; they are now
  drawn at the game's label size.

## Results

| Run | What | Result |
| --- | --- | --- |
| LW6 | `twopc --here --living --gui-preview 0`, 900 steps | PASS: both settle (host 222,228; joiner 215,221), capitals at the spots, hashes equal |
| CS3 | `rig.py city --humans 3 --new-world 12345 --carve-setup`, 3 copies, 1,500 steps | PASS: host carved first, the stand-in (region 0) to NPC 31, round trip keeps the humans, copies identical |
| HJ10 | `twopc --here --living --hot-join 20 --gui-preview 0`, 6,000 steps | PASS: the late game picks on the same screen (hover window, notices, frames, names), every game restarts with it, hashes equal after the restart |
