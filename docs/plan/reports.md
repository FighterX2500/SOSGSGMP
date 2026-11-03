# Reports: every PC's logs and saves on the host (SOS-133)

The playtest of 2026-11-02 lost the evidence of a freeze (SOS-132): the PC that held the game sent no logs, and
nobody can make a player zip folders. This card makes every joining PC send its logs and saves to the host by itself,
over the game's own connection, while the game runs (spec §11 Reports).

Owner's decisions (2026-11-04): on for every player, with a way to turn it off on one's own PC; every campaign save is
sent. The owner asked for a short build to reach the next playtest sooner, so the first version leaves out what the
list at the end names.

## What the host gets

In `%APPDATA%\songsofsyx\sosgsgmp-reports\<campaign id>\<slot>-<player name>\`, the same names as on the joiner:

| What | When it is sent |
| --- | --- |
| This session's playtest log (`sosgsgmp-logs\*-join-*.log`) | live: new lines within about 5 s |
| The Steam log (`*-steam.log`), over Steam only | live, the same way |
| The game's crash files (`logs\error*.txt`) of the last week not sent before | at the game's start; a crash ends the process, so its file goes in the next session |
| Each campaign save and its record | after the save, behind what already waits |

A log that streams live is on the host up to its last lines even when the joiner's game freezes, crashes or is closed:
the case SOS-132 needed. Nothing waits for the session's end, where the connection is already closing.

## How it works

- **Joiner:** `sosgsgmp.report.ReportOutbox`, plain Java, holds what is still to send and gives the game thread at
  most 32 KB per second, in pieces of at most 48 KB (`Protocol.MAX_STREAM_PIECE`). A followed log's size is looked at
  every 5 s and its new part read then; a save or crash file is read once, when it is queued. `menu.MpReports` starts
  it once the game runs, queues each save from `MpSaves`, and sends a piece a frame at most. The crash files it sent
  are listed in `sosgsgmp-reports\sent.txt` on the joiner.
- **Host:** `sosgsgmp.report.ReportStore`, plain Java, writes each piece at its offset into that player's folder,
  under a name it cleans itself (letters, digits, space, `.`, `-`, `_`), never a path. A repeated piece, or one past a
  gap, changes nothing; a file already there is not taken twice. Per joiner and session: 32 MB of logs, 64 MB of saves
  and crash files; past that the rest is dropped, and the host's playtest log says so once. Each whole file's arrival
  is a line in the host's playtest log.
- **Message:** `Report(name, total, offset, bytes)`, joiner to host; `total` is -1 for a log that still grows.
  Protocol 22.
- **Off:** `reports=false` in the PC's `SOSGSGMP-lobby.properties` (written as `reports=true` from now on). A joining
  PC that sends says so in its chat once, when the game starts.
- **Never sent:** what players write in the chat (the playtest log already leaves it out, spec §12), the player keys,
  the lobby settings.

## Not in this card (yet)

- The host's **Collect reports** button: every save is sent anyway, so it would only bring crash files sooner.
- A switch in the game's menus: for now the setting is the line in the lobby settings file.
- The host's rotation of the saves it got: they stay until deleted by hand.
- A crash file written while a session runs (vanilla's error window on a warning) goes in the next session.
- Logs of PCs that never reach a game.

## Tests

- Unit: `ReportsTest` (an outbox into a store: the budget, a growing log, a save byte for byte; a repeat and a gap;
  the name cleaning; the cap) and `ProtocolTest` (the round trip and the limits).
- twopc (`--here --save 600`): the host's reports folder holds the joiner's log and its save, byte for byte.
