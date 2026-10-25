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
- campaign saves and resume (SOS-64, open PRs, below).

**Open PRs.** Merge in this order:
1. SOSDET #38 (SOS-64): the statics reset at a session load waits until a game exists. Without it, a resume crashes on every PC.
2. SOSGSGMP #39 (SOS-64): campaign saves and Load Saved Lobby. Plan and test results: [campaign-saves.md](campaign-saves.md).
3. The docs PRs from this handoff, one per repository (`docs-status-2026-10-25`).

**Jira.** The owner closes cards. Cards with merged PRs that look ready to close: SOS-31, SOS-46, SOS-51 (spec), SOS-52, SOS-53, SOS-54, SOS-55, SOS-56, SOS-57, SOS-59, SOS-60, SOS-61, SOS-62, SOS-63. SOS-58 (cursors) was built inside SOS-56 and SOS-57, for the setup map and the world map; cursors in cities are still to do. SOS-64 closes once its two PRs are merged.

**Installed for play.** This PC's `%APPDATA%\songsofsyx\mods` and the laptop's hold the SOS-64 builds of both mods. `C:\ClaudeFolder\SOSGSGMP-playtest-2026-10-25.zip` holds the same builds for a friend, with a README and `send-logs.cmd`.

## 3. Next

1. **The owner's playtests**, with a friend over Steam or the laptop, using saves and resume. Collect the logs (section 5) and turn findings into cards.
2. **Spec §11 gaps**, as cards for the owner to approve:
   - a missing player's city frozen at resume (today every campaign player has to join);
   - removing a player;
   - the save before every battle;
   - the disconnect priority pause (today a disconnect ends the game for every PC; resume from the last save);
   - a desync's Save and exit as a campaign save. Today it writes a plain save, and the window says so. Resuming one needs a resync at the start, because its worlds differ.
3. **Battles, engine plan 0006:** M2 (the commanders table, spectators) and M3 (resync in a battle). Player against player and the speed vote come after.
4. **Other spec sections not started:** trade (§7), sieges of player cities (§9), city viewing (§10), chat and notifications (§12, apart from cursors).
5. **Planning cards still open:** SOS-18 (distribution), SOS-19 (test plan), SOS-21 (the board).

## 4. Known issues

- **Speed cap at 5x and above:** the lockstep cadence keeps high speeds from reaching their full rate.
- **"No active entry points" warning:** at the end of test S64A, vanilla's error window opened on the laptop. The warning says the joiner's city map got no roads, at the capital the autopilot picked. The run passed. Look at it with the joiner's city generation (`Engine.ownCityAt`).
- **City↔world items at a resume:** anything crossing in the last ~0.5 s before a campaign save is lost on resume. A battle's return has the same gap.
- **Engine open items** (SOSDET HANDOFF): SOS-32's owner checks (camera and clicks, older-save followers, draw tracing), the V01B rig split at step 154,365, and the resync with an open prompt.

## 5. Playtesting with logs

Every PC writes its own log of each multiplayer session, with no setup:
- **Where:** `%APPDATA%\songsofsyx\sosgsgmp-logs\<date>-<time>-<host|join>-<name>.log`.
- **What's in it:** the players and the network; every 600 steps a status line with the step, the world hash, speed, fps and each peer's step; desyncs with the engine's report; resyncs, battles, saves and loads; and any exception with its stack trace.
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
  - never merge PRs, close Jira cards or rename a branch with an open PR.
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
