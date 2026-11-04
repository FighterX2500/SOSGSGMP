# Trade in the faction lists, the depots and the Goods tab (SOS-134, SOS-135, SOS-136)

From the playtest of 2026-11-04: the host's faction list darkened both other players, though the market traded with
them; the Export Depot listed no player; and the owner asked for each player's Goods tab to show what was traded with
them. Owner's approval of this plan: 2026-11-04. One branch per repository (`batch-5-trade-ui`), one commit per card,
one shared test cycle (spec §7).

## SOS-134: players darkened though trade reaches them

- **Cause:** the faction list (hook M4) and Economy & Trade (M7) darken a row when `RD.DIST().reachable(f)` is false.
  That table uses vanilla's rule, which crosses a third faction's land only with a vanilla transit treaty. Trade between
  players uses the engine's rule (SOS-73): it also crosses the land of a player at peace, and reaches a player 6 to 15
  tiles away by the guaranteed route (SOS-120).
- **Fix:** SOSDET keeps, per human, which humans its trade reaches, filled by the world part with the trade rule
  (patch 98, `Engine.tradeReaches`). `MpPlayers.reachable(f)` asks it for a player and vanilla for an NPC; M4 sorts and
  darkens by it, M7 darkens by it. The world's own reach table is unchanged: raids and NPC choices read it.

## SOS-135: traded goods in a player's Goods tab

- SOSDET counts every market sale between two players per seller, buyer and item: the items and the denari that reached
  the seller (the price less the buyer's tariff), this year, last year and all time. It is world state: saved in its own
  section (older saves load with empty figures) and in the world hash. Deals made in the diplomacy window don't count.
- The Goods tab gets two columns, **Exported** (what this player sold them) and **Imported** (what this player bought
  from them), all time. The row's hover adds a small table: this year, last year and all time, each as items @ the
  average price.

## SOS-136: players in the depots' partner lists (hook M12)

- `UIGoodsTraders` holds any faction; the Export and Import depots price a player as their Goods tab does: in the Export
  depot their highest import price, in the Import depot their lowest export price plus the toll. The list holds the
  other players trade reaches (SOS-134's rule), highlighted when there is a trade agreement, as an NPC is. A click
  opens the player's window on its Diplomacy tab.
- **Best** and **Closest** stay NPC-only: they open vanilla's sale screen for an NPC, which has no player version.

## Tests

- Unit: SOSDET's `TradeTallyTest` (the periods and the year's turn, both directions, a removed human, the round trip,
  figures past an int, broken saves).
- Rig B5M (SOSDET, 3 humans, 45,000 steps, Mogus-72): a trade treaty between humans 0 and 2, human 2 exports furniture;
  the `deals` probe on every copy shows the reach and the traded figures, before and after a world resync at step
  36,000 (the save and load of the world sections). Every copy identical.
- In-game (not automatic): the faction list's and Economy & Trade's player rows not darkened, both depots listing the
  other players, the Goods tab's columns and hover.
