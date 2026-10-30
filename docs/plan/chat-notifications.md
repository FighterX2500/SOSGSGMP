# Chat and notifications (SOS-110)

Spec §12 Chat and notifications, §4 (who paused), §15. Card: [SOS-110](https://fighterx2500.atlassian.net/browse/SOS-110). Decisions: the owner's, 2026-10-30, on the card. Status: **approved 2026-10-30**: steps A to C now, D to F after SOS-93 merges. It builds on spec 0.33 (PR #72).

## 1. What exists

- **Transport:** `Message.Cursor` already goes through the host, outside the simulation, and the host stamps the sender. Chat takes the same path with a new message. Protocol 16 becomes 17.
- **Text input:** `StringInputSprite` and `GInput`. While a field listens, the keyboard sends keys to it and hotkeys stay off. Esc still reaches the game.
- **Windows:** `MpSessionPanel` is the movable pattern. `MpPanelButton` and hook M8 put its button on the top bar. Both panels are rebuilt after every load, so the chat's lines can't live in the window.
- **Colours:** `MpCursors.COLORS`, the n-th player's colour, the same on every PC.
- **Notices the mod already gets:** `MpPlay` takes the assault outcome, the assault terms and invasions from the engine. `PlayerGone` and the deal answers also reach it.
- **Missing in the engine:** no feed for war, peace, vassalage, a siege's start, an assault's start or a battle's start, and nothing says who paused. `Engine.speed` and the speed lines carry no player.

## 2. Plan

Each step is one commit in this repository's PR, except step D's engine PR.

### A. Chat over the network (mod)

- `Message.Chat(playerId, text)`, protocol 17. A joiner sends to the host; the host stamps the real sender, appends the line and sends it to every PC, the sender included. So every PC shows the same order, and a sender's line appears after the host's round trip.
- `sosgsgmp.session.Chat`: the last 200 lines, owned by the session, so it survives every load. It starts empty on a resume and isn't saved.
- Cleaning, on the host and on every receiver: control characters, surrogates and U+FFFF dropped, and at most 200 characters. U+FFFF is one past the end of `Font.map`, so it would crash a PC's text drawing.
- Rate limit: 5 messages in 5 seconds, checked on the sender's PC, which sees a local line.
- Chat isn't logged. The playtest log counts the new lines, with no text.

**Tests:** unit tests for the log's cap, the cleaning and the limit; protocol round trip; a loopback test that a message reaches each PC exactly once, in the host's order, with a forged sender replaced.

### B. The chat box (mod)

- `menu.MpChat`, an `Interrupter` in `VIEW.inters().manager`: the last lines and a click-to-type field. Enter sends and leaves the field. Nothing opens it from the keyboard.
- Starts bottom-left, moves like `MpSessionPanel`. The name is in the player's colour; event lines have their own colour.
- Hook M8 grows: a second button of the same size, next to the multiplayer one, collapses and expands the box. Its state and position stay for the session.
- Colours move out of `MpCursors` to one place both use.

**Tests:** none for layout (owner's rule). A GUI preview run gives screenshots; the owner checks them in-game. To check there: the box in a battle and an assault view, and how it sits with vanilla's left-side windows.

### C. Lobby and setup (mod)

- `MpChatBox`, the lines and the field, is one widget: `MpChat` holds it in the game, and `MpScreenLobby` shows it directly, as the menu has no VIEW. The chat passes from the lobby to the setup.
- The setup's map and faction screens run in a loaded game, so `MpChat` works there as it is. They have no top bar, so no button.

### D. Events in the chat (engine, then mod)

Waits until SOS-93 merges: it changes `Session` and `Hooks`.

- SOSDET: a notice queue, `Engine.noticeTake()`, filled at lockstep steps, so every PC has the same events in the same order. Each carries the kind, the factions and the step, never positions or numbers of armies. It reads nothing per-PC into the simulation and feeds no hash.
- Kinds: war declared, peace, vassalage, siege start, assault start, a battle between players starting and ending, pause and unpause with the player who did it.
- The mod's `Notifications` turns them into English lines in the `Chat`, and adds the ones it already gets: assault outcome, terms, invasions, a player leaving or returning. Elimination joins when it's built.
- Only on the PCs involved: deals, gifts, tariffs, view requests.
- The playtest log gets every notification.
- **Who paused (§4):** the engine has to learn who set a pause. The first task of this step is to read how a pause travels in a session. The status text and a chat line show the name.

**Tests:** engine unit tests for the queue; a rig run with 2 humans, war then peace, identical copies and the same notices on both; a repeat of a taken notice changes nothing.

### E. Popups become chat lines (mod)

- Become lines: `MpViewNotice` for other players' assaults, terms and invasions.
- Stay windows: anything with a decision or a button that acts, such as `MpSiegeNotice` (Sally out), `MpDealNotice`, `MpAssaultTerms`, `MpViewAsk`.
- Each remaining `MpViewNotice` is sorted in the PR's list.

### F. Check

- twopc over direct IP: chat both ways, timed against the 2 seconds. Once more during a city view's transfer.
- **GUI analysis (owner, 2026-10-30):** screenshots of the box in every place it works, read for what's wrong (overlap, cut-off text, colours, position), with fixes applied and new screenshots until it's right.
- A session with the owner for the look.

## 3. Draft spec text (spec 0.34)

Applied in this card's PR once approved.

**§12, replacing its first two paragraphs:**

> Players talk through one in-game chat. Every player and spectator reads the same chat, in the same order. There are no private messages.
>
> - **Where it works.** In the lobby and the setup, on the world map and in cities, during views, battles and assaults, and while the game is paused or held.
> - **The chat box.** It starts in the bottom-left corner and can be moved, like the multiplayer panel. A button next to the multiplayer panel's on the top bar collapses and expands it. Players click the box to type; no key opens it. There is no sound and no unread count.
> - **Messages.** A message is at most 200 characters. Each player's name shows in their cursor color. Text uses the game's own font, so a character that a player's language font lacks shows as `?`.
> - **History.** The chat keeps its last 200 lines for the session. It isn't part of the campaign save, so a resumed campaign starts with an empty chat.
> - **Event notifications** appear in the chat. Every player sees a war declared, a peace, a vassalage, a siege's start, an assault's start and outcome, an NPC invasion of a capital, a battle between players starting and ending, a player leaving, returning or being eliminated, and a pause with who paused. Deals, gifts, tariffs and view requests appear only for the players involved. Notifications never give army positions or numbers.
> - **Information without a choice** appears as a notification, not a popup.

**§15, added under Chat and notifications:**

> - Every player sees the same chat lines in the same order. [AT]
> - A message over 200 characters, or with characters the game can't draw, never stops a PC. [AT]
> - The chat works during a battle, an assault and a pause. [PT]
> - Notifications for the players involved don't reach other players. [AT]

**§16, "Things to optimize":**

> - **Chat in every language:** the game's fonts hold one language group each, so a message in another group shows as `?`. Wider support is for after v1.

## 4. Open

- The 2 seconds during a large transfer: if a message waits behind a save, §15 needs an exception for those moments. Measured in step F.
- `MpBattleOutcome` and `MpAssaultOutcome`, the windows for the two sides, have only an OK button but are the main report of a lost battle. They stay windows; other players get a chat line. The owner can reverse this.
