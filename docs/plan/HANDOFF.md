# Handoff: the next session starts here

Written 2026-10-25, end of day, after SOS-64; status brought up to main on 2026-10-30 (docs audit). It covers both repositories. SOSDET's own `docs/plan/HANDOFF.md` keeps the engine-only notes (rig, patches, the engine's open items).

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
- a campaign save before every battle, and a desync's Save and exit as a real campaign save (SOS-67, SOS-68); Save Campaign in the escape menu, with Quit and Exit saving first (SOS-79, [escape-save.md](escape-save.md));
- diplomacy between players: treaties, money and goods (SOS-72, [diplomacy.md](diplomacy.md)), another player in vanilla's faction window (SOS-76, [diplomacy-window.md](diplomacy-window.md)), regions, vassalage and passage (SOS-73, [diplomacy-2.md](diplomacy-2.md));
- goods and tariffs between players (SOS-77, [goods-tariffs.md](goods-tariffs.md)); names and banners on every PC (SOS-78, [names-banners.md](names-banners.md)); every transfer in the playtest log (SOS-81, [transfer-log.md](transfer-log.md));
- war between players and real-time battles between two players' armies, with the sally from a besieged region (SOS-74, SOS-83, [war.md](war.md), [battles-between-players.md](battles-between-players.md));
- city viewing (SOS-82, [city-viewing.md](city-viewing.md));
- sieges of a player's capital, step A: the siege's popup, the days to the assault and Sally out (SOS-92, hook M9, [sieges.md](sieges.md));
- playtest fixes: no capital without roads (SOS-75), the panel's status text and hide button (SOS-84, SOS-91), the host's lead of 1000 steps (SOS-86), the governor's reasons in the log (SOS-94), and batch 1 (PR #66): the ruler name (SOS-98), cursors in cities (SOS-58), a Steam invite from the main menu (SOS-103) and compressed streams with network figures in the log (SOS-96). Protocol 16.

**Open PRs** (2026-10-30):
- SOSGSGMP #67 and SOSDET #63: SOS-92 steps B and C in part, the assault on a player's capital.
- SOSGSGMP #68: SOS-93, the plan, spike design and draft spec changes for NPC battles on their own clock.
- SOSGSGMP #69: SOS-100, the player Diplomacy tab laid out as vanilla's NPC deal.
- SOSGSGMP #70: SOS-105, Steam's newer messages through JNA, behind `SteamPort` (opt-in).
- SOSDET #64: SOS-102, the unit tests run again.

**GUI changelog, SOS-80** (the owner checks GUI changes in-game; no tests for pure layout). Keep these conventions in new screens:
- **Leave** goes in the bottom-right corner of every setup panel: add the panel's other buttons as its last row, then call `MpSetupLeave.addBottomRight(panel, buttonsRow, MpSetupLeave.button(...))`. Never add Leave inline with other buttons.
- **Initialize World** is host-only and shows only on the last factions screen (Place Capital): `MpFactions.side(back, next)` adds it only when `next` is null.
- **Customize Faction's** button reads "Pick Location".
- **Player lists** wrap (`GText.setMaxWidth` + `setMultipleLines(true)`, then `adjustWidth()` after each rebuild) and take at most half the screen (`C.HEIGHT() / 2`); size them from the player count, not `LobbySettings.MAX_PLAYERS`. Rows past the cap are cut off; there is no scrolling.
- **Status text boxes** are sized for their longest message plus a spare line (`Font.getHeight(text, width)`), so the panel grows downward instead of the text overlapping the buttons. A new long status message must go into that measure (`MpFactions.statusHeight`).
- **Full-screen prompts with one button** go through `menu.MpPrompt.show(text, button)`: vanilla's `IPromtScreen` puts a lone button half a slot left of centre. Two or more buttons are centred already, so call `fullScreen.activate` directly.

**Jira** (read 2026-10-30). Every card with a merged PR is Done, SOS-58 (cursors) included. Open:
- In progress: SOS-92 (sieges), SOS-93 (NPC battles on their own clock), SOS-100 (the Diplomacy tab).
- Also open: SOS-85 (a viewer's PC stopped at a view's load), SOS-95 (a report desync after a view ends), and the 2026-10-30 cards SOS-101, SOS-104 and SOS-105.
- The epics SOS-22 (engine) and SOS-47 (mod).
- The planning cards SOS-18, SOS-19 and SOS-21.

From now on, move a card to In Review when its PR opens. When the PR merges, comment with it and move the card to Done, so cards don't pile up again.

**Installed for play.** This PC's `%APPDATA%\songsofsyx\mods` and the laptop's hold the SOS-64 builds of both mods. `C:\ClaudeFolder\SOSGSGMP-playtest-2026-10-25.zip` holds the same builds for a friend, with a README and `send-logs.cmd`.

## 3. Next

1. **Sieges of player cities, SOS-92** ([sieges.md](sieges.md)): step A is merged; steps B and C (the assault) are in review; the outcomes, the removed people and allied attackers follow.
2. **The open PRs above**, then the cards from the 2026-10-29 playtest (SOS-85, SOS-95, SOS-101, SOS-104, SOS-105).
3. **Battles:** resync in a battle (engine plan 0006 M3), the speed vote, allies joining, spectators' view; SOS-93 (NPC battles on their own clock) waits for the owner's answers in PR #68.
4. **Chat and notifications** (§12, apart from cursors), who paused, debug mode off, elimination and spectating.
5. **Planning cards still open:** SOS-18 (distribution), SOS-19 (test plan), SOS-21 (the board).
6. Spec §17 keeps the feature-by-feature status and the estimate for v1.

## 4. Known issues

- **Market runs in the rig:** a human trades only with capitals it reaches, never across unclaimed land. In Mogus-72 use `--make-humans 2,24`. A game day is 1,152 s and trade runs once a day, so a sale needs about 45,000 steps.
- **High speeds:** SOS-90 keeps the lockstep windows at half a second of real time at every speed; whether 5x and above now reach their full rate is still to check in a playtest.
- **"No active entry points" warning:** found and fixed in SOS-75 (SOSDET #46 and the SOSGSGMP PR). Islet capitals with no land beyond their square could get no roads; the location rules now refuse them. Plan and results: [joiner-roads.md](joiner-roads.md).
- **City↔world items at a resume:** anything crossing in the last ~0.5 s before a campaign save is lost on resume. A battle's return has the same gap.
- **Engine open items** (SOSDET HANDOFF): SOS-32's owner checks (camera and clicks, older-save followers, draw tracing), the V01B rig split at step 154,365, the resync with an open prompt, and the scenario suite's recordings, all older than the AI log's and hash's format 8.

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

- **Build:** `gradlew.bat build installMod` in each repository, run through PowerShell with `SOS_GAME_DIR` set. Mod: 158 unit and loopback tests (PR #66).
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
