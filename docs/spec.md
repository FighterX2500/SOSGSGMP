# SoS GSG MP Specification

Version 0.12 · 2026-10-24

This file is the source of truth for **what** the mod does. It does not say how to build it; that belongs in `docs/plan/` and `docs/decisions/`. When a rule here changes, update this file in the same PR.

## 1. Overview

The mod turns Songs of Syx into a multiplayer grand strategy game. Each player runs their own city-state and empire on one shared world map, which keeps changing as it does in the vanilla game. Players manage their own empires and interact with each other and with NPC factions through diplomacy, trade and war.

It is built for a small group of friends playing together, usually 2–3 people, in long sessions that are saved and resumed.

**Guiding principles**

- Vanilla first: wherever this spec says "same as vanilla", the player-to-player version reuses the vanilla rule unchanged.
- Vanilla look: every screen, window and popup the mod adds (lobby, chat, prompts, the viewing screen) uses the game's own UI style, so it looks like part of Songs of Syx.
- Friends trust each other: no anti-cheat, no penalties designed to stop griefing.
- Inside their own city, every player plays with zero latency. Delay is only acceptable where players interact.
- A sandbox with no win condition.

## 2. Scope

Version 1 includes six features:

- Diplomacy between players
- Trade between players
- Real-time field battles between players
- Real-time sieges of player cities
- Viewing other players' cities
- A shared NPC world

**Out of scope for v1**

- Anti-cheat
- A persistent world that runs without players
- GOG and itch.io versions
- Compatibility with other mods
- Updating the mod for new game versions
- Linux and macOS
- New players joining a campaign in progress
- AI running a disconnected player's city

## 3. Players and platform

| Item | Requirement |
| --- | --- |
| Players per campaign | 2–16 supported; 2–3 expected |
| Who plays | Friends who trust each other |
| Location | Worldwide, with no ping limit |
| Operating system | Windows only |
| Store | Steam only |
| Hosting | Listen server: the host plays and hosts |
| Networking | Two modes: direct IP (an open port, or a VPN such as Radmin VPN), or Steam P2P under Songs of Syx's own App ID (1162750) |
| Game version | The version in the working folder, pinned. The mod is not updated for later game versions. |

## 4. Session and time

All players are online at the same time. A campaign is saved and resumed later, and one campaign can run over several days.

- **Pausing.** Any player can pause and any player can unpause a normal pause. While paused, every player sees who paused the game. A priority pause can only be unpaused by the host; the game enters one when a player disconnects (section 11).
- **Game speed.** The host sets the speed. It is capped by the slowest PC: if one player's game falls behind, the speed drops for everyone, as in Paradox grand strategy games.
- **Lowest speed.** A slow PC can lower the speed to 0.5× and no further. If a PC can't hold even 0.5×, the game stops for everyone until it catches up, then resumes.
- **Menus, notifications and prompts don't pause.** Opening a menu or window, getting a notification popup, or a prompt that needs an answer (such as a world battle or a declaration of war) never pauses, holds or slows the game in multiplayer, unlike vanilla. The player's city and the world keep running. Only the pauses in this section stop time. Single-player keeps vanilla's behaviour.
- **Manual battles pause the world.** While a real-time battle or siege assault runs, the world map and every city are paused, and every player watches it. Otherwise a single battle could let years pass outside, because real-time battles are slow compared to city time.

## 5. Lobby and world setup

Every player starts with the same starting resources and difficulty, set by the host in the lobby. Each player picks their own race.

**Lobby**

1. In the main menu, a player opens Multiplayer and chooses Create Lobby, Load Saved Lobby or Join Lobby. A player joins by the host's IP address or through Steam (section 3).
2. With Create Lobby, the host chooses the difficulty, map size and number of players.
3. The lobby waits for players to join and shows a list of everyone in it. This list will be refined later.
4. The host clicks Start Lobby.
5. Each player picks their race and customizes their faction. No titles are given at this stage.
6. When every player is done, the host can generate the world for everyone.

**Starting locations**

1. Each player picks a location on the world map. There is no maximum distance, but a location closer than 6 tiles to another player can't be picked.
2. When everyone has picked, the host can initialize the world: NPC countries and routes are generated around the players. Any two players 6–15 tiles apart are guaranteed a land or water route between them. Players further apart are connected the vanilla way, through NPC states and other players.
3. Each player then picks their starting spot on their own city map. The game stays paused, and nobody can unpause it, but players can already plan their city.
4. When the last player picks their starting spot, the game unpauses automatically.

**World rules**

- NPC factions are generated as in vanilla. This may change in a later version.
- The game is a sandbox with no win condition.
- An eliminated player becomes a spectator (section 11).

## 6. Diplomacy

Players use the same agreements that exist between a player and an NPC faction in vanilla.

- **No opinion requirement.** Between players, any agreement can be concluded at any time. Opinion limits apply only to NPC factions.
- **Breaking treaties.** A player can break a treaty with another player at any time, with no penalty: unlike vanilla, NPC factions' opinion of that player doesn't drop either. Treaties with NPC factions keep their vanilla penalties.
- **Vassalage between players** follows vanilla: the vassal pays tribute, the two share wars, and the vassal has the right to rebel. The sovereign does not join a war its vassal starts.

## 7. Trade

Players trade through direct contracts and through a shared market.

- **Direct contracts** are agreed between two players: a player who wants a specific deal with another player proposes it as a diplomatic request (section 6).
- **Shared market.** Players' goods join the market, and prices form as in vanilla. The market is the same for everyone: no player gets special terms on it.
- **Caravans** work as in vanilla: they are visible on the map, travel to the other player, and cannot be raided.

## 8. Military

When two players' armies meet, a real-time battle is mandatory. Battles against NPC armies and attacks on NPC towns can be auto-resolved, as in vanilla.

- **Battle size** has no limit beyond vanilla's. Every PC simulates every field battle, and only orders, the host's AI orders and state checks cross the network ([ADR 0003](decisions/0003-battle-lockstep.md)). The battle AI runs on the host, and every PC applies its orders at the same moment. If any PC cannot keep up, the battle slows down for everyone, spectators included, as with the world speed (section 4).
- **Battle start.** Every PC generates the battlefield in the same step, from the same random numbers, so every PC starts from the same battle state ([ADR 0003](decisions/0003-battle-lockstep.md) amendment).
- **Desyncs.** If one PC's battle ever differs from the host's, the battle pauses briefly, every PC reloads the host's current battle state, and the battle continues.
- **Latency.** Up to 1 second between giving an order and seeing it carried out is acceptable.
- **Allies** can join a battle or siege when they are within range, as in vanilla.
- **Deployment.** Each player's army spawns as a separate army. Allied armies stand side by side on the same side of the field, 20 tiles apart, and the enemy's armies are placed the same way. Every player can rearrange their own army before the battle, as in vanilla.
- **Battle speed** is chosen by the commanders in the battle, by majority vote. On a tie, the speed stays as it is.
- **Retreat.** Only a side's commanders can order that side's retreat.
- **No restart.** Vanilla's battle restart is turned off in battles between players.
- **Spectating.** All other players watch the battle while the world is paused (section 4).
- **Offline players** cannot be attacked. Other armies can pass through their territory freely.

## 9. Sieges of player cities

Real-time sieges of player cities are part of v1. A siege runs in three phases:

1. **Siege.** The attacking army besieges the city, and the vanilla siege rules apply. The city is blockaded, and the attacking army needs supplies as in vanilla.
2. **Waiting period.** The attacker cannot assault until 1 in-game season has passed for every started 1,000 people in the city, up to a maximum of 4 seasons.
3. **Assault.** The city is simulated in real time while the attacker storms it. Every PC runs the assault in lockstep, like a field battle ([ADR 0006](decisions/0006-lockstep-city-viewing-and-assaults.md)): the defender's PC sends its city at the start, every PC loads it, and only orders, the host's AI orders and state checks cross the network. Desyncs are recovered from the host's assault state, as in section 8. The world is paused and every player watches.

During the siege, the defender can attack the besieging army. That creates a real-time field battle under the rules in section 8. Allies can lift the siege only by defeating the besieging army.

| City population | Waiting period before assault |
| --- | --- |
| 1–1,000 | 1 season |
| 1,001–2,000 | 2 seasons |
| 2,001–3,000 | 3 seasons |
| 3,001 or more | 4 seasons (maximum) |

**When the attacker wins**, they choose one outcome:

- **Sack:** the attacker chooses which resources and how much money to take, and can remove up to 80% of the population.
- **Eliminate:** the attacker takes 90% of the city's resources and money, and removes 90% of its population. The city then becomes an NPC city owned by a neutral faction, as in vanilla, and its player can only spectate from then on.
- **Vassalage:** the defender becomes the attacker's vassal (section 6).

**Removed people.** In a sack or an elimination, the attacker splits the removed people between three fates, as with vanilla's captives:

- **Moved:** they go to the attacker's city as prisoners, and the attacker decides there, as in vanilla, whether they become citizens or slaves.
- **Killed.**
- **Freed:** they are released, as with vanilla's Release, and go to neither city.

Each fate changes the attacker's mercy as in vanilla, and with it their nobles' Chivalry and Cruelty opinions: freeing raises it, moving lowers it, killing lowers it twice as much.

## 10. City viewing

Any player can view any other player's city, enemies included. This is intended.

- **Live view:** the viewer's PC loads the city and runs it in step with its owner's city, so it stays the same city, live ([ADR 0006](decisions/0006-lockstep-city-viewing-and-assaults.md)). Nothing in a viewed city can be changed.
- **The viewer's own city is frozen** while they view another city: it doesn't run, and they can't act in it. It resumes where it stopped when the view closes. The world runs on meanwhile: it sees the frozen city as its last city report showed it, and whatever the world sends that city (deliveries, immigrants, raids) waits and is applied when the view closes.
- **Watch only.** While viewing, the viewer can only watch and chat. Their armies and regions keep running on their last orders. A battle, or a prompt that needs the viewer's answer, ends the view.
- **Opening a view.** The owner saves their city and sends it; the viewer's PC loads it and runs it fast until it catches up with the owner's city. Target: a 5-second gap closes in about 2 seconds.
- **Too slow to catch up.** If the viewer's PC can't meet that target, the game speed drops for everyone until it can, as for any slow PC (section 4), but never below 1×.
- **Still too slow.** If the viewer hasn't caught up 1 minute after catching up started (the owner's save, the download and the load don't count), or their PC can't run the city even at 1× speed, the view ends with a message to the viewer, and the speed returns to what the host set.
- **Keeping up.** The same applies after catching up: a view that falls behind its owner's city lowers the speed for everyone, and if that doesn't bring it back, the view ends.
- **The owner is not slowed.** The owner keeps playing their city with zero latency while it is viewed.
- **During a siege assault:** every player watches the city in real time (section 9).

## 11. Saves, disconnects and elimination

**Saving.** When the host autosaves, every player autosaves their own city at the same moment. The game stays paused until every player has saved and the saves are in sync.

**Save before battles.** Before every real-time battle or siege assault starts, the campaign is saved the same way. If the connection is lost during the battle, the players can resume from that save.

**Resuming.** Any player can host when a saved campaign is resumed. If a player is missing, their city stays frozen until they join.

**Joining.** New players cannot join a campaign that is already in progress.

**Host disconnect.** When the host loses connection, every player gets a popup saying the host disconnected, and the whole simulation stops: the world, every city and any battle. Play can't continue in that session. The players resume the campaign from a save with any player as host (see Resuming); the save made before the last battle is the natural point.

**Disconnects.** When a player other than the host disconnects, the whole game enters a priority pause, which only the host can unpause. After that, the rules below apply until the player reconnects.

| Affected | What happens |
| --- | --- |
| Their city | Frozen |
| Their armies | Frozen and invulnerable; they don't block roads |
| Caravans to or from their city | Deleted, and their goods are lost |
| Caravans passing through their territory | Continue as normal |
| Their territory | Cannot be attacked; other armies can pass through freely |
| A battle or siege they are in | Forfeited by the side that disconnected, attacker or defender. That player's armies and empire freeze and become invulnerable. All armies on both sides stay intact, and the other side's armies return home. |

**Elimination.** An eliminated player becomes a spectator. Spectators can view every city and battle and can use chat, but cannot act in the game.

## 12. Chat and notifications

Players talk through an in-game chat.

- **Event notifications** appear in the chat or event log, for example when war is declared or a siege starts.
- **Prompts that need an answer**, such as a battle invite, appear as popups in the vanilla style.

## 13. Performance targets

The mod must meet these targets on the reference PC: an Intel Core i5-11600K with 16 GB of RAM.

| Target | Requirement |
| --- | --- |
| City simulation | A city of 10,000 people runs at 1× speed while no battle is running |
| Order latency in battles | Up to 1 second between an order and its effect |
| Latency inside your own city | None; your own city never waits on the network |
| Ping | No limit; players can be anywhere in the world |
| City size | No hard limit; a city larger than 10,000 may run slower than 1× |
| Battles too large for any PC in the game | The battle slows down for everyone |
| Battle network traffic | No limit in v1; measured in later tests |

### Sync and desync

These targets are tested with 2 players at 300 ms ping. The battle targets depend on the deterministic engine (section 14), which must keep every PC's battle identical; the mod carries its data between PCs.

| Target | Requirement |
| --- | --- |
| Battle stability | A battle with 2,000 units per side runs for 30 minutes with every PC's battle state identical to the host's |
| Battle desync detection | Any battle desync is detected within 2 seconds |
| Battle desync recovery | Any battle desync is recovered within 10 seconds, from the host's battle state |
| Battle desync reports | Every battle desync writes a report for debugging |
| Desync detection | Any world map desync is detected within 2 seconds |
| Desync recovery | Any world map desync is recovered within 10 seconds |
| Desync reports | Every world map desync writes a report for debugging |
| Battle order latency | At or under 1 second between an order and its effect |
| World map | The world map recovers automatically from any mismatch with the host |

## 14. Development workflow

The project lives in a private GitHub repository, and every change is built and tested before it is merged.

- **Work tracking:** a Kanban board in Jira, connected to the repository through the Jira API.
- **Checks before merge:** each change passes the local check (build, unit tests and a short multi-copy smoke run) before it is merged, and its result goes in the pull request. There is no CI server for now. The build needs the game's source and jar, which never leave the developer's PC. A self-hosted runner on the public SOSDET repository would also run outside code. Revisit this when outside contributors or a second developer join, or for game version updates (owner, 2026-10-24).
- **Deterministic engine:** a separate project in its own public repository, SOSDET, open source under the MIT license for its own code, with its own requirements and tests ([ADR 0004](decisions/0004-engine-split.md)). The game source lines in its patches stay the game developer's, published with their permission; nothing built from them is sold (owner, 2026-10-23). The mod uses it only through its API. Engine work is tracked on the same board.

## 15. Acceptance criteria

A v1 feature is accepted when every criterion below passes. **[AT]** marks an automated test, run by the local check or the scenario suite; **[PT]** marks a play-test with 2 players at 300 ms ping on PCs at or above the reference spec. Story-level criteria are written when each story is pulled onto the board.

### Lobby and setup

- A host can create a lobby with chosen difficulty, map size and player count, and a friend can join it by direct IP and through Steam. [PT]
- The lobby list shows every joined player within 2 seconds of them joining. [PT]
- A location that breaks the start location rules can't be confirmed, and the reason is shown. [AT]
- The game stays paused until the last player picks their city start spot, then unpauses automatically. [PT]
- Every player starts with the same resources and difficulty. [AT]

### World sync

- An army move ordered by one player appears on every other player's map within 1 second. [PT]
- After 2 hours of play, every client's world hash matches the host's at every check. [AT + PT]
- A deliberately corrupted client world is detected and resynced automatically, without player action. [AT]

### Pause and speed

- Any player can pause and unpause a normal pause, and every player sees who paused. [PT]
- Only the host can unpause a priority pause. [AT]
- When one PC falls behind, game speed drops for everyone, and no city ever runs ahead of world time. [AT]
- While a player has a menu, window, notification popup or prompt open, their city and the world keep running and the game speed doesn't drop. [PT]
- A slow PC never takes the speed below 0.5×; a PC that can't hold 0.5× stops the game for everyone until it catches up. [AT]

### Diplomacy

- Every vanilla NPC agreement can be offered between players, with no opinion requirement. [AT]
- A player can break a treaty with another player at any time, with no penalty, and NPC factions' opinion of them doesn't drop. [AT]
- A vassal pays tribute and shares wars, and the sovereign is not pulled into the vassal's offensive wars. [AT]

### Trade

- Goods sent in a direct contract leave the sender's warehouse and arrive at the receiver's after the travel time, with the caravan visible on the map. [PT]
- A resent or duplicated trade message never changes the amount delivered. [AT]
- Disconnecting mid-delivery never creates goods. Caravans to or from a disconnected city are deleted with their goods. [AT]

### Field battles

- When player armies meet, a real-time battle always starts. Battles against NPC armies offer auto-resolve. [PT]
- Allied armies spawn side by side, 20 tiles apart, and each player can rearrange only their own army. [PT]
- A majority vote of commanders changes battle speed; a tie keeps the current speed. [AT]
- A battle with 2,000 units per side runs 30 minutes with every PC's battle state identical to the host's. [AT + PT]
- The same battle save, seed and orders give an identical battle state on two runs, compared every step. [AT]
- When any PC in the game is slow, the battle slows for everyone. [AT]
- Only a side's commanders can order its retreat, and a battle between players can't be restarted. [AT]
- A forced battle desync is detected within 2 seconds, recovered from the host's battle state within 10 seconds, and writes a report. [AT]

### Sieges

- The attacker can't assault before the waiting period for the city's population band ends. [AT]
- The besieged city receives no trade. [AT]
- A defender's attack on the besiegers starts a real-time field battle. [PT]
- The assault shows the defender's city in real time to every player, and the attacker's orders work. [PT]
- A forced assault desync is detected within 2 seconds, recovered from the host's assault state within 10 seconds, and writes a report. [AT]
- Each outcome works. Sack takes the chosen resources and up to 80% of the population. Eliminate takes 90% of the resources, the money and the population, turns the city into a neutral NPC city and makes its player a spectator. Removed people are moved, killed or freed as chosen, and each fate changes the attacker's mercy as in vanilla. Vassalage applies the vassal rules. [AT + PT]

### City viewing

- Any player can open any other player's city and see it live at full detail within 1 minute of catching up starting (save, download and load not counted), and nothing in it can be changed. [PT]
- A viewed city's state matches its owner's city at every check while it is viewed. [AT]
- While a player views another city, their own city doesn't run, and it resumes exactly where it stopped when the view closes. [AT]
- While viewing, a player can't give orders; a battle or a prompt for them ends the view. [AT]
- When a viewer's PC can't catch up with or keep up with the viewed city, the game speed drops for everyone until it can. [AT]
- If the viewer still hasn't caught up 1 minute after catching up started, or can't run the city at 1× speed, the view ends with a message and the speed returns to the host's setting. [AT]
- Viewing a city causes no stutter longer than 1 second for its owner. [PT]

### Saves and resume

- A host autosave makes every player save at the same world tick, and the game resumes only when all saves are confirmed. [AT]
- Before a battle or siege assault starts, every player has a confirmed campaign save from that moment. [AT]
- A campaign saved under one host can be resumed with a different host. [PT]
- A player missing at resume has a frozen city until they join. [AT]

### Disconnects

- A disconnect triggers a priority pause within 2 seconds. [PT]
- When the host loses connection, every player sees a "host disconnected" popup and the whole simulation stops within 2 seconds. [PT]
- A disconnected player's city, empire and armies freeze and are invulnerable, without blocking roads. [AT]
- In a battle or siege, the side that disconnects forfeits, and all armies stay intact. [AT]
- A player who reconnects returns to their city and the current world state. [PT]

### Chat and notifications

- Chat messages reach every player, spectators included, within 2 seconds. [PT]
- War declarations and siege starts appear as notifications for every player. [PT]
- Battle invites appear as vanilla-style popups. [PT]

### Performance

- On the reference PC, a city of 10,000 people keeps 1× speed while no battle is running. [PT]

### Definition of Done

Every card on the board, not only features, is done when:

- the code is reviewed and merged, and the local check passed (result in the pull request)
- every [AT] criterion it covers has an automated test
- every [PT] criterion it covers has passed a play-test, noted on the card
- any rule that changed is updated in this spec

## 16. Open questions

- [ ] **Viewing numbers:** the catch-up numbers in section 10 (a 5-second gap closed in about 2 seconds, and how far behind a view may fall before the speed drops) are first guesses. Confirm them in playtests (owner, 2026-10-24): city save size and time, loading a city into a running game, and a 10,000-person city.
- [ ] **Pending world battles:** prompts no longer stop time (section 4), so the world runs on while a player decides on a world battle. What the armies involved do meanwhile (held in place, or the battle auto-resolves after a time limit) is still to decide.
- [ ] **Order delay above 1×:** the engine applies an order a fixed number of steps after it is given. Just above 1× speed that comes to about 1 second, plus ping, which is over the 1-second limit (section 8). Measure it in playtests and decide whether the engine's delay should shrink there.
