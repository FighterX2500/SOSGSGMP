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

**As built (SOS-247, 2026-11-24):**
- **Two marks.** `= ` is for kinds a twopc run showed equal; a kind meant to be equal but not confirmed yet is written
  with `~ ` instead of as a plain line, so the same twopc run can check it: `logdiff.py --all` compares the `~ `
  lines too. Once a run shows a kind equal, its mark becomes `= ` in the code (`MpLog.worldLines`, by kind).
- **Day keys.** The world's news and the scripted events are found by polling, after the step they happened at, so
  their key is the game day (`~ d812 world: news: ...`), which every PC shares; the listeners' lines carry the step.
- **Kinds still to confirm in twopc** (all `~ ` now): `world: stance`, `world: region`, `world: capital`,
  `world: faction born`, `world: faction gone`, `world: battle`, `world: siege`, `world: succession` (the
  listeners); `world: news` and `world: scripted event starts/ends` (day keys). Suspects: `faction born` and
  `faction gone` also come from SOSDET's `GameHumans.put` and `activateNpc` outside the step (setup, hot-join,
  the host's living world); scripted events may be this city's own.
- **The listeners** are made by `sosgsgmp.game.WorldWatch` when the playtest log first sees the loaded world, and
  again whenever `WORLD.LOG()` is another object (a load made the game again: `GameDisposable` cleared the lists). The
  events of the steps between a load and the next frame are not heard.
- **SOSDET's listeners:** `HeldTxn.invasionListener` picks the `Invador` listener by its enclosing class, and
  `GameTxns` (`WAR_ENDED`) calls the one it holds; `GameBreaks` and `GameHumans` only add or call listeners. A
  listener of the mod's in the lists changes none of them.
- **A city report mismatch** was logged already (`MpMismatch`: "the session ends: ..."), so it has no new line.
- **This PC's lines:** `hold over a second at step N: <holdWhy>; behind the host N steps, step past the peers N`
  once per hold, while it holds; `foreign draws: N more, N in all, at step N`; `message: <title>`;
  `city day N: deaths and leavers: ...; arrivals: ...; Riots 1, Accidents 2` (only what isn't 0); the status line's
  `draws simulation N, foreign N`; `world events: N lost, the queue was full` (4,096 records between two frames).

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
- **Owner, 2026-11-24 (added):** incident copies go to the host with the reports, queued like crash files. A joining
  PC also streams its recorder to the host as it writes it: only the kinds that are this PC's alone (network in and
  out, holds, frames, speed and pause as this PC saw them, its own lines, loading windows, session events); what every
  PC records alike (commands and AI entries, army decisions, transfers, the `= ` and `~ ` lines, hashes) the host
  records itself. The stream is a growing report named `recorder.stream` (total -1, offset = bytes streamed so far): no
  new message type, no protocol bump. It goes every quarter second (every frame until the review of 2026-11-25), first in the outbox's order (stream, logs, saves), within a
  share of its own of 16 KB/s. More than 2 s behind, it jumps to the newest entries with a gap record. The host keeps
  it as a ring of 16 MB per joining PC per campaign folder, outside the logs' cap; `recorder.py` reads it. `reports=false`
  stops it too. Spec §11 Reports says so (spec 0.74).

**As built (SOS-248, 2026-11-24):**
- **Layout, format 1** (big-endian): a 256-byte header (magic `SOSGSREC`, version 1, header size, session start in
  epoch ms, role byte 0 host / 1 joining, entry count 65,536, entry size 32, payload size 2 MiB, entries written and
  payload bytes written as running counts, the player's name as a length and UTF-8), then the entries, then the
  payload ring. An entry: time (long, ns since the session's start), step (**int**, -1 for none), kind (short), peer or
  human (short), size (int), A (long), B (int): the plan's fields with the step as an int, so the entry stays 32 bytes
  (an int holds 400 days of steps at 60 a second). The entries-written count moves after the entry is written.
- **Kinds:** 1 net-in, 2 net-out (A the message type, size its stream or report payload, peer), 3 net-connect,
  4 net-disconnect, 5 command and 6 AI entry (SOSDET 0022 E1, SOS-249), 7 army decision (E2), 8 transfer (reserved),
  9 hashes (A compared, B missed, as they move), 10 desync (A its step), 11 hold-start and 12 hold-end (A the step,
  B the ms), 13 speed (A speed x100, B paused), 14 frame (a frame of 50 ms or more, A its ms), 15 line-same and
  16 line-local (every playtest log line, text), 17 loading (A the key, B opened), 18 session (text: Go, restart,
  end), 19 types (text: the message types' numbers and names, at the start), 20 gap (the stream's only: A entries
  skipped). A text over 2 KB is cut at a whole character.
- **Network messages** are recorded by `RecordingNode`, which the lobbies put round their node (outside the test
  conditions' node) and the setup inherits: as the game thread sends and polls them; nodes stamp no arrival time.
  A message's type is its place among `Message`'s permitted types, named by the types entry.
- **The file** is `sosgsgmp
ecorder\<host|join>-<player>.rec`, opened with the playtest log at Host or Join. It is
  not truncated when it is opened again: Windows refuses that while an earlier session's mapping lives (Java unmaps
  only through the garbage collector), so a new session rewrites the header with nothing written.
- **Incidents:** a desync this PC's engine finds, a frame or a hold over 5 s, or an exception at `MpLog.error`'s
  boundaries; copied to `sosgsgmp
ecorder\<log name>-<step>-<why>.rec`, at most 5 a session; the log names the copy,
  and a joining PC's reports send it.
- **The stream** (`RecorderStream`): whole records, a text kind's record followed by its text. The outbox sends the
  stream's piece and then, in the same frame, a log's or a file's (`MpReports` asks twice); the stream gives one piece
  a quarter second at most (`ReportOutbox.STREAM_INTERVAL_NANOS`; every frame until the review of 2026-11-25), since
  the host writes each piece to its file. Sending allocates a piece and a message then; nothing is made while nothing
  waits. The host's ring is two files of at
  most 8 MB, `recorder.stream` and `recorder.stream.1` (the older half), each starting with `SOSGSTRM` and format 1.
  The host expects each session's stream from offset 0 and appends it to what the folder holds.
- **Not recorded yet:** transfers (kind 8 reserved: `MpTransferLog` writes them as lines, which the recorder keeps),
  city reports applied (only counted in the status line).

### 4. Engine rings and world parts (SOS-249, SOS-250, SOSDET 0022)

See SOSDET `docs/plan/0022-logging.md`: E1 the applied ring, E2 the NPC army decisions ring, E3 the world parts in
the hash record, E4 item hashes in the desync report. The mod reads E1 and E2 each frame between steps, writes the
recorder and the text lines; `tools/logdiff.py` also compares two PCs' desync reports item by item.

**As built (mod side, 2026-11-24):** `menu.MpEngineRings` reads both rings a few times a second (every frame until the review of 2026-11-25) from the engine session's
start (seq 0), so every PC writes every line, and again from 0 when the engine's counts fall (a new engine session).
Every entry goes into the recorder at its own step (`Recorder.recordAt`): a command (kind 5: peer the PC, size the
division, A the ring's seq, B the command id with bit 16 set when skipped), an AI entry (kind 6, B the AI kind), an
army decision (kind 7: peer the faction, size the order's kind, A the tile x and y in its top 32 bits over the army's
slot, B the target). Text lines, `= `: `= <step> command: <name> (<id>), PC <n>[, division <d>]` for ids 7000 to
8199, without `skipped` (not the same on every PC: SOSDET's `Applied` says which), and `= <step> army: create|besiege|
disband, army <slot> of <faction> (<i>), tile x,y[, target <t>]`. A ring overwritten before the log read it says so
in a line. The desync report's world parts and items come in the engine's own report text, which `logdiff.py`
compares item by item (`<part> <index> <hash>`). Protocol 34: the hash record's bytes changed (format 9).

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
- SOSDET's half (E1 to E4) built and committed in its worktree (`7cade05`, `1e0b70b`).
- The mod's half built, one commit per card: SOS-246 (log basics), SOS-247 (world and city lines, `logdiff.py`),
  SOS-248 (the recorder, the stream, incidents, `recorder.py`, spec 0.74), then the engine's rings (SOS-249, SOS-250:
  protocol 34). `gradlew.bat build` passes against the engine worktree's jar; 375 unit tests, and 5 for `logdiff.py`
  (`python tools\test_logdiff.py`). Nothing run in the game yet.
- **The twopc run must check** (one run, `--here`, sos-test-run): `logdiff.py` on both logs says the `= ` lines are the
  same, and with `--all` which `~ ` kinds are (each that is becomes `= `; the suspects are under SOS-247's notes); the
  log opens at Host and Join with the system line and the lobby's lines; the status line's heap and draws; both
  recorder files read back with `recorder.py`, with net-in and net-out entries, commands, AI entries and army
  decisions; the host's reports folder holds the joining PC's `recorder.stream` within a second of its entries
  (`recorder.py` on it), and no other stream than its own kinds; the step time's mean and p99 at speed 5 with and
  without the recorder (under 1% apart; turning it off needs a build without `Recorder.open`, or a read-only
  `sosgsgmp\recorder` folder, which turns it off with a line); a forced desync, if twopc has one, with the report
  naming the world part and an incident copy on both PCs (the joiner's at the host); a second lobby in the same game
  run reopens the recorder (the mapping of the first lives on).

## State on 2026-11-25: the test cycle

Reviewed by the lead; review fixes `bf2edf0` (the engine's rings read a few times a second, not every frame: reading
allocates; the recorder turns off on an `InternalError` from its mapped file, such as a full disk), `9591fef` (the
stream sends the message types' names first) and the commit after it (stances and news are `= `). `gradlew.bat build`
against the engine worktree: 376 unit tests, 0 failures; `tools\test_logdiff.py` 5 of 5.

- **twopc T1** (`--here --mods-from <engine worktree> --window 1280x768 --speed 5 --steps 9000 --save 3000 --corrupt
  6000 --on-desync resync`): `RESULT: PASS: 5 common hash steps, 0 differ`; the desync at 6030 held both PCs at 6072
  and the resync cleared it; the campaign saved on both.
  - The log opens at Host with the system line and the lobby's lines; the status line has the heap and the draws
    (foreign 0).
  - `logdiff.py` (and `--all`): 218 comparable lines on each PC, all the same: 20 `= army: create`, 197
    `~ world: news`, 1 `~ world: stance`. News and stances are `= ` from now on. Not seen in the run, still `~ `:
    region, capital, faction born and gone, battle, siege, succession, scripted events.
  - The desync reports at 6030: 1286 item lines both have, the only 8 that differ are the running world parts from
    "events per human" on, where the test broke the world.
  - Incident copies on both PCs; the joiner's at the host with its log, its save and `recorder.stream` (1.2 MB). The
    stream's last entry is 6 ms before the joiner's own last (it left); one gap, at its start (58 lobby entries before
    the reports begin); none while playing.
- **twopc T2** (the same, `--steps 6000`, no save or desync): PASS; the stream starts with the message types' names.
  **T3** (the same again): PASS, but the recorder was on (twopc makes the data folders again), so it is a second
  sample, not the "off" run: step time mean 1.03 against 1.14 ms, p99 6.0 against 8.1 ms between two runs alike.
  Two twopc runs can't show a 1% cost.
- **The recorder's cost, measured directly** (the game's JRE, the real mapped file, `RecBench` in the scratchpad): a
  plain entry 30 ns, a text entry 120 to 230 ns. The host wrote 25,838 entries in 146.7 s (about 176 a second, 2 PCs
  at speed 5): about 10 µs a second, 0.012% of the step work at that speed (80 steps a second of about 1 ms). Under
  1% by two orders of magnitude.
- **Not seen:** a second lobby in the same game run (the recorder file reopened while the first mapping lives); the
  `~ ` kinds above; a real crash (the stream ends with the joiner's last frame; a crash inside a step leaves its last
  entries in the joiner's own file); real Steam; the laptop as a second PC.

## State on 2026-11-25: the Steam test

The owner's manual test over real Steam on this PC: Spacewar (App ID 480), the joining game in the Sandboxie box
`Steam2` under the spare account, both games from the batch's worktrees (`manual.py start --worktrees ... --steam-app
480 --sandbox Steam2`), a live log at `events` depth.

- Lobby through Steam, a new game, play to step 13,463, an alliance proposed and accepted, a deal proposed, a pause;
  the joiner's Quit to menu ended the session for everyone with the campaign saved on both (world hash
  `52cb635badbc438b` on both). Both PCs' hashes the same at every status line.
- The log opens at Host and at Join (the system line, `lobby: hosting|joining ... network: Steam P2P`, the recorder,
  the players); a lobby left without a game closes its file (twice, on game 2); the second of those opened the same
  recorder file again in the same game run, which worked.
- `logdiff.py --all`: 16 lines each, the same: 13 `= army: create` (the NPCs' first armies, one every 540 steps), the
  commands by name (`humanDealPropose`, `humanDealAnswer`), `= world: stance: ... neutral to allied`.
- The joiner's `recorder.stream` on the host (2.2 MB) starts with the message types' names and ends 0.3 s before the
  joiner's own file (the session's close); one gap, at its start (344 lobby entries); none while playing.
- No new Steam dump during the session. At game 1's exit Steam wrote `assert_java.exe_20261125003541_1.dmp` ("Trying
  to close low level socket support, but we still have sockets open!"), the non-fatal kind seen at session ends since
  2026-11-15; not this batch's.
- Fixed after it: the lobby's first line said `hosting lobby 0` and `joined lobby 0` (Steam names the lobby a moment
  later): the network now says `creating a lobby` or `joining a lobby` until then (a test in `SteamNodeTest`); the hold
  line said its step twice.
- Explained: the three holds over a second at steps 4139 to 4289 were the world waiting for the joining PC (its game
  at 84 fps, 209 steps a second for a moment, `world may run false`); `battle hold true go -1` in the reason is the
  mode the mod turns on in every session (every PC holds before a battle's Engage, SOS-70), with no battle due.
- Not this batch's: the joiner's "save 1 done on every PC: the session closed before the host confirmed every player's
  save" although the host had confirmed both: the host closes the session right after its "done", and over Steam the
  close can come first (`MpSaves.frame`, SOS-68/79). Both saves were written and alike. A follow-up card for the
  owner.
- 377 unit tests pass.
- Review before the PRs (2026-11-25): the stream goes every quarter second, not every frame (the host wrote a file
  every frame for every joining PC; the joining PC made a piece every frame); a folder the `hs_err` search can't list
  is a log line, not an empty `catch`. 377 unit tests pass. Not rerun in a game: a test covers the pacing, and the
  host still holds a joining PC's last second.
