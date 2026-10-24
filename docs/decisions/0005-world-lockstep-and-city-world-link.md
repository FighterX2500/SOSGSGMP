# 0005: World lockstep and the city↔world link

Status: Accepted (owner decision, 2026-10-23); city viewing amended by [ADR 0006](0006-lockstep-city-viewing-and-assaults.md) (lockstep, not a snapshot); several human factions moved to the engine (2026-10-25, see the end) · Card: [SOS-38](https://fighterx2500.atlassian.net/browse/SOS-38) (SOSDET plan 0002, stage E)

## Context

- **What the docs say now.** `docs/architecture.md` has the host simulate the world map while clients mirror it, and each city run only on its owner's PC. The spec asks for zero latency inside your own city (§1, §13), a speed capped by the slowest PC (§4), and world desyncs detected within 2 s and recovered within 10 s (§13).
- **The owner's decision on 2026-10-23** (SOSDET [plan 0002](https://github.com/FighterX2500/Songs-of-Syx-Deterministic-Engine/blob/main/docs/plan/0002-city-and-world-lockstep.md)): the world map runs in lockstep on every PC, with the host's save for recovery. The engine has done this since SOS-35.
- **What V0.1 does.** SOSDET V0.1 is co-op. Every PC runs the same city and the same world, all in lockstep, and every player action is a command applied 30 steps later. That proves the world is deterministic. It is not the multiplayer game: there each player has their own city, and no other PC runs it.
- **The world reads the player's city directly.** It does so about 100 times, through a few chokepoints:
  - `Player.updateSpecial`;
  - `Player.res`, `buyer`, `seller`, `credits` and `offensivePower`;
  - `BOOSTABLES.*.get(player)`;
  - `SETT.INVADOR` and `SETT.ENTRY`.

  It also writes to the city in about 8 ways: deliveries, credits, invasions and raid demands, immigrants, returning soldiers, garrison casualties, supply withdrawals, and event triggers. The city also writes into the world: soldiers joining armies, army supply from depots, the capitol's population, invasion results.
- **The problem.** Once each PC runs a different city, a world that reads the live city gives a different world on every PC.

## Decision

**World lockstep.**
- Every PC runs the world map in lockstep: the same steps, the same seed, the same commands, and the host's AI log.
- The world's state hash is compared between PCs. On a mismatch, the PC reloads the host's world state.
- This replaces the "World map" row of `docs/architecture.md`, where the host simulates and clients mirror.

**The city↔world link:**

1. **One step, two parts.**
   - Each PC's step runs its own city part first, then the world part. Each part keeps vanilla's order among its own resources. Vanilla interleaves city and world work (tourism, nobles, the city events and the player's own update run after the world today), so the split reorders the step; in co-op mode every PC runs the same split.
   - **The city part is local.** No other PC runs it, and its state is never compared. A player's actions in their own city apply at once, with zero latency, as in vanilla. They are not lockstep commands.
   - **The world part is lockstep.** It reseeds the simulation stream at its start from (seed, step, world), so the city's draws on this PC never shift the world's draws.
   - `EVENTS`, which mixes city and world events today, is split between the two parts.
2. **City → world: city reports.**
   - The world part never reads a live city. At each chokepoint it reads that city's last applied report instead, and this holds for the PC's own city too.
   - Every 30 steps, each city publishes a report of the values the world reads: about 10 kinds (population by race, tradable stock and trade settings, credits, garrison and invasion power, 3 laws per race, the boosts the world reads, tech, level, titles). The capitol's wall count is only drawn, so it is not in the report.
   - A report is stamped to apply at a world step 30 steps ahead (the AI log's delay). Every PC, the owner's included, applies it at exactly that step.
   - Reports have a hand-written codec with a protocol version, and carry the city's faction.
   - Anything else the city part writes into world state also travels in the report, as an input applied at its stamped step. Player actions on the world map stay lockstep commands, as in V0.1. Stage E's first task is a scan for these writes.
3. **World → city: transactions.**
   - Every PC computes the same world, so each PC already produces every world change to every city, and no message has to cross the network for them.
   - Each change becomes a transaction with an ID made from its world step and a sequence number. Each PC applies only its own city's transactions, at the start of its next city part, exactly once.
   - **Withdrawals take two phases:** goods taken by trade, supply taken when a division is sent out, money. Army supply from depots and joining soldiers flows the other way, city to world, and travels in the report. The world only asks; the city applies as much as it has. The amount actually taken goes back in the city's next report, and the world counts only that amount. That way a report that is up to 30 steps old can never create or destroy goods.
4. **Pace.**
   - A PC runs world step N only once every report stamped for N has arrived.
   - So the slowest city sets the speed for everyone (spec §4), and no city runs ahead of world time.
   - The own city never waits on the network for anything except that.
5. **Saves and resync.**
   - The world and each city save and load separately. A campaign save is the world save plus every city save (`docs/architecture.md`, "Saves").
   - A world resync reloads only the world, and the city goes on.
   - Transaction IDs make sure a reloaded world never applies a transaction twice.
   - This needs a spike: loading only the world's resources from a vanilla save.
6. **Engine API for the mod.**
   - The engine gives out this PC's city reports.
   - It takes other cities' reports, each with its faction and step.
   - It runs the world part plus the own city part.
   - Several human factions in one world: engine work since 2026-10-25 ([SOS-46](https://fighterx2500.atlassian.net/browse/SOS-46), see the amendment); until then the mod's (plan 0002, decision 4).
7. **The co-op mode stays.** V0.1's co-op mode, with every PC running the same city in lockstep, remains a supported mode. It is the engine's test mode: it checks the city part and the world part against each other on every PC.

## Options rejected

- **Host-authoritative world mirror** (`docs/architecture.md` today): only the host runs the world, and clients apply state deltas. Rejected by the owner's decision for lockstep. Lockstep runs vanilla's world logic unchanged on every PC, sends commands instead of state, and reuses the engine's proven step, hash and resync.
- **Every city on every PC** (full lockstep, as in co-op): too heavy for 2–16 cities, and your own city could no longer run with zero latency.
- **Live city reads, checked by the hash:** the world would split as soon as two PCs run different cities.
- **Reports as deltas on change:** smaller messages, but a missed delta corrupts the world for good. A full report every 30 steps is a few KB. Deltas can come later if traffic needs them.

## Consequences

- **Docs.** `docs/architecture.md`: the World map row, the Clocks section (one step counter for city and world on each PC), and the ledger row (transactions are produced on every PC, not only on the host). The spec's rules don't change. §13's world sync targets now apply to the world part's hash.
- **Latency.** The world sees each city about 0.5 s late at 1x (30 steps of 1/60 s), and about 1.9 s late above 1x (steps of 1/16 s). Trade, AI reactions to a city's power, and invasions all run on that report.
- **Determinism rules** (SOSDET) gain:
  - nothing in the world part reads a live city;
  - city draws never reach the world stream;
  - the transaction IDs are the only state shared between the two parts.
- **Work** is SOSDET's stage E card (SOS-38). Its scan is SOSDET `docs/plan/notes/city-world-link-scan.md`. SOS-38 builds the whole link, invasions of a player's own capitol and scripted events included (owner, 2026-10-23). The mod's work follows:
  - carrying reports over Steam;
  - mapping each player to their human faction (the factions themselves are engine work, [SOS-46](https://fighterx2500.atlassian.net/browse/SOS-46));
  - city viewing, which stays a snapshot (spec §10) and is not part of this ADR.

## Amendment (2026-10-25, owner, SOS-44)

- **Several human factions move to the engine** ([SOS-46](https://fighterx2500.atlassian.net/browse/SOS-46)). The world part is lockstep, and every place where the world treats the player specially (about 343 references, plan 0002) runs inside the step, so it follows SOSDET's determinism rules and belongs with the engine's world patches. If the mod did it, its patches would sit on top of the engine's in the same classes.
- **The engine** holds every human faction in the world, the same on every PC, reads each one's values from its own city report, and keeps NPC relations, events and raids per human (SP1, SP2). An engine ADR chooses how the humans are represented (single-player-assumptions SP3).
- **The mod** maps players to those factions, carries the reports and commands between PCs, and applies the campaign rules (spec §6 to §9, §11).
- **Rejected:** keeping it in the mod, as plan 0002 decision 4 had it.
