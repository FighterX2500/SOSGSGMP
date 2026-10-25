# Walking skeleton

Card: [SOS-20](https://fighterx2500.atlassian.net/browse/SOS-20) · Date: 2026-10-25 · Status: in implementation (owner, 2026-10-25; CLAUDE.md "Current phase") · Spec: §3, §5

## 1. Goal

Two players start a campaign together and play it: each runs their own city on one shared world map. The first playtest also checks the Steam connection (owner, 2026-10-25). Direct IP is the main test route until then, PC to laptop over Radmin VPN, the way the engine's rig already runs (SOSDET relay).

SOS-20's original goal ("see the same world map, one army's movement synced") now comes for free: the engine runs the world in lockstep (ADR 0005), so the skeleton is the path from the main menu into that world.

## 2. Order

Each step is one card; each ends in something the owner can try.

| # | Step | Proves | Card | Needs |
| --- | --- | --- | --- | --- |
| 1 | **Lobby screens** (spec §5 lobby steps 1–4): Multiplayer, Create Lobby, Join by IP, the lobby list, Start Lobby | The mod's own menu works inside the game; hook M1; the mod's build and install | [SOS-49](https://fighterx2500.atlassian.net/browse/SOS-49) | Nothing |
| 2 | **Direct-IP join**: the host listens, joining PCs connect, the roster stays the same on every PC; protocol version on every message | Networking from inside the game process, PC to laptop over Radmin | SOS-49 (same card) | Nothing |
| 3 | **Steam lobby and invites**: create a Steam lobby, invite friends, join through the overlay; Steam P2P messages under App ID 1162750 | Steam P2P from the game's own Steamworks (the game already runs `SteamAPI`) | to propose | Placeholders from SOS-49; an ADR only if a library beyond the game's steamworks4j is needed |
| 4a | **Setup, map** (spec §5 Map): after Start Lobby the host generates and confirms the map (size, map type, latitude, seed); every PC checks it by terrain hash | Every PC builds the same world map | [SOS-54](https://fighterx2500.atlassian.net/browse/SOS-54) (SOSGSGMP PR #32, [setup-map-step.md](setup-map-step.md)) | Nothing: no engine session yet. The host sends its terrain: vanilla's river generator isn't seeded |
| 4b | **Setup, factions and locations** (spec §5 Factions and starting locations): each player picks race, faction and world location at their own pace (6-tile rule); the host initializes the world with NPC countries and routes; each player's city start spot; the automatic unpause | The lobby hands its players to the engine's human slots | [SOS-56](https://fighterx2500.atlassian.net/browse/SOS-56) (SOSGSGMP PR #33, [setup-factions-step.md](setup-factions-step.md)) | SOSDET SOS-55 (`Engine.buildWorld`, merged, SOSDET PR #35) and SOSDET PR #36 (`Engine.worldHash`, `ownCityAt`) |
| 5 | **Game**: the world in lockstep through the mod's transport, each city local, city reports and transactions | The engine's API carried over the mod's network | [SOS-57](https://fighterx2500.atlassian.net/browse/SOS-57) | SOSDET SOS-46 (merged); steps 2 or 3 |
| 6 | **Playtest** with the owner: two PCs over Steam, then direct IP | The whole path | none | 1–5 |

Lockstep battles come right after the skeleton: the engine has them (SOS-15), and the mod only carries their orders, AI log and hashes once step 5 works.

## 3. What SOS-49 built (steps 1 and 2)

- **Build:** Gradle in this repository (`gradlew.bat build`, `installMod`), against `SongsOfSyx.jar` from `SOS_GAME_DIR`; hooks as patches compiled with ECJ, the way SOSDET ships its own.
- **Hook M1** in `menu/ScMain` adds the Multiplayer button ([hooks.md](../hooks.md)).
- **Screens** (package `menu`, classes `Mp*`): Multiplayer (Create Lobby, Load Saved Lobby greyed out, Join by IP, Join through Steam greyed out); Create Lobby (name, port, players 2–16, difficulty from the vanilla modes); Join by IP (name, host address, port); the lobby (players with host and "you" marked, difficulty and player count, Start Lobby for the host at 2 or more players, Invite Steam friends greyed out, Leave).
- **Plain Java** (no game classes, unit-tested): `sosgsgmp.lobby` (lobby rules, settings, saved join settings), `sosgsgmp.net` (messages, their byte layout, the TCP transport with one I/O thread that only decodes and queues), `sosgsgmp.session` (a hosted or joined lobby, drained by the game thread once per frame).
- **Start Lobby** tells every PC the lobby has started and closes it to new players. The map step (4a) takes over from there; until it merges, nothing more happens.
- **Map size** is not on Create Lobby: spec 0.13 moved it to the map step after Start Lobby (owner, 2026-10-25). The game reads it from `TILE_DIMENSION` in `init/world/config/General.txt` (vanilla 256, allowed 128–512) when a game is built.

## 4. Wrap-up now that SOSDET SOS-46 is merged

The lobby was built before the engine's player slots. SOS-46 is merged (SOSDET PR #31, 2026-10-25). These join the two together (step 4's cards):

1. **Slots from the lobby.** At world generation the host turns the lobby's roster into slot indices, host first then join order (player-slots.md R2). The mod keeps the map from player identity to slot: the Steam ID, or over direct IP the player key each PC already sends in its hello (`LobbyPrefs.playerKey`). That map goes into the campaign save (R13).
2. **Local slot.** Each PC tells the engine its own slot (R3), from its lobby player id. SOS-46 built this: `Engine.localHuman(slot)` and `Engine.pcHuman(pc, slot)`, called at step 0 on every PC.
3. **Start Lobby → setup.** Start moves every PC from the lobby screen into a new game's world generator instead of showing "started". Vanilla's own stages are reused in the spec's order: the host's terrain stage first (every PC sees the map), then each player's race, Customize Faction (with vanilla's profile save and load) and location, with no titles stage. Race and faction exist only inside a built game (`GAME` creates `RACES` and `FACTIONS`), so none of this can run in the menu. The world generator's stage order is fixed in `WorldViewGenerator.set()`, so this needs a hook there. The map step (4a, draft SOSGSGMP PR #28) does the terrain part without a hook: it replaces the generator's stages with its own panel.
4. **Difficulty.** The lobby's difficulty key is applied to every human slot as vanilla applies a mode to the player (`bonusesCustom.setMode`, as in `menu/ScRandom`), the same on every PC.
5. **Starting the game from the menu.** The lobby screen leaves the menu the way vanilla starts a game (`Menu.start` with a `Constructor`), with the engine's world set up for several humans. SOSDET's [ADR 0004](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/docs/decisions/0004-several-humans.md) names the call: `addHuman(region)`, which turns an NPC's capital or a free region into a human during setup, before the session starts. SOS-46 didn't build it: today only the engine's test rig makes humans, from an active NPC with a capital. Step 4b needed it; SOSDET SOS-55 built it as `Engine.buildWorld` instead, which makes every human's realm while the world is generated (SOSDET PR #35).
6. **Network handoff.** The lobby's connections stay open into the game; the world's commands, city reports, hashes and the host's AI log become new message types in `sosgsgmp.net` (protocol version raised). The lobby session becomes the campaign session.
7. **Load Saved Lobby.** Enabled once campaign saves exist: the host loads a save, and its slot map decides who may take which slot (spec §11).
8. **Map size.** Applied before the game is built on every PC, so all PCs build the same size of world (see 3.).

## 5. Steam (step 3)

- The game initialises Steam itself (`integrations/SteamClient`) and runs its callbacks every 5 s on its own thread; the mod uses the same steamworks4j 1.9.0 classes from the game jar, so no new library.
- Placeholders to wire: "Join through Steam" on the Multiplayer screen, "Invite Steam friends" in the lobby.
- The transport stays behind the same message layout: a Steam P2P node next to `TcpNode`, giving the sessions the same events.
- Two Steam accounts are needed to test it; the owner tests it at the playtest.
