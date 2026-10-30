# SOS-105: Steam's newer networking API (research and plan)

2026-10-30, overnight. The owner decides; this note recommends.

## Today

The mod's Steam transport (SOS-60, `SteamworksPort`) uses `ISteamNetworking`: `sendP2PPacket` (reliable, channel 0), `isP2PPacketAvailable` / `readP2PPacket`, `acceptP2PSessionWithUser`, `closeP2PSessionWithUser`. It reaches these through steamworks4j 1.9.0, which the game jar ships with `steam_api64.dll` from Steamworks SDK 1.53. The rest of the mod sees only the narrow `SteamPort` interface (send, read, accept, close, lobby calls), on the `SteamNode` I/O thread.

## Findings

- **The old API is deprecated.** Valve's networking overview calls `ISteamNetworking` "an older, deprecated API" and says it may be removed from the SDK in a future release. It still works, and the game's DLL is pinned with the game, so nothing breaks while the game ships SDK 1.53.
- **The newer APIs:**
  - `ISteamNetworkingMessages` is UDP-like: you address each message to a Steam user, with no connection handles. It supports reliable messages larger than one packet. Traffic goes through Valve's relay network (SDR) by default, or a direct connection when appropriate, which also hides the players' IP addresses.
  - `ISteamNetworkingSockets` is the lower-level, connection-handle API. Valve calls the Messages API the easiest port for code like ours.
- **steamworks4j doesn't wrap the new APIs.** Its latest release, 1.10.0, targets SDK 1.62 but still only wraps `SteamNetworking` (CHANGES.md mentions neither NetworkingMessages nor NetworkingSockets).
- **The game's DLL already has them.** The `steam_api64.dll` inside SongsOfSyx.jar (SDK 1.53) exports the flat API:
  - `ISteamNetworkingMessages`: 8 functions, among them `SteamAPI_SteamNetworkingMessages_SteamAPI_v002`, `SendMessageToUser`, `ReceiveMessagesOnChannel`, `AcceptSessionWithUser`, `CloseSessionWithUser` and `GetSessionConnectionInfo`.
  - `ISteamNetworkingSockets`: 49 functions (`..._v012`), and `SteamNetworkingUtils_v004`.
- **A Java bridge needs no new dependency.** The game jar already carries JNA (about 4.3, from 2017, with the Windows x64 `jnidispatch.dll`) and LWJGL (`org.lwjgl.system.JNI` calls any function pointer). Java 21's foreign-function API is still a preview there and would need a JVM flag the mod can't set, so it is out.

## Options

| | What | Cost | Risk |
|---|---|---|---|
| A | Keep `ISteamNetworking` | none | Deprecated; fine while the game ships SDK 1.53; no SDR identity protection guarantees documented for it |
| B | `ISteamNetworkingMessages` through JNA, behind `SteamPort` (`SteamMessagesPort`) | about 1 to 2 days: 8 functions, the `SteamNetworkingIdentity` (136 bytes) and `SteamNetworkingMessage_t` layouts, the release callback, and a session-request callback (`SteamNetworkingMessagesSessionRequest_t`, id 1251), which steamworks4j's callback dispatcher doesn't know, so it is polled through `GetSessionConnectionInfo` or accepted implicitly by sending | Struct layouts by hand (a wrong offset crashes the game); JNA 4.3 is old; only on Windows (Linux/macOS later) |
| C | Our own JNI DLL, or a steamworks4j fork with Messages | 2 to 4 days plus a C toolchain and signing | A native build in the project, a new ADR for shipping native code |

## Recommendation

**Owner, 2026-10-30:** build B tonight. It is built as [ADR 0008](../../decisions/0008-steam-messages-through-jna.md) describes, with the old API as the default until twopc over Steam passes. The recommendation below is the one written before that.

1. **v1 keeps option A.** The old API works, the playtests over Steam (SOS-60) passed, and the DLL can't change under us.
2. **Build B only when it buys something**, behind the existing `SteamPort` so nothing above it changes. For example, if players report NAT failures, or the owner wants the relay's IP protection guaranteed. B uses only libraries already in the game jar, but calling into Steam's DLL by hand is new ground, so it needs an ADR first.
3. **If B is chosen, the plan:**
   1. A spike, `spikes/steam-messages/`: load the DLL through JNA, get the interface with `SteamAPI_SteamNetworkingMessages_SteamAPI_v002`, and send and receive one reliable message between this PC and the laptop over Steam (two accounts). It answers: do the struct layouts hold, does the session request need the callback, and what latency and size limits apply.
   2. An ADR: Messages over JNA, the struct layouts, the fallback to option A when the functions are missing.
   3. `SteamMessagesPort implements SteamPort`, chosen by a setting, with A as the default until twopc over Steam passes with B.
   4. Tests: the identity and message layouts as plain-Java byte tests; twopc `--net steam` with B for 10 minutes at 25x.

Sources: Steamworks networking overview (partner.steamgames.com/doc/features/multiplayer/networking), ISteamNetworking and ISteamNetworkingMessages references, steamworks4j CHANGES.md (github.com/code-disaster/steamworks4j).
