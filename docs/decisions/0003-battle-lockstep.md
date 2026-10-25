# 0003: Field battles run in deterministic lockstep

Status: Accepted (owner decision, 2026-10-21); amended 2026-10-21 for the AI general on the host (owner approval, [SOS-23](https://fighterx2500.atlassian.net/browse/SOS-23)); amended 2026-10-25 for the battle start and step lengths as the engine built them ([SOS-44](https://fighterx2500.atlassian.net/browse/SOS-44), see the end) · Card: [SOS-11](https://fighterx2500.atlassian.net/browse/SOS-11) · Supersedes [ADR 0002](0002-battle-streaming.md) for field battles; siege assaults are left open (settled by [ADR 0006](0006-lockstep-city-viewing-and-assaults.md): lockstep)

## Context

ADR 0002 chose host-authoritative streaming because the vanilla battle engine is not deterministic: worker threads on wall-clock timers, one shared random generator, and variable time steps. After SOS-10 the owner asked to rework the battle engine for lockstep instead, if it can be made deterministic.

Spike SOS-11 ([report](../plan/spikes/determinism.md)) did that with nine patches on our own rebuild of the game. A 2,000 vs 2,000 battle ran identically in 4 game processes at different game speeds for 10 minutes (36,000 steps). On the way it found nine causes of divergence. Four of them would also differ between two PCs in ways a single PC can't show: the tile order shuffled per process, sound picks skipped at zero volume, night light flicker tied to a player setting, and AI state drawn at battle start outside the simulation.

Spike SOS-23 ([report](../plan/spikes/city-determinism.md)) measured the timing against vanilla. With the AI general inside the step, its planning froze the game for 1–4 s dozens of times in 10 minutes. The owner then decided to run the general on the host only, with its changes applied at a stamped step by every PC. With that change, both the field battle and a city assault stayed identical in 4 processes for 10 minutes, and they met the owner's timing rule.

## Decision

Field battles run in **deterministic lockstep**: every PC in the game simulates the battle, and only orders, the host's AI log and state hashes cross the network.

- **Start.** The host generates the battle with the vanilla generator and sends the battle save (about 1.9 MB for 2,000 vs 2,000) to every PC. Every PC, the host included, loads that save, seeds the simulation random stream from the host's seed, and runs deploy as step 0 inside the simulation. Nobody regenerates, so the generator itself need not be deterministic.
- **Step.** The battle advances in fixed steps of 1/60 s. The step number is the battle tick. Frames only decide how many steps run; nothing runs while paused.
- **Battle jobs.** Four of the five battle worker jobs (orders, division centres, trajectories, status) and path finding run inside the step, in a fixed order and on a fixed beat, on the simulation thread.
- **AI general on the host** (amendment, SOS-23). The AI general (`Strategos2000`) runs only on the host, on its own thread as in vanilla. Every change it makes is captured instead of applied:
  - orders through `BattleOrder.Locked.set`: path, destination and task;
  - the division settings it writes directly: guard, formation, running, fire at will;
  - artillery targets.

  The host's simulation thread stamps each change for step N + 30 (0.5 s). Every PC, the host included, applies the changes at that step in sequence order. The other PCs have the general switched off and apply the host's AI log, which travels like orders.
- **Two random streams.** The simulation stream is used only inside the step, on the simulation thread. Everything visual or audible (rendering, tree sway, sound picks, light flicker, UI, saving) uses a separate cosmetic stream. No simulation draw may depend on a local setting (volume, light cycle, graphics).
- **Orders.** A commander's order carries the step it applies at. The host confirms that step, and every PC applies the order exactly then, never on arrival.
- **Pace.** Every PC in the game must keep up, so **the slowest PC sets the battle's pace** (owner, 2026-10-21). This matches the world speed rule in spec §4. Commanders still vote on the battle speed; the slowest PC caps it.
- **Desync detection.** Every PC hashes the battle state every few steps and sends the hash to the host.
- **Desync recovery: resync from the host** (owner, 2026-10-21). The battle pauses, the host sends its battle save at the current step, every PC loads it, and the battle continues. Every desync writes a report.
- **Siege assaults: open.** The defender's city exists only on the defender's PC, so lockstep would first send the city save to every PC. Decided after the late-game save size spike (owner, 2026-10-21). Until then ADR 0002's streaming model still describes siege assaults.

## Options rejected

- **Keep streaming (ADR 0002).** It needs no engine changes, but it means a display-only mode for every viewer (more hooks into `ARMIES` and the threads), about 1.8 Mbit/s per viewer, and the host's PC carries every battle alone. The spike showed the determinism work is bounded: nine causes, each fixed with a small change.
- **Commanders in lockstep, spectators streamed.** Spectators wouldn't slow the battle, but it means building both mechanisms.
- **Commanders set the pace; spectators fall behind and catch up.** Offered; the owner chose the simpler rule that every PC keeps up.
- **Every PC regenerates the battle from the seed.** Generation gives one of two results (cause unknown), and every PC would also pay about 17–20 s to generate. Loading the host's save avoids both.
- **AI general inside the step** (the original decision, replaced by the SOS-23 amendment). Every PC runs the general as one of the jobs, so it needs no log. But its planning runs all at once inside one step, and in SOS-23 it froze the game for 1–4 s dozens of times in 10 minutes (city assault and field battle).
- **Auto-resolve or report only on desync.** Auto-resolve throws away the real-time battle; report-only lets players see different battles.

## Consequences

- **Hooks.** Lockstep needs engine changes. They are listed as candidates B7–B15 in [hook-surface.md](../plan/hook-surface.md):
  - the fixed step;
  - battle jobs and path finding inside the step;
  - the split random stream, plus the cosmetic call sites (trees, audio, light);
  - a fixed tile order;
  - deploy as step 0;
  - the state hash;
  - loading a battle save for start and resync;
  - capturing the AI general's writes into the log, and applying the log at the step (B15, SOS-23).

  How hooks reach players is still the distribution ADR (planning task 5). B2, setting the seed after the pre-battle save, becomes plain API, because peers no longer generate.
- **Determinism rules apply.** `.claude/rules/determinism.md` covers lockstep battle code again, extended with what the spike found. Since [ADR 0004](0004-engine-split.md) the rules and the lockstep code live in the engine's repository, SOSDET.
- **Cost (SOS-23).** With the general inside the step, its planning froze the game for 1–4 s, dozens of times in 10 minutes. With the general on the host, a city assault (Mogus-72, 1,000 attackers) and a 2,000 v 2,000 field battle meet the owner's timing rule at 1× on the test PC: p99.9 at most 20 ms, no step over 1 s, and stalls no more often than vanilla. The field battle passes once its vanilla load stall at step 1 is left out. Its p99 is about twice vanilla's (11.8 v 5.8 ms), so high battle speeds are untested and may be limited by the slowest PC.
- **AI log traffic:** about 18 kbit/s in a city assault and 3.6 kbit/s in a 2,000 v 2,000 field battle (SOS-23, before any size optimisation).
- **Resync and host change** must carry the step number and the AI log entries not yet applied. A new host starts its own general.
- **The AI's decisions depend on the host's thread timing.** Two hosts would fight the same battle differently. Every PC still sees the same battle.
- **Resync needs more state.** AI state that isn't saved (the AI's `flanking` value) is drawn again on load outside the step. Resync needs it saved, or redrawn on the simulation stream after loading.
- **Still to prove:**
  - two PCs with different CPUs (`StrictMath` may be needed);
  - player orders applied at a confirmed step;
  - order delay at 300 ms ping (Steam P2P spike);
  - battle content the test didn't reach.
- **Spec 0.8.** §8, §13, §15 and §16 are rewritten for lockstep in the same PR. ADR 0002's §9 wording for siege assaults stays until the siege decision.
- **Spec 0.9** (SOS-23 amendment). §8 says that the host's AI orders cross the network along with players' orders, and that the battle AI runs on the host.

## Amendment (2026-10-25, owner, SOS-44)

The engine built two things differently from this ADR, and the engine's way stands (owner: SOS-32 has priority over the older docs):

- **Battle start: every PC generates the battle.** A battle started from the world map (SOSDET SOS-32, `MapBattles`) is generated inside the step on every PC, from the simulation random stream, so every PC gets the same battlefield. Nobody sends a battle save at the start. This replaces "The host generates the battle with the vanilla generator and sends the battle save" under Decision, and the rejected option "Every PC regenerates the battle from the seed": generation on the simulation stream inside the step gives one result. The host's battle save is still what a resync loads.
- **Step lengths (SOSDET SOS-42):** 1/60 s at 1× or slower, 1/30 s below 2×, 1/16 s from 2×. Orders and the AI log still apply 30 steps after they are given, so the delay in real time depends on the speed: 0.5 s at 1×, about 1 s just above 1×, about 0.94 s at 2× (spec §16, order delay above 1×).
- **Lowest speed (spec 0.12 §4):** the governor never lowers the speed below 0.5×; a PC that can't hold 0.5× stops the game for everyone until it catches up. Not built in the engine yet.
- **Retreat and restart (spec 0.12 §8):** only a side's commanders can order its retreat, and restart is off in battles between players. The engine's Retreat and Restart are commands any PC can give (SOS-41); the mod decides who may (SOS-15 spike, "What stays for the mod").

## Second amendment (2026-10-25, owner, SOS-61 and SOS-62)

With own cities, every PC runs its own city, and generating a battle reads city state that only the battle's own player's PC holds: the division slots, the formations, and that player's live boosts. So the first amendment's "every PC generates the battle" can't give one battlefield. The owner chose:

- **The battle PC generates and sends the battle.** At Engage, the PC of the player the world-battle prompt asks generates the battle as vanilla does. It sends the battle save (about 2.6 MB in the Mogus-72 test) to every PC over the engine's BATTLE stream, and every PC, that one too, loads it at the Engage step. Every PC holds there until it has. Co-op battles, with one city on every PC, still generate on every PC.
- **Rejected:** the battle PC sends its whole game, and every PC loads it and then generates the battle in the same step. That keeps the first amendment's rule, but it sends a bigger save and has to rebuild the battle's setup after the load: about twice the work.
- **At the end** every PC loads its own city as it was at Engage, so each city stands still during the battle (spec §4). SOSDET plan 0006 holds the engine's half.
