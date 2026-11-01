# Handoff: the next session starts here

Written 2026-10-25, end of day, after SOS-64; status brought up to main on 2026-11-01 (release docs audit). It covers both repositories. SOSDET's own `docs/plan/HANDOFF.md` keeps the engine-only notes (rig, patches, the engine's open items).

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

**What works.** Players share one world map, tested with up to 3, each running their own city, over direct IP or Steam:
- the lobby and the setup: map, factions and locations, then the landing party (SOS-49, SOS-54, SOS-56); Ready for every player, the connection notice while placing, and Leave's prompt in the lobby (SOS-120);
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
- watching another player's NPC battle, without asking: the watcher's PC runs the battle at its own pace from the fighter's tape, and each join reloads the battle on both PCs (SOS-109, SOSDET plan 0017);
- sieges of a player's capital, whole: the wait, Sally out, the lockstep assault with each side's commanders, Retreat, Sack, Vassalage, Eliminate and allied attackers (SOS-92, hook M9, [sieges.md](sieges.md));
- spectators after elimination (SOS-111); chat, notifications and who paused (SOS-110, [chat-notifications.md](chat-notifications.md));
- real-time NPC battles on their own clock (SOS-93);
- battles with allies: the winner's choice on either army, the speed vote, allies in both kinds of battle (SOS-116 to SOS-119, [battles-allies.md](battles-allies.md));
- the 2026-11-01 playtest's list: direct links, global events, cursors on the minimaps, no Resettle, and the NPC courts' desync (SOS-121 to SOS-125);
- playtest fixes: no capital without roads (SOS-75), the panel's status text and hide button (SOS-84, SOS-91), the host's lead of 1000 steps (SOS-86), the governor's reasons in the log (SOS-94), and batch 1 (PR #66): the ruler name (SOS-98), cursors in cities (SOS-58), a Steam invite from the main menu (SOS-103) and compressed streams with network figures in the log (SOS-96). Protocol 20; city report protocol 7.

**Open PRs:** none in either repository (2026-11-01).

**GUI changelog, SOS-80** (the owner checks GUI changes in-game; no tests for pure layout). Keep these conventions in new screens:
- **Leave** goes in the bottom-right corner of every setup panel: add the panel's other buttons as its last row, then call `MpSetupLeave.addBottomRight(panel, buttonsRow, MpSetupLeave.button(...))`. Never add Leave inline with other buttons.
- **Initialize World** is host-only and shows only on the last factions screen (Place Capital): `MpFactions.side(back, next)` adds it only when `next` is null.
- **Customize Faction's** button reads "Pick Location".
- **Player lists** wrap (`GText.setMaxWidth` + `setMultipleLines(true)`, then `adjustWidth()` after each rebuild) and take at most half the screen (`C.HEIGHT() / 2`); size them from the player count, not `LobbySettings.MAX_PLAYERS`. Rows past the cap are cut off; there is no scrolling.
- **Status text boxes** are sized for their longest message plus a spare line (`Font.getHeight(text, width)`), so the panel grows downward instead of the text overlapping the buttons. A new long status message must go into that measure (`MpFactions.statusHeight`).
- **Full-screen prompts with one button** go through `menu.MpPrompt.show(text, button)`: vanilla's `IPromtScreen` puts a lone button half a slot left of centre. Two or more buttons are centred already, so call `fullScreen.activate` directly.

**Jira.** The board holds each card's state; this file doesn't track it. A card goes to In Review when its PR opens. When the PR merges, comment with it and move the card to Done.

**Installed for play.** The newest playtest pack is `C:\ClaudeFolder\SOSGSGMP-playtest-2026-11-01.zip`, with a README and `send-logs.cmd`. It holds main plus SOS-120, from before SOS-121 to SOS-125, so build a new one before the next playtest.

## 3. Next

1. **For the owner:** debug mode isn't off in multiplayer (spec §4), and the engine's speed floor is 0.25× against the spec's 0.5× (spec §4).
2. **A player's install (SOS-18):** a clear message when SOSDET is missing, a check of the engine and game builds between PCs, and a check of the game's build at run time (section 4).
3. **Battles:** spec §16's open question (a commander who drops out, a slow ally), and each attacker's own bonuses in an assault, which no card covers yet.
4. **Long tests:** the 2-hour world check, the 30-minute battle with 2,000 units per side, the 10,000-person city at 1×, and play-tests at 300 ms ping.
5. **Planning cards still open:** SOS-18 (distribution), SOS-19 (test plan), SOS-21 (the board).
6. Spec §17 keeps the feature-by-feature status.

## 4. Known issues

- **Market runs in the rig:** a human trades only with capitals it reaches, never across unclaimed land. In Mogus-72 use `--make-humans 2,24`. A game day is 1,152 s and trade runs once a day, so a sale needs about 45,000 steps.
- **High speeds:** SOS-90 keeps the lockstep windows at half a second of real time at every speed. Two PCs over Radmin held 25x at 400 steps/s, with about 1,000 steps/s to spare, in the LLM runs of 2026-10-31. 250×, and 25× in a late-game city, run only as fast as the slowest PC.
- **SOSDET missing:** the game stops at start-up with "Failed loading assets! file corrupt: ...SOSGSGMP.jar" and "menu.MpDeals$1 could not be loaded". Nothing names SOSDET.
- **Versions between PCs:** `Hello` carries no engine or game build. The lobby refuses another mod protocol, but not another SOSDET or game build, and nothing checks the game's build at run time.
- **One world desync with no cause found:** step 133,230 of L2, in the NPC stocks section; the automatic resync cleared it at once. A desync report now names each NPC's stocks, so the next one names the NPC and the value.
- **"No active entry points" warning:** found and fixed in SOS-75 (SOSDET #46 and the SOSGSGMP PR). Islet capitals with no land beyond their square could get no roads; the location rules now refuse them. Plan and results: [joiner-roads.md](joiner-roads.md).
- **City↔world items at a resume:** anything crossing in the last ~0.5 s before a campaign save is lost on resume. A battle's return has the same gap.
- **Engine open items** (SOSDET HANDOFF): SOS-32's owner checks (camera and clicks, older-save followers, draw tracing), the V01B rig split at step 154,365, the resync with an open prompt, and the scenario suite's recordings, all older than the AI log's and hash's format 8.

## 5. Playtesting with logs

Every PC writes its own log of each multiplayer session, with no setup:
- **Where:** `%APPDATA%\songsofsyx\sosgsgmp-logs\<date>-<time>-<host|join>-<name>.log`.
- **What's in it:** the players and the network; every 600 steps a status line with the step, the world hash, speed, fps, treasury and each peer's step; proposals, treaties, tariffs and realms between players; every goods and money transfer of every player (`transfer step ...` lines; the host's lobby switch picks every transfer or only those between players; SOS-81); desyncs with the engine's report; resyncs, battles, saves and loads; and any exception with its stack trace.
- **Comparing PCs:** the hashes in the status lines are at the same steps on every PC, so two logs side by side show where a desync began.

**After a session:**
- **This PC plus the laptop:** `python tools\collect.py --remote user@address`. It zips both PCs' logs and crash files from the last 24 hours. Add `--remote-appdata C:\SOSTest\inst\2` for a `twopc.py` run.
- **A friend:** they close the game and run `send-logs.cmd` from the playtest zip. It puts a zip of their logs and crash files from the last 24 hours on their Desktop for them to send. Run `python tools\collect.py` here for this PC's half.
- **Write down** roughly when something happened, and what each player did just before.

## 6. Tests

- **Build:** `gradlew.bat build installMod` in each repository, run through PowerShell with `SOS_GAME_DIR` set. Mod: 184 unit and loopback tests.
- **Two PCs, mod:** `tools\twopc.py`. Set `SOSGSGMP_REMOTE=user@address` and `SOSGSGMP_HOST_ADDRESS=26.x.x.x`.
  - Options: `--steps N`, `--speed X`, `--seed N`, `--corrupt STEP --on-desync resync|exit`, `--net steam`, `--save STEP` and `--resume`.
  - `--resume` keeps both data folders from the last run and copies the installed mods into them.
  - Each instance gets its own player key: the runner deletes the copied lobby settings, and the game saves a new key at once.
  - Each plan under `docs/plan/` records its own runs.
- **Engine:** SOSDET's rig and suite (`rig\suite.py`, see SOSDET `rig/README.md`).
- **Owner rules for runs:**
  - one game at a time: list `java.exe` on both PCs first;
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
