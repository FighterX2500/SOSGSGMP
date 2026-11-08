# Reconnect: SOS-178, SOS-179, SOS-180

Owner, 2026-11-08: "continue with SOS-178, SOS-179 and SOS-180. Draft plans and start implementing." The owner decisions in the reconnect cards stand:
- the host decides, with no time limit;
- the host's own blip is covered;
- crashes and quits stay as today;
- test outages are at most 60 s.

## Where we start

SOS-177 made a *silent* PC "lost". The game waits for it, and play goes on by itself when it is heard again. A *closed* connection is still "gone":
- TCP: a read error or the other side closing;
- Steam: a failed P2P session, or Steam refusing a message.

A closed connection is what a real internet drop usually becomes: a router reset, a NAT timeout, or Steam's session timeout. The messages in flight on the dead connection are lost, and nothing can send them again.

## The idea: resumable message streams in the transport

The fix goes into the nodes (`TcpNode`, `SteamNode`), below the sessions. A broken connection then looks to the game exactly like silence, and SOS-177's lost window and hold already handle silence. Nothing above the nodes learns that a connection was replaced.

- **Sequence numbers.** Every message to a peer gets the next sequence number. The node keeps each message until the peer confirms it.
  - On the wire it is a `Sequenced(seq, message)` wrapper.
  - Heartbeats are not sequenced. Neither are the two new control messages, `Resume` and `Resumed`.
- **Acks.** Each heartbeat carries how many messages this PC has received from that peer. The other side then forgets what it no longer needs. Kept messages are therefore about one second of traffic, plus whatever piles up while a peer is away.
- **Receiving.** A message with the next sequence number is delivered. One seen before is dropped, so a repeat changes nothing. One past a gap is dropped and a resend is asked for; only Steam can lose messages mid-session.
- **Resume handshake.**
  1. A PC that (re)connects sends `Resume(token, received)`: who it is, and how much it has.
  2. The other side resends everything after `received` and answers `Resumed(received)` with its own count.
  3. The first PC then resends what the other lacks.
  4. Both sides resend from where the other stopped, so nothing is lost or applied twice.
  5. Compression isn't restarted: the engine's Deflate streams continue byte for byte, because the bytes in between are resent exactly.
- **Identity.**
  - **TCP:** a joining node makes a random 64-bit token when it is created. The host's node maps the token to the peer number it gave on the first connection. A host that doesn't know the token, for example after it restarted, answers `Resumed(-1)`, and the joining PC's connection then ends with a reason.
  - **Steam:** the Steam ID is the identity, as today.
- **TCP, joining PC.** A read or write error (reset, timeout) detaches the host's line without telling the game. The node tries to connect again every 2 s, with no limit, until the session ends. The player can still leave from the "Lost the connection to the host" window (SOS-179).
  - A clean close by the host (end of stream) is still the host leaving.
- **TCP, host.**
  - A joining PC's error detaches its line.
  - A new connection is identified by its first message:
    - `Resume` with a known token takes the line over, and the old socket is closed quietly if it is still open;
    - `Resume` with a new token is a new peer;
    - anything else is a PC of another version, delivered as today, so the version check still refuses it with a clear reason.
  - A connection that sends nothing within 10 s is closed.
- **Steam.**
  - A failed session, or a message Steam refuses, detaches the line instead of dropping the peer. The node sends `Resume` every 2 s, which reopens the session, until it hears `Resumed`.
  - A real Left from the lobby is still a disconnect.
  - A joining PC thrown out of the lobby because it lost Steam tries to enter it again; failing that changes nothing.
- **Removed while away.** `disconnect(peer)` on a detached line keeps the queued `Refused(REMOVED)`. When the PC comes back it gets the refusal and then its connection is closed, as SOS-177 already does for a silent one.
- **Crash or quit:** still a disconnect.
  - A quit sends `Leave`.
  - A crash closes the socket cleanly through the operating system, or Steam reports a Left.
  - If a crash shows up as a reset instead, the player shows as lost, and the host chooses as for a player who left.
- **Test tooling.** `ConditionedNode`'s cut becomes a real break: `Node.cut(peer)` resets the socket (TCP) or fails the session (Steam fake). While a test outage lasts, the joining PC's new connections fail too, so a cut inside an outage stays down until the outage ends.
- **Protocol 26 → 27.**

### What the sessions see

Nothing new. A detached line is a silent peer:
- under 5 s, nothing shows;
- from 5 s, the lost window (SOS-177) appears;
- once the line is back, play goes on.

The Departures, holds and choices are unchanged. Because the engine's streams are replayed by the transport, a hash, an AI log or an order is never lost to the break.

### Spec, section 11

- **Lost connection** now covers a closed connection too: "goes silent, or its connection breaks, for 5 seconds…; its game reconnects by itself".
- **Disconnects** keeps crashes and quits.
- The sentence "A disconnected player never rejoins…" now names only crashes and quits.
- **Host disconnect** gets the same reconnect: the joining PCs reconnect by themselves, and each can leave from its window. A host crash or quit still ends the session.

## SOS-178 steps

1. **`Resend`**, plain Java, per node: sequence numbers, kept messages, acks, and the delivery verdict (next, repeat or gap). Unit tests: in order, repeats, gaps, acks freeing memory, resend after a given count.
2. **`Protocol`:** `Sequenced`, `Resume`, `Resumed`, and Heartbeat's received count. Round-trip tests; version 27.
3. **`TcpNode`:** lines that outlive their sockets, the handshake, the reconnect loop and pending connections. Tests:
   - a cut mid-stream delivers every message exactly once and in order, both ways;
   - an unknown token ends with a reason;
   - a PC of another version is still refused by the version check;
   - a removed PC gets its refusal on return.
4. **`SteamNode`:** a session failure or a refused send detaches; `Resume` every 2 s; re-entering the lobby. Tests use `FakeSteam`, with a session failure that loses the messages in flight.
5. **`ConditionedNode`** and the autopilot: `netcut` is a real break.
6. **Spec 0.56** and `docs/hooks.md` if anything changes there (nothing expected).
7. **Smoke runs** (`battles3.py`): N2, the world plus J's cut, must now end with J back. Then B1 with a cut in the battle, and a cut inside a city view. Every hash the same to the end.

## SOS-179: the host's own drop

With TCP the mechanism above is already symmetrical: the joining PCs reconnect, and the host waits for them. What's left:
- the "Lost the connection to the host" window gets a **Leave** button: that player's session ends and they go to the main menu, while the host sees them as lost;
- the host's own cut and outages in the smoke runs: N1 with an H cut, and B1 with an H cut;
- over Steam the host's lobby may change owner while the host is away. Nothing after the start depends on the owner, but that is checked in the code and noted.

## SOS-180: the test pass

The matrix of SOS-176 again on the final build, plus cuts at awkward moments:
- mid-battle;
- during a save;
- in a loading window;
- at a view's start and end;
- the host's cuts.

The report is a PDF like the 2026-11-08 battle tests, and anything found becomes a card. The real Steam blip needs the owner on a second account, so this card stays open until the owner can do it.

## Risks

- **Memory while a PC is away a long time.** Kept messages grow with what the game sends during the hold: cursors and chat, a few MB an hour. Acceptable.
- **A half-open socket on the host.** The joining PC's new connection takes over, and the old one is closed.
- **Steam:** can't be tested here. `FakeSteam` covers the logic; real Steam waits for SOS-180 with the owner.
