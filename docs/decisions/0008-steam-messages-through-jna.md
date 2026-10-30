# 0008: Steam's newer messages through JNA

Status: Proposed (decided as recommended overnight, 2026-10-30; the owner asked for SOS-105 to be built that night) · Card: [SOS-105](https://fighterx2500.atlassian.net/browse/SOS-105) · Builds on SOS-60's Steam transport

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
- `SteamworksPort` keeps steamworks4j for lobbies, friends and the overlay, and sends and receives through `SteamMessages` when `SOSGSGMP_STEAM_NET=messages` is set; otherwise it uses the old P2P packets as before.
- Session requests and failures come from the DLL's global callbacks, inside `SteamAPI_RunCallbacks` on the node's I/O thread, so the threading rule (AGENTS.md) holds.
- No new library: JNA and the DLL are the game's own, compiled against as `compileOnly`, never shipped.
- The old packets stay the default until a twopc run over Steam passes with the new messages; then the owner chooses the default, and a setting replaces the variable.

## Options rejected

- **Keep only the old API.** Works today and the game pins the DLL, but it's deprecated, and the relay's IP protection is documented only for the newer APIs.
- **Our own JNI DLL, or a steamworks4j fork.** Native code to build, sign and ship for each platform, for the same calls JNA makes from Java.
- **Java's foreign-function API.** A preview in Java 21; the game starts the JVM, so the mod can't enable it.
- **`ISteamNetworkingSockets`.** Connection handles and their state machine, for no gain over Messages in a lobby of friends.

## Consequences

- A wrong offset or signature crashes the game rather than throwing. The surface is small (9 functions, 3 offsets) and the test is a twopc run over Steam.
- Windows only for now, like the rest of the mod (AGENTS.md); Linux and macOS need the same names from `libsteam_api`.
