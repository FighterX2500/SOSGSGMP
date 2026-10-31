# Battles: allies, results and the speed vote

Spec 0.39 §8, §9, §11, §15, §16. Decisions: [ADR 0009](../decisions/0009-several-humans-in-a-battle.md). Engine half: SOSDET plan 0018.

## Cards

Worked one at a time, in this order. Approved by the owner on 2026-10-31.

| Card | What | Tested on |
| --- | --- | --- |
| [SOS-116](https://fighterx2500.atlassian.net/browse/SOS-116) Results for either winner | Both armies get the winner's window, counts from the battle, a beaten sally gives captives | Two PCs |
| [SOS-117](https://fighterx2500.atlassian.net/browse/SOS-117) The commanders' speed vote | The vote, and the world's speed back after a battle | Two PCs |
| [SOS-118](https://fighterx2500.atlassian.net/browse/SOS-118) Allies in a battle between players | The prompt, owners, bonuses, slots, deployment, separate retreats, shared results | Rig, three humans |
| [SOS-119](https://fighterx2500.atlassian.net/browse/SOS-119) An ally in an NPC battle | Two PCs run one local battle, with both commanders | Two PCs |

The fourth depends on the third's owners and bonuses. The first two stand alone.

## Step 0: what a third army does today

A three-human rig run with no code change, before the third card: an allied human's army within 4 tiles of a battle between two others. It should confirm what ADR 0009 read from the code. If it desyncs or crashes, that goes in the card as a bug of its own. The rig needs a way to stand the third army there; `Sieges.ARMY_TELEPORT` already does that.

## Card 1: results for either winner

**Engine (plan 0018 M1):** the men at the battle's start, per division, kept in `OwnBattle` from the loaded battle, so the count needs no read of a city after the load back. `ResolverPlayer.Manual.afterBattle` uses it.

**Mod:** army 1's human already gets the result panel when they win, with Accept only: it gets the choice of captives and spoils. The loser's window stays. The autopilot's `accept` says victory or defeat by the human, not by army 0.

**Check, twopc:** field battles won by each army, and a beaten sally: the same captives and spoils on both PCs, no desync. This replaces S6T's "no captives".

## Card 2: the commanders' speed vote

**Engine (M2):** votes as commands, one per commander, inside the battle's steps. Speed changes at the step the majority forms.

**Mod:** in a battle between players the speed buttons send a vote, and the bar shows who voted for what. The host's own speed choice stops applying until the battle ends.

**Engine:** the world's speed before a battle is kept and comes back after it.

**Check, unit:** 2 commanders must agree, 3 need 2, a tie keeps the speed, a spectator's press changes nothing, a retreated commander stops counting. **twopc:** one vote changes nothing, two do, and the speed is back after the battle.

## Card 3: allies in a battle between players

Engine M3 to M7 carry most of it.

**Mod:**
- the Join and Stay out prompt, with its 30 seconds;
- the battle's UI for each commander's own divisions and slots;
- Retreat for one's own army, and a watching window for the player who left;
- the loser's and each winner's windows;
- chat notes: who joined, who stayed out, who retreated;
- the playtest log, and the autopilot's `join`, `stayout`.

**Check, rig:** three humans, two on a side, on two PCs (`--humans 3 --remote 3`):
- joined and stayed out, by answer and by timeout;
- each ally's orders reach only their own divisions;
- bonuses differ by human, the same on every copy;
- one retreats, the other stays and wins;
- both retreat;
- the shares and the leftover;
- a resync in the battle.

## Card 4: an ally in an NPC battle

Engine M8.

**Mod:** the same prompt; the ally's PC opens the battle as a watcher does, with their divisions commandable; orders go to the fighter's PC through a new message with a protocol version; the speed vote and pause.

**Check, twopc:** this PC and the laptop against an NPC army, with the world going on at 1×: both command, one retreats, the result applies once on every PC, both cities come back.

## Results

The rig results and the bugs found are in SOSDET plan 0018's Results. What the mod adds here (`MpAllyJoin`, the `allyAsked` and `allyAnswer` calls, the `join`, `stayout` and `ally` autopilot commands) compiles and passes its unit tests. Nobody has seen it in a game yet.

Choices made while building, for the owner to confirm:

- **Start in an NPC battle:** only the fighter's Start counts, so the ally can't hold the battle up at deployment.
- **One ally in an NPC battle:** the first who says Join, as the spec speaks of two players' PCs.
- **Deployment:** one shared area for a side, with the groups 20 tiles apart. The spec says "their own area".
- **NPC allies in a side:** their divisions go to the side's lead, where the spec says the host's AI commands only NPC divisions. The spec needs an answer here.
- **The last human of a side retreating** ends the side, NPC allies included.
- **The retreat dialog's** loss estimate doesn't count the ally's share.
- **The result panel:** the fighter's Close sends the result; the ally's does nothing.
- **Speed in a joined NPC battle:** either commander's pause holds it, and any press resumes it.
- **The ally's prompts** wait with the fighter's until the battle ends.
- **The ally's view** comes back after the game ends it or a desync does, not after they close it or fall behind (open question below).

## Open

Spec §16: a commander who drops out of a battle with allies, and a slow ally in a joined NPC battle. Until the owner decides, §11's Continue without them stays as it is, so it ends the battle with no result. Cards 3 and 4 can be built without this, but they don't ship without it.

## Gaps to expect

- An assault has no roster: read in the code, the invaders use the invasion's faction, so neither the stormer nor an ally fights with their own bonuses. Fixing it needs every attacker's report at the assault's start, for all attackers alike, so it was left for a card of its own.
- Only the rig can test three humans, as there are two PCs.
