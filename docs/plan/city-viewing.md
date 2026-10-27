# City viewing (SOS-82)

Card: [SOS-82](https://fighterx2500.atlassian.net/browse/SOS-82). Spec §10 (0.26) and [ADR 0006](../decisions/0006-lockstep-city-viewing-and-assaults.md). The engine's half is SOSDET plan 0012. Approved by the owner on 2026-10-27: option A for the host's own view, the view as a request, option C (moving the host's role) for later.

## 1. What the player sees

- **Asking.** The faction window of another player (SOS-76) gets a **View city** button. A click sends the request; the button shows "Asked…" until the answer comes. It is greyed out, with the reason, during a battle, while the player is already viewing, while their own city is being viewed, and for a player who is viewing someone else.
- **The owner's answer.** The owner gets a vanilla-style popup: "<name> asks to view your city", with **Allow** and **Refuse**. No answer within 30 seconds counts as Refuse. A refusal reaches the viewer as a message.
- **Opening.** Once allowed, every PC freezes the viewer at the same step. Every other player's game pauses for about a second while it reloads its own world (as it does again when the view closes); the engine does this itself, the mod shows nothing for it. The viewer sees a loading screen while the owner's save comes in and loads, then the owner's city, catching up. When the host is the viewer, every player sees "Host is opening a view of <name>'s city" while the game holds.
- **Viewing.** A bar at the top: "Viewing <name>'s city", with **Close view**. The player can move the camera, open windows and chat; every order and command is dropped (the engine's watch-only).
- **Closing.** **Close view**, a battle or prompt for the viewer, or a limit (below) ends the view. The viewer's own city loads back where it stopped. A view ended by a limit or a refusal shows a message saying why.

## 2. Messages and streams (protocol version 13)

- `VIEW_ASK(viewer, owner)`: the viewer's PC to the host, which passes it to the owner's PC. The host refuses at once, with the reason, when the engine would cancel the view.
- `VIEW_ANSWER(viewer, owner, allowed)`: the owner's PC to the host. On Allow the host calls `Engine.viewBegin`; on Refuse it passes the answer to the viewer.
- `VIEW_CLOSE(viewer, reason)`: the viewer's PC to the host, which calls `Engine.viewEnd`. The reason (closed, can't catch up, fell behind) goes into the playtest log.
- **Stream `VIEW`** (owner to viewer, through the host): the owner's `Engine.viewOut()` bytes, framed as the battle saves are (step, length, bytes). The host passes it on only to the viewer.
- **Stream `VIEW_HASH`** (owner to viewer, through the host): `Engine.viewHashOut(viewer)`; the viewer feeds `Engine.viewHashIn`.

The host only passes these on; it runs nothing of the view (spec §10, "only the viewer and the owner").

## 3. Limits (spec §10)

On the viewer's PC, from the moment its copy runs (after the load):
- **Catching up:** if `Engine.behindHost()` is still over 60 steps 60 seconds later, the view ends: "Your PC couldn't catch up with <name>'s city."
- **Keeping up:** once caught up, a stretch of more than 60 seconds over 60 steps behind ends it the same way.
- **A view desync** (`Engine.viewDesync()`): the view ends with "The view lost step with <name>'s city." (ADR 0006 says reload; a reload costs the owner another save stutter, so v1 ends the view and the player can ask again.)

## 4. Playtest log (SOS-63)

`viewask`, `viewanswer`, `viewbegin` (freeze step), `viewout` (bytes, save ms), `viewin`, `viewcaughtup` (seconds), `viewdesync`, `viewend` (reason, thaw step).

## 5. Tests

- Unit: the messages' round trips; the request flow (ask, allow, refuse, timeout, refused while viewing) in a plain-Java `ViewRequests` class; the limit clock.
- Two PCs (`tools/twopc.py --view`): the joiner asks, the host allows, the joiner views for 2 minutes and closes; then the host views the joiner (option A). The verdict needs the view's hashes compared, no desync, and the world identical after.
- The owner's playtest: §15's [PT] rows (open within 1 minute of catching up, the owner's one pause of a few seconds as the view opens).

## 6. Open

- **The owner's stutter.** The owner saves (about 1.2 s on Mogus-72, rig run V82B) and reloads its own city as the view opens (option E, ADR 0006): a few seconds, accepted by the owner. Playtests measure it.
- **A room being drawn.** A room still being drawn in the placer is lost on a reload: on the owner when the view opens, and on every PC at a world reload. Not handled in v1.
- **A prompt for the viewer.** Its actions are game objects the viewer's load would lose, so the view ends right after the prompt closes, not before it: the viewer can't answer it, and it takes its default after 30 seconds. Implemented this way; the owner can change it.
