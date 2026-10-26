# A player's name and banner on every PC (SOS-78)

Card: [SOS-78](https://fighterx2500.atlassian.net/browse/SOS-78). Spec §5 World rules (0.22). Status: approved (owner, 2026-10-27).

## 1. The bug

When a player changed their faction's name, ruler name or banner in game, only their own PC showed it. The other players kept seeing the old ones.

**Cause, in the engine (SOSDET):**
- The profile window's inputs, arrows, colour sliders and Accept already turn every change into a command (`cmd.Names`: 5002 factionName, 5003 rulerName, 5016 playerBanner).
- But those ids were in the city range. `CommandParts.world` counted only 7000–8999 as world commands.
- In own-city mode, a city command applies only on its sender's PC, so the change never reached the others.
- The world hash didn't cover names or banners either, so the PCs drifted apart without a desync.

## 2. The fix (SOSDET)

1. **World commands:** the player-identity commands (5002, 5003, 5016, plus 5009 and 5015 from older logs) are now world commands. Every PC applies them in the world part, at the host's step, for the human who sent them. Division, room, mark and subject names stay in the sender's city.
2. **The hash:** the "factions and stances" part now covers each human's name, ruler name and banner.
   - This PC's own player's banner is hashed as every PC has it: the banner as it was before an open draft, and the one before an Accept until that Accept's command applies.
   - So editing a banner, and the moment between Accept and its step, never look like a desync. `BannerDraft` counts the Accepts in flight, and a load resets them.
3. **The draft:** a banner command drops only its own PC's draft. Before, every PC applying it would have thrown away another player's unfinished edit.
4. **Profile Load:** `UIProfile`'s Load, in patch 96, sent nothing. It set the name, ruler name and banner on one PC. Now it sends the same three commands.
5. **Saves:** nothing new. `Faction.save` and `Player.save` already store the names and the banner, and the campaign save is that world save.

## 3. Mod (this repository)

- **Test tooling:** the autopilot gets `rename=STEP`. The player renames the faction and the ruler and changes the banner's shape and a colour, through the same engine hooks as the profile window (`sosgsgmp.game.FactionIdentity`).
- **What every PC reports:** each player's name, ruler name and banner with every hash, as an `identity` event.
- **`tools/twopc.py`:** gets `--rename STEP` and `--expect-renamed`, for a resume.

## 4. Checks

- **Reproduce first:** two PCs with the engine before the fix, `--rename`. The host doesn't see the joiner's change.
- **SOSDET unit test:** `CommandPartsTest.thePlayersNameAndBannerAreWorldCommands`.
- **Two PCs with the fix:** `--rename`. Both PCs report the same identities, including the joiner's change, and the hashes match.
- **Resume:** `--save` after the rename, then `--resume --expect-renamed`. The resumed campaign shows the new name and banner on both PCs.
- **By hand, the owner's playtest:** rename and change the banner in the profile window, and use the profile's Load, with a second player watching.

## 5. Results (2026-10-27)

- **SOSDET build:** 156 tests pass, including `CommandPartsTest.thePlayersNameAndBannerAreWorldCommands`.
- **Two PCs** (this PC hosts, the laptop joins), with `tools/twopc.py`:
  - **R78A, the reproduction, on the engine before the fix:** `--rename 600`. The joiner saw `Joiner Renamed|Joiner Ruler|1|63,…`; the host still saw `Mogus|bob|0|127,…` for the joiner's slot. The world hashes were the same (0 differ), since they didn't cover names: the bug.
  - **F78A, with the fix, pass:** `--rename 600 --save 1800`. Both PCs report the same names, ruler names and banners, the joiner's change included, and the hashes, which now cover them, match.
  - **F78R, pass:** `--resume --expect-renamed`. The resumed campaign shows the joiner's new name and banner on both PCs. 3,000 steps, 0 differ.
- **Not checked yet, by hand:**
  - the profile window's own inputs, arrows, sliders and Accept with a second player watching;
  - the profile's Load;
  - editing a banner while the other player changes theirs.
- **Known limit:** a name or banner change made during a battle on the map is skipped on every PC, like every world command then (SOS-41). Before, it applied on one PC and was undone when the battle's end reloaded the game.
