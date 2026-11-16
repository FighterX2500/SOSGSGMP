# 0008: Steam's newer messages through JNA

Status: Accepted, built (decided as recommended overnight, 2026-10-30, when the owner asked for SOS-105 to be built that night; the default Steam transport in every playtest pack since 2026-10-30d, on Linux and macOS too since 2026-11-16, see the update at the end) · Card: [SOS-105](https://fighterx2500.atlassian.net/browse/SOS-105) · Builds on SOS-60's Steam transport

## Context

- The mod's Steam transport (SOS-60) sends with `ISteamNetworking`'s P2P packets through steamworks4j 1.9.0, which the game jar ships.
- Valve calls `ISteamNetworking` deprecated and says it may leave a future SDK. It recommends `ISteamNetworkingMessages`, which relays through Valve's network by default (hiding players' IP addresses), may connect directly when that's better, and handles large reliable messages.
- steamworks4j doesn't wrap the newer APIs, not even in its latest release (1.10.0, SDK 1.62).
- The `steam_api64.dll` in the game jar (SDK 1.53) exports their flat C API, along with helpers for identities and messages (`SteamNetworkingIdentity_SetSteamID64`, `SteamNetworkingMessage_t_Release`) and global session callbacks.
- The game jar also carries JNA (about 4.3, with its Windows x64 dispatcher). Java 21's foreign-function API is a preview in the game's JRE and needs a JVM flag a mod can't set.

## Decision

- `sosgsgmp.game.SteamMessages` calls `ISteamNetworkingMessages` through JNA, on the very `steam_api64.dll` steamworks4j loaded: it finds the module with `GetModuleHandleA` and each function with `GetProcAddress`, once.
- A missing function fails with its name.
- It reads only the first three fields of `SteamNetworkingMessage_t` (data at 0, size at 8, the sender's identity at 16), stable since the API began; identities go through the DLL's own helpers.
- `SteamworksPort` keeps steamworks4j for lobbies, friends and the overlay, and sends and receives through `SteamMessages` unless `SOSGSGMP_STEAM_NET=p2p` is set, which chooses the old P2P packets.
- Session requests and failures come from the DLL's global callbacks, inside `SteamAPI_RunCallbacks` on the node's I/O thread, so the threading rule (AGENTS.md) holds.
- No new library: JNA and the DLL are the game's own, compiled against as `compileOnly`, never shipped.
- The host accepts each lobby member's session again every 250 ms: an accept before the member's first message does nothing, so a join can't rest on the session-request callback alone. Pack 2026-10-30d, without this, hung on "connecting" between two PCs.
- A lobby's connection writes a `steam-*.log` beside the session logs: a join that hangs writes no session log.
- `SteamMessages` sets Steam's global defaults before the first session: a send buffer of 256 MB and a send rate of 1 MB/s. The DLL's own are 512 KB, past which it refuses a message and `SteamNode` drops the player, and a fixed 256 KB/s (both read from the DLL, 2026-10-30).
- The owner made the new messages the default for the playtest build 2026-10-30d, before a twopc run over Steam, so that playtest is their first test between two PCs. A setting replaces the variable once it passes.

## Options rejected

- **Keep only the old API.** Works today and the game pins the DLL, but it's deprecated, and the relay's IP protection is documented only for the newer APIs.
- **Our own JNI DLL, or a steamworks4j fork.** Native code to build, sign and ship for each platform, for the same calls JNA makes from Java.
- **Java's foreign-function API.** A preview in Java 21; the game starts the JVM, so the mod can't enable it.
- **`ISteamNetworkingSockets`.** Connection handles and their state machine, for no gain over Messages in a lobby of friends.

## Consequences

- A wrong offset or signature crashes the game rather than throwing. The surface is small (9 functions, 3 offsets) and the test is a twopc run over Steam.
- Windows only at first, like the rest of the mod then; Linux and macOS need the same names from `libsteam_api` (built since, see the update below).

## Update, 2026-11-16: Linux and macOS

The same functions now come from `libsteam_api` on Linux (SOS-157) and macOS (PR #118). steamworks4j loads it by its path in an extract folder, so the mod finds that path among the loaded libraries (`/proc/self/maps` on Linux, dyld's image list on macOS) and opens the same file again with JNA. Windows keeps `GetModuleHandleA` and `GetProcAddress`.

On macOS the mod also sets `k_ESteamNetworkingConfig_P2P_Transport_ICE_Enable` to Disable. The game's macOS Java is x86_64, so on Apple Silicon it runs under Rosetta, and there Steam's WebRTC ICE thread crashed the game (SIGSEGV in `libsteamwebrtc.dylib`). A Mac's sessions go through Valve's relays only, with a little more delay. Turning ICE off only under Rosetta (`sysctl.proc_translated`) was not done: Intel Macs are rare, and one path is simpler to test. A full session between a Mac and another PC over the relays is still to be tested.
