# Save Campaign in the escape menu (SOS-79)

Card: [SOS-79](https://fighterx2500.atlassian.net/browse/SOS-79). Spec §11 Saving. Status: approved (owner, 2026-10-27), with the answers in section 5.

## 1. What vanilla has

- The escape menu is `view/menu/IMenu.java`. Its constructor builds one column of buttons: Resume, Quick-save, Save, Load, Options, Keys, Quit to menu, Exit. There is no script API or other extension point for it.
- Quick-save and Save grey themselves out with `VIEW.canSave()`. In a multiplayer game that is already false, because the mod's panel refuses saving (`MpSessionPanel.canSave`). The same check already stops Ctrl+S and vanilla's autosave.
- Load is never greyed out. The Quick Load key (`VIEW.java`, `KEYS.MAIN().QUICKLOAD`) isn't either. Either one loads a single-player save in the middle of a session.
- Quit to menu and Exit leave without ending the session.

## 2. Approach

**The mod's own column in vanilla's menu.** In a multiplayer game, the escape menu shows a second column built by the mod from vanilla's widgets: Resume, Save Campaign, Options, Keys, Quit to menu, Exit. Single player keeps vanilla's column untouched.
- **Save Campaign:** the host's save, the same code the panel's button ran (`MpSaves.canSave` and `saveNow`). Other players see it greyed out, with "Only the host can save the campaign". The host sees it greyed out with the reason whenever a save can't start: a battle, a save already under way, a desync hold.
- **Quit to menu and Exit** run Save Campaign first, then end the session for every player, as a desync's Save and exit does. The player who quit goes to the main menu, or the game closes. The others see that this player quit, the campaign is saved, and the session has ended.
  - A joiner's Quit asks the host's PC to save, with a new message. The host's PC orders the save as soon as one can start.
  - In a battle no save can start. The player is asked to leave without saving instead: for the host the session ends for everyone; for a joiner the others get the disconnect window (SOS-65).
- **Gone:** the panel's "save campaign" button. The escape menu is the only way to save.
- **Quick Load key:** does nothing while an engine session runs (SOSDET).

**The column is installed, not looked up.** `MpSessionPanel.show`, which runs after every load (a battle's too), also hands the column to the menu. The menu holds no reference to the mod, and the mod holds no state in statics.

**The save-and-exit ending gets one owner.** Today `MpDesync` shows the "saving, then the session ends" screen and the ending. Both the desync's Save and exit and the quit now go through a new `MpExit`. `SaveOrder` records who quit (none for a desync), so every PC's ending text can say why the session ended.

## 3. Hooks

- **M6, mod** (`view/menu/IMenu.java`, patch `mod/patches/M6-escape-menu.patch`):
  - a field for the multiplayer column and a public setter;
  - `setMain` and the right-click "back" go to that column when it's set;
  - package-private `options()` and `keys()`, so the mod's column (class `view.menu.MpEscapeMenu`) can open them.
  - No other code patches `IMenu.java`.
- **SOSDET** (`view/main/VIEW.java`, already patched by SOSDET's `65-shared-ui-state.patch`): the Quick Load key is skipped while a session runs, through `Hooks.quickLoadAllowed()`. A SOSDET PR, merged before the mod's.

## 4. Checks

- **Unit tests:**
  - `Protocol`: the new message, and `SaveOrder`'s quitter, round-tripping.
  - `HostedSetup`/`JoinedSetup`: a joiner's request reaches the host once. A repeated request changes nothing.
- **Two PCs (twopc):** the autopilot runs Save Campaign from the escape menu's action, then resumes from that save. A joiner's Quit saves on both PCs and ends the session, and that save resumes too.
- **By hand, the owner's playtest:**
  - the menu in single player is unchanged;
  - in multiplayer, the column, the greyed-out reasons, Quit and Exit;
  - Quick Load does nothing.

## 5. The owner's answers (2026-10-27)

1. The panel's button goes; Save Campaign is only in the escape menu.
2. Blocking the Quick Load key is part of this card.
3. Quit to menu and Exit both run Save Campaign. A joiner's Quit asks the host to save for every player, then the session ends.

## 6. Results (2026-10-27)

- **Unit tests:** 136 pass. They include `ProtocolTest.aQuitterComesOnlyWithAnExit` and `LoopbackSetupTest.aQuitAskReachesTheHostOnce`.
- **Two PCs** (this PC hosts, the laptop joins, over Radmin), with `tools/twopc.py`:
  - **Q79A (fail, fixed):** the save at the quit was written on both PCs. But the host's "save done" and its close of the session reached the joiner in one update, and the joiner ended with "The host closed the game." A desync's Save and exit had the same race.
  - **The fix:**
    - `MpSaves` still takes the "save done" of a save that ends the session after the session closed.
    - `MpExit` runs after the saves in the frame.
  - **Q79B, pass:** `--save 1200 --quit 2400`. The host's save went through the escape menu's action; the joiner's Exit at step 2400 asked the host. Both PCs saved the campaign (save 2) and ended with an empty outcome, naming the joiner as the one who quit. The hashes match.
  - **Q79C, pass:** `--resume` loaded campaign save 2, the quit's save. 3,600 steps, 6 hash checks, 0 differ.
- **Not checked yet, by hand:**
  - the menu's look in single player and in multiplayer;
  - the greyed-out reason line;
  - the host's own Quit and Exit;
  - a quit during a battle;
  - that the Quick Load key does nothing.
