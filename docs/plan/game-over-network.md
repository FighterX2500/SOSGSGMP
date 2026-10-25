# The game over the network (SOS-57)

Status: building (owner, 2026-10-25: "Build SOS-57 now"). Walking skeleton step 5 ([walking-skeleton.md](walking-skeleton.md)); card [SOS-57](https://fighterx2500.atlassian.net/browse/SOS-57).

SOS-56 ends with every PC holding the same world at step 0, each with its own city and landing party, and the host's `Go`. This card runs that world in lockstep over the lobby's connection. The engine has every part (SOSDET SOS-46, SOS-52, SOS-53); its rig carries the bytes through files ([Rig.java](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/rig/java/sosdet/rig/Rig.java) `afterFrame`). The mod carries the same bytes over TCP.

## 1. What runs where

| Stream | Made by | Engine call out | Goes to | Engine call in |
| --- | --- | --- | --- | --- |
| AI log (the general, the host's actions, every peer's orders stamped by the host) | host | `aiLogOut` | every peer | `aiLogIn` |
| Orders and world commands | each peer | `ordersOut` | host | `ordersIn(pc)` |
| City reports (own-city mode) | every PC | `reportsOut` | every other PC | `reportsIn(pc)` |
| State hashes | every PC | `hashOut` | every other PC | `hashIn(pc)` |

The connection is a star: a peer's reports and hashes go to the host, which feeds them to its engine and relays them unchanged to the other peers. PC numbers are the slots, the host 0 (as `MpWorldLoad` sets them).

## 2. One message: `Stream`

`Stream(kind, pc, offset, step, bytes)`, protocol 6. `kind` names the stream, `pc` the PC that made it, `offset` the stream's length before these bytes, `step` the sender's step when it sent them (for logs). The steps that changes apply at live inside the engine's bytes: the AI log stamps every entry, a report or hash names its step. That covers AGENTS.md's "carries the world tick it applies at".

- **Exactly once.** The engine reads each stream as one byte sequence "in any pieces", so a piece seen twice would corrupt it. A receiver keeps each stream's length so far and drops bytes it already has; a gap (an offset past what it has) is a protocol error and drops the connection. A repeated message changes nothing (the card's unit test).
- **Size.** Pieces of at most 48 KB, under the 64 KB message cap.
- The routing and the offsets are plain Java (`EngineLink`, tested without the game); the engine calls go through an adapter.

## 3. The start (at `Go`)

Before `Go` the session holds at step 0, as now. At `Go`, on every PC: `Engine.followAgreedSpeed()`; on the host also `Engine.pace(peers, 60)` (the slowest PC caps the speed; the rig's default lead); then `Engine.runFreely()`. A peer can't run ahead of the host's log anyway (at most 30 steps past what it confirms). The starting speed is 1x, set at step 0 with the session.

The exchange runs once per frame from `GAME.addAfterUpdate` (registered by the load), outside the step: drain the network, feed what came in, send what the engine made. The network thread only decodes and queues, as now.

## 4. Speed

The engine does it: the host's speed buttons set the agreed speed, which goes through the log and switches at the stamped step on every PC; a peer's game speed follows it; the host's governor lowers it to what the slowest PC keeps up with (spec §4).

## 5. The panel

After `Go` the setup panel shows the step, the agreed speed and each player's step (`Engine.peerStep`), and a desync line if `Engine.desync()` reports one (the desync window and resync are their own card).

## 6. Test: an autopilot and two PCs

There's no screen control from here, so the two-PC test runs the mod by itself: with `SOSGSGMP_AUTOPILOT` set, a test-only driver calls the same session methods the buttons call. The host creates the lobby, waits for the players, builds and generates the map, confirms it, places its capital, initializes the world; every PC places its landing party; after `Go` the host raises the speed and moves an army, and every PC writes its step, hashes and the army's position to an events file. A runner script starts the game on this PC and the laptop (as SOSDET's rig does) and checks the events. Without the variable the driver does nothing. Its button wiring is the one thing it can't prove; the owner's playtest covers that.

## 7. Out of scope

Disconnects and priority pause (spec §11), the desync window and resync, saves and resume, Steam (the card's own list).

## 8. Order

1. `Stream`, `EngineLink`, the adapter, the start at `Go`, the per-frame exchange, the panel; unit and loopback tests.
2. The autopilot and the runner; the two-PC test through the landing party (also the SOS-56 fixes), then 10 minutes of play.
3. The owner's 30-minute playtest (the card's first criterion).
