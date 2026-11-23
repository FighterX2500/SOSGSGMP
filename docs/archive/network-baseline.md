# Network baseline (SOS-176)

Today's behaviour under bad network conditions, measured 2026-11-08 on main (f672160, the batch 10 merge) with SOS-175's test network conditions. It is the baseline the reconnect cards (SOS-177 to SOS-180) must beat. The Steam lobby fix (SOS-177 part 1, PR #99) isn't in this build, and nothing here ran over Steam.

## Setup

- **Games:** three on one PC (host H, joiner J, second joiner L) over direct IP on loopback, from battles3's kept positions (ALLIES for the world and the NPC battle, WAR for the battle between players). The host asks 25x in the world.
- **Conditions:** SOS-175's `SOSGSGMP_NETEM` on one game's connections:

  | Preset | Meaning |
  |---|---|
  | `150ms` | +150 ms round trip, jitter ±10 ms |
  | `300ms` | +300 ms, jitter ±80 ms |
  | `loss2` | +60 ms; 2 % of messages wait for a resend (≥200 ms; messages behind them wait too) |
  | `loss10` | +60 ms; 10 % resent |
  | outage S | nothing passes either way for S seconds, then everything held arrives |
  | cut | the game's connections close, as a lost internet connection |
- **Runner:** `python tools\netbase.py` runs the 14 cases through battles3, in scenarios:
  - N1: the world; J views H's city, H saves the campaign, then each outage;
  - N2: N1, then a cut;
  - B1 and A1: as in the battle tests.

  `python tools\netbase.py summary` prints the table below from the playtest logs. Reports with screenshots: `C:\ClaudeFolder\PTreport\2026-11-08-network\`.
- **Limits:** three games share one CPU, so the speed governor often lowers the speed for a PC's load ("PC n can't keep up"), even with no conditions. The connection's own lowering is counted separately below.

## Results

| Case | Condition | Result | Dropped | Holds (outside loading windows) | Agreed speed (host asked 25x) |
|---|---|---|---|---|---|
| N1-none | none (control) | PASS | no | ≤1.9 s | last 20.8x; lowered once for the connection (8.8x) |
| N1-150ms | J 150ms | PASS | no | ≤2.0 s | last 18.2x; once (6.6x) |
| N1-300ms | J 300ms | PASS | no | ≤1.9 s | last 25x; once (8.6x) |
| N1-loss2 | J loss2 | PASS | no | ≤2.4 s | last 16.8x; once (6.5x) |
| N1-loss10 | J loss10 | PASS | no | ≤2.2 s | last 17.1x; once (7.9x) |
| N1-all300ms | every game 300ms | PASS | no | ≤2.5 s | last 13.8x; once (4.8x) |
| N1-joutages | J outages 3, 15, 60 s | PASS | no | every PC: 1.8-3.4, 14.3-14.7, 59.3-59.7 s | down to 1.7x, back to 16.3x |
| N1-houtages | H outages 15, 60 s | PASS | no | every PC: 14.5-14.6 s, 59.5-59.9 s | down to 2.3x, back to 15x |
| N2-jcut | J cut | FAIL (expected) | **J, for good** | ≤2.7 s | |
| B1-300ms | battle between players, J 300ms | PASS | no | ≤1.9 s | never lowered for the connection |
| A1-loss10 | battle against NPCs with an ally, J loss10 | PASS | no | ≤1.9 s | |
| B1-j15 | B1, J 15 s outage in the battle | PASS | no | J 14.6 s; H, L ≤1.8 s | |
| B1-j60 | B1, J 60 s outage in the battle | PASS | no | J 59.6 s; H, L wait 67 s in the battle's end window | |
| A1-h15 | A1, H 15 s outage in the battle | PASS | no | J's and L's view of the battle opened in 37 s and 24 s (normally 9-12 s) | |

No case desynced. Every case except the cut reached its end on every game with the same hashes. Each battle's result applied once, at one step, on every PC.

## What it shows

1. **Delay, jitter and loss cost speed, not the connection.**
   - Up to 300 ms on every PC, and 10 % resends, play went on with no drop and no desync.
   - Holds stayed under 2.5 s outside loading windows, as in the control.
   - The governor lowered the speed for the connection about once per run, to 4.8-8.8x. The control lowered it once too (8.8x), so the conditions only deepen a drop the CPU load already causes.
   - The lowest was 4.8x with 300 ms on every PC.
2. **Over direct IP, the lockstep already waits out an outage of up to 60 s.**
   - In the world at 25x the pacing lead lasts about 2.5 s, so every PC freezes for the whole outage, the host's own included. Then play goes on, with the speed lowered for a while and raised again.
   - Nothing on screen says why the game froze.
3. **In a battle the others play on.**
   - The battle runs at 1x, where the pacing lead (1000 steps) lasts longer than a 15 s outage. In B1-j15 only J froze; H and L fought on, and J caught up.
   - In B1-j60 the battle ended without J for a minute. H and L then waited 67 s in the "battle is over" window until J was back. J's army got no orders for that minute.
   - An outage during the start of a view stretched that view's loading window (A1-h15).
4. **A closed connection ends the player's game for good.**
   - N2-jcut: J's session closed at once ("Lost the connection to the host"). The host logged "Joiner left at step 6130" and held for the choice, and Continue removed J, with an autosave.
   - J can't come back: the host refuses a Hello once the game has started, and the streams can't resume.
5. **Over Steam** (from the code and the 2026-11-06 playtest, not measured here):
   - Steam's lobby "Disconnected" counted as Left, so any Steam blip removed the player at once. SOS-177 part 1 (PR #99) fixes that.
   - Steam's session fails after 10 s of silence; PR #99 raises it to 60 s.

## Thresholds the later cards must beat

- **T1, the cut (SOS-178).** Today a closed connection of any length removes the player. A cut player must be able to come back into the running game within the host's wait, with no desync.
- **T2, Steam (SOS-177 part 1, verified in SOS-180).** Today a Steam blip removes the player at once, and a session fails after 10 s of silence. Over Steam with two accounts, a 15 s and a 60 s outage must drop nobody, as over direct IP.
- **T3, the frozen screen (SOS-177).** Today a silent PC freezes every game without a word: within about 2.5 s in the world, and in a battle once the lead runs out or the battle ends. The "lost connection" window must show within a few seconds of the freeze, naming the player and counting the seconds, with the host's Wait / Continue without them / Return to the last save.
- **T4, kept.** No desync and no double-applied result in any of these cases, and holds under 2.5 s outside loading windows at 300 ms or 10 % loss. The later cards must not make these worse.
- **T5, the host's blip (SOS-179).** Today the host's 15 s and 60 s outages over direct IP freeze everyone and recover (N1-houtages). Over Steam and after a cut, the host's blip must recover the same way, not end the session.

## Not covered

- Steam's fake network settings (`steambad`) and anything over Steam: that needs two Steam accounts (SOS-180).
- A city view under an outage, beyond A1-h15's battle view.
- A campaign save started during an outage.
- Real round trips: the log shows the delay the conditions add; SOS-177's heartbeat will measure the actual round trip.
