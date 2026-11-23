# Reconnect fixes (review of 2026-11-16)

A review of the reconnect logic (SOS-177 to SOS-179) found two problems that go against spec section 11 as it stands. This plan fixes both, and one line of an older plan. No rule of the spec changes.

Not in this plan, each needs the owner first:
- a game that hangs (not crashes) is never "lost", because the heartbeats come from the I/O thread;
- the messages kept for a peer that is away have no size limit;
- a direct-IP corner: an unknown token with a received count of 0 is taken as a new peer.

## Fix A: Steam, a game that starts again under the same Steam ID

### The problem

Over direct IP a joining node has a random token, so the host can tell a returning connection from a new game. Over Steam the Steam ID is the only identity (`Resume.token` is 0). When the host still holds a line for a Steam ID and that player's game comes back as a **new node** (the game was restarted, or the player pressed Leave the game and joined again):

1. the host ignores the lobby join, because the Steam ID is a peer already (`SteamNode.Callbacks.memberJoined`);
2. the new node's `Hello` arrives as `Sequenced(1)` and is dropped as a repeat of the old stream;
3. the host's `Resume` carries the old stream's count, the new node finds it impossible (`Resend.after` throws) and drops the host with "It sent a broken message";
4. if the host has not chosen yet, the new node's `Resumed(0)` can make the old line look alive, so the host says the player is back for a moment.

A crash with Steam still running is not affected: Steam reports a Left and the peer is dropped cleanly. The case needs a line the host still holds: the player lost Steam or their internet, and the game then came back as a new node. It is the way back after **Freeze them** for a player who was lost over Steam.

### The fix

Give the Steam line the same identity the direct-IP line has, with no change to the wire format (`Resume(token, received, attempt)` already has the field). Protocol version stays 31.

- **Token.** Every `SteamNode` makes a random non-zero 64-bit token when it is created, as `TcpNode` does. A line's token is the **joining** node's token, as over direct IP. The host keeps it per peer (0 until it hears it). Every `Resume` carries it: a joining node sends its own, and the host sends the one it knows for that line (0 if none).
- **A new node shakes hands first.** A joining node, once it has its host (`lobbyEntered`), starts with its line waiting for a resume: it sends `Resume(token, 0, 0)` at once and every `RESUME_MS` until it hears `Resumed`. Until then it sends nothing else (messages are kept by `Resend`, as on a broken line, and go out on `Resumed`), and it ignores everything from the host but `Resumed`. A `Resumed` whose count is more than this node has sent is not for this node and is ignored too. So nothing of an older game's stream (an ack, a `Sequenced`, a `Resume`) can touch the new one. This mirrors a new TCP socket, which carries nothing before `Resume`/`Resumed`.
- **The join timeout** ("The host's PC didn't answer over Steam within 30 seconds") now ends when the first `Resumed` is taken, not at any packet.
- **Host, a `Resume` from a member:**
  - token 0, or no token known yet for the line, or the same token: as today (and the token is remembered);
  - another token: the player's game started again. The old line ends: if the sessions had dropped it already (`dropWhenSent`), silently; otherwise as a disconnect, `Disconnected(oldPeer, "Their game started again.")`, so the sessions treat the old player as gone. The Steam session with that user is **not** closed. Then the Steam ID becomes a new peer (`Connected(newPeer)`), with the new token, and the host answers `Resumed(0)`.
- **Joining node after its first handshake:** a `Resume` with a non-zero token that isn't its own is ignored.
- From there the new node's `Hello` reaches `HostedSetup.hello` exactly as a new direct-IP connection's does. Nothing above the node changes.
- Old build against new build still ends with the build refusal: a new joining node gets `Resumed` from an old host and then sends its `Hello`; an old joining node sends `Hello` at once and a new host takes it (no token known). Check both by reading; say so in the report.
- `Message.Resume`'s javadoc says "0 over Steam": update it. The first handshake must not log "is back; N messages go again".

### Tests (`SteamNodeTest`, with `FakeSteam`)

Write the first one before the fix and show it failing.

1. **A game that starts again while its line is broken.** Host and Bob (user 2) connected and exchanging. Bob loses Steam (`Port.loseSteam`), his internet goes down (`steam.down(2, true)`), the session fails (`steam.failSession(1, 2)`). Bob's node closes (it is out of the lobby, so no Left reaches the host). His internet is back, and a **new** node for user 2 joins the same lobby and sends a `Hello`. Assert:
   - the host gets `Disconnected(oldPeer, …)`, then `Connected(newPeer)` and `Received(newPeer, Hello)` once;
   - the new node never gets a `Disconnected` for the host;
   - a message from the host to `newPeer` arrives at the new node, and nothing of the old stream does.
2. **The same, after the host removed the old line** (`h.disconnect(oldPeer)` while it is broken, with a `Refused` queued): no `Disconnected` for the old peer, the new node connects and its `Hello` arrives, and it does not get the old `Refused`.
3. Every existing `SteamNodeTest` stays green, among them `aFailedSessionResumesAndLosesNothing`, `aMemberWhoOnlyLostSteamStaysAPeerBothWays`, `theHostLosingSteamKeepsItsPlayers`, `aHostWhoNeverAnswersEndsTheJoinWithAReason` and `aSessionThatStartsAfterTheMemberJoinedIsStillAccepted`. If one of them needs a change, say why.

`FakeSteam` may get a small addition if a step can't be expressed (for example closing a port without leaving the lobby); keep it a test fake.

## Fix B: a window when the session ends under a player

### The problem

Spec section 11, Host disconnect: "When the host quits or its game crashes, every player gets a popup saying the host disconnected". Section 15 repeats it. In play, a joining PC whose host closed the connection gets `JoinedSetup.close("Lost the connection to the host. …")` and nothing else: `MpGone.hostLost` skips a closed session, and the reason only reaches the session panel's status line (`MpSessionPanel.refresh`), which the player can hide. The same holds for every other close the player didn't ask for: removed while lost (`Refused(REMOVED)`), a spectator too slow, a broken stream, and the host's own `closedByPeer`.

### The fix

A small owner in `menu/`, run from `MpPlay.frame` after `exit.frame()`: when the session closed during play, on this PC, and no other window reports it, show a window once with `setup.closedReason()` and "The session has ended.", and a Main menu button. It ends the engine session and stops the speed as `MpGone.end()` does.

"Another window reports it" must be worked out from the code, not guessed. List every path that closes a session in play (`JoinedSetup.close`, `JoinedSetup.leave`, `HostedSetup.leave`, `closedByPeer`, `chooseGone(RETURN)`, the exit save, Leave and freeze, a hot-join restart, a files mismatch, the session panel's Leave) with the window that follows it today, and put the table in the report. The new window is for the paths with none. It must never show over, or after, another end window, and never for a close this PC's player asked for. Mind the frame where an exit save's done and the close arrive in one update (`MpSaves.frame`).

Text: in play, a lost host reads "The host disconnected. " + the network's reason + " Resume the campaign from Load Saved Lobby." Before play the text stays as it is. The owner may reword it; keep it in one place.

No unit test: the window lives in `menu/`, which needs the running game (AGENTS.md, Adapter Boundary). If a part of the decision is plain logic, put it where a test can reach it and test it. Otherwise list the manual steps in the report:

1. two PCs over direct IP, in play; kill the host's game; the joining PC shows the window within 2 seconds, and Main menu works;
2. the same with the session panel hidden;
3. the host quits through the menu (the exit save's window shows, not this one);
4. a joining PC presses Leave (no window);
5. Return to the last save (its own window, once).

## Fix C: one line of `docs/archive/reconnect.md`

"Crash or quit" says a crash closes the socket cleanly. Spec section 11 says a crashed game usually breaks its connection and shows as lost. Bring the plan's bullet in line with the spec: either can happen, and the host chooses in both.

## Rules for the work

- Branch `reconnect-fixes`, worktree `C:\ClaudeFolder\SOS-wt\reconnect-fixes`. One commit per fix (A with its tests, B, C). No push, no PR, no merge.
- AGENTS.md applies in full: surgical changes, style match, no new mutable static state, no logging in per-frame paths, the I/O thread only decodes and queues.
- Verify: `gradlew.bat compileJava compileTestJava`, then `gradlew.bat test`, then `gradlew.bat build`, with `SOSDET_DIR=C:\ClaudeFolder\SOSDET`. No rig runs, no game launches, no `installMod`.
- Spec sections touched: 11 (Host disconnect, Lost connection, Freezing a player: coming back) and 15 (Disconnects). No spec text changes. No hooks.
