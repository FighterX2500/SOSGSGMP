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
| Map size | 128, 192, 256, 384, 512 (default: the host's last choice, first time 256) | A slider over 128–512 is possible too; the steps keep to sizes the noise and region code divide well. A new size is only a choice until Generate, which rebuilds the game on every PC at that size and then generates (section 3; owner, 2026-10-25). |
| Map type | Random, plus vanilla's presets (`sprite/world/generatorMaps/`) | Presets are offered **only at size 256** (risk 1). |
| Latitude | 0–100 | As vanilla's slider: `lat = value / 100.0` (`view/world/generator/tools/UIWorldGenerateTerrain.java:92-113`). |
| Seed | 0 to 2,147,483,647, plus a "random" button | As vanilla's field. |
| Generate | | Generates on the host and sends the map to every PC (section 7). At a new size, every PC rebuilds first. |
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
3. **Generate.** The host presses Generate and sends `MapGenerate(genId, buildId, mapType, latitude, seed)`. The host then generates:
   - set `WORLD.GEN().map`, `lat` and `seed` (public fields, `world/WorldGen.java:23-25`);
   - `WORLD.TERRAIN().saver().generate(print)`, then `WORLD.LANDMARKS().saver().generate(print)`;
   - `WORLD.MINIMAP().repaint()`;
   - write the terrain and landmark sections, hash them, compress them (`java.util.zip.Deflater`) and send them in `MapTerrain` pieces.

   This is vanilla's own Generate action (`view/world/generator/StageTerrain.java:41-50`) without its rendering progress callback and without moving on to the next stage. Joiners don't generate: vanilla's generator gives another map on another PC (section 7). Each joiner collects the pieces, loads the sections into its game (`TERRAIN().saver().load`, `LANDMARKS().saver().load`), sets the same `WorldGen` fields, repaints the minimap, hashes its own sections again and replies `MapResult(genId, hash, status)`. The hash checks that the load gave the host's map.
4. **Regenerate.** The host changes the options and presses Generate again: a new `genId`, and the same steps. Every PC processes every `MapGenerate` and its pieces in order; pieces of an older generation are dropped.
5. **Size change.** The host picks a new size; nothing happens until Generate. Generate then sends `MapBuild(buildId + 1, newSize)`, and every PC rebuilds its game with `CORE.setCurrentState(Constructor)` (public, `snake2d/CORE.java:448`), as vanilla's loader does from inside a game (`game/save/GameLoader.java:74-76`). When the host's rebuild is done, it generates with the options it kept and sends the map as in step 3. A joiner handles nothing while it rebuilds, so the generation waits for it. The panel says that a new size reloads everyone at the next Generate (owner, 2026-10-25: no reload, and no empty map, on a size change alone).
6. **Confirm.** When every hash matches, the host presses Confirm and sends `MapConfirm(genId, hash)`. Every PC locks the map: `WORLD.GEN().hasGeneratedTerrain = true`, and the options are kept for the campaign save. The panel then shows "map confirmed; race and location come next".

**Terrain sections and hash.** Write the terrain and landmark save sections with the game's own `FilePutter` (`snake2d/util/file/FilePutter.java:21`):
- `TERRAIN().saver().save(fp)`;
- `LANDMARKS().saver().save(fp)`;
- `fp.l(0)`: vanilla's `FileGetter` refuses a double in a file's last 8 bytes, so a long follows the sections;
- `fp.save()` to a temp file.

These bytes are what the host sends. The hash is SHA-256 of them, first 8 bytes as a `long`. This is the game's byte layout, carried as it is; it is never a mod format. In the rig, sections written, loaded and written again come out byte for byte the same; at a 256 map they are about 400 KB, about 90 KB compressed (two pieces).

## 4. Messages

The protocol version goes from 1 to 3 (2 was this plan's first draft, never released). As in `sosgsgmp.net.Protocol`, each message starts with the version (short) and the type (byte); `TcpNode` adds the length. A host-assigned `buildId` and `genId` order everything: there is no world tick before the world exists, so these stand in for the tick rule in AGENTS.md.

| Type | Message | Direction | Layout after version and type | Checked on decode |
| --- | --- | --- | --- | --- |
| 7 | `MapBuild` | host → all | `buildId` int, `size` short | buildId ≥ 1; size 128–512 |
| 8 | `BuildReady` | joiner → host | `buildId` int | buildId ≥ 1 |
| 9 | `MapGenerate` | host → all | `genId` int, `buildId` int, `mapType` UTF ("" = Random), `latitude` byte (unsigned), `seed` int | genId, buildId ≥ 1; latitude 0–100; mapType ≤ 64 characters; seed ≥ 0 |
| 13 | `MapTerrain` | host → all | `genId` int, `index` short, `count` short, then the piece: length int + bytes (at most 60 KB) | genId ≥ 1; count 1–273; index < count; length ≤ 60 KB |
| 10 | `MapResult` | joiner → host | `genId` int, `hash` long, `status` byte (ordinal of `MapStatus`: OK, BAD_TERRAIN, WRONG_BUILD) | known ordinal |
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
| `sosgsgmp.net.Message`, `Protocol` | The seven records and their encoding, version 3. |
| `sosgsgmp.net.Pieces` | Data larger than one message: splits it, and puts a transfer's pieces back together. A piece of an older transfer, a repeat or one that doesn't fit changes nothing. The world save of the next card travels the same way. |
| `sosgsgmp.session.HostedSetup`, `JoinedSetup` (with a `SetupSession` interface) | Take over the started lobby's `TcpNode` and roster (`HostedLobby`/`JoinedLobby` hand them over after `Start`). They drain events once per frame, as the lobby sessions do, and feed `MapStep`. The lobby classes keep only the lobby. |

**Adapters (game classes):**

| Class | Responsibility |
| --- | --- |
| `sosgsgmp.game.WorldGenAdapter` | One place for the game calls: `GameSpec` size by reflection (the handles are resolved once and fail with a message naming `game.GameSpec.wx/wy` and V71); `generate(MapOptions)` with a print that doesn't render, returning the hash and the compressed sections; `load(MapOptions, bytes)` on a joining PC, returning its hash after the load; the map type names (`WorldGen.WorldGenMapType.getAll`, `world/WorldGen.java:177`); the engine-session guard (section 7). |
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

**Vanilla's generator isn't a function of the seed.** The first draft assumed terrain plus landmarks were a function of (size, map type, latitude, seed, data files). The owner's first two-PC test showed different maps, and a rig check confirmed it (SOSDET rig `terrainhash`, 2026-10-25): the same seed generated twice in a row in one game gives another map, and so do two game processes on one PC and a third on the laptop. Every terrain layer differs.

The cause is `world/map/terrain/GeneratorRiver.java:43` and `:153`: `Collections.shuffle(coo)` without a `Random`. That uses Java's own random stream, seeded once per process from the clock, never from the game's seed. Every `RND` draw comes from the generating thread (no other thread draws during generation), and the stages before the rivers draw the same number of values every time; from the rivers on, the paths split. No other place in the game calls Java's unseeded shuffle, so the lockstep simulation isn't touched by it.

**So the host generates and every other PC loads the host's map** (section 3). This needs no change to game code and doesn't depend on any other source of difference between PCs. It is the same choice as for the world itself (one PC builds, every PC loads; SOSDET plan 0005, owner 2026-10-25). Making the generator deterministic instead would need a hook in `GeneratorRiver` (a seeded shuffle), with no guarantee that nothing else differs.

What still matters:

| Condition | Why | How it's held |
| --- | --- | --- |
| **No engine session yet** | With a SOSDET session, every `RND` draw outside a step goes elsewhere (SOSDET patch 20). | `WorldGenAdapter.generate` and `load` fail fast if `sosdet.Engine.active()`. `Engine.begin()` comes only after setup. |
| **The load gives the host's map** | A joiner whose game differs (other mods or data) could load the bytes into different layers, or fail to load them. | Each joiner hashes its sections after the load; a mismatch or a failed load (`BAD_TERRAIN`) blocks Confirm. |

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
- `ProtocolTest`: round trips for the seven new messages; malformed values refused; a version-1 message reads as `Unsupported`.
- `PiecesTest`: pieces back together in any order; repeats, older transfers and pieces that don't fit change nothing.

**Loopback** (like `LoopbackLobbyTest`): a real host and two joiners over localhost, with a fake world in place of the adapter.
- Start, `MapBuild`, `BuildReady`, generate, matching results, Confirm on every PC.
- A joiner that reports another hash blocks Confirm.
- A joiner sees another joiner's progress (`MapPlayers`).
- A size change and its new build; a Generate at a new size rebuilds every PC, then generates once.
- Every joiner loads the host's map (several pieces), never generates; a joiner that can't load it shows an error and blocks Confirm.

**In game, manual (PC and laptop):**

- Hashes match, and the maps look the same, for 5 seeds × {Random, one preset} at 256, and for Random at 512 and 128.
- Sizes 256 → 512 → 128 rebuilt in one session, with no errors in `%APPDATA%\songsofsyx\logs\`.
- Times: generation at 512, and a rebuild at 512.

Before the PR: `gradlew.bat compileJava compileTestJava`, `gradlew.bat test`, `gradlew.bat build`.

## 9. Risks

1. **Presets at sizes other than 256.** The preview indexes a 3-colour table by `pixel / size * 3` (`world/WorldGen.java:168-171`). Map pixels reach 255, so below 256 it should throw (found by reading the source, not yet seen in game). Mitigation: presets only at 256; at other sizes the type is Random and the arrows are hidden.
2. **Leaving during setup** through ESC, quickload or exit to menu. The panel's Leave button closes the session cleanly, and the other PCs see the player leave. Leaving through ESC → quit to menu, quickload or load is **not detected** (known gap): the connection stays open until the game quits, the other PCs don't see the leave, and hosting again on the same port fails until a restart. Closing it needs a hook (`view/main/VIEW.java`) or a campaign script that outlives the generator view (later card).
3. **Rebuild time.** Every size change rebuilds the game on every PC (`INIT`, textures). This probably takes tens of seconds; it is measured in the manual test. Mitigation: the size comes first and defaults to the host's last choice.
4. **Transfer time.** A 512 map is about 1.6 MB of sections, about 400 KB compressed (7 pieces); over a home connection that takes a second or two. Not measured yet over Steam.
5. **Different mods** give a failed load or a mismatch. A mods check in the lobby hello would catch this earlier (later card).

## 10. Out of scope

- Race, faction (with vanilla's profile save and load), location with the 6-tile rule: the next card. Vanilla's capital placement generates the whole NPC world around one player (`view/world/generator/StageCapitol.java:101-108, 226-245`), so the mod needs its own location stage.
- World init (NPC countries, routes, the guaranteed routes between players): a later card, with SOSDET's `addHuman`. NPC generation as it stands is not reproducible across PCs: `world/region/Gen.java:93-96` calls `SPRITES.loader().init()`, which draws a random number only if 10 s passed since the last call (`init/sprite/RLoadPrinter.java:218-223, 270`), right before shuffling the regions. So either the host initializes the world and sends the world save, or a hook removes that draw. The next card decides.
- Difficulty per human slot, the engine session, Steam: walking skeleton §4 and §5.
