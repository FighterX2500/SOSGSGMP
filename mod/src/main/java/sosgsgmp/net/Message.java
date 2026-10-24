package sosgsgmp.net;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;

/** The lobby's network messages ({@link Protocol} encodes them). */
public sealed interface Message {

    /** Joining PC to host, first message: who is joining. The key identifies the player across sessions (direct IP). */
    record Hello(String name, long playerKey) implements Message {}

    /** Host to a joined PC: its player id and the lobby's settings. */
    record Welcome(int yourId, LobbySettings settings) implements Message {}

    /** Host to every joined PC: everyone in the lobby, the host first. Sent whenever it changes. */
    record Roster(List<LobbyPlayer> players) implements Message {
        public Roster {
            players = List.copyOf(players);
        }
    }

    /** Host to a joining PC that can't join; the host then closes the connection. */
    record Refused(Refusal reason) implements Message {}

    /** Host to every joined PC: the host pressed Start Lobby. */
    record Start() implements Message {}

    /** Either way: the sender leaves (from the host: the lobby is closed). */
    record Leave() implements Message {}

    /** A message from another protocol version; only its version is read. */
    record Unsupported(int version) implements Message {}
}
