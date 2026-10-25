# SoS GSG MP Specification

Version 0.15 · 2026-10-25

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
- **Speeds.** The host can choose 1×, 5×, 25× or 250×. Vanilla's 0.25× isn't available in multiplayer, in battles either, since 0.5× is the lowest speed.
- **Lowest speed.** A slow PC can lower the speed to 0.5× and no further. If a PC can't hold even 0.5×, the game stops for everyone until it catches up, then resumes.
- **Menus, notifications and prompts don't pause.** Opening a menu or window, getting a notification popup, or a prompt that needs an answer (such as a world battle or a declaration of war) never pauses, holds or slows the game in multiplayer, unlike vanilla. The player's city and the world keep running; while a world-battle prompt is open, only the armies in that battle wait (section 8). Only the pauses in this section stop time. Single-player keeps vanilla's behaviour.
- **No debug mode or cheats** in multiplayer: the game's debug mode and cheat tools are turned off.
- **Manual battles pause the world.** While a real-time battle or siege assault runs, the world map and every city are paused, and every player watches it. Otherwise a single battle could let years pass outside, because real-time battles are slow compared to city time.

## 5. Lobby and world setup

Every player starts with the same starting resources and difficulty, set by the host in the lobby. Each player picks their own race.

**Lobby**

1. In the main menu, a player opens Multiplayer and chooses Create Lobby, Load Saved Lobby or Join Lobby. A player joins by the host's IP address or through Steam (section 3).
2. With Create Lobby, the host chooses the difficulty and number of players.
3. The lobby waits for players to join and shows a list of everyone in it. This list will be refined later.
4. The host clicks Start Lobby.

**Map**

1. The host chooses the map size and vanilla's map options (map type, latitude, seed) and generates the terrain. Vanilla's map is 256×256 tiles; the game supports 128 to 512.
2. Every player sees the generated map. The players decide together whether it's fine, by talking; there is no vote. The host can change the options and generate again as often as they like.
3. The host confirms the map. After that it can't be changed.

**Factions and starting locations**

1. Each player, at their own pace, picks their race, customizes their faction and picks a location on the world map. A player who is done doesn't wait for the others to start, and can change their picks until the host initializes the world. Customizing works as in vanilla, including saving and loading the faction profile. No titles are given at this stage.
2. Every player sees the locations the others have picked. There is no maximum distance, but a location closer than 6 tiles to another player's can't be picked.
3. When everyone has picked, the host can initialize the world: NPC countries and routes are generated around the players. Any two players 6–15 tiles apart are guaranteed a land or water route between them. Players further apart are connected the vanilla way, through NPC states and other players.
4. Each player then picks their starting spot on their own city map. The game stays paused, and nobody can unpause it, but players can already plan their city.
5. When the last player picks their starting spot, the game unpauses automatically.

**World rules**

- NPC factions are generated as in vanilla. This may change in a later version.
- Players use vanilla's 64 faction slots, so each player takes one slot from the NPC factions: 61 NPC factions with 3 players, 48 with 16. With many players on a big map there are fewer NPC kingdoms than in vanilla.
- The game is a sandbox with no win condition.
- An eliminated player becomes a spectator (section 11).

## 6. Diplomacy

Players use the same agreements that exist between a player and an NPC faction in vanilla.

- **No opinion requirement.** Between players, any agreement can be concluded at any time. Opinion limits apply only to NPC factions.
- **Proposals** reach the other player as a popup (section 12). One left unanswered for 30 in-game days expires and counts as declined.
- **No uniting.** Vanilla's "Unite" agreement, where a whole faction joins another, isn't available between players.
- **Regions** other than capitals can be given in an agreement, as in vanilla.
- **Emissaries** work as in vanilla against NPC factions. Against another player only Gather support works, in that player's regions; the missions aimed at a court (Flatter, Sabotage, Assassinate) aren't available.
- **Passage.** Players at peace can move armies through each other's territory freely, with no penalty.
- **Breaking treaties.** A player can break a treaty with another player at any time, with no penalty: unlike vanilla, NPC factions' opinion of that player doesn't drop either. Treaties with NPC factions keep their vanilla penalties.
- **Vassalage between players** follows vanilla: the vassal pays tribute, the two share wars, and the vassal has the right to rebel. The sovereign does not join a war its vassal starts.

## 7. Trade

Players trade through direct contracts and through a shared market.

- **Direct contracts** are agreed between two players: a player who wants a specific deal with another player proposes it as a diplomatic request (section 6).
- **Shared market.** Players' goods join the market, and prices form as in vanilla. The market is the same for everyone: no player gets special terms on it.
- **Trade agreements between players** (trade partners, colleagues, allies) give the same price discount on trade between the two as in vanilla.
- **Caravans** work as in vanilla: they are visible on the map, travel to the other player, and cannot be raided.

## 8. Military

When two players' armies meet, a real-time battle is mandatory. Battles against NPC armies and attacks on NPC towns can be auto-resolved, as in vanilla.

- **Battle size** has no limit beyond vanilla's. Every PC simulates every field battle, and only orders, the host's AI orders and state checks cross the network ([ADR 0003](decisions/0003-battle-lockstep.md)). The battle AI runs on the host, and every PC applies its orders at the same moment. If any PC cannot keep up, the battle slows down for everyone, spectators included, as with the world speed (section 4).
- **Battle start.** Every PC generates the battlefield in the same step, from the same random numbers, so every PC starts from the same battle state ([ADR 0003](decisions/0003-battle-lockstep.md) amendment).
- **Desyncs.** If one PC's battle ever differs from the host's, the battle pauses briefly, every PC reloads the host's current battle state, and the battle continues.
- **Latency.** Up to 1 second between giving an order and seeing it carried out is acceptable.
- **World battle prompts.** When a battle on the world map needs a player's answer, such as fighting it in real time or auto-resolving it, only the armies in that battle wait in place while the prompt is open; the rest of the world runs on. If nobody answers within 30 seconds of real time, every PC gives the default answer at the same moment: auto-resolve, wait for a siege, accept a result with no captives or spoils, occupy a captured settlement.
- **Battles against NPC armies** can be fought in real time, as in vanilla. Like any real-time battle, they pause the world and every player watches (section 4).
- **Allies** can join a battle or siege when they are within range, as in vanilla.
- **Captives** after a battle between players are handled as in vanilla (capture, execute or release, each changing mercy). Each winning player decides for their own share.
- **Deployment.** Each player's army spawns as a separate army. Allied armies stand side by side on the same side of the field, 20 tiles apart, and the enemy's armies are placed the same way. Every player can rearrange their own army before the battle, as in vanilla, and commands only their own divisions during it.
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

**A player's other regions** (not the capital) are attacked and taken by vanilla rules, including vanilla's choices after a capture.

**Invasions by NPC armies.** When an NPC army attacks a player's capital, the assault runs like a siege assault between players: every PC loads the city, the world pauses and every player watches. The outcome is vanilla's.

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

**Allied attackers.** When several players take a city together, the player with the most soldiers in the assault chooses the outcome. Taken resources, money and removed people are split between them by the number of soldiers each brought.

## 10. City viewing

Any player can view any other player's city, enemies included. This is intended.

- **Live view:** the viewer's PC loads the city and runs it in step with its owner's city, so it stays the same city, live ([ADR 0006](decisions/0006-lockstep-city-viewing-and-assaults.md)). Nothing in a viewed city can be changed.
- **The viewer's own city is frozen** while they view another city: it doesn't run, and they can't act in it. It resumes where it stopped when the view closes. The world runs on meanwhile: it sees the frozen city as its last city report showed it, and whatever the world sends that city (deliveries, immigrants, raids) waits and is applied when the view closes.
- **Watch only.** While viewing, the viewer can only watch and chat. Their armies and regions keep running on their last orders. A battle, or a prompt that needs the viewer's answer, ends the view.
- **Only the viewer and the owner.** A view is between those two players: the host and the other players take no part, and a view never changes the game speed for anyone.
- **Opening a view.** The owner saves their city and sends it straight to the viewer; the viewer's PC loads it and runs it fast until it catches up with the owner's city. Target: a 5-second gap closes in about 2 seconds.
- **Too slow.** If the viewer hasn't caught up 1 minute after catching up started (the owner's save, the download and the load don't count), or their PC can't keep up with the city at the current speed, the view ends with a message to the viewer. Only that view ends; nobody else is affected.
- **Keeping up.** The same applies after catching up: a view that falls behind its owner's city and doesn't catch up again within 1 minute ends.
- **The owner is not slowed.** The owner keeps playing their city with zero latency while it is viewed.
- **During a siege assault:** every player watches the city in real time (section 9).

## 11. Saves, disconnects and elimination

**Saving.** When the host autosaves, every player autosaves their own city at the same moment. The game stays paused until every player has saved and the saves are in sync.

**Save before battles.** Before every real-time battle or siege assault starts, the campaign is saved the same way. If the connection is lost during the battle, the players can resume from that save.

**Resuming.** Any player can host when a saved campaign is resumed. If a player is missing, their city stays frozen until they join.

**Joining.** New players cannot join a campaign that is already in progress.

**Removing a player.** The host can remove a player who is missing at resume or disconnected. Their city becomes an NPC city owned by a neutral faction, as with Eliminate, and they can't rejoin the campaign as a player.

**Host disconnect.** When the host loses connection, every player gets a popup saying the host disconnected, and the whole simulation stops: the world, every city and any battle. Play can't continue in that session. The players resume the campaign from a save with any player as host (see Resuming); the save made before the last battle is the natural point.

**World desyncs.** When a player's world map differs from the host's, the game stops at the same moment for every player, and every player sees a window saying the world desynced and which player differs. The host chooses what follows; the others wait:

- **Resync:** every player's game reloads the host's world, each player keeping their own city, and play continues.
- **Save and exit:** every player saves the campaign and the session ends; it can be resumed later (see Resuming).

The window appears on every world desync; there is no automatic resync.

**Disconnects.** When a player other than the host disconnects, the whole game enters a priority pause, which only the host can unpause. After that, the rules below apply until the player reconnects.

| Affected | What happens |
| --- | --- |
| Their city | Frozen |
| Their armies | Frozen and invulnerable; they don't block roads |
| Caravans to or from their city | Deleted, and their goods are lost |
| Caravans passing through their territory | Continue as normal |
| Their territory | Cannot be attacked; other armies can pass through freely |
| Events and raids aimed at them | Wait until they're back; a raider already on its way to them waits too |
| A battle or siege they are in | Forfeited by the side that disconnected, attacker or defender. That player's armies and empire freeze and become invulnerable. All armies on both sides stay intact, and the other side's armies return home. |

**Elimination.** An eliminated player becomes a spectator. Spectators can view every city and battle and can use chat, but cannot act in the game.

## 12. Chat and notifications

Players talk through an in-game chat.

- **Event notifications** appear in the chat or event log, for example when war is declared or a siege starts.
- **Prompts that need an answer**, such as a battle invite, appear as popups in the vanilla style.
- **Other players' cursors.** Every player sees the other players' mouse cursors, each marked with that player's name and color: on the world map, every other player's cursor that is on the world map too; in a city, the cursor of every other player looking at the same city (its owner and its viewers, section 10). A cursor is only shown, never an action.

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
| Desync recovery | After the host chooses Resync (section 11), every world map is identical to the host's again within 10 seconds |
| Desync reports | Every world map desync writes a report for debugging |
| Battle order latency | At or under 1 second between an order and its effect |
| World map | The world map recovers from any mismatch with the host when the host chooses Resync |

## 14. Development workflow

The project lives in a private GitHub repository, and every change is built and tested before it is merged.

- **Work tracking:** a Kanban board in Jira, connected to the repository through the Jira API.
- **Checks before merge:** each change passes the local check (build, unit tests and a short multi-copy smoke run) before it is merged, and its result goes in the pull request. There is no CI server for now. The build needs the game's source and jar, which never leave the developer's PC. A self-hosted runner on the public SOSDET repository would also run outside code. Revisit this when outside contributors or a second developer join, or for game version updates (owner, 2026-10-24).
- **Deterministic engine:** a separate project in its own public repository, SOSDET, open source under the MIT license for its own code, with its own requirements and tests ([ADR 0004](decisions/0004-engine-split.md)). The game source lines in its patches stay the game developer's, published with their permission; nothing built from them is sold (owner, 2026-10-23). The mod uses it only through its API. Engine work is tracked on the same board.

## 15. Acceptance criteria

A v1 feature is accepted when every criterion below passes. **[AT]** marks an automated test, run by the local check or the scenario suite; **[PT]** marks a play-test with 2 players at 300 ms ping on PCs at or above the reference spec. Story-level criteria are written when each story is pulled onto the board.

### Lobby and setup

- A host can create a lobby with chosen difficulty and player count, and a friend can join it by direct IP and through Steam. [PT]
- After the host generates the map, every player's terrain matches the host's, for every map size offered. [AT]
- A player can pick their race, faction and location while others are still picking, and can change them until the host initializes the world. [PT]
- The lobby list shows every joined player within 2 seconds of them joining. [PT]
- A location that breaks the start location rules can't be confirmed, and the reason is shown. [AT]
- The game stays paused until the last player picks their city start spot, then unpauses automatically. [PT]
- Every player starts with the same resources and difficulty. [AT]

### World sync

- An army move ordered by one player appears on every other player's map within 1 second. [PT]
- After 2 hours of play, every client's world hash matches the host's at every check. [AT + PT]
- A deliberately corrupted client world stops every player's game within 2 seconds, with the desync window naming that client; after the host chooses Resync, every world is identical to the host's again within 10 seconds. [AT]
- After the host chooses Save and exit at a world desync, every player's campaign save is written and the session ends. [AT]

### Pause and speed

- Any player can pause and unpause a normal pause, and every player sees who paused. [PT]
- Only the host can unpause a priority pause. [AT]
- The host can choose only 1×, 5×, 25× or 250×; 0.25× is never offered. [AT]
- Debug mode and cheat tools can't be opened in multiplayer. [AT]
- When one PC falls behind, game speed drops for everyone, and no city ever runs ahead of world time. [AT]
- While a player has a menu, window, notification popup or prompt open, their city and the world keep running and the game speed doesn't drop. [PT]
- A slow PC never takes the speed below 0.5×; a PC that can't hold 0.5× stops the game for everyone until it catches up. [AT]

### Diplomacy

- Every vanilla NPC agreement can be offered between players, with no opinion requirement. [AT]
- A player can break a treaty with another player at any time, with no penalty, and NPC factions' opinion of them doesn't drop. [AT]
- A vassal pays tribute and shares wars, and the sovereign is not pulled into the vassal's offensive wars. [AT]
- A proposal unanswered for 30 in-game days expires as declined. [AT]
- "Unite" can't be offered between players; regions other than capitals can be given. [AT]
- Against another player, only the Gather support emissary mission is offered. [AT]
- A player's army crosses another player's territory at peace with no penalty. [AT]

### Trade

- Goods sent in a direct contract leave the sender's warehouse and arrive at the receiver's after the travel time, with the caravan visible on the map. [PT]
- A resent or duplicated trade message never changes the amount delivered. [AT]
- A trade agreement between two players gives the vanilla discount on their trade. [AT]
- Disconnecting mid-delivery never creates goods. Caravans to or from a disconnected city are deleted with their goods. [AT]

### Field battles

- When player armies meet, a real-time battle always starts. Battles against NPC armies offer auto-resolve. [PT]
- Allied armies spawn side by side, 20 tiles apart, and each player can rearrange only their own army. [PT]
- A majority vote of commanders changes battle speed; a tie keeps the current speed. [AT]
- A battle with 2,000 units per side runs 30 minutes with every PC's battle state identical to the host's. [AT + PT]
- The same battle save, seed and orders give an identical battle state on two runs, compared every step. [AT]
- When any PC in the game is slow, the battle slows for everyone. [AT]
- Only a side's commanders can order its retreat, and a battle between players can't be restarted. [AT]
- While a world-battle prompt is open, only that battle's armies wait; with no answer within 30 seconds, every PC applies the default answer at the same step. [AT]
- A forced battle desync is detected within 2 seconds, recovered from the host's battle state within 10 seconds, and writes a report. [AT]

### Sieges

- The attacker can't assault before the waiting period for the city's population band ends. [AT]
- The besieged city receives no trade. [AT]
- A defender's attack on the besiegers starts a real-time field battle. [PT]
- The assault shows the defender's city in real time to every player, and the attacker's orders work. [PT]
- A forced assault desync is detected within 2 seconds, recovered from the host's assault state within 10 seconds, and writes a report. [AT]
- An NPC invasion of a player's capital pauses the world and every player watches it. [PT]
- With allied attackers, the player with the most soldiers chooses the outcome, and the spoils are split by soldiers brought. [AT]
- Each outcome works. Sack takes the chosen resources and up to 80% of the population. Eliminate takes 90% of the resources, the money and the population, turns the city into a neutral NPC city and makes its player a spectator. Removed people are moved, killed or freed as chosen, and each fate changes the attacker's mercy as in vanilla. Vassalage applies the vassal rules. [AT + PT]

### City viewing

- Any player can open any other player's city and see it live at full detail within 1 minute of catching up starting (save, download and load not counted), and nothing in it can be changed. [PT]
- A viewed city's state matches its owner's city at every check while it is viewed. [AT]
- While a player views another city, their own city doesn't run, and it resumes exactly where it stopped when the view closes. [AT]
- While viewing, a player can't give orders; a battle or a prompt for them ends the view. [AT]
- A view never changes the game speed, and the host and other players take no part in it. [AT]
- If the viewer hasn't caught up 1 minute after catching up started, falls behind for more than 1 minute later, or can't keep up at the current speed, only that view ends, with a message to the viewer. [AT]
- Viewing a city causes no stutter longer than 1 second for its owner. [PT]

### Saves and resume

- A host autosave makes every player save at the same world tick, and the game resumes only when all saves are confirmed. [AT]
- Before a battle or siege assault starts, every player has a confirmed campaign save from that moment. [AT]
- A campaign saved under one host can be resumed with a different host. [PT]
- A player missing at resume has a frozen city until they join. [AT]
- A removed player's city becomes a neutral NPC city, and they can't rejoin as a player. [AT]

### Disconnects

- A disconnect triggers a priority pause within 2 seconds. [PT]
- When the host loses connection, every player sees a "host disconnected" popup and the whole simulation stops within 2 seconds. [PT]
- A disconnected player's city, empire and armies freeze and are invulnerable, without blocking roads. [AT]
- Events and raids aimed at a disconnected or missing player wait until they're back, raiders already on their way included. [AT]
- In a battle or siege, the side that disconnects forfeits, and all armies stay intact. [AT]
- A player who reconnects returns to their city and the current world state. [PT]

### Chat and notifications

- Chat messages reach every player, spectators included, within 2 seconds. [PT]
- War declarations and siege starts appear as notifications for every player. [PT]
- Battle invites appear as vanilla-style popups. [PT]
- On the world map every player sees the other players' cursors, with their names, where they point. In a viewed city, the owner and the viewer see each other's cursors. [PT]

### Performance

- On the reference PC, a city of 10,000 people keeps 1× speed while no battle is running. [PT]

### Definition of Done

Every card on the board, not only features, is done when:

- the code is reviewed and merged, and the local check passed (result in the pull request)
- every [AT] criterion it covers has an automated test
- every [PT] criterion it covers has passed a play-test, noted on the card
- any rule that changed is updated in this spec

## 16. Open questions

- [ ] **Viewing numbers:** the catch-up numbers in section 10 (a 5-second gap closed in about 2 seconds, and the 1-minute limit before a view that can't keep up ends) are first guesses. Confirm them in playtests (owner, 2026-10-24): city save size and time, loading a city into a running game, and a 10,000-person city.
- [ ] **Order delay above 1×:** the engine applies an order a fixed number of steps after it is given. Just above 1× speed that comes to about 1 second, plus ping, which is over the 1-second limit (section 8). Measure it in playtests and decide whether the engine's delay should shrink there.
