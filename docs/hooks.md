# Hooks into game code

Every change to the game's own code that the mod makes, with the reason it exists. The deterministic engine's hooks (hook-surface B7–B15) are listed in its own repository, SOSDET ([ADR 0004](decisions/0004-engine-split.md)). See AGENTS.md "Hooks into Game Code" for when a hook is allowed. How hooks reach players is decided in an ADR; until then no hook is added without approval. For now the mod ships its hooks the way the engine does (SOSDET SOS-25, option A): each hook is a patch in `mod/patches/`, the build applies it to a copy of the game file and compiles it with ECJ at the game's class level, and the changed classes go into the mod's script jar, which the launcher puts ahead of `SongsOfSyx.jar`. The mod and the engine never patch the same game file.

| Hook | Game file | Calls into the mod | Why no API or adapter route was enough | Card |
| --- | --- | --- | --- | --- |
| M1 | `menu/ScMain.java` (`getFirst`: one line after the Play button; patch `mod/patches/M1-multiplayer-button.patch`) | `MpMenu.button(menu)`: adds the Multiplayer button that opens the mod's lobby screens | Scripts start only once a game is loaded, and the main menu has no extension point; the button list is built inside a private method of a package-private class. The screens themselves are mod classes in package `menu` (named `Mp*`) because the menu's screen interface `SC` is package-private. Approved by the owner with the card (2026-10-25). | [SOS-49](https://fighterx2500.atlassian.net/browse/SOS-49) |

**Package-private access (no game-code change).** Some mod classes sit in the game's own packages to use package-private members. They change no game file, so they are not hooks, but they depend on the mod's script jar sharing the game's class loader, on the game jar not being sealed, and on V71's member names:

| Mod class | Game package | Package-private members used | Card |
| --- | --- | --- | --- |
| `menu/Mp*` (lobby screens, `MpGameBuild`) | `menu` | the screen interface `SC`, `Menu.switchScreen`, `Menu.start(Constructor)` (leaving the menu for the map step) | [SOS-49](https://fighterx2500.atlassian.net/browse/SOS-49), setup map step |
| `view/world/generator/MpMapStage` (the map step's panel) | `view.world.generator` | `WorldViewGenerator.reset()`, `dummy` (the `Intr` panel holder and its `add`), `minimap`, `hasSelectedTitles`. The map stage replaces the generator's stages right after the game is built and never calls `set()`, so vanilla's stage order needs no hook ([setup-map-step.md](plan/setup-map-step.md) §5). | setup map step |
