# SoS GSG MP Specification

Version 0.7 · 2026-10-20

This file is the source of truth for **what** the mod does. It does not say how to build it; that belongs in `docs/plan/` and `docs/decisions/`. When a rule here changes, update this file in the same PR.

## 1. Overview

The mod turns Songs of Syx into a multiplayer grand strategy game. Each player runs their own city-state and empire on one shared world map, which keeps changing as it does in the vanilla game. Players manage their own empires and interact with each other and with NPC factions through diplomacy, trade and war.

It is built for a small group of friends playing together, usually 2–3 people, in long sessions that are saved and resumed.

**Guiding principles**

- Vanilla first: wherever this spec says "same as vanilla", the player-to-player version reuses the vanilla rule unchanged.
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
| Networking | Steam P2P, using App ID 480 (Spacewar) during development |
| Game version | The version in the working folder, pinned. The mod is not updated for later game versions. |

## 4. Session and time

All players are online at the same time. A campaign is saved and resumed later, and one campaign can run over several days.

- **Pausing.** Any player can pause and any player can unpause a normal pause. While paused, every player sees who paused the game. A priority pause can only be unpaused by the host; the game enters one when a player disconnects (section 11).
- **Game speed.** The host sets the speed. It is capped by the slowest PC: if one player's game falls behind, the speed drops for everyone, as in Paradox grand strategy games.
- **Menus and notifications don't pause.** Opening a menu or window, or getting a notification popup, never pauses or slows the game in multiplayer, unlike vanilla. The player's city and the world keep running. Only the pause in this section stops time.
- **Manual battles pause the world.** While a real-time battle or siege assault runs, the world map and every city are paused, and every player watches it. Otherwise a single battle could let years pass outside, because real-time battles are slow compared to city time.

## 5. Lobby and world setup

Every player starts with the same starting resources and difficulty, set by the host in the lobby. Each player picks their own race.

**Lobby**

1. In the main menu, a player opens Multiplayer and chooses Create Lobby, Load Saved Lobby or Join Lobby.
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
- **Breaking treaties.** A player can break a treaty with another player at any time, with no penalty. Treaties with NPC factions keep their vanilla penalties.
- **Vassalage between players** follows vanilla: the vassal pays tribute, the two share wars, and the vassal has the right to rebel. The sovereign does not join a war its vassal starts.

## 7. Trade

Players trade through direct contracts and through a shared market.

- **Direct contracts** are agreed between two players.
- **Shared market.** Players' goods join the market, and prices form as in vanilla.
- **Prioritizing a player.** A player can mark other players, or one specific player, as preferred trade partners. Trades with a preferred player bypass the minimum and maximum buy and sell limits that apply to NPC factions. This rule is a first outline and needs more design.
- **Caravans** work as in vanilla: they are visible on the map, travel to the other player, and cannot be raided.

## 8. Military

When two players' armies meet, a real-time battle is mandatory. Battles against NPC armies and attacks on NPC towns can be auto-resolved, as in vanilla.

- **Battle size** has no limit beyond vanilla's. The host's PC runs every field battle and the other PCs only display it ([ADR 0002](decisions/0002-battle-streaming.md)). If the host's PC cannot keep up, the battle slows down for everyone; a spectator's PC never slows it down.
- **Latency.** Up to 1 second between giving an order and seeing it carried out is acceptable.
- **Allies** can join a battle or siege when they are within range, as in vanilla.
- **Deployment.** Each player's army spawns as a separate army. Allied armies stand side by side on the same side of the field, 20 tiles apart, and the enemy's armies are placed the same way. Every player can rearrange their own army before the battle, as in vanilla.
- **Battle speed** is chosen by the commanders in the battle, by majority vote. On a tie, the speed stays as it is.
- **Spectating.** All other players watch the battle while the world is paused (section 4).
- **Offline players** cannot be attacked. Other armies can pass through their territory freely.

## 9. Sieges of player cities

Real-time sieges of player cities are part of v1. A siege runs in three phases:

1. **Siege.** The attacking army besieges the city, and the vanilla siege rules apply. The city is blockaded, and the attacking army needs supplies as in vanilla.
2. **Waiting period.** The attacker cannot assault until 1 in-game season has passed for every started 1,000 people in the city, up to a maximum of 4 seasons.
3. **Assault.** The city is simulated in real time while the attacker storms it. The defender's PC runs the assault, because the city exists only there; it acts as the server for the assault, as the host does for field battles ([ADR 0002](decisions/0002-battle-streaming.md)). The attacker sends orders, and every other PC only displays the assault. The world is paused and every player watches.

During the siege, the defender can attack the besieging army. That creates a real-time field battle under the rules in section 8. Allies can lift the siege only by defeating the besieging army.

| City population | Waiting period before assault |
| --- | --- |
| 1–1,000 | 1 season |
| 1,001–2,000 | 2 seasons |
| 2,001–3,000 | 3 seasons |
| 3,001 or more | 4 seasons (maximum) |

**When the attacker wins**, they choose one outcome:

- **Sack:** the attacker chooses which resources and how much money to take, and can remove up to 80% of the population. The attacker decides whether the removed people are killed or moved to the attacker's cities.
- **Eliminate:** the city is destroyed and becomes an NPC city owned by a neutral faction, as in vanilla. Its player can only spectate from then on.
- **Vassalage:** the defender becomes the attacker's vassal (section 6).

## 10. City viewing

Any player can view any other player's city, enemies included. This is intended.

- **Normal view:** a snapshot of the city, which the viewer can refresh to get a newer one. Nothing in a viewed city can be changed.
- **During a siege assault:** the city is shown as a real-time simulation, and every player watches it.
- **Later:** a ghost sim (a snapshot that keeps running so the city looks alive) may be added on top of snapshots.

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
| Battles too large for the host's PC | The battle slows down for everyone |
| Battle network traffic | No limit in v1; measured in later tests |

### Sync and desync

These targets are tested with 2 players at 300 ms ping.

| Target | Requirement |
| --- | --- |
| Battle stability | A battle with 2,000 units per side runs for 30 minutes, and every player's view stays at most 1 second behind the host's |
| Desync detection | Any world map desync is detected within 2 seconds |
| Desync recovery | Any world map desync is recovered within 10 seconds |
| Desync reports | Every world map desync writes a report for debugging |
| Battle order latency | At or under 1 second between an order and its effect |
| World map | The world map recovers automatically from any mismatch with the host |

## 14. Development workflow

The project lives in a private GitHub repository, and every change goes through CI/CD.

- **Work tracking:** a Kanban board in Jira, connected to the repository through the Jira API.
- **CI/CD:** each change is built and tested automatically before it is merged.

## 15. Acceptance criteria

A v1 feature is accepted when every criterion below passes. **[CI]** marks an automated test; **[PT]** marks a play-test with 2 players at 300 ms ping on PCs at or above the reference spec. Story-level criteria are written when each story is pulled onto the board.

### Lobby and setup

- A host can create a lobby with chosen difficulty, map size and player count, and a friend can join it through Steam. [PT]
- The lobby list shows every joined player within 2 seconds of them joining. [PT]
- A location that breaks the start location rules can't be confirmed, and the reason is shown. [CI]
- The game stays paused until the last player picks their city start spot, then unpauses automatically. [PT]
- Every player starts with the same resources and difficulty. [CI]

### World sync

- An army move ordered by one player appears on every other player's map within 1 second. [PT]
- After 2 hours of play, every client's world hash matches the host's at every check. [CI + PT]
- A deliberately corrupted client world is detected and resynced automatically, without player action. [CI]

### Pause and speed

- Any player can pause and unpause a normal pause, and every player sees who paused. [PT]
- Only the host can unpause a priority pause. [CI]
- When one PC falls behind, game speed drops for everyone, and no city ever runs ahead of world time. [CI]
- While a player has a menu, window or notification popup open, their city and the world keep running and the game speed doesn't drop. [PT]

### Diplomacy

- Every vanilla NPC agreement can be offered between players, with no opinion requirement. [CI]
- A player can break a treaty with another player at any time, with no penalty. [CI]
- A vassal pays tribute and shares wars, and the sovereign is not pulled into the vassal's offensive wars. [CI]

### Trade

- Goods sent in a direct contract leave the sender's warehouse and arrive at the receiver's after the travel time, with the caravan visible on the map. [PT]
- A resent or duplicated trade message never changes the amount delivered. [CI]
- Disconnecting mid-delivery never creates goods. Caravans to or from a disconnected city are deleted with their goods. [CI]
- A prioritized player's trades bypass NPC minimum and maximum limits. [CI]

### Field battles

- When player armies meet, a real-time battle always starts. Battles against NPC armies offer auto-resolve. [PT]
- Allied armies spawn side by side, 20 tiles apart, and each player can rearrange only their own army. [PT]
- A majority vote of commanders changes battle speed; a tie keeps the current speed. [CI]
- A battle with 2,000 units per side runs 30 minutes, and every player's view stays at most 1 second behind the host's. [CI + PT]
- When the host's PC is slow, the battle slows for everyone; a slow spectator's PC never slows it. [CI]

### Sieges

- The attacker can't assault before the waiting period for the city's population band ends. [CI]
- The besieged city receives no trade. [CI]
- A defender's attack on the besiegers starts a real-time field battle. [PT]
- The assault shows the defender's city in real time to every player, and the attacker's orders work. [PT]
- Each outcome works. Sack takes the chosen resources and up to 80% of the population, killed or moved as chosen. Eliminate turns the city into a neutral NPC city and makes its player a spectator. Vassalage applies the vassal rules. [CI + PT]

### City viewing

- Any player can open any other player's city at full detail within 30 seconds, and nothing in it can be changed. [PT]
- Refreshing a view shows a newer snapshot. [PT]
- Viewing a city causes no stutter longer than 1 second for its owner. [PT]

### Saves and resume

- A host autosave makes every player save at the same world tick, and the game resumes only when all saves are confirmed. [CI]
- Before a battle or siege assault starts, every player has a confirmed campaign save from that moment. [CI]
- A campaign saved under one host can be resumed with a different host. [PT]
- A player missing at resume has a frozen city until they join. [CI]

### Disconnects

- A disconnect triggers a priority pause within 2 seconds. [PT]
- When the host loses connection, every player sees a "host disconnected" popup and the whole simulation stops within 2 seconds. [PT]
- A disconnected player's city, empire and armies freeze and are invulnerable, without blocking roads. [CI]
- In a battle or siege, the side that disconnects forfeits, and all armies stay intact. [CI]
- A player who reconnects returns to their city and the current world state. [PT]

### Chat and notifications

- Chat messages reach every player, spectators included, within 2 seconds. [PT]
- War declarations and siege starts appear as notifications for every player. [PT]
- Battle invites appear as vanilla-style popups. [PT]

### Performance

- On the reference PC, a city of 10,000 people keeps 1× speed while no battle is running. [PT]

### Definition of Done

Every card on the board, not only features, is done when:

- the code is reviewed and merged, and CI is green
- every [CI] criterion it covers has an automated test
- every [PT] criterion it covers has passed a play-test, noted on the card
- any rule that changed is updated in this spec

## 16. Open questions

- [ ] **Prioritized market trading:** a fuller design of how bypassing NPC limits works.
- [ ] **Battle stream catch-up:** how fast a player whose battle stream drops catches up again. To be set after the battle streaming spike.
- [ ] **Release App ID:** Spacewar (480) is for development only. Which App ID does the released mod use?
