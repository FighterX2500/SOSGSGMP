# Goods and tariffs between players (SOS-77)

Card: [SOS-77](https://fighterx2500.atlassian.net/browse/SOS-77). Spec §7 Trade, §6 Diplomacy. Builds on SOS-76 (the player's faction window, still in review). Status: approved (owner, 2026-10-27), with the answers in section 6.

## 1. What vanilla does

- **Who trades with whom.** Every day `TradeManager` runs a round per faction, and `TradeSorter` picks the deals.
  - A faction's partners are the capitals its path search reaches (`RD.DIST().tradePartners`). For a player, that is only factions with a trade stance (`DIP.trades`).
  - **The player sells** in an auction (`sellPlayer`): each partner bids its price for each good, less the fees, and the best bid above the player's minimum export price wins.
  - **The player buys** (`buy`): it takes the cheapest seller, at the seller's price plus the fees, while the price is under the player's import cap.
- **The fees** (`TradeManager.totalFee`), per item:
  - **Toll:** (20 + capital-to-capital path distance) × a toll per tile, lowered by the player's proximity-toll boost (roads).
  - **Tariff:** only when the player sells to an NPC. It's the NPC's stance tariff times its price:
    | Stance | Tariff |
    | --- | --- |
    | Neutral | 25% |
    | Trade partners | 20% |
    | Colleagues | 15% |
    | Allies, vassal, overlord | 10% |

    On top comes a volume surcharge for big sales, capped at 90%. The tariff money goes to no one: it only lowers what the player gets.
- **Money and goods.**
  - The buyer pays and the seller is paid at once (`addReserve`, `remove`), each booked as `CTYPE.TRADE`.
  - The goods travel by caravan from the seller's capital to the buyer's.
- **The Goods tab** of an NPC shows Rate, Stored, Traded and Price. SOS-76 already took Rate, Purchasing Power and the traded bar off a player's tab.

## 2. What SOSDET does today

- Each human's trade round runs as that human, with its reads from its city report and its money and goods through the city's two-phase ledger (SOS-46, ADR 0005).
- **Humans never trade with each other in the market.** Two SOSDET guards stop it:
  - `TradeSorter.sellPriceItemPlayer` returns no bid for a buyer that isn't an NPC;
  - `buy` skips every human seller.
- Also, `TradeManager.tarif` returns 0 between two humans.
- So spec §7's shared market isn't built yet, and a tariff would have nothing to tax. **This card builds both.** It's mostly engine work: every file involved (`TradeSorter`, `TradeManager`, `DIP`) is a SOSDET patch, and the mod never patches the same file.

## 3. Proposal

### 3.1 The shared market between players (SOSDET)

- **Who:** two humans with a trade stance (trade partners, colleagues or allies) become trade partners, as a player and an NPC do. The path search between them already finds them.
- **How:** a human's purchase round may buy from another human, as from an NPC.
  - **The price** is the seller's minimum export price (their own setting in the export room), plus the toll, as vanilla prices an NPC's goods.
  - **The amount** is capped by the seller's stock for sale, as their last report shows it.
  - Each human's goods are bought only in the other's purchase round, never also sold in their own auction to that human, so one deal can't happen twice.
- **Money and goods** move as a deal's do (SOS-72):
  - the seller's goods leave their warehouse, and a caravan carries them to the buyer's capital;
  - the money moves through each city's ledger at the step.
  - A report up to 30 steps old can't create goods: the city gives what it has, as ADR 0005 rules. **The engine's open question:** when the seller's city has less than its report said, the caravan should carry, and the buyer pay for, only what was taken. I'll check how today's NPC sales settle a short delivery and use the same rule. If none exists, the deal is cut to the amount taken, in the seller's next report.
- **Determinism:** the rounds run in the world part, in lockstep, on every PC; the market's state is world state, in the save and the hash.

### 3.2 Set Tariff (SOSDET + the mod)

- **What it is:** a player's own tariff on what another player sells them, 0% to 80%, in steps of 5%.
- **Where the money goes** (recommended, question 1):
  - the buyer's tariff on the seller is cut from the sale price;
  - the seller gets the price less the tariff;
  - the buyer's treasury gets the tariff back as tariff revenue.

  That's vanilla's NPC tariff (it lowers what the seller gets), except that the money reaches the buyer's treasury instead of vanishing. A high tariff makes your imports from that player cheaper, and their goods go elsewhere: their auction takes the best net bid, and an NPC's may beat yours.
- **The default when a trade stance starts** (owner, question 2): 20%, for every trade stance. Between players, Become Colleagues and Alliance don't lower it, unlike vanilla's NPC tariffs. Spec §7's "the same discount as in vanilla" changes to this.
- **Setting it:**
  - a new world command (8103, `humanTariff`) applies at once, with no answer: it's one's own policy (question 3);
  - it's refused without a trade stance, and outside 0–80%.
  - The tariff is world state, kept per ordered pair of players: in the save (a versioned section), in the world hash, and the same on every PC.
  - It stays stored when the trade stance ends, and applies again if trade resumes.
- **In the window (mod):** a "Set Tariff" row with the denarii icon in the Diplomacy list, between Trade and Become Colleagues.
  - It's greyed out without a trade stance, with the reason.
  - Its popup has vanilla's slider (0–80%) and Apply.
  - The row shows your tariff on them and theirs on you.

### 3.3 Distance

- As vanilla: the toll uses the path distance from capital to capital (`tradePartners`), lowered by the buyer's or seller's roads, exactly as for an NPC.
- The window's header and Goods tab show the distance; the toll per item shows in the price hover.

### 3.4 Economy & Trade (mod)

- **Players in the trade-partner list** (`view/ui/economy/Factions.java`, hook M7, about 10 lines, like M4). A player's row shows:
  - your tariff on them;
  - the toll;
  - this year's tariff revenue from them.

  It leaves out the NPC-only columns: their opinion tariff and riches. The row's hover is SOS-76's player box; a click opens the player's window.
- **Tariff revenue** shows on that row, and as a total line above the list. The engine keeps the revenue per pair for the current and the last year, read by `Engine.tariffRevenue(slot)`.
  - It's booked in the treasury as `CTYPE.TRADE`. A new money category would change vanilla's enum, which the saves use.

### 3.5 The Goods tab (mod)

- Next to Stored, a **Price** column, as vanilla's:
  - what you'd pay for one item: their export price + toll;
  - in the hover, what they'd pay you: their import price, less the toll and your tariff (it's cut from their side). Both come from their report and the engine's fee rules.
- **Not in this card:** the goods traders list in the goods views (`UIGoodsTraders`). It could list players with one more hook. I'd leave that to a later card unless you want it here.

## 4. Hooks and engine changes

- **SOSDET:**
  - `TradeSorter`: buy from another human; no auction bid between humans. These are changes to SOSDET's own patch 15.
  - `TradeManager.tarif`: between humans, the buyer's set tariff.
  - Settling the tariff: the cut goes to the buyer's ledger.
  - The tariff book: save, load, hash and the command.
  - `Engine.tariff`, `tariffRefusal`, `setTariff`, `tariffRevenue`, `reportedPrice`.
- **The mod:**
  - the Set Tariff row and popup;
  - the Price column;
  - hook M7 on `view/ui/economy/Factions.java`. M6 is SOS-79's, and SOSDET doesn't patch this file.
- **Spec §7 (0.21, or 0.22 after SOS-79's), updated in the same PR:**
  - the shared market between players with a trade stance;
  - Set Tariff and where its money goes;
  - the default;
  - two new criteria.

## 5. Checks

- **Unit tests:**
  - the tariff book: set, refuse, save → load round trip, and a save without the section;
  - the fee rule: the seller gets the price less the tariff, and the buyer gets the tariff back, so no money is made or lost apart from the toll;
  - the mod's tariff popup model, 0–80 in steps of 5.
- **Rig, 3 humans:**
  - two with a trade stance, one exporting a good the other imports: they trade, a caravan goes, and every copy stays identical;
  - then a tariff of 50%: the seller's revenue drops and the buyer's tariff revenue grows, on every copy.
- **Two PCs (twopc):** the autopilot sets trade, an export and an import, then a tariff; both PCs agree, through a save and a resume.
- **By hand, your playtest:**
  - the Set Tariff row and popup;
  - the Economy & Trade rows;
  - the Price column;
  - a real trade between your two cities.

## 6. Questions for you

1. **Where does the tariff money go?** Recommended: the buyer's tariff is cut from what the seller gets, and paid to the buyer's treasury. The alternative is that the buyer pays it on top of the price, to their own treasury. That only makes their imports dearer, which means no real revenue, since a player is both the importer and the treasury.
2. **The default tariff when trade starts?** Recommended: vanilla's stance tariff (20% / 15% / 10%), so spec §7's "vanilla discount" holds until a player changes it. The alternative is 0%.
3. **Set Tariff applies at once, with no answer?** Recommended: yes, as Cancel All Agreements. It's a player's own policy, and the other player sees it in your window and on their Economy & Trade row.

**The owner's answers (2026-10-27):**
1. As recommended: the buyer's tariff is cut from what the seller gets, and paid to the buyer's treasury.
2. The default tariff is 20%. It stays 20% for colleagues and allies when the other side is a player.
3. Yes: Set Tariff applies at once, with no answer.

## 7. As built

- **Engine (SOSDET, plan 0010):** the market between humans in patch 15, the tariff book (`TariffBook`, saved as `SOSDET_TARIFFS` and in hash part 7), command 8103, and `Engine.tariff*` and `reportedPrice`.
- **The mod:**
  - `PlayerDeals`: `inSession`, `tariffRefusal`, `setTariff`, `tariff`, `tariffRevenue`, `price`, all through the engine.
  - `TariffSteps` (plain Java, tested): the slider's 17 steps, 0% to 80%.
  - `MpPlayerDiplomacy`: the Set Tariff row after Trade, with the denarii icon and the current percent. It's greyed out with the engine's reason. Its popup has the slider, Apply, and their tariff on you.
  - `MpPlayerWindow`, Goods tab: a Price column (their export price plus the toll). The row hover shows the buy price, the sell price and their tariff on you.
  - `MpPlayers` and hook M7 (`view/ui/economy/Factions.java`):
    - the other players with a trade agreement join Economy & Trade's partner list, each with the tariffs and the toll;
    - the row's hover shows the tariff revenue this year and last;
    - a click opens the player's window;
    - a line above the list shows this year's tariff revenue from all players.
  - Autopilot `tariff=<step>` and twopc `--tariff`: the joiner sets 50% on the host, and every PC reports it.
- **Not built:** the goods traders list in the goods views (`UIGoodsTraders`), as the plan left it to a later card. Tariff revenue is booked as `CTYPE.TRADE`, so the treasury's own history shows it inside trade.

## 8. Results

- **Builds:** SOSDET `build` and the mod's `build` pass, every test included (the new `TariffBookTest` and `TariffStepsTest`).
- **Rig (SOSDET plan 0010):**
  - C77B: a 50% tariff applies at once on every copy.
  - **C77K, pass:** humans 0 and 2 trade furniture over 1.5 game days. The buyer's tariff revenue grows at 20% and then faster at 50%, and the seller's income falls. Every copy is identical to step 45,000.
  - One finding: as in vanilla, a player trades only with capitals it can reach, never across unclaimed land. The rig's default humans in Mogus-72 reach no one, so nothing traded until the run picked bordering NPCs.
- **Two PCs:**
  - D77A2 pass: the deal, then the joiner's 50% tariff at step 900; both PCs see it at step 949, and the campaign is saved.
  - D77B pass: the resume; both PCs see the 50% tariff at once after the load.
  - The twopc world's new cities have no goods, so the two-PC runs check the tariff and its save, not a sale.
- **Not checked, your playtest:** the Set Tariff row and popup, the Price column, Economy & Trade's player rows and total line, and a real sale between two cities that border.
