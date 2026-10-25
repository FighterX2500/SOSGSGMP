package sosgsgmp.net;

import java.util.List;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.setup.MapStatus;

/** The lobby's and the setup's network messages ({@link Protocol} encodes them). */
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

    /**
     * Host to every joined PC: build (or rebuild) the game with a world of {@code size} tiles. Builds are numbered
     * from 1; with no world tick before the world exists, build and generation numbers order the setup.
     */
    record MapBuild(int buildId, int size) implements Message {}

    /** Joining PC to host: its game for {@code buildId} is built and shows the map step. */
    record BuildReady(int buildId) implements Message {}

    /** Host to every joined PC: generate build {@code buildId}'s terrain with these options (map type "": Random). */
    record MapGenerate(int genId, int buildId, String mapType, int latitude, int seed) implements Message {}

    /** Joining PC to host: how generation {@code genId} went, and this PC's terrain hash. */
    record MapResult(int genId, long hash, MapStatus status) implements Message {}

    /** Host to every joined PC: the map of generation {@code genId} is confirmed and can't change any more. */
    record MapConfirm(int genId, long hash) implements Message {}

    /** A message from another protocol version; only its version is read. */
    record Unsupported(int version) implements Message {}
}
