# Batch 12 (owner, 2026-11-16)

Three items from the owner's Steam session of 2026-11-16 (16:01 to 16:15), built as one batch with one commit per item, then tested in one cycle.

1. A player the host continued without can't join again.
2. The buttons of the player-left window aren't centred.
3. A resume can start without a missing player and keep them frozen (section "Item 3" is added once it is planned).

Branch `batch-12-1116`, worktree `C:\ClaudeFolder\SOS-wt\b12`. Item 2 is built on `batch-12-gui` (`C:\ClaudeFolder\SOS-wt\b12-gui`) and picked onto the batch branch before the tests.

## Item 1: a removed player joins again as a new player

### What happened

Host log `20261116-160135-host-FighterX2500.log` and its Steam log `20261116-160122-steam.log`:

- 16:02:04 the joining player left the game; the host chose Continue without them (16:02:26).
- 16:02:54 the same player joined the Steam lobby again ("peer 2 connected after the start").
- 16:02:56.740 the host closed that Steam session, with no note of a join request. Nothing more happened for the player, whose screen stayed on "Connecting to the host..." until the host quit at 16:04.
- Meanwhile the player's game kept asking for a session every 10 seconds, and never left the lobby.

Two causes, one behind the other:

- **1A, the rule.** `HostedSetup.hello` refuses a key the campaign lists as removed (`Refusal.REMOVED`), as spec section 11 says: "They can't rejoin the campaign as a player." The owner now wants a removed player to be able to join the running game again through the normal join (owner, 2026-11-16). This changes a rule of the spec.
- **1B, the refusal never arrives over Steam.** `HostedSetup.refuse` sends `Refused` and calls `node.disconnect(peer)`. `SteamNode`'s `Command.Drop` closes the Steam session in the same pass of the I/O thread, and Steam throws away what it hasn't delivered yet. The joining game never hears `Refused`, so it never closes and never leaves the lobby. Every refusal sent over Steam has this race: Refuse in the join popup, a join while another is under way, a full game, other game files, an eliminated player, a spectator dropped as too slow, a lost player removed while their line is open.

### 1A: the rule

**New rule.** A player the host removed (Continue without them, a resume started without them, a frozen player removed for good) has nothing left in the campaign, and can join its running game again as a **new** player: the host's Accept, a new race, name and spot, a new city-state, the campaign's starting resources. Nothing of their old realm comes back. An eliminated player stays a spectator and can't join as a new player. A resume's lobby still refuses a removed player: there is no join in a lobby, so the text tells them to join once the game runs.

**Code.**

- `HostedSetup.hello`: a key the campaign lists as removed and not eliminated (`Campaign.Player.refused()`) is taken as an unknown key: a new candidate, with the name its hello carries, `Running(false)`. An eliminated key is refused with a text of its own (below). A key that is playing now, or frozen, goes as today.
- `Campaign.adding(key, name, slot)`: a removed, not eliminated entry with the same key is replaced by the new one. Without this the constructor throws ("two players with one key") and the restart's `HostedSetup` would read the old slot from `campaign.player(key)`.
- The same for a removed, not eliminated entry in the **same slot** with another key: `Engine.joinSlot()` is the first inactive NPC slot above the last human's (`GameHumans.joinSlot`, SOSDET), so after the highest human is removed, a newcomer can get a slot the campaign still lists for a removed player, and `adding` throws on the host at the restart. Check by reading the engine whether a removed human's slot can come back from `joinSlot()`; if it can, drop that entry in `adding` and test it. If an eliminated player's slot could come back too, don't change anything for it: report it.
- `Refusal.REMOVED`'s text states the old rule. New text: "The host removed you from this campaign. You can join it again as a new player while its game is running." It is used by a resume's lobby (`Lobby.refusal`) and by `HostedSetup.dropLost`; it is right for both.
- A new `Refusal.ELIMINATED`, appended at the end (the enum is sent by its ordinal): "You were eliminated in this campaign. You can come back to watch when it is resumed." `hello` uses it for an eliminated key. No protocol change: `hello` checks the builds first, so a PC with another build never gets the new ordinal. Say in the report how `Protocol` decodes an ordinal it doesn't know.
- `MpScreenLobby`'s status line for a resume with players missing says "they are removed from the campaign and can't play in it again". Change the end to fit the new rule. (Item 3 changes this line again.)
- The returning PC still has its records and saves of this campaign from before it was removed, with its old slot. Read the newcomer's path (`JoinedSetup` on `Restart`, `MpScreenLobby`'s restart branch, `MpWorldLoad`'s newcomer constructor, `MpSaves.store()`) and confirm nothing takes an old record for the founding save or for the PC's slot. Fix what does; say what you checked.

**Spec** (`docs/spec.md`, in the same commit; the owner asked for the change on 2026-11-16):

- Section 11, Removing a player: replace "They can't rejoin the campaign as a player." with the new rule.
- Section 11, the paragraph after the three choices ("A disconnected player rejoins a running session only after a freeze; otherwise they come back only through a resume."): a disconnected player gets their city back in a running session only after a freeze, or through a resume of a save from before they left; a removed player can join again as a new player (see Joining).
- Section 11, Joining: one sentence that a removed player joins the same way.
- Section 11, Resuming and Spectators: check "a player left out is removed" and "A player removed after a disconnect stays out" still read true; reword only what the new rule makes false.
- Section 15: the criterion "they can't rejoin as a player" becomes "they can join again only as a new player, with a new city-state".
- The version number and the decisions table, as the file's earlier changes did them.

**Tests** (write the first before the fix and show it failing):

1. `LoopbackSetupTest`: three PCs in play; one leaves; the host chooses Continue; a new session with the **same key** says hello. The host is asked (`joinAsk`), accepts, the candidate picks, `restart` makes the new setup; its campaign lists that key once, not removed, in the new slot; the next resume's lobby takes that key.
2. `CampaignTest` (or where `Campaign` is tested): `adding` over a removed entry with the same key; over a removed entry in the same slot with another key (if the engine allows it); an eliminated entry is never replaced; a playing or frozen entry with the same key or slot still throws.
3. An eliminated key's hello during play gets `Refusal.ELIMINATED`.
4. `LobbyTest`'s "a removal is for good" still holds for a resume's lobby; rename it if its message is now wrong.
5. Save and load: a `CampaignRecord` round trip of a campaign after such an `adding`.

### 1B: a dropped peer still gets what was sent to it

- In `SteamNode`, a `Command.Drop` of a line that isn't broken forgets the peer as today, but closes its Steam session later: when that Steam ID leaves the lobby (the normal case: its game got the refusal and closed), or after a limit (propose one; 10 seconds is my guess), whichever comes first. A Steam ID that becomes a peer again meanwhile (`added`) keeps its session: the pending close is dropped. The node's own close still closes every session.
- Nothing from that Steam ID is read while it isn't a peer; that is so today (`readPackets`).
- `drop(peer, reason)`, the I/O thread's own drop of a peer that broke the protocol, stays as it is: nothing is owed to that peer.
- `FakeSteam.closeSession` only un-accepts today, so the fake delivers what the real Steam drops. Make the fake drop what was sent to a user and not read yet when the session closes, so the test below fails before the fix. If an existing test then fails for a reason other than this bug, stop and report.
- Test (`SteamNodeTest`): a member joins a hosting node; the host sends `Refused` and disconnects it in the same update; the member receives `Refused`; after the member leaves the lobby the session is closed. A second test: the member never leaves; the session closes at the limit (use a short limit through the constructor, as `joinMs` does).
- Direct IP already delivers before it closes (`TcpNodeTest`, line 157): confirm by reading, change nothing.
- Not verified by this: the real Steam. Valve's documentation doesn't say what `CloseSessionWithUser` does with queued messages; the log above is the evidence. Say so in the report.

## Item 2: the player-left window's buttons aren't centred

Screenshot: `C:\ClaudeFolder\PTreport\NotCenteredGui.png` (the host's window with Continue without them, Freeze them, Return to the last save).

- **Cause.** Vanilla's `IPromtScreen.activate` puts button *i*'s centre at `WIDTH/2 - n*w/2 + 2*w*i` with `w = WIDTH/12`, which is centred only for two buttons: one button sits half a slot left, three sit half a slot right. `MpPrompt.show` exists to fix this, but `MpGone.show` (two and three buttons) and `MpDesync.show` (two buttons) call `VIEW.inters().fullScreen.activate` themselves.
- **Fix.** Every window of the mod with buttons goes through `MpPrompt.show`. Windows with no button stay as they are.
- **Also in the screenshot:** the text is cut after "Freeze them: they keep their capital until they". Vanilla draws the text in a box 600 by 100 and the buttons start under that box. Find out which of the mod's windows have a text longer than the box, and what vanilla does with the rest (cut, or drawn under the buttons). Don't redesign: report each window with its screenshot and propose the smallest fix (a hook into vanilla needs the owner's approval; a shorter text doesn't).
- **Test, in the batch's test cycle:** a screenshot of every window of the mod that has buttons (`MpGone` with one, two and three buttons, `MpDesync`, `MpExit`'s three, `MpClosed`, `MpMismatch`, the join popup `MpJoinAsk`, the lobby's and escape menu's yes/no), at 1920x1080 and at 1280x720, with the buttons' measured left and right gaps.

## Item 3: start a resume without a missing player, frozen

To be planned.

## The test cycle

After all three items are on the batch branch. In-game runs take turns with any other session's: before starting a game, list `java.exe` and wait while a game that isn't this run's is up. Games run windowed (`SCREEN_MODE 2`), with a check every minute for a stall or a crash file.

1. Item 1: a two-game run on this PC over direct IP: the joiner leaves in play, the host continues without them, the same game starts again and asks to join, the host accepts, it settles, every game restarts, and both play on with equal hashes.
2. Item 2: the screenshots above.
3. Item 3: with its plan.

No other in-game runs.

## Rules for the work

- AGENTS.md applies in full. One commit per item. No push, no PR, no merge, no `installMod`.
- Build with `SOSDET_DIR=C:\ClaudeFolder\SOSDET-wt\pack-1116b` (SOSDET `origin/main`, built). The jar in `C:\ClaudeFolder\SOSDET\build` is older than `main`.
- Per item: `gradlew.bat compileJava compileTestJava`, then `gradlew.bat test`, then `gradlew.bat build`.
- No hooks. No protocol version change unless a wire format changes; if one must, stop and report.
- Spec sections touched: 11 (Removing a player, Joining, Resuming, Disconnects) and 15. Item 1A changes a rule, at the owner's word of 2026-11-16.
