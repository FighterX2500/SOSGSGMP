# The game over the network (SOS-57)

Status: merged (SOSGSGMP PR #34); §9 to §11 merged with PRs #35 (desync window), #37 (battles) and #38 (playtest log). Walking skeleton step 5 ([walking-skeleton.md](walking-skeleton.md)); card [SOS-57](https://fighterx2500.atlassian.net/browse/SOS-57).

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

`Stream(kind, pc, offset, step, bytes)`, protocol 6 (protocol 9 since SOS-64). `kind` names the stream, `pc` the PC that made it, `offset` the stream's length before these bytes, `step` the sender's step when it sent them (for logs). The steps that changes apply at live inside the engine's bytes: the AI log stamps every entry, a report or hash names its step. That covers AGENTS.md's "carries the world tick it applies at".

- **Exactly once.** The engine reads each stream as one byte sequence "in any pieces", so a piece seen twice would corrupt it. A receiver keeps each stream's length so far and drops bytes it already has; a gap (an offset past what it has) is a protocol error and drops the connection. A repeated message changes nothing (the card's unit test).
- **Size.** Pieces of at most 48 KB, under the 64 KB message cap.
- The routing and the offsets are plain Java (`EngineLink`, tested without the game); the engine calls go through an adapter.
- **Compressed where made** (SOS-96, protocol 16). Each stream a PC makes runs through its own Deflater, flushed after every send, so the bytes and offsets on the network are compressed bytes. The host relays a peer's pieces as they came; each receiver keeps one Inflater per stream and maker and inflates only the bytes the exactly-once check lets through. City reports, nearly the same from one to the next, shrink 13-16 times in Deflate's window. The playtest log's status line carries the rates and totals, compressed and raw.

## 3. The start (at `Go`)

Before `Go` the session holds at step 0, as now. At `Go`, on every PC: `Engine.followAgreedSpeed()`; on the host also `Engine.pace(peers, 60)` (the slowest PC caps the speed; the rig's default lead); then `Engine.runFreely()`. A peer can't run ahead of the host's log anyway (at most 30 steps past what it confirms). The starting speed is 1x, set at step 0 with the session.

The exchange runs once per frame from `GAME.addAfterUpdate` (registered by the load), outside the step: drain the network, feed what came in, send what the engine made. The network thread only decodes and queues, as now.

## 4. Speed

The engine does it: the host's speed buttons set the agreed speed, which goes through the log and switches at the stamped step on every PC; a peer's game speed follows it; the host's governor lowers it to what the slowest PC keeps up with (spec §4).

## 5. The panel

After `Go` the setup panel shows the step, the agreed speed and each player's step (`Engine.peerStep`), and a desync line if `Engine.desync()` reports one (the desync window and resync came with SOS-59, §9).

## 6. Test: an autopilot and two PCs

There's no screen control from here, so the two-PC test runs the mod by itself: with `SOSGSGMP_AUTOPILOT` set, a test-only driver calls the same session methods the buttons call. The host creates the lobby, waits for the players, builds and generates the map, confirms it, places its capital, initializes the world; every PC places its landing party; after `Go` the host raises the speed and moves an army, and every PC writes its step, hashes and the army's position to an events file. A runner script starts the game on this PC and the laptop (as SOSDET's rig does) and checks the events. Without the variable the driver does nothing. Its button wiring is the one thing it can't prove; the owner's playtest covers that.

## 7. Out of scope

Disconnects and priority pause (spec §11), the desync window and resync, saves and resume, Steam (the card's own list).

## 8. Order

1. `Stream`, `EngineLink`, the adapter, the start at `Go`, the per-frame exchange, the panel; unit and loopback tests.
2. The autopilot and the runner; the two-PC test through the landing party (also the SOS-56 fixes), then 10 minutes of play.
3. The owner's 30-minute playtest (the card's first criterion).

## 9. The desync window (SOS-59)

Spec §11 World desyncs; the engine half is SOSDET SOS-53 (every PC holds at the same step, `Engine.desyncHold`) and SOS-52 (world resync with several humans).

- **Window:** `menu/MpDesync`, once per frame from `MpPlay`, shows vanilla's full-screen prompt on every PC while `Engine.desyncHold()` is set, naming the player whose world differs (the hold's PC is a slot). The host gets Resync and Save and exit; the others read "waiting for the host". Clicked away, it comes back while the hold lasts.
- **Resync:** at the hold step the host runs `Engine.worldResyncSave`; the engine's state and the world save travel as one transfer of `Resync` pieces (protocol 7, transfer ID, a repeated piece changes nothing; `WorldResync` frames the bytes). Every PC, the host too, loads it with `Engine.worldResyncLoad` once its engine stands at that step, then runs on (`Engine.runFreely`).
- **Save and exit:** the host sends `DesyncExit`; every PC saves its game into vanilla's saves list ("Multiplayer step N"), leaves the session, ends the engine and offers the main menu. Such a save can't be resumed as a campaign: it has no campaign record, and its worlds differ. Load Saved Lobby (SOS-64) resumes from the last campaign save; the window says so.
- **Test:** the autopilot's `corrupt=STEP` breaks the joiner's world as SOSDET's rig `wcorrupt` does; `ondesync=resync|exit` is the host's choice; `tools/twopc.py --corrupt 1800 --on-desync resync|exit`. Runs D2 (resync: held at 1,889 1.2 s after the corruption, resynced within 1 s, 7 identical hashes after) and D3b/D3c (both PCs saved at the hold step).

## 10. Battles (SOS-62)

Spec §8, with the engine's half in SOSDET SOS-61 (plan 0006) and the ADR 0003 second amendment.

- **The battle save travels as the fifth engine stream, BATTLE** (protocol 8). Every PC makes and takes it, and the host relays it, like reports and hashes, so it gets pieces, offsets and exactly-once delivery for free. A record is the Engage step, the save's length and the save (`session/BattleSaves`).
- **Every frame** `MpPlay` asks the adapter (`EnginePlay.battleFrame`): while the engine waits for a battle's save, the save loads as soon as it is here. The battle PC loads its own too.
- **After each load** (the battle's, then each PC's own city at the end) the game is new, so `MpPlay` and the session panel are put into it again (`Engine.afterEachLoad`).
- **Test:** the engine's rig with two humans, two copies on one PC (SOSDET plan 0006). Two PCs through the mod come with the general playtest (owner, 2026-10-25).

## 11. The playtest log (SOS-63)

During playtests every PC writes a log of its game session, so a desync, a crash or a stuck battle leaves a trail.

- **Where:** `%APPDATA%\songsofsyx\sosgsgmp-logs\<date>-<time>-<host|join>-<name>.log`, one file per PC per session, from the world's load on. Each line is flushed at once.
- **What:**
  - **Session header:** role, player, network (direct IP or Steam lobby), the mod's and engine's jars with their times, protocol, game version, Java and memory.
  - **Load and start:** the world load (time, map seed, slot, world hash) and Go.
  - **Network notes:** why a PC's connection dropped, or what it sent that broke the protocol. A joining PC's problems close its session, so they show as the close reason.
  - **Players and close:** the player list whenever it changes, and the session's close reason.
  - **Every 600 steps** (the same steps on every PC, so the hashes compare across logs): the state hash, speeds, fps, step times, how far the other PCs are, the host's hold time, and the engine's counters.
  - **Battles:** Engage, the battle save's size, the load, the return.
  - **Desyncs:** the hold, the engine's desync report on the PC that found it, the resync, and Save and exit.
  - **Exceptions:** every exception at the mod's two boundaries (the world load and the per-frame update), with its stack, before the game handles it as before.
- **Never in the way:**
  - A failure inside the log is logged and the log stops; the game goes on.
  - Each frame only compares a few values, and text is built only for a line it writes.
- **Collecting:** `python tools\collect.py --remote user@address` zips both PCs' logs and the game's logs and crash files from the last 24 hours. Add `--remote-appdata C:\SOSTest\inst\2` for a `twopc.py` run.
