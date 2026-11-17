# Handoff: the next session starts here

Written 2026-10-25, end of day, after SOS-64; status brought up to main on 2026-11-01 (release docs audit), batch 10 added on 2026-11-08, and brought up to main again on 2026-11-16 (SOSGSGMP #119, SOSDET #100), and batch 12 added on 2026-11-17 (SOSGSGMP #123, SOSDET #102). It covers both repositories. SOSDET's own `docs/plan/HANDOFF.md` keeps the engine-only notes (rig, patches, the engine's open items).

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
- disconnects (SOS-65, with the engine's removal SOS-66 and SOSDET #43): a player dropping holds every PC at one step; the host continues without them (they are removed and the campaign autosaves) or returns to the last save. A resume can start without a missing player, who is then frozen or removed (batch 12). Plan and results: [disconnects.md](disconnects.md).
- a campaign save before every battle, and a desync's Save and exit as a real campaign save (SOS-67, SOS-68); Save Campaign in the escape menu, with Quit and Exit saving first (SOS-79, [escape-save.md](escape-save.md));
- diplomacy between players: treaties, money and goods (SOS-72, [diplomacy.md](diplomacy.md)), another player in vanilla's faction window (SOS-76, [diplomacy-window.md](diplomacy-window.md)), regions, vassalage and passage (SOS-73, [diplomacy-2.md](diplomacy-2.md));
- goods and tariffs between players (SOS-77, [goods-tariffs.md](goods-tariffs.md)); names and banners on every PC (SOS-78, [names-banners.md](names-banners.md)); every transfer in the playtest log (SOS-81, [transfer-log.md](transfer-log.md));
- war between players and real-time battles between two players' armies, with the sally from a besieged region (SOS-74, SOS-83, [war.md](war.md), [battles-between-players.md](battles-between-players.md));
- city viewing (SOS-82, [city-viewing.md](city-viewing.md));
- watching another player's NPC battle, without asking: the watcher's PC runs the battle at its own pace from the fighter's tape, and each join reloads the battle on the fighter's PC and every watcher's, and any number can watch (SOS-109, SOS-151, SOSDET plan 0017);
- sieges of a player's capital, whole: the wait, Sally out, the lockstep assault with each side's commanders, Retreat, Sack, Vassalage, Eliminate and allied attackers (SOS-92, hook M9, [sieges.md](sieges.md));
- spectators after elimination (SOS-111); chat, notifications and who paused (SOS-110, [chat-notifications.md](chat-notifications.md));
- real-time NPC battles on their own clock (SOS-93);
- battles with allies: the winner's choice on either army, the speed vote, allies in both kinds of battle (SOS-116 to SOS-119, [battles-allies.md](battles-allies.md));
- the 2026-11-01 playtest's list: direct links, global events, cursors on the minimaps, no Resettle, and the NPC courts' desync (SOS-121 to SOS-125);
- playtest fixes: no capital without roads (SOS-75), the panel's status text and hide button (SOS-84, SOS-91), the host's lead of 1000 steps (SOS-86), the governor's reasons in the log (SOS-94), and batch 1 (PR #66): the ruler name (SOS-98), cursors in cities (SOS-58), a Steam invite from the main menu (SOS-103) and compressed streams with network figures in the log (SOS-96);
- since 2026-11-01: reports, every joining PC's logs, crash files and saves sent to the host (SOS-133); the depots and the Goods tab with other players (SOS-134 to SOS-139, SOS-148); the playtest batches 4 to 9 (SOS-140 to SOS-155); one data folder, `sosgsgmp` (SOS-155); the loading window (SOS-158); the same game files in the lobby (SOS-159); Linux (SOS-157) and macOS (PR #118, from a contributor);
- reconnect: the lost-connection window, resumable message streams and the host's own reconnect (SOS-175 to SOS-180, [reconnect.md](reconnect.md), [network-baseline.md](network-baseline.md));
- the living world, joining a running campaign by restart, and freezing a player (SOS-187 to SOS-199, SOS-204, SOS-212 to SOS-216; [hot-join-restart.md](hot-join-restart.md), [freeze.md](freeze.md), [living-world-refine.md](living-world-refine.md));
- the 2026-11-12 and 2026-11-14 playtests' fixes (SOS-197 to SOS-211);
- batch 12, from the owner's Steam session of 2026-11-16 (SOS-217 to SOS-220, [batch-12.md](batch-12.md)): a player the host continued without joins the running game again as a new player (spec 0.70), and over Steam a refusal now arrives; full-screen windows centre their buttons and never cut their text; a resume started without players asks Freeze them or Remove them (spec 0.71, SOSDET plan 0021); over Steam a host of another version is named at once; macOS keeps Steam's direct connections on an Intel Mac (PR #122, from a contributor). Network protocol 32 (`Protocol.VERSION`), campaign record format 5.

**Batch 10, the overnight three-player battle tests (2026-11-08).** Plan: `C:\ClaudeFolder\Test List.txt`; report and per-scenario PDFs: `C:\ClaudeFolder\PTreport\2026-11-08-battles\` (REPORT.txt).
- Feature: every allied player near a battle against NPCs is asked Join, and each who joins fights on their own PC (SOS-160 engine, SOS-161 mod; SOSDET plan 0019).
- Fixes: SOS-163 to SOS-172 and SOS-174. SOS-173 (a region town's sally brought no soldiers) was closed on 2026-11-11 as a test setup: `giveregion` gives a region no garrison.
- Test tooling (SOS-162): test-only lockstep commands (`morale`, `recruit`, `giveregion`, `givevassal`, `battlemen`, teleports, ...) and `tools\battles3.py`: `prep BASE|ALLIES|WAR|REGIONS`, then `run <scenario>` or `all`, three games on this PC from a scratch APPDATA, like raid3.
- On the final builds every scenario passed (A1 to A8 without A5, B1 to B11, C1 to C4). A5 is blocked: vanilla V71 auto-resolves a town's storm.
- Owner decisions open: A5, and an eliminated player's UI showing the host's realm (B8).

**Open PRs (2026-11-17):** none but this handoff update.

**GUI changelog, SOS-80** (the owner checks GUI changes in-game; no tests for pure layout). Keep these conventions in new screens:
- **Leave** goes in the bottom-right corner of every setup panel: add the panel's other buttons as its last row, then call `MpSetupLeave.addBottomRight(panel, buttonsRow, MpSetupLeave.button(...))`. Never add Leave inline with other buttons.
- **Initialize World** is host-only and shows only on the last factions screen (Place Capital): `MpFactions.side(back, next)` adds it only when `next` is null.
- **Customize Faction's** button reads "Pick Location".
- **Player lists** wrap (`GText.setMaxWidth` + `setMultipleLines(true)`, then `adjustWidth()` after each rebuild) and take at most half the screen (`C.HEIGHT() / 2`); size them from the player count, not `LobbySettings.MAX_PLAYERS`. Rows past the cap are cut off; there is no scrolling.
- **Status text boxes** are sized for their longest message plus a spare line (`Font.getHeight(text, width)`), so the panel grows downward instead of the text overlapping the buttons. A new long status message must go into that measure (`MpFactions.statusHeight`).
- **Full-screen prompts** go through `menu.MpPrompt.show(text, buttons...)`, never `fullScreen.activate` directly: vanilla's `IPromtScreen` centres only two buttons and draws five lines of text. `MpPrompt` centres any row and makes the text box as high as the text (adapter `PromptBox`; batch 12).

**Installed for play.** The newest playtest pack is `C:\ClaudeFolder\modpack\SOSGSGMP-playtest-2026-11-17.zip` (merged main of both repositories, batch 12), with CHANGELOG.txt and `send-logs.cmd`; its Workshop entry is on top of `C:\ClaudeFolder\modpack\WORKSHOP-CHANGELOG.txt`.

## 3. Next

1. **For the owner:** debug mode isn't off in multiplayer (spec §4), and the engine's speed floor is 0.25× against the spec's 0.5× (spec §4).
2. **A player's install (SOS-18):** the build checks are in (SOS-126 to SOS-128). Left: publishing on the Steam Workshop.
3. **Battles:** spec §16's open question (a commander who drops out, a slow ally), and each attacker's own bonuses in an assault, which nothing covers yet.
4. **Long tests:** the 2-hour world check, the 30-minute battle with 2,000 units per side, the 10,000-person city at 1×, and play-tests at 300 ms ping.
5. **Steam tests:** a join over Steam (SOS-191); batch 12's joining again after Continue without them, and a resume with Freeze them; a full session between a Mac and another PC over Valve's relays.
6. **SOS-221:** a frozen player comes back from their newest save, not the one the host resumed; the owner wants a solution of its own.
7. **Planning still open:** distribution (SOS-18: the hooks' route is [ADR 0010](../decisions/0010-mod-hooks-ship-in-script-jar.md), publishing is left) and the test plan (SOS-19).
8. Spec §17 keeps the feature-by-feature status.

## 4. Known issues

- **Market runs in the rig:** a human trades only with capitals it reaches, never across unclaimed land. In Mogus-72 use `--make-humans 2,24`. A game day is 1,152 s and trade runs once a day, so a sale needs about 45,000 steps.
- **High speeds:** SOS-90 keeps the lockstep windows at half a second of real time at every speed. Two PCs over Radmin held 25x at 400 steps/s, with about 1,000 steps/s to spare, in the LLM runs of 2026-10-31. 250×, and 25× in a late-game city, run only as fast as the slowest PC.
- **One world desync with no cause found:** step 133,230 of L2, in the NPC stocks section; the automatic resync cleared it at once. A desync report now names each NPC's stocks, so the next one names the NPC and the value.
- **"No active entry points" warning:** found and fixed in SOS-75 (SOSDET #46 and the SOSGSGMP PR). Islet capitals with no land beyond their square could get no roads; the location rules now refuse them. Plan and results: [joiner-roads.md](joiner-roads.md).
- **City↔world items at a resume:** anything crossing in the last ~0.5 s before a campaign save is lost on resume. A battle's return has the same gap.
- **Engine open items** (SOSDET HANDOFF): SOS-32's owner checks (camera and clicks, older-save followers, draw tracing), the V01B rig split at step 154,365, the resync with an open prompt, and the scenario suite's recordings, all older than the AI log's and hash's format 8.

## 5. Playtesting with logs

Every PC writes its own log of each multiplayer session, with no setup:
- **Where:** `%APPDATA%\songsofsyx\sosgsgmp\logs\<date>-<time>-<host|join>-<name>.log` (`sosgsgmp-logs` before SOS-155).
- **What's in it:** the players and the network; every 600 steps a status line with the step, the world hash, speed, fps, treasury and each peer's step; proposals, treaties, tariffs and realms between players; every goods and money transfer of every player (`transfer step ...` lines; the host's lobby switch picks every transfer or only those between players; SOS-81); desyncs with the engine's report; resyncs, battles, saves and loads; and any exception with its stack trace.
- **Comparing PCs:** the hashes in the status lines are at the same steps on every PC, so two logs side by side show where a desync began.

**After a session:**
- **This PC plus the laptop:** `python tools\collect.py --remote user@address`. It zips both PCs' logs and crash files from the last 24 hours. Add `--remote-appdata C:\SOSTest\inst\2` for a `twopc.py` run.
- **A friend:** they close the game and run `send-logs.cmd` from the playtest zip. It puts a zip of their logs and crash files from the last 24 hours on their Desktop for them to send. Run `python tools\collect.py` here for this PC's half.
- **Write down** roughly when something happened, and what each player did just before.

## 6. Tests

- **Build:** `gradlew.bat build installMod` in each repository, run through PowerShell with `SOS_GAME_DIR` set. Mod: unit and loopback tests (312 on 2026-11-17).
- **Two PCs, mod:** `tools\twopc.py`. Set `SOSGSGMP_REMOTE=user@address` and `SOSGSGMP_HOST_ADDRESS=26.x.x.x`.
  - Options: `--steps N`, `--speed X`, `--seed N`, `--corrupt STEP --on-desync resync|exit`, `--net steam`, `--save STEP`, `--resume` (with `--on-missing freeze|remove`) and `--window WxH`. `--window` sets the game's window, which the game keeps in twentieths of the monitor; before batch 12 every twopc game ran at the full monitor.
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

- **Plan first** for anything larger than a small fix; one piece of work at a time; new work needs the owner's approval.
- **Git:**
  - one branch per piece of work;
  - PRs are based on main;
  - never merge PRs or rename a branch with an open PR.
  - Commits end with the `Co-Authored-By` line, PR bodies with the Claude Code line.
- **Secrets:** the keys in `C:\ClaudeFolder\SOS\.env` are never printed. Read the GitHub token into `GH_TOKEN` (see memory `jira-github-setup`). Push with `git -c credential.helper= -c credential.helper='!gh auth git-credential' push`.
- **Shell:**
  - never `cmd /c` from Git Bash;
  - Gradle goes through PowerShell;
  - Python edit scripts go in files written with the Write tool, because heredocs break escapes like `\n`;
  - keep each file's line endings, and compare `git diff --stat` with `--ignore-cr-at-eol` before committing.
- **Game files:** never commit, copy or shade the game's folder, source or jar, and never patch `SongsOfSyx.jar`. Patched classes shipped inside the mods are fine (the developer allowed it).
