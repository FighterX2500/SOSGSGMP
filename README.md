# SoS GSG MP

Multiplayer for Songs of Syx V71 (build 71.44). Each player runs their own city-state on one shared world map, which keeps changing as it does in the vanilla game. Players meet each other and the NPC factions through diplomacy, trade and war, with real-time battles between players. It is built for a small group of friends, usually 2 or 3, in long sessions that are saved and resumed.

The simulation runs in lockstep on every PC through the deterministic engine [SOSDET](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine), a separate mod that this one needs. The mod's name in the game's launcher is SOS GSG MP.

**Status:** V1 Beta. All of v1's main features are built and play over Steam or direct IP; three players have played it together. Long tests and the performance targets are still open. What works and what's next: [Status](#status) below, and spec §17.

## What it does

- **Lobby and setup:** a Multiplayer button in the main menu. The host creates a lobby over Steam or by IP, picks the map with vanilla's options, and every player picks a race, a faction and a capital location. NPC realms are generated around the players; each player then places their landing party. The lobby refuses a PC whose game files or mods differ from the host's, and says what differs.
- **Living world:** instead of the classic setup, the host can build a vanilla world first; every player then settles a small city-state carved into its real map.
- **One world, own cities:** every PC runs only its own city, with no delay, and the world map runs in lockstep on every PC. The world never reads a live city: it reads city reports and changes a city only through transactions applied exactly once.
- **Diplomacy between players:** peace, trade, colleagues and alliance as proposals; money, goods and regions in a deal; vassals with a tribute rate, and protectors; free passage at peace. You open another player's window wherever you'd click an NPC.
- **Trade between players:** each sells to the other in the market, as with an NPC, across any land at peace, with each player's own tariff on what the other sells them.
- **War and real-time battles:** a declaration of war applies at once. When two players' armies meet, each player commands their own army on the battle map, with their own technology and bonuses, while everyone else watches. A siege of a player's region (not the capital) asks the defender to sally out or fall back. A siege of a player's capital shows the defender a popup and the days to the assault, and they can sally out at any time. When the wait ends the attacker can storm: a real-time assault every player watches, ending in Sack, Eliminate or Vassalage.
- **Viewing a city:** ask to watch another player's city live; they allow or refuse. Your own city freezes until you close the view, then loads back where it stopped.
- **Saves:** Save Campaign in the escape menu, the host's autosave, a save before every battle, and Quit or Exit saving first. The host resumes a campaign with Load Saved Lobby.
- **Robustness:** a PC whose connection drops for a while, the host's too, connects again by itself, with nothing lost. A player whose game crashes or quits holds every PC at the same step, and the host goes on without them, freezes them, or returns to the last save. If the worlds ever split, the host chooses Resync (every PC takes the host's world and keeps its own city) or Save and exit.
- **Joining and leaving:** a new player can join a running campaign after the host accepts, and a player can leave with Leave and freeze and come back later; their capital waits for them, protected.
- **Pace:** the host sets the speed, and the slowest PC caps it for everyone. Menus, popups and prompts never pause the game.
- **Chat and spectators:** one chat for every player, with wars, sieges and battles announced in it. An eliminated player stays in the session and watches.
- **Routes:** in a new world, players who start 6 to 15 tiles apart get a direct road or sea crossing.
- **Global events:** eclipses, comets, storms and similar events reach every city at the same step, and world news applies once.
- **Session log:** every PC logs its session in `sosgsgmp/logs` in the game's data folder (see [Playing](#playing)), including every transfer of goods and money, for bug reports. Each joining PC also sends the host its logs, crash files and campaign saves; a player can turn that off.

The rules are in [docs/spec.md](docs/spec.md).

## Status

**Built** (merged, playtested with up to 3 players over Steam):
- the lockstep engine (SOSDET), the lobby with its game-files check, the setup and the living world, Steam and direct IP, campaign saves and resume, disconnects, reconnecting after a lost connection, and desync recovery;
- joining a running campaign, and leaving with Leave and freeze and coming back;
- diplomacy between players: treaties, money, goods, regions, vassalage, passage;
- trade between players, with tariffs, and direct routes in new worlds;
- war between players, and real-time field battles with each side's commanders, deployment, retreat, spoils, the sally, allies, a speed vote and resync inside a battle;
- battles against NPCs on their own clock, which other players can watch;
- sieges of a player's capital: the wait, Sally out, the real-time assault, its outcomes and allied attackers;
- viewing another player's city live;
- chat, notifications, who paused, spectators, global events;
- names and banners on every PC; other players' cursors on the maps and minimaps; a session log on every PC, sent to the host;
- Windows, Linux (the Steam Deck too) and macOS.

**Still open:**
- **Spec gaps:** debug mode isn't turned off in multiplayer, the code's lowest speed is 0.25× where spec §4 says 0.5×, and each attacker's own bonuses in an assault aren't built.
- **Tests:** a 2-hour session, a 30-minute battle with 2,000 units per side, the 10,000-person city at 1x, 300 ms ping, a join over Steam, and a full session between a Mac and another PC over Steam.

**Out of scope for v1:** anti-cheat, a world that runs without players, AI running a disconnected player's city, support for other mods (allowed when every player has them, but untested), later game versions, GOG and itch.io (spec §2).

## Playing

You need Songs of Syx V71, **build 71.44**, through Steam on Windows, Linux (the Steam Deck too) or macOS, and both mods: SOSDET and SOS GSG MP. Every player must have the same build of both. The lobby refuses a different game, SOSDET or mod build and says which one differs. If SOSDET is off, the game says so at start-up.

1. Close the game.
2. Copy the `SOSDET` and `SOSGSGMP` folders into the `mods` folder in the game's data folder (create `mods` if it isn't there):
   - Windows: `%APPDATA%\songsofsyx\mods`
   - Linux: `~/.local/share/songsofsyx/mods`
   - macOS: `~/Library/Application Support/songsofsyx/mods`
3. Start the game. In the launcher's mod list, enable SOSDET and SOS GSG MP, with SOSDET above SOS GSG MP.
4. The main menu now has a Multiplayer button.

- **Over Steam:** the players are Steam friends. The host chooses Multiplayer, Create Lobby, then "create Steam lobby"; the others choose "join through Steam", or accept the host's Steam invite anywhere in the main menu.
- **By IP:** the host's TCP port 47510 must be reachable (a forwarded port, or a VPN such as Radmin VPN). The host chooses "create (IP)"; the others choose "join by IP" with the host's address.

**On macOS**, Steam connects a Mac only through Valve's relays, never directly. On Apple Silicon the game runs under Rosetta, where Steam's direct connections crash it. Expect a little more delay than between two Windows PCs. A full session between a Mac and another PC over Steam is still to be tested.

With the mods disabled in the launcher, the game is vanilla again; neither mod changes the game folder.

## Building

You need:
- a JDK 21 or newer (`JAVA_HOME` set). The commands below are for Windows, where development happens. On macOS or Linux, `./gradlew compileJava compileTestJava test` works too: first put `systemProp.javax.net.ssl.trustStoreType=PKCS12` in `~/.gradle/gradle.properties`, since the project's `Windows-ROOT` trust store exists only on Windows. `installMod` needs `APPDATA`, so there you copy the mod folder into the `mods` folder (see [Playing](#playing)) by hand;
- Songs of Syx V71, build 71.44 (the build refuses any other);
- SOSDET built first: by default the build reads `..\SOSDET\build\mod\SOSDET\V71\script\SOSDET.jar` (set `SOSDET_DIR` to change it). Build it with `gradlew.bat assemble` there ([SOSDET's README](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine#building)).

Set `SOS_GAME_DIR` to the game folder, the one that holds `SongsOfSyx.jar`, then build:

```
set SOS_GAME_DIR=C:\Program Files (x86)\Steam\steamapps\common\Songs of Syx
gradlew.bat build
```

The result is the mod folder `build\mod\SOSGSGMP`.

| Command | What it does |
| --- | --- |
| `gradlew.bat compileJava compileTestJava` | Fast check |
| `gradlew.bat test` | Tests |
| `gradlew.bat build` | Tests, the jar check and the mod folder |
| `gradlew.bat installMod` | Copies the mod to `%APPDATA%\songsofsyx\mods\SOSGSGMP`; enable it in the launcher after SOSDET |
| `gradlew.bat uninstallMod` | Removes it again |

Two-PC tests: `python tools\twopc.py` plays a whole game by itself on this PC (the host) and on a second PC (the joiner). `python tools\collect.py --remote user@address` zips both PCs' logs and crash files.

No game files or game source are ever committed. The game jar is a compile-only dependency, and so is SOSDET's.

## Layout

| Path | Contents |
| --- | --- |
| `mod/src/main/java/` | The mod's code: the lobby, the network, the setup, the session and the game adapters |
| `mod/src/test/java/` | Tests (JUnit 5) |
| `mod/patches/` | The mod's small hooks into the game's own screens ([docs/hooks.md](docs/hooks.md)) |
| `docs/` | The spec, the architecture, the decisions (ADRs), the plans and the hook list |
| `tools/` | The two-PC test driver (`twopc.py`), the three-game test runners (`battles3.py`, `raid3.py`), the network-conditions runner (`netbase.py`) and the log collector |
| `spikes/` | Throwaway experiments; never part of the build |

## Documentation

- **What the mod must do:** [docs/spec.md](docs/spec.md), with its acceptance criteria (§15) and milestones (§17).
- **How it is built:** [docs/architecture.md](docs/architecture.md), and the decisions in [docs/decisions/](docs/decisions/); for example why the engine is a separate project ([ADR 0004](docs/decisions/0004-engine-split.md)) and how battles, city viewing and assaults run in lockstep ([ADR 0003](docs/decisions/0003-battle-lockstep.md), [ADR 0006](docs/decisions/0006-lockstep-city-viewing-and-assaults.md)).
- **Changes to the game's screens:** [docs/hooks.md](docs/hooks.md).
- **Plans per feature:** [docs/plan/](docs/plan/).
- **The coding rules, also for AI agents:** [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md).

## Credits and permission

By FighterX2500. Songs of Syx is by Gamatron AB. The mod stays a mod that the game's own launcher loads, with the developer's permission; it is free and must not be sold.

License: MIT for this repository's own code ([LICENSE](LICENSE)). The game source lines in `mod/patches/` stay the game developer's, published with their permission. SOSDET has its own license (also MIT).
