# Transfers in the playtest log (SOS-81)

Card: [SOS-81](https://fighterx2500.atlassian.net/browse/SOS-81). Status: **proposed, waiting for the owner's approval.**

Each PC's playtest log (SOS-63) should show every transfer of goods or money that involves a player: deals, market sales, tariffs, tribute, caravans. Today it shows only a count of transactions in the status line, plus (since SOS-73) the deals, treaties, tariffs and realms between players.

## 1. What exists

- **One place sees every transfer.** In the world part, every change to a player's city goes through the engine's five transaction hooks in `sosdet.Hooks`. Each queues a `Txn` that the city tail applies (ADR 0005):

  | Hook | Game call | What it carries |
  | --- | --- | --- |
  | `buyerReserve` | `PBuyer.addReserve` | goods bought: amount, price, seller, trade type |
  | `buyerDeliver` | `PBuyer.addDeliver` | goods delivered: amount, trade type |
  | `sellerRemove` | `PSeller.remove` | goods sold: amount, price, buyer, trade type |
  | `credits` | `PCredits.inc` | money: amount, reason (`CTYPE`) |
  | `credits` (traded) | `PCredits.inc` with goods | money for goods: amount, reason, goods, count |

- **What this covers:**
  - deal money and goods between players (`CTYPE.DIPLOMACY`, `TRADE_TYPE.diplomacy`);
  - market sales and purchases, with players and with NPCs;
  - the tariff cut (`CTYPE.TRADE`);
  - the tribute (`CTYPE.TRIBUTE`);
  - deliveries by caravan.
- **It is the same on every PC.** The world part runs on every PC, so every PC makes the same transactions at the same steps, with the same ids. Only the owner's PC applies a transaction to its city, but every PC can record it.
- **What the mod sees today:** only `Stats.txnsApplied`. Transactions are pooled and cleared after their step, so nothing is left to read later.

## 2. Design

### 2.1 Engine (SOSDET)

- **A transfer record, `sosdet.txn.TransferLog`:** a ring of the last 4,096 transfers in preallocated primitive arrays, so recording allocates nothing in the step. Each entry holds:
  - the step and the transaction id;
  - the human's slot;
  - the kind: bought, delivered, sold, money, or money for goods;
  - the goods (tradable index, or -1) and the amount (count, or denari);
  - the price, where there is one;
  - the reason (the `CTYPE` or `TRADE_TYPE` ordinal);
  - the other faction's index (the seller or buyer), or -1.
- **Recording:** the five hooks add an entry right after they queue their transaction. It is world-part code: it reads only what the transaction carries and draws nothing.
- **Not world state:** the record isn't saved, isn't in the hash and doesn't reach the simulation. A load or resync clears it, and the log notes that.
- **API:** `Engine.transfers(long afterSeq, List<Transfer> out)` fills `out` with the entries after sequence number `afterSeq` and returns the latest one. `Transfer` is a small immutable value object. This is called between steps only, so allocating there is fine. If the ring wrapped past `afterSeq`, the mod is told how many were lost.
- **Timing:** recording is a few array writes per transaction. A long market run measures the step time before and after (the timing rule, requirements §4).

### 2.2 Mod (SOSGSGMP)

- **`MpTransferLog`, read from `MpLog` twice a second, like `MpDiplomacyLog`:**
  - It writes one line per transfer between players (decision 1): step, who, what, how much, the price, the reason and the other side. For example: `step 18,240 Host sold 40 Furniture to Joiner at 31 each (tariff 20%: 248 to Joiner's treasury)`.
  - It writes everything else as a summary once a game day: NPC market sales and purchases, income and costs by reason.
  - It writes a line when transfers were lost to the ring wrapping.
- **Names:** player names by slot, as in `MpDiplomacyLog`; NPC factions by name; goods by name; reasons as words.

## 3. Checks

- **Unit:** the ring keeps order, wraps, counts lost entries and returns entries after a given sequence number. Plain Java, no game classes.
- **Rig, 3 humans, Mogus-72** (the market run of plan 0010: `--make-humans 2,24`, about 45,000 steps, so trade happens):
  - a deal with goods and money;
  - a market sale between two players with a tariff;
  - the `transfers` probe on every copy.

  Every copy identical, and every copy's record the same. The step time is compared with the C77K run.
- **twopc:** a short run with `--deal`. Both PCs' logs show the same transfer lines.
- **Owner:** a playtest with the logs collected.

Order: the SOSDET PR first, then the SOSGSGMP PR. Both PCs need the new engine.

## 4. Decisions for the owner

1. **How much to log:**
   - **(a) recommended:** one line for each transfer between players, and for each deal or tribute payment; everything else (trade with NPCs, other income and costs) as a summary per game day.
   - **(b)** one line for every transfer, NPC trade included. This is complete, but a busy city writes hundreds of lines a day.
2. **Whose transfers:**
   - **(a) recommended:** every player's, on every PC. The two logs then match line for line, which helps when comparing them after a desync.
   - **(b)** only this PC's own player's.
