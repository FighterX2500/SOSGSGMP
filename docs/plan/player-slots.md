# Player slots: what the mod needs from SOS-46

Card: [SOS-48](https://fighterx2500.atlassian.net/browse/SOS-48) · Date: 2026-10-25 · For: SOSDET [SOS-46](https://fighterx2500.atlassian.net/browse/SOS-46) (several human factions on the world map), single-player assumption SP3 ([single-player-assumptions.md](single-player-assumptions.md))

## 1. The engine's direction (SOS-46, 2026-10-25)

- **A Player faction per human.** Each human gets the vanilla `Player` faction type, in a fixed slot that's the same on every PC.
- **Outside the world part,** on each PC, "the player" means that PC's own human. The about 620 city and UI references keep working untouched.
- **Inside the world part,** the engine sets a "current human" the same way on every PC. Code that concerns one human runs once per human, in faction order. That's how each human gets their own events, raids and NPC opinion as if alone (SP1, SP2).
- **"Is this the player?"** checks across all factions become "is this any human?": about 150 places in the world code.

## 1a. What SOS-46 built (SOSDET [PR #31](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/pull/31), merged 2026-10-25)

SOSDET [ADR 0004](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/docs/decisions/0004-several-humans.md) records the design. Against the requirements in section 3:

- **Met:**
  - R1: up to 16 humans (slot 0 and 15 more).
  - R3: `Engine.localHuman(slot)` and `Engine.pcHuman(pc, slot)`, set at step 0 on every PC.
  - R4: city reports and transactions per human. World commands act for the human of the PC that sent them. The world reads each human's values from that human's report.
  - R11: each NPC keeps opinion, support, requests, tribute and war plans per human.
  - R12: diplomacy events, the war and rebellion timers, and the current raid per human.
- **Partly met:**
  - R13: the world save holds every human and their world state, and an old single-player save loads as one human in slot 0. There are no live, frozen, eliminated or removed states yet.
  - R12: a frozen slot's events don't wait, since there are no frozen slots (R5).
- **Open:**
  - R2: ADR 0004 plans `addHuman(region)` (an NPC's capital or a free region becomes a human during setup). It wasn't built as such: SOSDET SOS-55 built `Engine.buildWorld` instead, which makes every human's realm while the world is generated (SOSDET PR #35).
  - R5 to R10: freeze, eliminate, remove, spectator, running another human's city, agreements between humans.
  - R14: each human's battles against NPCs work. Battles between humans, and allied humans in one battle, are outside SOS-46.

## 2. What the mod owns

- **Who sits in which slot.** The lobby hands out slots once, when the host initializes the world (spec §5). The mod keeps the map from a player's identity (Steam ID, or a player key for direct IP) to a slot index, and saves it in the campaign save.
- **Telling the engine which slot is local** on each PC, before the world is created or loaded.
- **Stamping** every command and city report it carries with the slot index (ADR 0005 already has reports carry their faction).
- **The campaign rules** that decide when a slot is frozen, eliminated or removed (spec §6 to §9, §11). The engine applies them in the world part; the mod decides and sends the command.

The mod never needs to know which `FACTIONS` index a slot uses, only that it is stable.

## 3. Requirements for the engine

Each item names the spec rule it comes from. "At a step" means a lockstep command applied at the step the host confirmed, the same on every PC.

| # | Requirement | Spec |
| --- | --- | --- |
| R1 | **Up to 16 human slots** in one world, next to the NPC factions. The lobby's player count (2–16) never exceeds what the engine supports. Human slots come out of vanilla's 64, so NPC slots are 64 minus the players (Q3). | §3, §5 |
| R2 | **Slots are created once, when the host initializes the world**, from the lobby's list: each human's race, banner and name, and their picked world location. The terrain already exists by then: the host generated and confirmed it right after Start Lobby, and every player picked their race, faction and location on it (spec 0.13). NPC countries and routes are then generated around them (6-tile minimum distance, route guaranteed at 6–15 tiles). Each human then picks a city start spot. No slot is ever added later, since nobody joins a running campaign. | §5, §11 |
| R3 | **Local slot per PC.** An API call sets which slot is "the player" for the city part and the UI on this PC. It is never read inside the world part, so it can't desync the world (AGENTS.md "Determinism"). | §1 |
| R4 | **Per-slot inputs.** City reports, transactions and world-map commands are each tied to a slot, and the world part reads each human's values only from that human's report (ADR 0005). | §1, §7 |
| R5 | **A frozen slot,** set and cleared at a step: the city sends no new reports, and the world keeps reading its last one. Its armies freeze and become invulnerable and don't block roads. Its territory can't be attacked and other armies pass freely. Caravans to or from its city are deleted with their goods. Events and raids aimed at it wait until it is live again, a raider already on its way included (owner, 2026-10-25). Used for a disconnected player and for a player missing at resume. | §11 |
| R6 | **Eliminate, at a step.** The human's capital and remaining regions become an NPC city owned by a neutral faction (after the 90% take, which the mod's §9 rules already decide). The slot stays in the world, has no city or report, and is skipped by "is this any human?" checks and the per-human loop. | §5, §9, §11 |
| R7 | **Remove a player, at a step.** Same as R6, without the 90% take. The slot can never become live again. | §11 |
| R8 | **No local city (spectator).** A PC whose slot was eliminated or removed runs the world with no own city part. The city and UI code that reads "the player" must still work there, either through a city-less local slot or a "no local human" mode. The engine chooses. Later, not v1: giving a region to a spectator so they start a new city from scratch (owner, 2026-10-25); a city-less slot that can become live again keeps that open. | §11 |
| R9 | **Running another human's city on this PC.** Vanilla's city code reads the city's owner (treasury, tech, laws, race, boosts, the top bar) from "the player", so while this PC runs another slot's city, "the player" for the city part and the UI is that slot. It switches back when the city is closed. Two cases: **a view** (ADR 0006): viewer A freezes their city and loads owner B's city, read-only. Only A's PC switches; the save, B's actions and the city hash go straight between A and B, and the host and other PCs take no part (owner, 2026-10-25). A still runs the world part in lockstep, so a view must never make A hold up the world: A's PC is left out of the slowest-PC speed cap while viewing, and if it can't keep up, A's view ends. **A siege assault** on a human's capital: every PC loads the defender's city and switches at the same step. | §9, §10 |
| R10 | **Agreements between two human slots,** applied at a step: vanilla's player–NPC agreements except Unite, with regions other than capitals giftable; vassalage with vanilla tribute, shared wars and the right to rebel (the sovereign doesn't join a war its vassal starts); free passage between humans at peace; breaking a treaty at any time without penalty. Emissaries against another human: Gather support only, in that human's regions. Vanilla's deal code has one player side and one NPC side (hook-surface F7). The mod draws its own deal screen, handles proposals and their 30-day expiry, and sends the agreed treaty as a command; the engine applies it to two `Player`-type factions. | §6 |
| R11 | **NPC relations per human.** Each NPC keeps opinion, stance history, war plans and tribute per human. Breaking a treaty with another human doesn't change NPC opinion. | §6, SP1 |
| R12 | **Events and raids per human,** each with their own timers and raiders, as if alone. A frozen slot's timers and raiders wait until it is live again (R5); an eliminated or removed slot gets none. | §11, SP2 |
| R13 | **Saves.** The world save holds every human slot and its state (live, frozen, eliminated, removed). It loads with any player as host, and on any PC as its local slot. The mod's slot map goes in its own save section. | §11 |
| R14 | **Battle sides.** In a field battle or assault, each side's faction is the real human's slot or the NPC's (hook-surface B6). Allied humans spawn on the same side, and each commands only their own divisions through the engine's commanders table (SOS-15); the owner has tested this and it works (SP4, 2026-10-25). | §8 |

## 4. Open questions

- **Q1. A frozen human's events. Decided (owner, 2026-10-25):** they wait until the human is back, and so does a raider already on its way. In spec §11 and §15, R5 and R12.
- **Q2. SP4: allied humans in one battle. Decided (owner, 2026-10-25):** their armies spawn on the same side, and each human commands their own divisions. Tested by the owner. In spec §8 and R14.
- **Q3. How many NPC factions, with several humans? Decided (owner, 2026-10-25): A, keep the 64 slots** (spec §5). A campaign rule, so the spec decides it; the engine builds it. Vanilla has a fixed 64 faction slots, 1 `Player` and 63 NPCs (`FACTIONS.MAX`). World generation gives the player a ring of 9 small NPC kingdoms nearby (`world/region/Gen.generatePlayer`: 2, 3 and 4 of growing size), then creates NPC kingdoms until 3/4 of the regions are owned or the 63 NPC slots run out (`generateKingdoms`). New factions emerge later only while fewer than 48 are active (`EventFactionPopup`). So every human slot taken from the 64 is one NPC fewer, and a ring of 9 per human "as if alone" fills all 63 at 7 humans. Options:
  - **A. Keep the 64 slots.** Humans take slots from the NPCs: 61 NPCs with 3 players, 48 with 16. Above about 6 players the rings shrink and big maps get fewer NPC kingdoms. No change to the save layout. Chosen; 2–3 players are expected (spec §3).
  - **B (rejected). Raise the cap so NPCs stay at 63** (up to 79 slots with 16 humans). Engine work in `FACTIONS`, its save layout, and every array sized from `FACTIONS.MAX()`.

## 5. What changes in the mod's docs

- [hook-surface.md](hook-surface.md): F1–F5 and F8–F10 were written for the puppet model and move to the engine with SOS-46. F6 and F7 stay with the mod (treaty commands and the deal screen).
- [single-player-assumptions.md](single-player-assumptions.md): SP3 records the engine's direction and points here.
