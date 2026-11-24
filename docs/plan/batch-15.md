# Batch 15: more logging and the recorder (SOS-246 to SOS-250)

Owner, 2026-11-24: "add all of this to the recorder, and make 4 (the engine's sub-hashes); the desync snapshot and the
crash bundle are unnecessary for now, but more logging is definitely necessary." Not telemetry: everything stays on
the players' PCs, and the host gets the joiners' logs over the reports transfer that exists (SOS-133, spec §11). No
upload server (owner, 2026-11-17 and 2026-11-24).

Branches `batch-15-1124` in both repositories (worktrees `SOS-wt\b15-logging`, `SOSDET-wt\b15-logging`). The engine's
half has its own plan: SOSDET `docs/plan/0022-logging.md`. The engine merges first.

## Why

- **NPC world decisions are logged nowhere.** Army moves, recruitment, sieges, raids, NPC wars and peace are
  simulated by every PC itself, in the world part; the host's AI log holds only the battle general's orders, players'
  commands and the engine's control entries. A world desync shows only as "the world section differs".
- **The playtest log has events, not what led up to them.** Commands, transactions, city reports and AI entries are
  only counted, every 600 steps.
- **A crash leaves little:** no memory figures, no `hs_err` file, nothing logged before the world loads.

## Two kinds of lines

- **Lines every PC writes alike:** the same step and the same text on every PC. They start with `= ` and the step
  (`= 123450 world: ...`), and hold nothing about this PC (no wall time after the line's own stamp, no "this PC").
  `tools/logdiff.py <log A> <log B>` lines two PCs' `= ` lines up by step and prints the first that differs, with the
  lines around it. A kind of line is marked `= ` only once a twopc run shows it the same on both PCs; until then it is
  an ordinary line.
- **This PC's lines:** its city, its frames, its memory. Context for crashes and freezes.

## The items

### 1. Playtest log basics (SOS-246, mod)

- **Memory** in every status line: heap used, committed and max (`Runtime`).
- **A system line** when the log opens: OS name and version, Java version and vendor, heap max, CPU cores, the game's
  build, the enabled mods in order.
- **The lobby log:** the playtest log opens when the player presses Host or Join (or a resume, or a Steam join), not
  at world load. Refusals, the game-files check, Steam joins and the setup land in the same file as the game after.
  A lobby left without a game closes its log.
- **`hs_err_pid*.log`:** taken with the game's crash files (`logs\error*.txt`) by the reports (the last week's, not
  sent before). Where the JVM writes it: the game's working folder unless the launcher sets `-XX:ErrorFile`; check
  the launcher and the Steam start on Windows (and say what Linux and macOS do, from the launcher's code).
  Redact the environment section (the lines between `Environment Variables:` and the next blank line) before it
  leaves the PC.
  - **Found (2026-11-24):** the game runs in a second JVM. `init/Main` (the launcher's process, started by
    `SongsofSyx.exe` from Steam in the game folder) starts it with `Proccesser.executeLwjgl`, a `ProcessBuilder` with no
    `directory(...)`: the game's JVM inherits the launcher's working folder, the game folder. Its arguments come from
    `LSettings.jvmArguments` (`-Xms512m -Xmx4096m -XX:+UseCompressedOops -Dfile.encoding=UTF-8 -server
    -Dfml.earlyprogresswindow=false -XX:+UseSerialGC`; `jvmargs-launcher.txt` holds the same for the launcher's own
    JVM): no `-XX:ErrorFile`. So `hs_err_pid<pid>.log` lands in the game folder, or in the temp folder when the JVM
    can't write there. The code is the same on Linux and macOS (Steam starts the launcher in the game folder there
    too). The mod runs inside the game's JVM, so it looks in its own `user.dir` and `java.io.tmpdir`.

### 2. World and city lines (SOS-247, mod)

All are read between steps (from `MpLog.frame`) or recorded inside the step into a preallocated queue that `MpLog`
drains between steps. Nothing in the step writes a file, allocates or draws.

| Line | Source | Kind of line |
| --- | --- | --- |
| World news: wars, truces, trade pacts, battles, sieges, region changes, factions born and dead, successions, collapses, capital moves | poll `WORLD.LOG().all()` (`world/log/WorldLog`, a 256 ring) for new entries by identity | `= ` if equal in twopc |
| Stance changes; region owner changes; factions spawned and removed; world battle and siege results; successions, as they happen | the game's listeners `DIP.DipActivityListener`, `RD.RDOwnerChanger`, `Faction.FactionActivityListener`, `world.battle.BattleListener`, `NPCCourt.RoyaltyEventListener`, made in `createInstance` (the game's lists are cleared per game) | `= ` if equal in twopc |
| Applied commands by name, world, military and diplomacy only (ids 7000 to 8199); every command in the recorder | `Engine.applied` (SOSDET 0022, E1) | `= ` |
| NPC army decisions | `Engine.armyOrders` (SOSDET 0022, E2): recorder only, except recruit, besiege and disband, which are text lines too | `= ` |
| Scripted world events starting and ending | poll `GAME.EVENT().current()` | `= ` if equal in twopc |
| Random draws: `simulationDraws`, `foreignDraws` | `Engine.stats()`, added to the status line; a `foreignDraws` that grows writes a line of its own | this PC |
| A hold over a second: its reason, who is behind | `holdWhy()`, `behindHost()`, `stepPastPeers()` | this PC |
| A city report mismatch | `Engine.reportMismatch()` | this PC |
| The local player's messages: riots, strikes, disease, accidents, raiders, invasions, starving armies, desertion, drought, region lost | reflection on the private `Messages.all` (`view/ui/message/Messages`) in the adapter, new entries by identity, a few times a second; the title by reflection | this PC |
| The city's day: deaths and arrivals by cause, riots, accidents, executions, invasions won and lost | `GAME.count()` (`GCOUNTS`), `STATS.POP().COUNT.enters()/leaves()`, once a game day | this PC |

**Determinism:** a listener runs inside the step. It reads its arguments, writes them into the mod's preallocated
queue and returns: no game state read beyond its arguments' indexes, no draw, no allocation, no I/O. With several
humans the world part runs each human's half with that human as `FACTIONS.player()`; a listener never asks who the
player is. SOSDET calls `DipActivityListener`s itself (`GameTxns`, `HeldTxn` finds one by class through the private
`all` list): check that one more listener changes nothing there.

**Volume:** world and city lines are a few per game day; the local player's messages, some a minute. The commands
and army decisions in the text log are the rare kinds only.

### 3. The recorder (SOS-248, mod)

- **File:** `sosgsgmp\recorder\<host|join>-<player>.rec`, mapped with `FileChannel.map` (JDK only). Not `%TEMP%`.
  A process crash keeps what was written: the pages belong to the OS.
- **Layout:** a header (magic, format version 1, the session's start time, the PC's role and name, the entry ring's
  and payload ring's sizes, the write indexes), then 65,536 entries of 32 bytes (2 MB), then a 2 MB payload ring.
- **An entry:** time (`System.nanoTime` from the session's start), step, kind (short), peer or human (short), size
  (int), argument A (long), argument B (int). Kinds: network message in and out (type, size, peer); command and AI
  entry applied (from `Engine.applied`: seq, id); NPC army decision (army, faction, kind, target); transfer (txn id);
  city report applied; hash compared, missed, differing; hold start and end; speed and pause; world and city lines
  (an id into the payload ring holding the line's text); loading windows; the session's own events.
- **Payloads:** commands', AI entries' and the world and city lines' text. Never chat text: a chat message is
  recorded as a header only (spec §11).
- **Threads:** the game thread only. A network message is recorded when the game thread polls it from the node's
  queue; its arrival time comes with it if the node stamps one.
- **Hot path:** a write is a few puts into the mapped buffer at a wrapping index. No allocation, no logging, no I/O
  call. Any exception turns the recorder off for the session, logged once.
- **Incidents:** at a desync, a freeze over 5 s (as the log already sees it), or an exception the mod's boundaries
  catch, the file is copied to `sosgsgmp\recorder\<log name>-<step>-<why>.rec` (at most 5 a session) and the log
  names the copy.
- **Reading:** `tools/recorder.py <file> [--from STEP] [--to STEP] [--kind K]` prints it in order. It reads format 1
  and says what it can't read.
- **Cost:** the step time's mean and p99 with and without the recorder, at speed 5 in a twopc run: under 1% apart.

### 4. Engine rings and world parts (SOS-249, SOS-250, SOSDET 0022)

See SOSDET `docs/plan/0022-logging.md`: E1 the applied ring, E2 the NPC army decisions ring, E3 the world parts in
the hash record, E4 item hashes in the desync report. The mod reads E1 and E2 each frame between steps, writes the
recorder and the text lines; `tools/logdiff.py` also compares two PCs' desync reports item by item.

## Spec and docs

- `docs/plan/HANDOFF.md` §5 (playtesting with logs): the new lines, `= ` lines, `logdiff.py`, the recorder and
  `recorder.py`.
- Spec §11 Reports: no change now. The recorder's copies stay on the PC; sending them to the host with the reports is
  an open decision for the owner.
- Protocol: bump to 34 if the hash record's bytes change (E3).

## Tests

- **Unit:** the recorder's ring (wrap, payload wrap, header, a reader in Java that reads what the writer wrote); the
  `= ` line format; the redaction of `hs_err`; the queue the listeners write into; `logdiff.py` on two sample logs.
- **twopc** (`--here`, one run for the batch, sos-test-run): both PCs' `= ` lines equal over the run
  (`logdiff.py` says so); the recorder files read back; the step time with and without the recorder; a forced desync
  if twopc has one, with the report naming the world part.
- **Engine:** SOSDET's own tests and one combined rig smoke run (SOSDET 0022).

## Later (not in this batch)

- A crash bundle: an unclean-exit marker, a "make report" zip on the Desktop at the next start.
- Desync snapshots: each PC's world (and city) state at the desync hold, sent to the host, compared offline.
- A freeze watchdog thread (needs the owner's approval for the thread).

## State on 2026-11-24

- Cards SOS-246 to SOS-250 created and In Progress. Worktrees made from main (SOSGSGMP `1b9ec0f`, SOSDET `bad818f`).
- Nothing built yet.
