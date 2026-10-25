# Setup: the map step

Card: to propose (walking skeleton step 4, part 1) · Date: 2026-10-25 · Status: draft for the owner's review · Spec: §5 Map

Game paths are relative to `<game>/info/SongsOfSyx-sources/`. All findings come from reading the V71 source; nothing here has been run yet.

## 1. Goal

After Start Lobby, the host picks the map size and vanilla's map options and generates the terrain. Every PC shows the same terrain. The host can regenerate as often as they like, then confirms. After the confirm the map can't change (spec §5 Map, steps 1–3).

The card ends when two PCs (PC and laptop over Radmin) show the same confirmed map with equal terrain hashes. Race, faction and location come in the next card.

## 2. What the player sees

The world map fills the screen on every PC: vanilla's world generator view, which draws the world itself (`view/world/generator/WorldViewGenerator.java:124-129`). The camera pans and zooms as in vanilla. Vanilla's stage controls are replaced by one panel.

**Host panel**

| Control | Values | Notes |
| --- | --- | --- |
| Map size | 128, 192, 256, 384, 512 (default: the host's last choice, first time 256) | A slider over 128–512 is possible too; the steps keep to sizes the noise and region code divide well. Changing the size rebuilds the game on every PC (section 3). |
| Map type | Random, plus vanilla's presets (`sprite/world/generatorMaps/`) | Presets are offered **only at size 256** (risk 1). |
| Latitude | 0–100 | As vanilla's slider: `lat = value / 100.0` (`view/world/generator/tools/UIWorldGenerateTerrain.java:92-113`). |
| Seed | 0 to 2,147,483,647, plus a "random" button | As vanilla's field. |
| Generate | | Sends the options, then generates locally. |
| Confirm | | Active only when every PC's terrain hash for the latest generation equals the host's. |
| Players | One row each: building, generating, match, mismatch, error | |
| Leave | | After a yes/no prompt: ends the setup for every player (they see "The host closed the game."), then returns to the main menu as vanilla's "quit to menu" does (`CORE.setCurrentState` with `Menu.make()`, `view/menu/IMenu.java:220-225`). |

**Joiners' panel:** the same values, read-only, with the same player rows (from `MapPlayers`) and "waiting for the host". The minimap is shown. Leave drops this PC from the setup (it disappears from every player's rows) and returns to the main menu. Once the host has left, Leave returns to the menu without a prompt.

## 3. Flow

1. **Start Lobby.** The host sends `Start` as today, then `MapBuild(buildId 1, size)`.
2. **Build.** Every PC, host included, leaves the menu the way vanilla starts a game. `Menu.start(Constructor)` (`menu/Menu.java:207-220`; package-private, so it is called from the lobby screen in package `menu`). Inside the constructor:
   - `GameSpec spec = GameSpec.get(SCRIPTS)`. `SCRIPTS` is a fixed list, the same on every PC. `ScriptEngine` adds every `forceInit` script itself (`script/ScriptEngine.java:28-55`).
   - The adapter sets `spec.wx` and `spec.wy` to the size by reflection. Both are `public final int` fields (`game/GameSpec.java:42-43`). Vanilla fills them from `TILE_DIMENSION` (`:63-64`, `init/constant/Config.java:88`), and `WORLD` takes its size only from them (`game/GAME.java:159`).
   - `GAME.create(spec)`, which is public (`game/GAME.java:211`).
   - The map stage takes over the generator view, which `new VIEW` has just opened (`view/main/VIEW.java:111-114`).

   Joiners then send `BuildReady(buildId)`. The lobby session reaches the game through the constructor's closure, never through a static.
3. **Generate.** The host presses Generate and sends `MapGenerate(genId, buildId, mapType, latitude, seed)`. Every PC runs the same method:
   - set `WORLD.GEN().map`, `lat` and `seed` (public fields, `world/WorldGen.java:23-25`);
   - `WORLD.TERRAIN().saver().generate(print)`, then `WORLD.LANDMARKS().saver().generate(print)`;
   - `WORLD.MINIMAP().repaint()`;
   - compute the terrain hash.

   This is vanilla's own Generate action (`view/world/generator/StageTerrain.java:41-50`) without its rendering progress callback and without moving on to the next stage. Joiners reply `MapResult(genId, hash, status)`.
4. **Regenerate.** The host changes the options and presses Generate again: a new `genId`, and the same steps. Every PC processes every `MapGenerate` in order, never only the latest.
5. **Size change.** The host sends `MapBuild(buildId + 1, newSize)`. Every PC rebuilds its game with `CORE.setCurrentState(Constructor)` (public, `snake2d/CORE.java:448`), as vanilla's loader does from inside a game (`game/save/GameLoader.java:74-76`). The panel says that a size change reloads everyone.
6. **Confirm.** When every hash matches, the host presses Confirm and sends `MapConfirm(genId, hash)`. Every PC locks the map: `WORLD.GEN().hasGeneratedTerrain = true`, and the options are kept for the campaign save. The panel then shows "map confirmed; race and location come next".

**Terrain hash.** Write the terrain and landmark save sections with the game's own `FilePutter` (`snake2d/util/file/FilePutter.java:21`):
- `TERRAIN().saver().save(fp)`;
- `LANDMARKS().saver().save(fp)`;
- `fp.save()` to a temp file.

Then take SHA-256 of that file and keep its first 8 bytes as a `long`. This hashes the game's byte layout; it is never a mod format.

## 4. Messages

The protocol version goes from 1 to 2. As in `sosgsgmp.net.Protocol`, each message starts with the version (short) and the type (byte); `TcpNode` adds the length. A host-assigned `buildId` and `genId` order everything: there is no world tick before the world exists, so these stand in for the tick rule in AGENTS.md.

| Type | Message | Direction | Layout after version and type | Checked on decode |
| --- | --- | --- | --- | --- |
| 7 | `MapBuild` | host → all | `buildId` int, `size` short | buildId ≥ 1; size 128–512 |
| 8 | `BuildReady` | joiner → host | `buildId` int | buildId ≥ 1 |
| 9 | `MapGenerate` | host → all | `genId` int, `buildId` int, `mapType` UTF ("" = Random), `latitude` byte (unsigned), `seed` int | genId, buildId ≥ 1; latitude 0–100; mapType ≤ 64 characters; seed ≥ 0 |
| 10 | `MapResult` | joiner → host | `genId` int, `hash` long, `status` byte (ordinal of `MapStatus`: OK, UNKNOWN_MAP_TYPE, WRONG_BUILD) | known ordinal |
| 11 | `MapConfirm` | host → all | `genId` int, `hash` long | genId ≥ 1 |
| 12 | `MapPlayers` | host → all | `buildId` int, `genId` int (0 = none yet), count byte, then per player (host first): `id` int, `state` byte (ordinal of `MapStep.PlayerState`: BUILDING, READY, GENERATING, GENERATED, MATCH, MISMATCH, ERROR) | buildId ≥ 1; genId ≥ 0; count 1–16; id ≥ 0; known ordinal |

The existing messages and their layouts stay as they are. `Start` stays type 5 and still closes the lobby.

`MapPlayers` gives the joiners the same player rows as the host. The host sends it after each `MapBuild` and `MapGenerate`, and whenever a player's progress changes (a build ready, a result, a player leaving, the confirm). A joining PC keeps it only for its current build and generation, so an older one changes nothing. Its own row shows match or mismatch as the host reports it.

## 5. Classes

**Plain Java, no game imports, unit-tested:**

| Class | Responsibility |
| --- | --- |
| `sosgsgmp.setup.MapOptions` | Record `(size, mapType, latitude, seed)`, its limits and the size steps. Fails fast on bad values. |
| `sosgsgmp.setup.MapStep` | The map step's state machine. Host side: current `buildId` and `genId`; for each player, the build they reported, their latest result and status; `canGenerate()`, `canConfirm()`, locked after confirm; a player who leaves is dropped. Joiner side: the build and generation to run next, and confirmed. Stale replies (an older `buildId` or `genId`) change nothing; a repeated message changes nothing. |
| `sosgsgmp.net.Message`, `Protocol` | The six records and their encoding, version 2. |
| `sosgsgmp.session.HostedSetup`, `JoinedSetup` (with a `SetupSession` interface) | Take over the started lobby's `TcpNode` and roster (`HostedLobby`/`JoinedLobby` hand them over after `Start`). They drain events once per frame, as the lobby sessions do, and feed `MapStep`. The lobby classes keep only the lobby. |

**Adapters (game classes):**

| Class | Responsibility |
| --- | --- |
| `sosgsgmp.game.WorldGenAdapter` | One place for the game calls: `GameSpec` size by reflection (the handles are resolved once and fail with a message naming `game.GameSpec.wx/wy` and V71); `generate(MapOptions)` with a print that doesn't render; `terrainHash()`; the map type names (`WorldGen.WorldGenMapType.getAll`, `world/WorldGen.java:177`); the engine-session guard (section 7). |
| `menu.MpGameBuild` | The `Constructor` used at Start and on a size change: spec, size, `GAME.create`, takeover. |
| `view.world.generator.MpMapStage` | The panel: host controls or the read-only view, plus the player rows. The takeover calls `reset()` and `dummy.add(panel, title)`, shows the minimap for joiners and sets `hasSelectedTitles = true`. It pumps the session once per frame from the panel's `render`, which `Intr` always calls (`view/world/generator/Intr.java:65-69`). |

`MpMapStage` lives in the game's package because `reset()`, `dummy`, `minimap` and the stage flags are package-private (`WorldViewGenerator.java:35-53, 131-136`). This is the technique M1's `menu/Mp*` screens already use, and it needs no change to game code. It goes into `docs/hooks.md` as a note, next to M1's.

The takeover throws away the race stage the generator built (`WorldViewGenerator.java:69`). Vanilla's `set()` (`:138-155`) runs only from its own stage buttons, which the map step never shows, so the stage order needs no hook here.

## 6. Hooks

**None required.**

Optional hooks we skip:

| Hook | Game file | Why it would exist | Why we skip it |
| --- | --- | --- | --- |
| Map type scale | `world/WorldGen.java:103` | Preset map types divide pixel values by the world size instead of 256. They come out too low above 256, too high below it, and the preview crashes below 256 (risk 1). | Offering presets only at 256 avoids the problem without touching game code. |
| Setup lock | `view/main/VIEW.java:242-255` | ESC opens the in-game menu (save, load, exit), and the quicksave and quickload keys still work during setup. | The panel's Leave button is the supported way out; leaving through ESC or quickload stays a known gap (risk 2). Blocking can come later if playtests need it. |

## 7. Determinism

Terrain plus landmarks are a function of (size, map type, latitude, seed, data files). The generator seeds `RND` itself (`world/map/terrain/Generator.java:49`). Every draw comes from that one generator; there is no time, `Math.random`, hash-map order or extra thread in the generators. Three conditions keep it so:

| Condition | Why | How it's held | How it's tested |
| --- | --- | --- | --- |
| **No engine session yet** | With a SOSDET session, every `RND` draw outside a step goes to `ThreadLocalRandom` (SOSDET `Streams.java:37-44`, patch 20), so the seed would do nothing. | `WorldGenAdapter.generate` fails fast if `sosdet.Engine.active()`. `Engine.begin()` comes only after setup. | In game: hashes match on both PCs. Code review of the guard. |
| **A print that doesn't render** | Vanilla's progress callback renders the whole view between generator steps (`init/sprite/RLoadPrinter.java:105-126`). Any random draw there would shift the stream; the full loading screen draws some itself. | Every PC passes the same non-rendering print. The screen holds still during generation. Landmarks follow terrain directly. | In game: hashes match across several seeds, types and sizes. |
| **Regenerate doesn't depend on history** | The host may have generated 5 times where a joiner generated once. | Every PC runs every `MapGenerate` in order. | In game: on one PC, generate A then B, and compare B's hash with B in a freshly built game. If they differ, the joiner must replay the same history, which it already does. |

Mods and data must match. A different preset list shows as `UNKNOWN_MAP_TYPE`, and other differences show as a mismatch.

## 8. Tests

**Unit (plain Java):**

- `MapOptionsTest`: limits and size steps.
- `MapStepTest`:
  - Confirm only when every player's hash for the latest `genId` equals the host's.
  - A mismatch or an error blocks Confirm.
  - A stale `MapResult` (older `genId` or `buildId`) changes nothing.
  - A repeated message changes nothing.
  - A player leaving removes their row and can unblock Confirm.
  - Nothing is accepted after the confirm.
  - A new `MapBuild` resets every result.
- `ProtocolTest`: round trips for the six new messages; malformed values refused; a version-1 message reads as `Unsupported`.

**Loopback** (like `LoopbackLobbyTest`): a real host and two joiners over localhost, with a fake world in place of the adapter.
- Start, `MapBuild`, `BuildReady`, generate, matching results, Confirm on every PC.
- A joiner that reports another hash blocks Confirm.
- A joiner sees another joiner's progress (`MapPlayers`).
- A size change and its new build.

**In game, manual (PC and laptop):**

- Hashes match for 5 seeds × {Random, one preset} at 256, and for Random at 512 and 128.
- A-then-B equals a fresh B (section 7).
- Sizes 256 → 512 → 128 rebuilt in one session, with no errors in `%APPDATA%\songsofsyx\logs\`.
- Times: generation at 512, and a rebuild at 512.

Before the PR: `gradlew.bat compileJava compileTestJava`, `gradlew.bat test`, `gradlew.bat build`.

## 9. Risks

1. **Presets at sizes other than 256.** The preview indexes a 3-colour table by `pixel / size * 3` (`world/WorldGen.java:168-171`). Map pixels reach 255, so below 256 it should throw (found by reading the source, not yet seen in game). Mitigation: presets only at 256; at other sizes the type is Random and the arrows are hidden.
2. **Leaving during setup** through ESC, quickload or exit to menu. The panel's Leave button closes the session cleanly, and the other PCs see the player leave. Leaving through ESC → quit to menu, quickload or load is **not detected** (known gap): the connection stays open until the game quits, the other PCs don't see the leave, and hosting again on the same port fails until a restart. Closing it needs a hook (`view/main/VIEW.java`) or a campaign script that outlives the generator view (later card).
3. **Rebuild time.** Every size change rebuilds the game on every PC (`INIT`, textures). This probably takes tens of seconds; it is measured in the manual test. Mitigation: the size comes first and defaults to the host's last choice.
4. **Floating point across CPUs.** `Math.pow` and trig use JIT intrinsics. The JRE is the same on every PC, so the results are expected to match; the hash is the guard.
5. **Different mods** give unknown map types or a mismatch. A mods check in the lobby hello would catch this earlier (later card).

## 10. Out of scope

- Race, faction (with vanilla's profile save and load), location with the 6-tile rule: the next card. Vanilla's capital placement generates the whole NPC world around one player (`view/world/generator/StageCapitol.java:101-108, 226-245`), so the mod needs its own location stage.
- World init (NPC countries, routes, the guaranteed routes between players): a later card, with SOSDET's `addHuman`. NPC generation as it stands is not reproducible across PCs: `world/region/Gen.java:93-96` calls `SPRITES.loader().init()`, which draws a random number only if 10 s passed since the last call (`init/sprite/RLoadPrinter.java:218-223, 270`), right before shuffling the regions. So either the host initializes the world and sends the world save, or a hook removes that draw. The next card decides.
- Difficulty per human slot, the engine session, Steam: walking skeleton §4 and §5.
