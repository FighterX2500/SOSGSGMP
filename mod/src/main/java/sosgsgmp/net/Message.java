package sosgsgmp.net;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.setup.MapStatus;
import sosgsgmp.setup.MapStep;

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

    /**
     * Host to every joined PC: generation {@code genId} of build {@code buildId}, made with these options (map type "":
     * Random). Its terrain follows in {@link MapTerrain} pieces.
     */
    record MapGenerate(int genId, int buildId, String mapType, int latitude, int seed) implements Message {}

    /**
     * Host to every joined PC: piece {@code index} of {@code count} of generation {@code genId}'s terrain and landmarks,
     * as the game saves them, compressed. Joining PCs load the host's terrain rather than generating it: vanilla's
     * generator isn't a function of the seed (setup-map-step.md §7).
     */
    record MapTerrain(int genId, int index, int count, byte[] bytes) implements Message {
        public MapTerrain {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MapTerrain t && genId == t.genId && index == t.index && count == t.count && Arrays.equals(bytes, t.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(genId, index, count, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "MapTerrain[genId=" + genId + ", index=" + index + ", count=" + count + ", " + bytes.length + " bytes]";
        }
    }

    /** Joining PC to host: how generation {@code genId} went, and this PC's terrain hash. */
    record MapResult(int genId, long hash, MapStatus status) implements Message {}

    /** Host to every joined PC: the map of generation {@code genId} is confirmed and can't change any more. */
    record MapConfirm(int genId, long hash) implements Message {}

    /**
     * Host to every joined PC: every player's progress (the host first) for build {@code buildId} and generation
     * {@code genId} (0: none yet). Sent after each build and generation, and whenever a player's progress changes.
     */
    record MapPlayers(int buildId, int genId, List<MapStep.PlayerStatus> players) implements Message {
        public MapPlayers {
            players = List.copyOf(players);
        }
    }

    /** A message from another protocol version; only its version is read. */
    record Unsupported(int version) implements Message {}
}
