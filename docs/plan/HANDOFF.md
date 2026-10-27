# Handoff: the next session starts here

Written 2026-10-25, end of day, after SOS-64. It covers both repositories. SOSDET's own `docs/plan/HANDOFF.md` keeps the engine-only notes (rig, patches, the engine's open items).

## 1. Read first

1. This repository's `CLAUDE.md` and `AGENTS.md`: the workflow and the coding rules.
2. SOSDET's `CLAUDE.md`, `AGENTS.md` and `.claude/rules/determinism.md`, before any engine work.
3. The memory index (loaded on its own), especially `session-handoff`, `laptop-rig`, `tooling-quirks`, `jira-github-setup`, `run-checks-every-minute`, `check-other-games` and `short-smoke-runs`.
4. `docs/spec.md` for the section you work on, and its plan under `docs/plan/`.

## 2. Where things stand

**Repositories**
- **Mod:** `C:\ClaudeFolder\SOS`, GitHub `FighterX2500/SOSGSGMP`.
- **Engine:** `C:\ClaudeFolder\SOSDET`, GitHub `FighterX2500/Songs-of-Syx-Deterministic-Engine` (public).
- **The game:** `C:\ClaudeFolder\Songs of Syx`, V71 build 71.44.

**What works.** Two players play one world map together, each running their own city, over direct IP or Steam:
- the lobby and the setup: map, factions and locations, then the landing party (SOS-49, SOS-54, SOS-56);
- the world in lockstep through the mod's network (SOS-57);
- the desync window: the host chooses Resync or Save and exit (SOS-59);
- Steam lobbies and P2P (SOS-60);
- battles: the battle PC sends its battle save, and every PC runs the battle (SOS-61 engine, SOS-62 mod);
- a playtest log on every PC (SOS-63);
- campaign saves and resume (SOS-64: SOSGSGMP #39 with the engine fix SOSDET #38). Plan and test results: [campaign-saves.md](campaign-saves.md).
- disconnects (SOS-65, with the engine's removal SOS-66 and SOSDET #43): a player dropping holds every PC at one step; the host continues without them (they are removed and the campaign autosaves) or returns to the last save. A resume can start without a missing player, who is then removed. Plan and results: [disconnects.md](disconnects.md).

**Open PRs** (2026-10-27): SOS-81, every goods and money transfer in the playtest log, with a lobby switch (the SOSDET PR first, then the SOSGSGMP PR; plan [transfer-log.md](transfer-log.md)). The SOSGSGMP PR also carries the diplomacy log lines (SOS-73's last commit, written after #55). Merged: SOS-80 (#54, the owner's GUI fixes; see "GUI changelog" below), SOS-73 (SOSDET #51, SOSGSGMP #55: regions, vassalage, passage), SOS-72 and SOS-75 to SOS-79.

**GUI changelog, SOS-80** (the owner checks GUI changes in-game; no tests for pure layout). Keep these conventions in new screens:
- **Leave** goes in the bottom-right corner of every setup panel: add the panel's other buttons as its last row, then call `MpSetupLeave.addBottomRight(panel, buttonsRow, MpSetupLeave.button(...))`. Never add Leave inline with other buttons.
- **Initialize World** is host-only and shows only on the last factions screen (Place Capital): `MpFactions.side(back, next)` adds it only when `next` is null.
- **Customize Faction's** button reads "Pick Location".
- **Player lists** wrap (`GText.setMaxWidth` + `setMultipleLines(true)`, then `adjustWidth()` after each rebuild) and take at most half the screen (`C.HEIGHT() / 2`); size them from the player count, not `LobbySettings.MAX_PLAYERS`. Rows past the cap are cut off; there is no scrolling.
- **Status text boxes** are sized for their longest message plus a spare line (`Font.getHeight(text, width)`), so the panel grows downward instead of the text overlapping the buttons. A new long status message must go into that measure (`MpFactions.statusHeight`).
- **Full-screen prompts with one button** go through `menu.MpPrompt.show(text, button)`: vanilla's `IPromtScreen` puts a lone button half a slot left of centre. Two or more buttons are centred already, so call `fullScreen.activate` directly.

**Jira** (cleaned up 2026-10-25 at the owner's request; SOS-64 Done 2026-10-26). Every card with a merged PR is Done. Open:
- SOS-58: In Progress. Cursors are built on the setup map and the world map, not yet in cities.
- The epics SOS-22 (engine) and SOS-47 (mod).
- The planning cards SOS-18, SOS-19 and SOS-21.

From now on, move a card to In Review when its PR opens. When the PR merges, comment with it and move the card to Done, so cards don't pile up again.

**Installed for play.** This PC's `%APPDATA%\songsofsyx\mods` and the laptop's hold the SOS-64 builds of both mods. `C:\ClaudeFolder\SOSGSGMP-playtest-2026-10-25.zip` holds the same builds for a friend, with a README and `send-logs.cmd`.

## 3. Next

1. **SOS-72, diplomacy milestone 1** (owner, 2026-10-26: priority): treaties, money and goods between players. Built and tested, in review: the SOSDET PR first, then the SOSGSGMP PR. Plan and results: [diplomacy.md](diplomacy.md). Milestones 2 (regions, vassalage, emissaries, passage: SOS-73) and 3 (war between players: SOS-74) follow. SOS-71 (a siege by a human other than slot 0) was not a bug: Done.
2. **The owner's playtests**, with a friend over Steam or the laptop, using saves and resume. Collect the logs (section 5) and turn findings into cards.
3. **Spec §11 gaps:** done (the save before every battle, and a desync's Save and exit as a campaign save: SOS-67, SOS-68).
4. **Battles, engine plan 0006:** M2 (the commanders table, spectators) and M3 (resync in a battle). Player against player and the speed vote come after.
5. **Other spec sections not started:** trade (§7), sieges of player cities (§9), city viewing (§10), chat and notifications (§12, apart from cursors).
6. **Planning cards still open:** SOS-18 (distribution), SOS-19 (test plan), SOS-21 (the board).

## 4. Known issues

- **Market runs in the rig:** a human trades only with capitals it reaches, never across unclaimed land. In Mogus-72 use `--make-humans 2,24`. A game day is 1,152 s and trade runs once a day, so a sale needs about 45,000 steps.
- **Speed cap at 5x and above:** the lockstep cadence keeps high speeds from reaching their full rate.
- **"No active entry points" warning:** found and fixed in SOS-75 (SOSDET #46 and the SOSGSGMP PR). Islet capitals with no land beyond their square could get no roads; the location rules now refuse them. Plan and results: [joiner-roads.md](joiner-roads.md).
- **City↔world items at a resume:** anything crossing in the last ~0.5 s before a campaign save is lost on resume. A battle's return has the same gap.
- **Engine open items** (SOSDET HANDOFF): SOS-32's owner checks (camera and clicks, older-save followers, draw tracing), the V01B rig split at step 154,365, and the resync with an open prompt.

## 5. Playtesting with logs

Every PC writes its own log of each multiplayer session, with no setup:
- **Where:** `%APPDATA%\songsofsyx\sosgsgmp-logs\<date>-<time>-<host|join>-<name>.log`.
- **What's in it:** the players and the network; every 600 steps a status line with the step, the world hash, speed, fps, treasury and each peer's step; proposals, treaties, tariffs and realms between players; every goods and money transfer of every player (`transfer step ...` lines; the host's lobby switch picks every transfer or only those between players; SOS-81); desyncs with the engine's report; resyncs, battles, saves and loads; and any exception with its stack trace.
- **Comparing PCs:** the hashes in the status lines are at the same steps on every PC, so two logs side by side show where a desync began.

**After a session:**
- **This PC plus the laptop:** `python tools\collect.py --remote Qarasiq@26.161.86.190`. It zips both PCs' logs and crash files from the last 24 hours. Add `--remote-appdata C:\SOSTest\inst\2` for a `twopc.py` run.
- **A friend:** they close the game and run `send-logs.cmd` from the playtest zip. It puts a zip of their logs and crash files from the last 24 hours on their Desktop for them to send. Run `python tools\collect.py` here for this PC's half.
- **Write down** roughly when something happened, and what each player did just before.

## 6. Tests

- **Build:** `gradlew.bat build installMod` in each repository, run through PowerShell with `SOS_GAME_DIR` set. Mod: 122 unit and loopback tests.
- **Two PCs, mod:** `tools\twopc.py`. Set `SOSGSGMP_REMOTE=Qarasiq@26.161.86.190` and `SOSGSGMP_HOST_ADDRESS=26.215.11.176`.
  - Options: `--steps N`, `--speed X`, `--seed N`, `--corrupt STEP --on-desync resync|exit`, `--net steam`, `--save STEP` and `--resume`.
  - `--resume` keeps both data folders from the last run and copies the installed mods into them.
  - Each instance gets its own player key: the runner deletes the copied lobby settings, and the game saves a new key at once.
  - Latest runs: S64A (save) and S64B (resume) PASS.
- **Engine:** SOSDET's rig and suite (`rig\suite.py`, see SOSDET `rig/README.md`).
- **Owner rules for runs:**
  - one game at a time: list `java.exe` on both PCs first (PID 15828 is the Gradle daemon);
  - a monitor every minute that flags stalls and crash files;
  - short smoke runs only;
  - never rebuild SOSDET while a rig run is live;
  - after a run, close vanilla's error windows (`util.error.ErrorMessage` processes) on both PCs.

## 7. How to work

- **Plan first** for anything larger than a small fix; one card at a time; new cards need the owner's approval.
- **Git:**
  - branches are named after the card;
  - PRs are based on main;
  - never merge PRs or rename a branch with an open PR. Jira cards follow their PRs (section 2).
  - Commits end with the `Co-Authored-By` line, PR bodies with the Claude Code line.
- **Secrets:** the keys in `C:\ClaudeFolder\SOS\.env` are never printed. Read the GitHub token into `GH_TOKEN` (see memory `jira-github-setup`). Push with `git -c credential.helper= -c credential.helper='!gh auth git-credential' push`.
- **Jira:** `python C:\Users\FighterX2500\.claude\projects\C--ClaudeFolder-SOS\jira.py list|get|comment|create|move`.
  - `comment` takes a file.
  - Set `PYTHONIOENCODING=utf-8` for `list`.
- **Shell:**
  - never `cmd /c` from Git Bash;
  - Gradle goes through PowerShell;
  - Python edit scripts go in files written with the Write tool, because heredocs break escapes like `\n`;
  - keep each file's line endings, and compare `git diff --stat` with `--ignore-cr-at-eol` before committing.
- **Game files:** never commit, copy or shade the game's folder, source or jar, and never patch `SongsOfSyx.jar`. Patched classes shipped inside the mods are fine (the developer allowed it).
