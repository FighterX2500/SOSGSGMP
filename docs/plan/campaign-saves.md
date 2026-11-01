# Campaign saves and resume (SOS-64)

Spec §11: Saving, Resuming. The owner asked for this before the general playtest (2026-10-25): "without saves it's kinda meh".

## What the player gets

- **Save campaign**, a button on the host's session panel. Every PC stops at the same step, saves its own game, and play goes on once every PC has saved. The other players see "Saving the campaign..." and can't save themselves.
- **Autosave.** The host's vanilla autosave interval (Settings, Auto-Saving) starts the same save on every PC; vanilla's own autosave stays off in a session, as before. Each PC keeps the host's number of autosave files (Auto-Save-Files) per campaign. Saves the host starts with the button are never deleted.
- **Load Saved Lobby** in the multiplayer menu lists this PC's campaign saves, newest first. The host picks one and opens a lobby over IP or Steam. Only that campaign's players can join: each PC is known by its player key, which it already sends in its hello. A joining PC that doesn't have that save is told so and leaves. Start is active once every player of the campaign is in. When the lobby starts, every PC loads its own save, the host compares the world hashes as at a new game, and the game starts at the host's Go with no landing party.

## How a save runs

1. **Order.** The host picks the save step S = `Engine.stepPastPeers()`, its horizon plus one lockstep window (its step + 60 at 1x, + 400 at speed 25: the window grows with the speed, SOS-90), and sends `SaveAt(order)` to every PC, keeping it for itself too. A peer can't run past the host's horizon, and the window covers the order's way there. So every PC gets the order before it reaches S. While a player views a city the order waits, and no new view begins until the save is done: the viewer's own city isn't loaded, so it couldn't save. A spectator's view doesn't hold it up, a save that ends the session closes the views instead, and the save before a battle can't wait, since a view needs steps to end and the world holds there. Every PC then calls `Engine.runUntil(S)`.
2. **Save.** At S, between frames, each PC saves its game into vanilla's saves list with vanilla's time stamp: `MP <campaign> <Save|Auto>`. It also writes the campaign record (below) and sends `Saved(number, world hash, problem)`. A PC already past S, or whose save failed, reports the problem instead of a hash.
3. **Done.** Once every PC has reported, the host sends `SaveDone(number, outcome)`. Every PC then runs freely again and shows the outcome: saved, or which PC failed or differs.
4. **No save while** a battle runs, the desync window is open, or another save is under way. Saving in a battle is spec §11's "save before battles", which stays open. An autosave that comes due then waits.

The engine needs no change: `runUntil` holds the step, and a vanilla save between steps changes nothing in the simulation. Save and exit after a desync already saves this way (SOS-59).

## The campaign record

Each PC writes one small file next to each of its campaign saves: `%APPDATA%\songsofsyx\sosgsgmp-campaigns\<campaign id>-<save number>.txt`. Its lines are `key=value`, with the format version first. Plain Java: `sosgsgmp.campaign.CampaignRecord` and `CampaignStore`.
- **The campaign,** the same on every PC (the host sends it with every order): its id, name, seed and difficulty, and its players' keys, names and slots.
- **This save,** the same on every PC: its number (the host counts on from the campaign's last), Save or Auto, and the time.
- **This PC's own part:** its slot, the vanilla save's file name, and its world hash at S.

The campaign id is the first game's start id, a SHA-256 of the world save every PC loaded at the start, so every PC already knows the same value. Old autosaves are deleted together with their records.

## How a resume runs

- **Lobby.** `LobbySettings` gains an optional `Resume`: the campaign, the save number, and every player's key, name and slot. `Welcome` carries it, and the protocol version goes up to 9.
- **Who may join.** The host refuses a hello whose key isn't in the campaign (`Refusal.NOT_IN_CAMPAIGN`) or is already in the lobby (`Refusal.ALREADY_JOINED`, in a new game too: a campaign needs one key per player). A PC whose profile was copied from another has the same key; the refusal says to delete `SOSGSGMP-lobby.properties` on one of them, and `twopc.py` deletes it from each instance's copied profile. A joining PC checks that it has the record and the save file. If it doesn't, it leaves with "You don't have this save".
- **Handover.** On a resume, `handOver` gives a setup that is already at the world-loaded step. The host's factions step gets every player's slot from the keys, with transfer 1, and sends `SlotMap`. A joining PC starts its factions step at once and takes that `SlotMap`.
- **Load.** Once the slots are known, every PC's lobby screen starts `MpWorldLoad` on this PC's own save:
  - the engine session is begun as for a new game, with the campaign's seed and the start id taken from the campaign id and the save number;
  - the save is loaded;
  - every PC's human gets its slot;
  - the world hash goes to the host.
- **Go.** The city has people, so the panel reports the start spot at once. The host's Go starts the game at step 0 on every PC, as for a new game.
- **Any player can host.** The host runs the AI and paces the others whatever its slot. Slots stay as they were saved.
- **Every PC's world must match.** Each PC saved the same world at S, so the hashes agree. If one doesn't, the factions step shows the mismatch as at a new game, and the game can't start.
- **A save holds all of the world but one value.** The slave recipes' NPC production boosts aren't in a save, and the game draws them as it is built. A process's first game makes draws a later one doesn't, so a PC just started and one that had played loaded different boosts from one save (playtest of 2026-11-01, save 12). In a session they now come from the campaign's seed, so a resume no longer depends on who restarted (SOSDET `Hooks.unsavedBoost`).

## Not in this card

- A missing player at resume: spec §11 freezes their city until they join. Here every player of the campaign has to be in the lobby.
- Removing a player, and the save before battles.
- Vanilla's own Save, Quicksave and autosave in a session write a local save that can't be resumed as a campaign. Load Saved Lobby doesn't list such saves.
- Anything crossing between a city and the world in the last half second before the save is lost at a resume: a soldier on the way out of the city to their army, or an event's mark. The same is true of a battle's return (SOSDET plan 0006). Commands players gave for steps after S are dropped too.

## Tests

- **Unit tests:**
  - the record written and read back, an unknown format refused, and the store's listing and autosave rotation;
  - the save round (orders, reports, done, failures);
  - the lobby refusing a stranger's key, and Start waiting for every player;
  - the protocol round trip of every new message.
- **Loopback test** with three PCs:
  - the host orders a save; every PC takes the order, reports, and gets the done;
  - a resume lobby hands over at the loaded step, the slot map reaches every PC, and the host's Go starts every PC.
- **Two PCs with `twopc.py`:**
  - play to step 1500, save there, play on to 2400 and quit;
  - then `--resume`, which keeps both PCs' data folders: the host opens the latest save, the laptop joins, both load, and they play 1800 steps;
  - the hashes must agree on both PCs, and each playtest log shows the save and the resume.
  - The autopilot takes `save=STEP` (the host saves the campaign there) and `resume=1` (the host hosts this data folder's
    newest campaign save); `twopc.py --save STEP` checks that both PCs saved, and `--resume` keeps both data folders
    but copies the installed mods into them.

## Results (2026-10-25)

- **Unit and loopback tests:** 122 pass. The loopback resume puts the host at slot 2, so the AI log comes from a
  host that isn't PC 0.
- **S64A** (this PC hosts, the laptop joins, direct IP over Radmin): `twopc.py --steps 1800 --save 600`. The order
  went out at step 600 for step 660; both PCs held there, saved and played on about 3 s later. Hashes agree at 600,
  1200 and 1800. PASS.
- **S64B:** `twopc.py --resume --steps 1800` on S64A's folders. Both PCs loaded their own save in 9.8 s, the world
  hashes matched, Go came at once (the cities have people), and the hashes agree at 600, 1200 and 1800. PASS.
- **Found and fixed on the way:**
  - A joining PC never saved its player key unless Join was pressed, so it came back as a stranger
    (`NOT_IN_CAMPAIGN`). A new key is now saved as soon as it is made.
  - SOSDET's `WorldStatics.reset` at the start of a session load forced classes to initialize, and one of them
    needs the game's clock. A resume is the first session load in a process with no game, so it crashed on both PCs.
    The reset now waits until a game exists; before that every reset value is still its start value
    (SOSDET `SOS-64-fresh-process-load`).
  - The autopilot read the difficulty from a profile without one; it now picks the game's first mode, as Create
    Lobby does.
- **Seen, not part of this card:** at the end of S64A, vanilla's error window opened on the laptop for the warning
  "No active entry points exist: the city map did not generate any roads", from the joiner's own city at the
  autopilot's capital. The run passed; it is a location the autopilot chose, to look at with the joiner's city
  generation.
