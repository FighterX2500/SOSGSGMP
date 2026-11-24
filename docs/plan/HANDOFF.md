# Handoff: where things stand

Updated 2026-11-23: main at SOSGSGMP #127 and SOSDET #104 (both merged 2026-11-22). This file covers both repositories and is the one place for the project's status: what works, what's next, known issues, how to test, and the milestones (section 8, which was spec §17). SOSDET's own `docs/plan/HANDOFF.md` keeps the engine's open items. The rules are in `docs/spec.md`, the design in `docs/architecture.md` and the ADRs. Finished plans, spikes and batch notes are in `docs/archive/`: history, not kept up to date.

## 1. Read first

1. This repository's `CLAUDE.md` and `AGENTS.md`: the workflow and the coding rules.
2. For engine work: SOSDET's `CLAUDE.md`, `AGENTS.md`, `.claude/rules/determinism.md` and `docs/architecture.md`.
3. `docs/spec.md` for the section you work on, and `docs/architecture.md`.

## 2. Where things stand

**Repositories**
- **Mod:** `C:\ClaudeFolder\SOS`, GitHub `FighterX2500/SOSGSGMP`.
- **Engine:** `C:\ClaudeFolder\SOSDET`, GitHub `FighterX2500/Songs-of-Syx-Deterministic-Engine` (public).
- **The game:** `C:\ClaudeFolder\Songs of Syx`, V71 build 71.44.

**Versions:** network protocol 34 (`Protocol.VERSION`), campaign record format 5 (`CampaignRecord.FORMAT`). The mod has 383 `@Test` methods (unit and loopback tests).

**What works:** every v1 feature in spec §2, played over Steam and direct IP with up to 3 players. The README's "What it does" lists them; section 8 below says when each came in and what each still lacks.

**Since 2026-11-16:**
- **Batch 12** (SOSGSGMP #123, SOSDET #102): a player the host continued without joins again as a new player (spec 0.70); a resume started without players asks Freeze them or Remove them (spec 0.71); full-screen windows centre their buttons and never cut their text; over Steam a refusal arrives and a host of another version is named.
- **Batch 13** (SOSGSGMP #125, #126, SOSDET #103): the 2026-11-17 playtest's list (SOS-222 to SOS-236): allies in an invasion's Attack, allied armies stationed at a capital and its camp ([ADR 0011](../decisions/0011-allied-army-stationed-at-a-capital.md)), a battle restart by vote, deals as vanilla's message, gear after a battle, the Goods tab's prices, the resume list, the autosave while paused, cursors and long names. Protocol 33.
- **Batch 14** (SOSGSGMP #127, SOSDET #104): the Steam pass of 2026-11-22 (SOS-237 to SOS-243): Steam starts once per game process ([ADR 0012](../decisions/0012-steam-once-per-process.md)), so End for everyone no longer crashes the host; invites and Join Game work on every multiplayer screen; Steam tests run on one PC under App ID 480 with a second account in Sandboxie; the host's log names refused joins; a frozen city doesn't trade (spec 0.72); Remove them at a resume's start with armies; a leaving PC closes its TCP sockets cleanly. Hot-join through Steam passed end to end in that session.
- **Batch 15** (SOS-246 to SOS-250, [plan](batch-15.md)): logging for precise bug reports, without any upload: the log opens at the lobby; lines every PC writes alike (`=`/`~`) and `tools\logdiff.py`; the recorder, a crash-proof ring of everything this PC saw, streamed to the host as it is written, with incident copies at a desync, a long freeze or an error (spec 0.74); the engine's applied-command and army-decision rings and the world hash split into 14 parts, so a desync report names the part (SOSDET 0022). Protocol 34.

**Open PRs (2026-11-23):** only this docs compression.

**Installed for play:** the newest playtest pack is `C:\ClaudeFolder\modpack\SOSGSGMP-playtest-2026-11-22.zip` (batch 14's tested jars), with CHANGELOG.txt and `send-logs.cmd`; its Workshop entry is on top of `C:\ClaudeFolder\modpack\WORKSHOP-CHANGELOG.txt`.

## 3. Next

1. **For the owner:** debug mode isn't off in multiplayer (spec §4); the engine's speed floor is 0.25× (`Pacer.MIN_SPEED`) against spec §4's 0.5×.
2. **Release:** publishing on the Steam Workshop (SOS-18; the hooks' route is [ADR 0010](../decisions/0010-mod-hooks-ship-in-script-jar.md)) and the test plan (SOS-19).
3. **SOS-221:** a frozen player comes back from the save the host resumed, not their newest; the owner wants a solution of its own.
4. **Battles:** spec §16's open question (a commander who drops out, a slow ally); each attacker's own bonuses in an assault, which no card covers yet; the owner's open decisions from batch 10's battle tests: A5 (vanilla V71 auto-resolves a town's storm) and B8 (an eliminated player's UI shows the host's realm).
5. **Long tests:** the 2-hour world check, the 30-minute battle with 2,000 units per side, the 10,000-person city at 1×, and a playtest at a real 300 ms ping (emulated conditions passed: spec §16).
6. **Steam tests:** a full session between a Mac and another PC over Valve's relays; SOS-191's battles3 scenarios for carved city-states and hot-join, with their report.
7. **Follow-up cards:** SOS-244 (the host's log repeats the last session's chat notices after a hot-join restart; log only), SOS-245 (an invite accepted while the game isn't running doesn't join: `+connect_lobby`); also an invite accepted during a game.
8. **Older open cards:** SOS-85 (a viewer's PC stopped at a view's load), SOS-95 (a desync report 180 steps after a view ends), SOS-132 (every game freezes during an NPC invasion of a player's capital, and a save hangs), SOS-107 and SOS-108 (engine: the suite's recordings, citizens on worker threads), SOS-156 (telemetry, parked).

## 4. Known issues

- **A view's lost steps (SOS-222, batch 13 item 11):** not reproduced; the causes are probable only. Batch 13 shipped logging for the next playtest: `viewdivs` on the owner's and the viewer's PC, the whole `viewdesync` report at a lost step.
- **The sally's battle is generated inside the step:** about 11.7 s on the host that every PC waits for (batch 8's B8F; later, no card).
- **A local battle with allies has no 2-minute deploy timeout** (a battle between players has one): an ally who stays in the view and never presses Ready holds the start.
- **City viewing:** a room the viewer is drawing is lost when the view reloads; if the owner leaves while the viewer loads, the viewer's PC stays held; a `RaidingMap.process()` run from the UI's hover may be a per-PC draw (seen in the SOS-82 work, not proven).
- **Steam:** an `Assert( !IsLocked() )` dump now and then on the host as a PC joins (18 between 2026-11-16 and 2026-11-22); nothing seen breaks.
- **Market runs in the rig:** a human trades only with capitals it reaches, never across unclaimed land. In Mogus-72 use `--make-humans 2,24`. A game day is 1,152 s and trade runs once a day, so a sale needs about 45,000 steps.
- **High speeds:** SOS-90 keeps the lockstep windows at half a second of real time at every speed. Two PCs over Radmin held 25x at 400 steps/s, with about 1,000 steps/s to spare, in the runs of 2026-10-31. 250×, and 25× in a late-game city, run only as fast as the slowest PC.
- **One world desync with no cause found:** step 133,230 of the L2 run, in the NPC stocks section; the run's resync (chosen by the test runner) cleared it at once. A desync report now names each NPC's stocks, so the next one names the NPC and the value.
- **City↔world items at a resume:** anything crossing in the last ~0.5 s before a campaign save is lost on resume. A battle's return has the same gap.
- **Engine open items:** SOSDET's `docs/plan/HANDOFF.md`.

## 5. Playtesting with logs

Every PC writes its own log of each multiplayer session, with no setup:
- **Where:** `%APPDATA%\songsofsyx\sosgsgmp\logs\<date>-<time>-<host|join>-<name>.log`. It opens at Host, Join, a resume or a Steam join (batch 15), so the lobby (refusals, players, its start) is in the same file as the game after it, a hot-join's restart too; a lobby or setup left without a game closes it.
- **What's in it:** a system line (OS, Java, heap, cores, the game's build, the mods in order); the players and the network; every 600 steps a status line with the step, the world hash, speed, fps, treasury, each peer's step, the heap used, committed and max, and the engine's draws; proposals, treaties, tariffs and realms between players; every goods and money transfer (`transfer step ...` lines; the host's lobby switch picks every transfer or only those between players); desyncs with the engine's report (the world parts that differ and their items, SOSDET 0022); resyncs, battles, saves and loads; what holds a step over a second; draws outside the simulation; this player's game messages (`message: ...`) and its city's day (`city day N: deaths ...; arrivals ...`); and any exception with its stack trace.
- **Lines every PC writes alike** start, after the time, with `= <step>` (world, military and diplomacy commands; armies created, besieging, disbanded) or, for kinds not yet confirmed equal by a twopc run, `~ <step>` or `~ d<day>` (stances, region owners, capitals, factions born and gone, battles, sieges, successions, the world's news, scripted events). `python tools\logdiff.py <log A> <log B> [--all]` prints the first key where two PCs' lines differ, and the desync reports' items that differ.
- **The recorder:** `%APPDATA%\songsofsyx\sosgsgmp\recorder\<host|join>-<name>.rec`, a memory-mapped ring that survives a crash: network messages (type and size, never their text), every log line, holds, slow frames, speed, loading windows, hashes, every applied command and AI entry, every army decision. A desync, a freeze over 5 s or an error copies it to `recorder\<log name>-<step>-<why>.rec` (5 a session at most, one per incident; the newest 20 copies are kept). `python tools\recorder.py <file> [--from STEP] [--to STEP] [--kind K]` prints it.
- **On the host:** each joining PC's reports folder also holds its `recorder.stream` (the entries only that PC has, a 16 MB ring of two files; `recorder.py` reads it), its incident copies and this game's JVM `hs_err_pid*.log` files, without their environment section and with the user name and home folder replaced. A new session's stream starts its own file.
- **Comparing PCs:** the status lines' hashes are at the same steps on every PC, so two logs side by side show where a desync began; `logdiff.py` does it line by line.

After a session:
- **This PC plus the laptop:** `python tools\collect.py --remote user@address` zips both PCs' logs and crash files from the last 24 hours. Add `--remote-appdata C:\SOSTest\inst\2` for a `twopc.py` run.
- **A friend:** they close the game and run `send-logs.cmd` from the playtest zip, which puts a zip of their logs and crash files on their Desktop. Run `python tools\collect.py` here for this PC's half.
- **Write down** roughly when something happened, and what each player did just before.

## 6. Tests

- **Build:** `gradlew.bat build installMod` in each repository, through PowerShell with `SOS_GAME_DIR` set.
- **Two PCs, mod:** `tools\twopc.py`, with `SOSGSGMP_REMOTE=user@address` and `SOSGSGMP_HOST_ADDRESS=26.x.x.x`. Options: `--steps N`, `--speed X`, `--seed N`, `--corrupt STEP --on-desync resync|exit`, `--net steam`, `--save STEP`, `--resume` (with `--on-missing freeze|remove`; keeps both data folders from the last run) and `--window WxH`. Each instance gets its own player key.
- **Three games on this PC:** `tools\battles3.py` (`prep BASE|ALLIES|WAR|REGIONS`, then `run <scenario>` or `all`) and `tools\raid3.py`; test-only lockstep commands (`morale`, `recruit`, `giveregion`, `givevassal`, `battlemen`, teleports) come from SOS-162. Network conditions: `tools\netbase.py`.
- **Steam on one PC:** App ID 480 with a second Steam account in a Sandboxie box (SOS-242).
- **Engine:** SOSDET's rig and suite (`rig\suite.py`, see SOSDET `rig/README.md`).
- **Rules for runs:** one game run at a time (list `java.exe` on both PCs first); a monitor every minute that flags stalls and crash files; short smoke runs; never rebuild SOSDET while a rig run is live; afterwards close vanilla's error windows (`util.error.ErrorMessage` processes) on both PCs.

## 7. How to work

- The workflow is in `CLAUDE.md`, the coding rules in `AGENTS.md`.
- **Git:** one branch per piece of work, PRs based on main, never merge a PR or rename a branch with an open PR. Commits end with the `Co-Authored-By` line, PR bodies with the Claude Code line.
- **Secrets:** the keys in `C:\ClaudeFolder\SOS\.env` are never printed. Push with `git -c credential.helper= -c credential.helper='!gh auth git-credential' push`.
- **Shell:** never `cmd /c` from Git Bash; Gradle goes through PowerShell; Python edit scripts go in files written with the Write tool; keep each file's line endings, and compare `git diff --stat` with `--ignore-cr-at-eol` before committing.
- **Game files:** never commit, copy or shade the game's folder, source or jar, and never patch `SongsOfSyx.jar`. Patched classes shipped inside the mods are fine (the developer allowed it).

## 8. Milestones

What was built and when, from the merged pull requests of both repositories, and where v1 stands. Section numbers in these tables are the spec's.

### Done so far

| Date | Milestone | Cards |
| --- | --- | --- |
| 2026-10-20 | Project set up; spec 0.3; Sprint 0 planning: the rebuild check, the hook surface and the single-player assumptions; menus don't pause; the battle-streaming spike | KAN-4, SOS-2 to SOS-10 |
| 2026-10-21 | The determinism spike: a battle and a city run identically twice | SOS-11, SOS-23 |
| 2026-10-22 | The engine splits off as SOSDET: its build, core, the host's battle AI, the state hash and the battle resync | SOS-24 to SOS-30 |
| 2026-10-23 | City commands and room planning in lockstep; the world map in lockstep; SOSDET goes public (MIT); the city-world link (ADR 0005) | SOS-32, SOS-34 to SOS-38 |
| 2026-10-24 | Battles started from the world map; the speed governor; local checks instead of CI; spec 0.11 and 0.12 (lockstep viewing and assaults, the full review) | SOS-19, SOS-32, SOS-38, SOS-40 to SOS-43 |
| 2026-10-25 | Two-PC battles with a viewer; prompts don't hold the world; several humans in one world; the scenario suite. **The walking skeleton:** the lobby, the setup's map and factions, the game over the network, the desync window, Steam, battles, the playtest log, campaign saves | SOS-15, SOS-31, SOS-44 to SOS-64 |
| 2026-10-26 | A save before every battle; a desync's Save and exit; disconnects and removing a player; sieges by another human arrive; **diplomacy milestone 1** (treaties, money and goods between players); no capital without roads | SOS-65 to SOS-72, SOS-75 |
| 2026-10-27 | Another player's faction window; Save Campaign in the escape menu; names and banners on every PC; **goods and tariffs between players**; **diplomacy milestone 2** (regions, vassals and protectors, trade at peace); the setup's GUI fixes; every transfer in the playtest log | SOS-73, SOS-76 to SOS-81 |
| 2026-10-28 | **War between players, and real-time battles between two players' armies**, with each side's commanders, deployment, retreat, spoils and the sally; the panel's status text | SOS-74, SOS-83, SOS-84 |
| 2026-10-29 | **City viewing:** a live view of another player's city, on request; playtest fixes: the host's lead up to 1000 steps, each player's own banner, slider buttons, messages on their own player's PC only, lockstep windows of half a second at every speed, a button that hides the panel; the README, the mod's MIT license and the Steam Workshop texts | SOS-82, SOS-86 to SOS-91 |
| 2026-10-30 | **Sieges of a player's capital, step A:** the siege's popup, the days to the assault, Sally out at any time, no trade into or out of a besieged capital; the governor tells a slow PC from a slow link; playtest fixes: cursors in cities, the ruler name, the top bar's banner, world news on every player, Production Rate, a view's held transactions, compressed network streams, a Steam invite from the main menu | SOS-58, SOS-92, SOS-94, SOS-96 to SOS-99, SOS-102, SOS-103 |
| 2026-10-30 | **Sieges and NPC battles:** no assault while a player fights an NPC battle. **A sack's people:** the 80% counts the citizens and slaves in the city, in total; nobles are never removed; with no choice in time, no one is removed (spec 0.35) | SOS-92 |
| 2026-10-30 | **Watching an NPC battle:** any other player joins without asking; a watcher's PC runs the battle at its own pace from the fighting player's orders and speed, and the watch ends only 3 minutes behind. Joining reloads the battle on both PCs | SOS-109 |
| 2026-10-31 | The night run: **sieges finished** (the lockstep assault with commanders, Retreat, every outcome, Eliminate, allied attackers), **spectators**, battle resync, one viewer for each city; a joiner's crash | SOS-92, SOS-111, SOS-114, SOS-109, SOS-112 |
| 2026-10-31 | **Battles with allies, decided:** allies within 4 tiles are asked Join or Stay out; every player's own bonuses; slots split evenly; each commander retreats their own army, at half their share while an ally stays; spoils split by men brought; both armies get the winner's window; the commanders' speed vote; the world's speed comes back after a battle; an ally can join an NPC battle on two PCs only | [ADR 0009](../decisions/0009-several-humans-in-a-battle.md) |
| 2026-10-31 | **Battles with allies, built:** the winner's choice on either army, the commanders' speed vote, allies in both kinds of battle | SOS-116 to SOS-119 |
| 2026-11-01 | Ready for every player, the connection notice while placing, Leave's prompt, market trade at 6 to 15 tiles; a three-player playtest over Steam, about two hours | SOS-105, SOS-120 |
| 2026-11-01 | The 2026-11-01 playtest's list: **direct links** between players 6 to 15 tiles apart, by land or across water (new worlds); **global events**; other players' cursors on the minimaps; no Resettle; the placing screen's top-right buttons reduced to + and −; the desync that playtest showed (NPC courts differed per PC) is fixed and hashed. | SOS-121 to SOS-125 |
| 2026-11-01 | Through Steam, a player who never chose a name joins under their Steam name. | SOS-131 |
| 2026-11-02 | An autosave or Save Campaign waits until no player is viewing a city. A save that ends the session closes the views instead, and the save before a battle can't wait, so a viewer has no save from that moment. | Batch 4 |
| 2026-11-04 | **Reports:** every joining PC sends the host its logs, crash files and campaign saves (section 11). | SOS-133 |
| 2026-11-04 | Players that trade reaches aren't darkened in the faction lists; the depots list other players; a player's Goods tab shows the goods traded with them (section 7). | SOS-134, SOS-135, SOS-136 |
| 2026-11-04 | The 2026-11-04 playtest's list: the depots list only players whose depot takes the goods, with the price in its parts; trade and colleague agreements reach a player's NPC vassals; no trespassing for colleagues; another player's battle against NPCs is told in the chat, and notices about a player open their window (sections 6, 7, 12). | SOS-138 to SOS-141, SOS-144 |
| 2026-11-06 | **Several watchers:** any number of players can watch one NPC battle or view one city; each join reloads the owner's game and every viewer's for a few seconds (spec 0.49; sections 8, 10, 15). | SOS-151 |
| 2026-11-06 | **Joining an assault:** a player can join a running assault's view as any city's; an attacker who joins again commands their soldiers again (spec 0.49; section 10). | SOS-151 |
| 2026-11-06 | **Cursors in a battle:** the other players' cursors show on the battle map too (spec 0.49; section 12). | SOS-152 |
| 2026-11-06 | **One data folder:** the mod's saves, records, logs, reports and lobby settings live under `songsofsyx\sosgsgmp`; an older version's move there once (spec 0.50; section 11). | SOS-155 |
| 2026-11-06 | **Loading window:** every PC shows why the game freezes, and for how long, while the world generates or initializes, a battle starts or ends and a city view starts or ends; the playtest log times every freeze and hold (spec 0.51; sections 12, 15). | SOS-158 |
| 2026-11-07 | **Same game files:** a PC whose game files or mods differ from the host's can't join, and the lobby names what differs; other mods are allowed when every player has them, with a warning; game data that still differ in play end the session with a message instead of a crash (spec 0.52; sections 2, 3, 5, 15). | SOS-159 |
| 2026-11-08 | **Every ally in an NPC battle:** every allied player near a battle against NPCs is asked Join, and every player who joined fights it on their own PC with the fighting player's (spec 0.53; sections 8, 15). | SOS-160, SOS-161 |
| 2026-11-08 | **The end of a battle every PC runs:** a battle between players or an assault opens the loading window on every PC before the load that ends it (spec 0.54; section 12). | SOS-169 |
| 2026-11-09 | **Reconnect:** a PC silent for 5 seconds pauses every PC with the lost-connection window; a broken connection, a player's or the host's, connects again by itself, and every message sent meanwhile arrives once, in order (section 11). | SOS-175 to SOS-180 |
| 2026-11-10 | **Fog through a colleague's land:** a player with transit lifts the fog through another player's land and past it; peace alone lifts it over their land only (section 6). | SOS-182 |
| 2026-11-12 | **Living world:** a lobby option; the world is built around the host, and every other player settles a city-state carved into its real map, with the split rule for the land it is cut from (spec 0.59; section 5). | SOS-187 |
| 2026-11-12 | **A gone player's city-state comes off the map:** a removed or eliminated player's carved city-state is un-carved while every PC holds a moment (spec 0.59; sections 9, 11, 15). | SOS-188 |
| 2026-11-12 | **Joining a running campaign:** a new player joins after the host's Accept, picks their spot on a copy of the world while everyone plays on, and every game reloads with their city-state; they start with the campaign's starting resources (spec 0.60; sections 2, 11, 12, 15). | SOS-189, SOS-190 |
| 2026-11-12 | **Freezing a player:** Leave and freeze in the escape menu and Freeze them at a disconnect keep a player's capital, frozen and protected, until they come back into the running game (spec 0.61; sections 11, 12, 15). | SOS-192 to SOS-196 |
| 2026-11-12 | **Playtest of 2026-11-12:** each player's fog is their own; player treaties need reach (spec 0.62; section 6). | SOS-197 |
| 2026-11-12 | **The hot-join pick** goes through the setup's race, Customize Faction and location screens (spec 0.62; section 11). | SOS-198 |
| 2026-11-12 | **City-state spots** need only 6 tiles from every town and player; roads and water near them don't matter (spec 0.62; section 5). | SOS-199 |
| 2026-11-12 | **A colleague player's land carries reach on**, as an NPC's does (spec 0.63; section 6). | SOS-200 |
| 2026-11-12 | **A joining player's quit** asks: Leave and freeze, or End for everyone (spec 0.63; section 11). | SOS-203 |
| 2026-11-12 | **The chat and the multiplayer panel** close with an X; their buttons also show on the setup's screens and the landing (spec 0.63; section 12). | SOS-201, SOS-202 |
| 2026-11-12 | **A frozen player comes back** also after others joined since they froze (spec 0.64; section 11). | SOS-204 |
| 2026-11-15 | **Playtest of 2026-11-14:** another player's speed press asks the host in the chat (section 4); trade and reach carry on along a reached player's guaranteed routes (sections 6, 7); the depots' Best and Closest say they reach NPC traders only, and their messages count player traders (section 7) (spec 0.65). | SOS-205 to SOS-207, SOS-210 |
| 2026-11-15 | **Living world settling:** every player, the host too, settles on the built world, which has a stand-in capital for the host; the pick is on the world generator's screen with vanilla's hover window, the guaranteed connection and the road links to NPC states; picked spots are thick squares; hot-join has the same window and notices (spec 0.66; sections 5, 11). | SOS-212 to SOS-214 |
| 2026-11-16 | **Linux and macOS:** the mod runs on Windows, Linux (the Steam Deck too) and macOS; on macOS, Steam P2P goes through Valve's relays only (spec 0.67; section 3). | SOS-157, PR #118 |
| 2026-11-16 | **A removed player joins again** as a new player, with a new city-state, while the game runs; an eliminated player still comes back only to watch, at a resume (spec 0.70; sections 11, 15). | Batch 12 |
| 2026-11-16 | **A resume can freeze its missing players:** starting without them asks the host Freeze them or Remove them; a save of an older version offers Remove them only (spec 0.71; sections 11, 15). | Batch 12 |
| 2026-11-18 | **A war on a treaty breaker:** a war on an NPC faction that cancelled its treaties with the player costs no betrayal; breaking a treaty yourself, then declaring war, still does (section 6). | SOS-227 |
| 2026-11-18 | **Gear after a battle:** survivors keep their gear, the dead lose it, and a loser gets nothing back, no caravan of spoils (section 8). | SOS-225 |
| 2026-11-18 | **Allies in the Attack:** allied players with an army within 4 tiles of a capital are asked to join its Attack against an invasion; the spoils and captives are split by power, and the raider's stash stays the defender's (sections 9, 15). | SOS-232 |
| 2026-11-18 | **Allied armies at a capital:** allies, colleagues, overlords and vassals can station an army at another player's capital, within its free army slots; the host can send it away or supply it; it ends with the agreement, a move or a freeze; its divisions defend the city in an invasion or an assault (sections 9, 11, 15). | SOS-233, SOS-234 |
| 2026-11-19 | **The owner's session on save 94:** a stationed army fights in the host's Attack once, as itself, not also as the host's garrison, and each player commands only their own divisions there; the deploy button reads Ready in a battle with several commanders, which starts once every commander is ready; a restart goes back to 1x; an ally who leaves an NPC battle's view leaves their divisions to the fighting player; the multiplayer panel's Restart battle asks first; the chat and multiplayer buttons show in the battle view; a stationed army's soldiers in the host's city match its men at once after a battle (sections 8, 9, 12). | batch 13 |
| 2026-11-19 | **Owner's additions:** cursors as filled arrows in the banner foreground with bold names; the battle view's Chat and Multiplayer buttons beside the Tome of Knowledge; no throne in a battle between players; a player's deal as vanilla's deal message, kept in the message folder (sections 5, 6, 8, 12). | batch 13 |
| 2026-11-19 | **Owner's save 94 session:** a viewer's profile window can't change the viewed player's name or banner (it desynced the viewer's world); a view allowed while an autosave waits opens at once; another player's divisions show their player's banner on their cards (sections 5, 10, 15). | batch 13 |
| 2026-11-19 | **The camp (stationing, third version):** stationed soldiers come and go by an entry point; Leave for the camp in the division popup near one; the army moves only once every division is in the camp, its divisions in the city are darkened in its window and stay through Recall; Station again brings them back; a stationed army can't be attacked directly but still helps (sections 9, 15). | batch 13 |
| 2026-11-19 | **Stationed soldiers in the host's city:** a stationed army's soldiers stand in the host's city all the time, matched to the world army every 120 game seconds, outside its population, with no demuster; their player commands them, and gives the host command with a button; an invasion asks them Watch or Decline. The map's move tool says Station over a partner's capital. Battle Restart asks first; Sell is blank for goods this player doesn't export; every PC draws every player's capital alike (sections 7, 8, 9). | batch 13 |
| 2026-11-22 | **A frozen city doesn't trade:** it neither buys nor sells while its player is frozen, and nobody trades with it; its treaties and trade routes stay (spec 0.72; section 11). | SOS-240 |
| 2026-11-22 | **The Steam pass:** Steam starts once per game process, so End for everyone no longer crashes the host; invites and Join Game work on every multiplayer screen; Steam tests run on one PC under App ID 480; the host's log names refused joins; Remove them at a resume's start works with armies; a leaving PC closes its TCP sockets cleanly. Hot-join through Steam passed end to end. | SOS-237 to SOS-239, SOS-241 to SOS-243 |
| 2026-11-24 | **The recorder in the reports:** each joining PC streams the host the recorder's entries only it has (network, holds, frames, its city's events), ahead of its logs and saves, and sends a copy of its recorder file after a desync, a long freeze or an error; what every PC records alike stays out (spec 0.74; section 11). | SOS-248 |

### Where each v1 feature stands (2026-11-23)

| Feature (section 2) | State | Still to do |
| --- | --- | --- |
| Groundwork: the lockstep engine, lobby, setup, network, Steam, saves, disconnects, desyncs | Built | Done since 2026-11-01: the same game files in the lobby (SOS-159), reconnect after a lost connection (SOS-175 to SOS-180), Linux and macOS (SOS-157, PR #118). Left: a full session between a Mac and another PC over Steam; the 2-hour world check, the tests at 300 ms ping, the order delay above 1× (section 16). Two PCs held 25× at 400 steps a second in the 2026-10-31 runs; 250×, and 25× in a late-game city, run only as fast as the slowest PC. Open: the engine's speed floor is 0.25× (`Pacer.MIN_SPEED`), against section 4's 0.5× |
| A shared NPC world | Built | Covered by the groundwork's long tests |
| Diplomacy (section 6) | Built | Done: treaties, money and goods, regions, vassalage, passage, war (SOS-72 to SOS-74, SOS-83); notifications of a war in the chat (SOS-110). Left: a check that breaking a treaty with a player leaves NPC opinion alone; no test covers it yet |
| Trade (section 7) | Built | Done: no trade into or out of a besieged capital (SOS-92). Left: caravans on a disconnect, checked in a playtest |
| Field battles (section 8) | Built | Done: battles between players with each side's commanders, deployment, retreat, the result for the winner, the sally (SOS-83); watching an NPC battle (SOS-109); resync in a battle (SOS-92); the winner's choice on either army (SOS-116); the commanders' speed vote (SOS-117); allies in a battle between players (SOS-118) and in an NPC battle (SOS-119). Left: section 16's open question on a commander who drops out, and allies seen in a playtest |
| Sieges of player cities (section 9) | Built | Done: sieges by another player arrive, the sally from a region, the save before an assault (SOS-67, SOS-71, SOS-83); the siege of a capital with its wait, the popup and Sally out (SOS-92 step A); the lockstep assault with each side's commanders, Retreat, the outcomes, Sack, Vassalage and Eliminate with the removed people, the two-minute default, allied attackers and their share, NPC invasions (SOS-92). Left: confirmed by players in a playtest, and each attacker's own bonuses in an assault, which no card covers yet |
| Joining, freezing and the living world (sections 5, 11) | Built | Done: the living world and its settling (SOS-187, SOS-212 to SOS-214); a gone player's city-state un-carved (SOS-188); joining a running campaign (SOS-189, SOS-190, SOS-198); freezing a player and coming back (SOS-192 to SOS-196, SOS-204). Done: a join over Steam (2026-11-22, one PC under App ID 480). Left: SOS-191's battles3 scenarios for carved city-states and hot-join, with their report |
| City viewing (section 10) | Built | Done: the live view on request, catching up, the frozen own city, the other PCs' world reload (SOS-82); several viewers of one city or battle (SOS-151). Left: a viewer's PC that stopped at a view's load (SOS-85), the catch-up numbers confirmed in playtests (section 16) |
| Chat, notifications, pause, spectators (sections 4, 11, 12) | Built, apart from debug mode | Done: cursors on the world map and in cities (SOS-58); chat, notifications, who paused, any player unpausing (SOS-110); elimination and spectating (SOS-92, SOS-111). Left: debug mode isn't off in multiplayer (section 4) |
| Release: performance and distribution (sections 13, 14) | Not done | Done: the README, the license and the Steam Workshop texts. Left: the 10,000-person city at 1×, the 30-minute battle with 2,000 units per side, publishing on the Steam Workshop (SOS-18), the test plan (SOS-19) |

Every feature in section 2 is built as of 2026-11-01, and joining, freezing and the living world as of 2026-11-12. The table above lists what's left.

### Estimate for v1

The last estimate put v1 feature-complete between 2026-11-05 and 2026-11-09, and the features came in by 2026-11-01. No new date is set: what's left depends on playtests, the long tests and the open items above.
