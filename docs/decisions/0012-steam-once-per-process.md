# 0012: Steam once per game process

Status: Accepted (owner, 2026-11-22) · Card: [SOS-237](https://fighterx2500.atlassian.net/browse/SOS-237), also [SOS-238](https://fighterx2500.atlassian.net/browse/SOS-238) · Changes how SOS-60's and SOS-103's Steam nodes start and stop Steam

## Context

- Each `SteamNode` used to create its own `SteamworksPort`, which initialized the Steam API, and shut it down when the node closed: every lobby, every Join through Steam screen, and the main menu's invite watch (SOS-103).
- The Steam test of 2026-11-22 crashed the host's game when a joining player chose End for everyone: `Assert( PureClass ):memoverride.cpp:26` inside `SteamAPI_Shutdown`, on the node's I/O thread (Steam's dump `crash_java.exe_20261122003458_1.dmp`). Steam tore itself down while it still delivered news about the sessions the node had closed a moment before.
- The same race had left about 50 non-fatal `assert_java` dumps since 2026-11-15, one per session end or lobby close (`IPC call to IClientNetworkingUtilsSerialized::PostConnectionStateMsg failed`).
- Steam's invites reached the game only while a node ran. On the Multiplayer screens no node ran and Steam wasn't initialized in the process, so Steam took an accepted invite as a request to launch the game and said it was already running.
- Valve's advice is to initialize the Steam API once and shut it down at exit. The Steam API is one per process whatever the mod does.

## Decision

- `SteamworksPort` is the process's one Steam. The first node that needs Steam initializes it (`SteamworksPort.lease()`); nothing shuts it down, and the process's exit ends it.
- `sosgsgmp.net.SteamLease` lends it to one node at a time: a node's I/O thread acquires it when the node starts and gives it back when the node closes (`SteamPort.close()`). A second node waits up to 5 s for the first, then fails with a reason. A Steam that couldn't start is tried again by the next node.
- Between nodes the port forgets the last node's listener, Steam trace and session states. Steam's callbacks that waited more than 2 s without a node are dropped when the next node starts, so an invite accepted during a single-player game doesn't join a lobby minutes later.
- The node still closes its own sessions and leaves its lobby before it gives the port back.
- This is mutable static state, which AGENTS.md otherwise forbids: the lease is the one static field, in the adapter. A game session started again in the same JVM borrows the same Steam, which is what the Steam API is anyway. The threading rule holds: only the holding node's I/O thread calls Steam.

## Options rejected

- **Drain before shutdown.** Keep a Steam per node, and at close clear the callbacks, close the sessions, run Steam's callbacks for half a second, then shut down. Smaller and no static state, but Steam still starts and stops on every screen, so the race only gets rarer, and the invite gap between nodes stays.
- **A Steam thread of its own for the whole process.** Every node would hand its calls to it; a larger change to `SteamNode` for the same result, since one node at a time already holds Steam.
