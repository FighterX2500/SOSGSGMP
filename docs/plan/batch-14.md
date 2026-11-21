# Batch 14: the Steam pass of 2026-11-22

The owner's Steam test of 2026-11-22 (SOS-191): one PC, Valve's Spacewar (480), the main account outside and a spare account in a Sandboxie-Plus box. Hot-join through Join through Steam passed end to end; Refuse passed. These items are what it found. Owner's go: "Open cards, start resolving the issues" (2026-11-22), with two decisions: Steam once per run (SOS-237) and a frozen city that doesn't trade (SOS-240).

Branches `batch-14-1122` in both repos (worktrees `SOS-wt/b14`, `SOSDET-wt/b14`), cut from main on 2026-11-22 (mod a87c3b7, engine a493453).

## Items

| # | Card | Repo | What | Commit |
|---|---|---|---|---|
| 1 | SOS-242 | mod | The friends' lobby list uses the App ID Steam started with, so Steam tests run under 480 with an account that doesn't own the game | b56e30a |
| 2 | SOS-237 | mod | Steam once per game process: `SteamLease` lends the one `SteamworksPort` to one node at a time; nothing calls `SteamAPI_Shutdown`, which crashed the host at End for everyone ([ADR 0012](../decisions/0012-steam-once-per-process.md)) | cdc9813 |
| 3 | SOS-238 | mod | Steam invites and Join Game are heard on the Multiplayer, Create Lobby, Load Saved Lobby and Join by IP screens too, not only the main menu | 195e114 |
| 4 | SOS-241 | mod | The host's log says who was refused at a join and why; frozen players are named after the player in view messages and the transfer log | cc42649 |
| 5 | SOS-240 | mod + engine | Spec 0.72 §11: a frozen city neither buys nor sells, and nobody trades with it. Engine: `TradeManager` skips a dormant human's round, `TradeShipper` leaves it out of trade partners (patches 14 and 15) | mod e87f0bf, engine 56e7d37 |
| 6 | SOS-239 | engine | Remove them at a resume's start no longer crashes when a missing player has armies: the human retires on the save's last report, or an empty one | c151497 |
| 7 | SOS-243 | mod | A closing `TcpNode` shuts its sockets down cleanly and reads what still comes for up to 0.5 s, so a Windows reset no longer loses a leaver's Leave (`LoopbackSetupTest` flake) | ebe5ee1 |

Left for later (not in this batch): `+connect_lobby` when an invite starts the game (SOS-238's note); an invite accepted during a game.

## Tests

- Mod unit tests: `SteamLeaseTest` (6, new), `SteamNodeTest` (19), `LoopbackSetupTest` (53, with the refusal note asserted in the hot-join test).
- SOS-243: `aPlayerLeavingUnblocksConfirmAndLeavesEveryRoster` repeated: 1 failure in 40 before, none in 240 after.
- Engine: `EngineTest` with two new tests (a removal at the start retires on the save's report, and on an empty one for an older save); both fail without the fix. Engine `gradlew.bat build`: 461 tests, no failures, the patched-class checks pass.

## Needs the owner's manual session (Steam, one PC, 480 + Sandboxie)

1. **SOS-237:** a joiner's Quit to menu → End for everyone; the host's game stays, and `Steam\dumps` gets no new `crash_java` or `assert_java` dump. Also leave and re-create a lobby a few times.
2. **SOS-238:** with game 2 on the Multiplayer screen (and on Create Lobby), accept an invite in the box's Steam, then Join Game; each joins, with no "already running" popup.
3. **SOS-239:** save 94, Start without, **Remove them**: the host plays on.
4. **SOS-240:** save 94 with Freeze them: no transfer lines for Fray2's or Adam's city past step 15267 (before: sales at that step).
5. **SOS-241:** a refused join shows "*name* (peer N) may not join: *reason*" in the host's log.

Not run: the engine's scenario suite (`rig\suite.py --short`, ask the owner first), twopc.

## State on 2026-11-22

All seven items committed on both branches; mod build against the batch engine and docs (architecture, ADR 0012, spec 0.72, engine hooks and requirements) done.

Owner's manual session, 02:49 to 03:03 (two games, 480, game 2 in the `Steam2` box, the batch builds):

- SOS-237 PASS: a joiner's Quit to menu, End for everyone: save 117 on both PCs (hash 8f84e10f4bdd650b), both games stayed up, the host made a new lobby 23 s later. No shutdown dump (`PostConnectionStateMsg`, `Invalid pipe handle`); at game exit Steam prints "Trying to close low level socket support, but we still have sockets open!" with no dump.
- SOS-238: game 2 joined the lobby through Steam four times with no "already running" popup; which screen it was on isn't in the logs (asked the owner).
- SOS-239 PASS: save 94, Remove them: slots 1 and 2 removed at the start, play went on.
- SOS-240 PASS: save 94, Freeze them, run past step 48000: no sale or purchase by Fray2 or Adam (before: six sales at step 15267); only deliveries already on the road arrived (steps 166 and 6350).
- SOS-241 PASS: "FighterX2500 may not join: DECLINED" in the host's log; frozen players named in transfer lines.
- Hot-join over Steam again end to end: slot 8 at 165,57, world hash ede886a021f66a46 on both PCs.
- Seen, not from this batch: one `Assert( !IsLocked() )` Steam dump on the host as a PC joined (17 older ones since 11-16); the host's log repeats the previous session's chat notices after a hot-join restart (`MpLog.chatCount` counts from 0 while the chat carries over): log only.

Owner, 2026-11-22: "All works as intended". Main has nothing new since the branches were cut, so no rebase. Pack SOSGSGMP-playtest-2026-11-22 made from the tested jars of these branches. Follow-up cards: SOS-244 (the host's log repeats the chat after a hot-join restart), SOS-245 (`+connect_lobby`).

Next: push both branches and open the PRs, engine first.
