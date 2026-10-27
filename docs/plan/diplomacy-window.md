# Diplomacy with another player in a vanilla-style window (SOS-76)

Card: [SOS-76](https://fighterx2500.atlassian.net/browse/SOS-76). Spec §6 Diplomacy, §12 popups. Follows SOS-72 (deals between players), whose engine commands stay; this card replaces its UI. Related cards: SOS-77 (goods and tariffs, the same window), SOS-78 (banner and name), SOS-79 (Save Campaign). Status: approved (owner, 2026-10-27), with the answers in section 6; built and in review (section 8).

## 1. What vanilla has

- **One window, NPC only.** Vanilla's faction window is `UIFaction`, opened through `UIFactions.open(FactionNPC)`. It has a banner header and the tabs Court, Realm, Goods, Boosts and Diplomacy.
  - Every part except Boosts takes a `GETTER<FactionNPC>`: `Banner`, `Court`, `Realm`, `Goods`, `UIDiplomacy`.
  - The diplomacy tab's model, `Deal`, always has an NPC side, which prices the terms and judges them by opinion. That's where Value, Opinion and Betrayal come from.
- **Clicking a player does nothing today.** Every place that opens the window guards with `instanceof FactionNPC`. That's the world map click (`ToolDefault.click`), the region panel's faction button (`region/Other`), the faction list (`UIFactionList`), Economy & Trade (`economy/Factions`) and the goods traders (`UIGoodsTraders`). So it doesn't crash.
- **The hover box** comes from `UIFactions.hover` → `Hoverer`, and shows the NPC's status ("Neutral"), king, opinion and trade figures.

## 2. Approach

**Recommended: a mod window that looks and works like vanilla's, opened wherever vanilla's opens for an NPC.** It sits in `view.world.ui.faction`, so it can use that package's package-private parts, and it's built from vanilla's own widgets, sizes and icons.
- **Header:** the banner and the name, as vanilla's, with "Player" where vanilla shows the stance.
- **Tabs:** Realm, Goods, Boosts and Diplomacy. Court is gone.
  - **Realm:** soldiers, conscripts, the armies list, area and the regions list, as vanilla's Realm tab shows them.
  - **Goods:** the player's goods, without Rate, Purchasing Power and the traded bar. SOS-77 adds the tariff.
  - **Boosts:** vanilla's `UIBonus`, which already takes any faction.
  - **Diplomacy:** vanilla's list in vanilla's order and look. The terms that work are selectable; the others are greyed out with their reason, not removed:
    - **Declare War:** greyed out, "No battles between players yet". Once war exists, it applies at once.
    - **Peace, Trade, Become Colleagues, Alliance:** proposals.
    - **Cancel All Agreements:** applies at once, with no proposal and no answer.
    - **Become Protector, Become Vassal:** greyed out, "Not between players yet". Since SOS-73 they can be proposed, with a Tribute row below them (0% to 100%, 10% until set).
    - **Unite:** greyed out, "Not between players" (spec §6).
    - **Offer and Demand:** Regions were greyed out; since SOS-73 they open a picker of the giver's regions other than the capital, where a region the rule refuses is greyed out with the reason. Resources take several kinds, as vanilla's picker does; money is vanilla's input.
    - **Gone:** Value, Opinion, Betrayal and Barter. Accept becomes "Propose", greyed out with the reason while your side can't be given.
- **A proposal from another player** shows in this window's Diplomacy tab, as vanilla shows an NPC's request: a notice opens the window on the proposer, with their terms filled in and Accept and Decline. The 30-day expiry stays. SOS-72's popup, the multiplayer panel's "deal: name" buttons and its offer window go.

**Rejected: changing vanilla's own window to accept a player.** It would mean changing about ten game files (`UIFaction`, `Banner`, `Court`, `Realm`, `Goods`, `UIDiplomacy`, `UIDealConfig`, `Deal`, `Hoverer`, `UIFactionList`), and `Deal` is game logic built around the NPC side. That's many more hooks to keep through game updates, for the same result.

## 3. Hooks (mod patches, each in `docs/hooks.md`)

These need your approval: each is a small change to a game file that only calls into the mod.
- **Opening the window:** at each `instanceof FactionNPC` guard above (map click, region panel, faction list, Economy & Trade, goods traders), the `else` opens the mod's window when the faction is another human. That's 5 one-line hooks.
- **The hover box:** `UIFactions.hover` asks the mod first for a player. The mod's box shows the banner, name, "Player", treasury, population and the treaties. It has no king, opinion, power, trust or emissary target. That's 1 hook.
- **The king icon:** the region list and the faction list don't draw a ruler portrait for a player. That's 1 or 2 hooks.
- **Does the faction list show players at all?** I'll check this first (SOSDET patch 15 touches it). If it doesn't, adding them is one more hook.

## 4. Engine (SOSDET)

- **Cancel All Agreements becomes one-sided:** a new world command that sets the stance between the two players to peace without trade at once, on every PC at the same step. Declaring war will work the same way once battles between players exist.
- **Proposals:** the existing `HumanDeals` commands are unchanged. `DealBook.Terms` already holds several kinds of goods.
- **Spec §6, updated in the same PR:** a proposal reaches the other player as a popup, except declaring war and cancelling agreements, which apply at once.

## 5. Checks

- **Unit tests:** the one-sided cancel in `DealBook` or the new command's rules. The mod's view-model is the list of terms and why each one is greyed out, kept as plain Java.
- **Rig, 3 humans:** a proposal accepted, then a one-sided cancel; every copy identical.
- **Two PCs (twopc):** the autopilot proposes through the same engine calls, the other side accepts, then cancels; both PCs see the same stances.
- **By hand, your playtest:** open the window from each place, the greyed-out terms, the hover box, no Court tab, and no king icon for players.

## 6. The owner's answers (2026-10-27)

1. The mod's own vanilla-style window: approved, with the hooks. Polish that makes it look even more like vanilla is listed as optional (section 7).
2. The Goods tab in this card only loses the NPC fields; the tariff and the trade mechanics are SOS-77.
3. Incoming proposals show in the window's Diplomacy tab, like vanilla's NPC requests, not in a popup.

## 7. Optional polish (after the card's criteria are met)

- The tab buttons, title font, separators and spacing measured against vanilla's window, pixel for pixel.
- The right-hand summary of the chosen terms with vanilla's icons and row style.
- Vanilla's sounds on Propose, Accept and Decline.
- The Realm tab's army and region pickers with vanilla's hover boxes.

## 8. As built (2026-10-27)

Where the build differs from sections 2 and 3:
- **The window's package.** It's `menu` (`MpPlayerWindow`, `MpPlayerDiplomacy`), not `view.world.ui.faction`. It uses no package-private member of that package. In `menu` it shares the session's deal code and window base.
- **Four hooks, not seven.**
  - M2: the map click.
  - M3: the region banner.
  - M4: the faction list, which now shows players.
  - M5: the hover box.
  - Economy & Trade and the goods traders list only an NPC's trade terms (tariff, toll, riches). Players get added there with trade between players, in SOS-77.
  - The "king icon" is the ruler portrait in the faction list's rows. M4 leaves it out for a player, so no separate hook is needed.
- **Boosts: greyed out, with the reason.** A player's boosts come from their city, which runs on their PC. The city report carries only the boosts the world reads, so this PC can't show them yet.
- **Goods: what the player last reported.** A new engine call, `Engine.reportedStock`, reads the stock from the player's city report. Only the Stored column stays. The Demand side's resource picker is capped by the same figure, and the engine checks it again when the deal applies.
- **The notice.** A proposal opens a small notice with Inspect and Later. Inspect opens the proposer's window on its Diplomacy tab, where Accept and Decline are. The session panel's "deal: name" buttons, SOS-72's popup and its offer window are gone.
- **Engine.** Command 8102 `humanDealCancel`: Cancel All Agreements sets the two players neutral at once, on every PC at the stamped step. It's refused at war ("make peace instead") and when there is no treaty.
- **Tests.**
  - `PlayerTerms` is the plain-Java part of the Diplomacy tab: the terms, their kinds, one term at a time, and the engine's terms. It is unit-tested.
  - The mod's tests now have the engine jar on their classpath, for its plain-Java `DealBook`.

**Runs (2026-10-27):**
- **C76A, rig, 3 humans, 3,000 steps:** the copies stayed identical, but the cancel came after the host's last step, so it never applied.
- **C76B, rig, 3 humans, 9,000 steps:** PASS.
  - Slot 1 proposed trade to slot 2, slot 2 accepted, and slot 1 cancelled at once.
  - Every copy read TRADE, then NEUTRAL, and all 3 commands applied.
  - All copies were identical at every step.
- **C76C, two PCs, 1,800 steps:** PASS.
  - The joiner proposed trade at step 600, and the host accepted.
  - The joiner's cancel from step 900 applied at step 957.
  - The world hashes agreed at every check.

**Not covered yet, for the playtest by hand:**
- the window opened from the map, the region banner and the faction list;
- the layout at the owner's resolution;
- the greyed-out terms and their reasons;
- the hover box;
- the notice's Inspect;
- Accept and Decline in the window;
- picking resources and money in Offer and Demand.
