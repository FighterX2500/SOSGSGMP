# SOS-101: why the export depots never traded between the players

Investigation, 2026-10-30. Verdict: **as designed** (vanilla's rule, spec §7), not a bug. The game doesn't show why, though; that part is a UI gap, recommended for SOS-100.

## What the playtest shows

- The host's playtest logs from 2026-10-29/30 hold 403 transfers. All the market trades (`Trade`) were with two NPC realms, Hayven and Vorkia: 82 sales to Hayven, 66 purchases from Hayven, 70 from Vorkia, 41 sales to Vorkia.
- No market trade ran between the two players. Everything that passed between them went through diplomacy deals (`Diplomacy` transfers: Bread, Stone, Wood, Clothes).
- The two were allied the whole time (tariffs 0%/20%, then 0%/0%), and each held one region, its capital.

## The rule

- A human's market purchases buy from the partners its traders reach (`TradeShipper.init` → `RDDistance.tradePartners`).
- For a player, those are the capitals found by `WRegFinder` with `Treaty.FACTION_REACHABLE`, among factions with a trade stance (`selTrade`: `DIP.get(..).trades`).
- `FACTION_REACHABLE` lets traders cross:
  - the player's own regions;
  - the destination faction's regions;
  - regions of a faction it has transit with;
  - tiles outside any region.

  It stops at any region with no faction: unclaimed land.
- Spec §7 (Shared market) says the same: "a trade partner must be reachable: across bordering land, or through a neighbour's land with transit, never across unclaimed land."

## The check on the campaign save

Rig run R101C on campaign record 32 (`MP FighterX2500s campaign-1a18951782a-…-45d.save`), resumed with both humans, probe `wtrade`:

```
slot 0 Amogus capital Amogus regions 1; with slot 1 ALLIED (trades); reaches Vorkia@30 Hayven@40
slot 1 Sugoma capital Sugoma regions 1; with slot 0 ALLIED (trades); reaches Hayven@51
```

Both stances allow trade, but neither player's traders reach the other's capital: unclaimed regions lie between them. Hayven and Vorkia are reachable, which matches the logs.

## Recommendation (applied as the owner asked: "do as recommended")

- **No change to the rule.** It is vanilla's and in the spec. Claiming the land between the capitals opens the route: settling or conquering a region next to the other player's land, or a neighbour's land with transit.
- **Tell the players why.** The Diplomacy trade view shows no hint that the partner can't be reached. Recommended for SOS-100, the trade GUI revamp: a line like "Your traders can't reach X: unclaimed land lies between your capitals." This was added to SOS-100 as a comment, not built.
