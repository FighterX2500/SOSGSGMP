package sosgsgmp.net;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;
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

    /** Joining PC to host: this PC's latest picks: race, faction, banner and location (setup-factions-step.md §4). */
    record Pick(FactionPick pick) implements Message {}

    /** Host to every joined PC: every player's latest picks, the host's first, with the reason a location was cleared. */
    record Picks(List<FactionStep.PlayerPick> picks) implements Message {
        public Picks {
            picks = List.copyOf(picks);
        }
    }

    /** Host to every joined PC: the world is initialized; every player's slot in it, and the save's transfer number. */
    record SlotMap(int transfer, List<FactionStep.PlayerSlot> slots) implements Message {
        public SlotMap {
            slots = List.copyOf(slots);
        }
    }

    /** Host to every joined PC: piece {@code index} of {@code count} of the world save, compressed. */
    record WorldPart(int transfer, int index, int count, byte[] bytes) implements Message {
        public WorldPart {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof WorldPart w && transfer == w.transfer && index == w.index && count == w.count && Arrays.equals(bytes, w.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(transfer, index, count, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "WorldPart[transfer=" + transfer + ", index=" + index + ", count=" + count + ", " + bytes.length + " bytes]";
        }
    }

    /** Joining PC to host: this PC loaded the world of transfer {@code transfer}, with this world hash at step 0. */
    record Loaded(int transfer, long hash) implements Message {}

    /** Joining PC to host: this PC's landing party is placed. */
    record StartSpot(int transfer) implements Message {}

    /** Host to every joined PC: every player's progress after the world is initialized, the host first. */
    record WorldPlayers(int transfer, List<FactionStep.PlayerProgress> players) implements Message {
        public WorldPlayers {
            players = List.copyOf(players);
        }
    }

    /** Host to every joined PC: every PC loaded the same world and placed its landing party; the game starts. */
    record Go(int transfer) implements Message {}

    /**
     * A player's cursor on the world map, at world pixel {@code x, y}, or off it. Joining PC to host (its own id), and
     * host to every other PC. Only shown, never an action, so it carries no tick (spec §5 "Other players' cursors").
     */
    record Cursor(int playerId, boolean onMap, int x, int y) implements Message {}

    /**
     * Bytes {@code offset} on of PC {@code pc}'s engine stream {@code kind}, sent at the sender's step {@code step}
     * (game-over-network.md §2). The steps changes apply at are inside the engine's bytes. A receiver drops bytes it
     * already has, so a repeated message changes nothing.
     */
    record Stream(StreamKind kind, int pc, long offset, long step, byte[] bytes) implements Message {
        public Stream {
            if (pc < 0 || pc >= LobbySettings.MAX_PLAYERS || offset < 0 || step < 0 || bytes.length > Protocol.MAX_STREAM_PIECE)
                throw new IllegalArgumentException("SOSGSGMP: stream piece of PC " + pc + " at " + offset + ", step " + step + ", " + bytes.length + " bytes");
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /** The byte count without a copy. */
        public int length() {
            return bytes.length;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Stream s && kind == s.kind && pc == s.pc && offset == s.offset && step == s.step && Arrays.equals(bytes, s.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, pc, offset, step, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "Stream[" + kind + ", pc=" + pc + ", offset=" + offset + ", step=" + step + ", " + bytes.length + " bytes]";
        }
    }

    /**
     * Host to every joined PC, at a world desync the host chose to resync (spec §11 World desyncs): piece
     * {@code index} of {@code count} of transfer {@code transfer}, the world resync for step {@code step} (the engine's
     * state and the world save, {@code sosgsgmp.session.WorldResync}). A repeated piece changes nothing.
     */
    record Resync(int transfer, long step, int index, int count, byte[] bytes) implements Message {
        public Resync {
            if (step < 0)
                throw new IllegalArgumentException("SOSGSGMP: a resync for step " + step);
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Resync r && transfer == r.transfer && step == r.step && index == r.index && count == r.count
                    && Arrays.equals(bytes, r.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(transfer, step, index, count, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "Resync[transfer=" + transfer + ", step=" + step + ", index=" + index + ", count=" + count + ", " + bytes.length + " bytes]";
        }
    }

    /** Host to every joined PC, at a world desync: the host chose Save and exit at step {@code step}; every PC saves. */
    record DesyncExit(long step) implements Message {}

    /** A message from another protocol version; only its version is read. */
    record Unsupported(int version) implements Message {}
}
