package sosgsgmp.net;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.lobby.Builds;
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
    record Hello(String name, long playerKey, Builds builds) implements Message {}

    /** Host to a joined PC: its player id and the lobby's settings. */
    record Welcome(int yourId, LobbySettings settings) implements Message {}

    /** Host to every joined PC: everyone in the lobby, the host first. Sent whenever it changes. */
    record Roster(List<LobbyPlayer> players) implements Message {
        public Roster {
            players = List.copyOf(players);
        }
    }

    /**
     * Host to a joining PC that can't join; the host then closes the connection. {@code detail}: what differs, a line
     * each, for {@link Refusal#WRONG_FILES} (SOS-159); empty for the others.
     */
    record Refused(Refusal reason, List<String> detail) implements Message {
        public Refused {
            detail = List.copyOf(detail);
        }

        public Refused(Refusal reason) {
            this(reason, List.of());
        }

        /** The refusal as the joining player reads it. */
        public String text() {
            return detail.isEmpty() ? reason.text : reason.text + "\n" + String.join("\n", detail);
        }
    }

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

    /**
     * Hot-join (SOS-190), host to a PC that said hello after the start, after its Welcome: the game is under way, and the
     * host is asked whether it joins; {@code returning}: it is a frozen player of the campaign, asked whether it comes back
     * (spec §11 Freezing a player, SOS-195).
     */
    record Running(boolean returning) implements Message {}

    /** Hot-join, the joining PC to the host: its race, faction and spot on the host's world, picked on a snapshot. */
    record JoinPick(FactionPick pick) implements Message {
        public JoinPick {
            Objects.requireNonNull(pick, "pick");
        }
    }

    /**
     * Hot-join, host to the joining PC: why its spot can't be used on the live world, or empty: the founding save is
     * ordered, and a {@link Restart} follows.
     */
    record JoinPicked(String problem) implements Message {
        public JoinPicked {
            Objects.requireNonNull(problem, "problem");
        }
    }

    /**
     * Hot-join, host to every joined PC and the joining one: the founding save {@code number} is done; every PC restarts
     * from its copy of it with {@code campaign}, which has the newcomer, player {@code newcomerId}, in its slot. Every PC
     * adds the newcomer at {@code pick}'s spot with {@code credits} after its load. From here on, the host's messages are
     * the new session's.
     *
     * <p>A rejoin (SOS-195) has {@code rejoinSlot}, the frozen player's slot, and no pick: the returning PC loads its own
     * last save of the campaign, every other PC its copy of save {@code number}; then every PC loads the host's world,
     * from the {@link RejoinPart}s sent before this, and the slot thaws. {@code rejoinSlot} is {@link #NO_REJOIN} otherwise.
     */
    record Restart(int number, sosgsgmp.campaign.Campaign campaign, int newcomerId, FactionPick pick, double credits,
            int rejoinSlot) implements Message {
        public static final int NO_REJOIN = -1;

        public Restart {
            Objects.requireNonNull(campaign, "campaign");
            if (rejoinSlot < NO_REJOIN || rejoinSlot != NO_REJOIN && (pick != null || credits != 0))
                throw new IllegalArgumentException("SOSGSGMP: rejoin restart of slot " + rejoinSlot + " with a pick or credits");
            if (rejoinSlot == NO_REJOIN)
                Objects.requireNonNull(pick, "pick");
            if (number < 1 || newcomerId < 0 || !(credits >= 0) || Double.isInfinite(credits)
                    || pick != null && !pick.hasLocation())
                throw new IllegalArgumentException("SOSGSGMP: restart " + number + ", newcomer " + newcomerId + ", credits "
                        + credits + ", pick " + pick);
        }

        /** A hot-join's restart: a newcomer at {@code pick}. */
        public Restart(int number, sosgsgmp.campaign.Campaign campaign, int newcomerId, FactionPick pick, double credits) {
            this(number, campaign, newcomerId, pick, credits, NO_REJOIN);
        }

        /** A rejoin's restart: the frozen player in {@code slot} comes back as {@code playerId}. */
        public static Restart rejoin(int number, sosgsgmp.campaign.Campaign campaign, int playerId, int slot) {
            return new Restart(number, campaign, playerId, null, 0, slot);
        }

        /** Whether this restart brings a frozen player back. */
        public boolean rejoins() {
            return rejoinSlot != NO_REJOIN;
        }
    }

    /**
     * Rejoin (SOS-195), host to every joined PC and the returning one, before the {@link Restart}: piece {@code index}
     * of {@code count} of the host's rejoin files: the world's sections as its founding save had them, and the world's
     * state no save holds (SOSDET Engine.rejoinOut), packed together.
     */
    record RejoinPart(int index, int count, byte[] bytes) implements Message {
        public RejoinPart {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RejoinPart w && index == w.index && count == w.count && Arrays.equals(bytes, w.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, count, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "RejoinPart[index=" + index + ", count=" + count + ", " + bytes.length + " bytes]";
        }
    }

    /** Hot-join, a joined PC to the host after {@link Restart}: its messages from here on are the new session's. */
    record Restarted() implements Message {}

    /** Joining PC to host: this PC's player pressed Ready, or pressed it again (SOS-120). */
    record Ready(boolean ready) implements Message {}

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

    /**
     * Host to every joined PC, a living world (SOS-187): piece {@code index} of {@code count} of the world built around
     * the host, which every PC loads to settle on, compressed. Once per setup.
     */
    record SettlePart(int index, int count, byte[] bytes) implements Message {
        public SettlePart {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SettlePart w && index == w.index && count == w.count && Arrays.equals(bytes, w.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, count, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "SettlePart[index=" + index + ", count=" + count + ", " + bytes.length + " bytes]";
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
     * A player's cursor at pixel {@code x, y} of its place: the world map or a city ({@link sosgsgmp.session.Cursors}),
     * or off every map. Joining PC to host (its own id), and host to every other PC. Only shown, never an action, so it carries no tick (spec §5 "Other players' cursors").
     */
    record Cursor(int playerId, int place, int x, int y) implements Message {}

    /**
     * A chat message (spec §12, SOS-110). Joining PC to host, and host to every joined PC, the sender too: the host's
     * order is every PC's, and it stamps the sender. Only shown, never an action, so it carries no tick.
     */
    record Chat(int playerId, String text) implements Message {}

    /** Player {@code playerId} paused or unpaused the game (spec §4): the host orders these like chat lines. */
    record Pause(int playerId, boolean paused) implements Message {}

    /**
     * Host to every joined PC (SOS-158): open or close the loading window of {@code kind} (a
     * {@code sosgsgmp.session.Loading.Kind} ordinal) and {@code key}, showing {@code text}. Only shown, never an
     * action, so it carries no tick.
     */
    record Loading(int kind, long key, String text, boolean open) implements Message {}

    /**
     * Bytes {@code offset} on of PC {@code pc}'s engine stream {@code kind}, sent at the sender's step {@code step}
     * (game-over-network.md §2). The steps changes apply at are inside the engine's bytes. A receiver drops bytes it
     * already has, so a repeated message changes nothing.
     */
    record Stream(StreamKind kind, int pc, long offset, long step, byte[] bytes) implements Message {
        public Stream {
            if (pc < 0 || pc >= LobbySettings.MAX_SLOTS || offset < 0 || step < 0 || bytes.length > Protocol.MAX_STREAM_PIECE)
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

    /** Host to every joined PC: save the campaign at the order's step (spec §11, SOS-64). */
    record SaveAt(SaveOrder order) implements Message {}

    /** A joined PC to the host: it saved order {@code number} with world hash {@code hash}, or couldn't ({@code problem}, empty when it did). */
    record Saved(int number, long hash, String problem) implements Message {}

    /** Host to every joined PC: every PC reported on order {@code number}; {@code outcome} is empty, or what went wrong. Play goes on. */
    record SaveDone(int number, String outcome) implements Message {}

    /**
     * Host to every joined PC: player {@code playerId}'s PC is gone (spec §11 Disconnects, SOS-65). Every PC holds at
     * {@code holdStep} until the host's {@link GoneChoice} for it.
     */
    record PlayerGone(int playerId, long holdStep) implements Message {}

    /**
     * Host to every joined PC: its choice for the hold at {@code holdStep}: Continue without the players gone
     * ({@link #CONTINUE}), Freeze them ({@link #FREEZE}, SOS-194), or Return to the last save ({@link #RETURN}), which
     * ends the session.
     */
    record GoneChoice(long holdStep, int choice) implements Message {
        public static final int RETURN = 0, CONTINUE = 1, FREEZE = 2;

        public GoneChoice {
            if (choice < RETURN || choice > FREEZE)
                throw new IllegalArgumentException("SOSGSGMP: gone choice " + choice);
        }
    }

    /**
     * Host to every joined PC (SOS-177): player {@code playerId}'s PC went silent, its connection still open, for
     * {@code silentMs} so far. Every PC holds at {@code holdStep}, as for {@link PlayerGone}, and waits: the player may
     * come back ({@link PlayerBack}), or the host chooses ({@link GoneChoice}).
     */
    record PlayerLost(int playerId, long holdStep, long silentMs) implements Message {}

    /**
     * Host to every joined PC (SOS-177): lost player {@code playerId}'s PC is heard again before the host chose; the
     * hold at {@code holdStep} ends once nobody else is in it.
     */
    record PlayerBack(int playerId, long holdStep) implements Message {}

    /**
     * Each way on every connection, about once a second, from the network's I/O thread (SOS-177): the connection is
     * alive even while the game sends nothing. {@code sentMs} is the sender's clock; {@code echoMs} the last
     * {@code sentMs} it heard from the other side, {@code heldMs} after hearing it (-1 before any): the other side
     * gets its round trip. {@code received}: how many {@link Sequenced} messages the sender has had from the other side
     * (SOS-178), which the other side then stops keeping. The transports consume it; it never reaches the game.
     */
    record Heartbeat(long sentMs, long echoMs, int heldMs, long received) implements Message {}

    /**
     * Each way on every connection (SOS-178): {@code message}, the {@code seq}-th the sender sent the other side (from
     * 1). The sender keeps it until a {@link Heartbeat} says it arrived, and sends it again after a broken connection;
     * the receiver takes each number once, in order. The transports unwrap it; the game gets {@code message} only.
     */
    record Sequenced(long seq, Message message) implements Message {}

    /**
     * The first message on a new connection, and an ask for what is missing (SOS-178): the PC whose connection token is
     * {@code token} (a direct-IP joining PC's own random number; 0 over Steam, where the Steam ID says who) has had
     * {@code received} {@link Sequenced} messages from the other side. The other side sends again those after it and
     * answers {@link Resumed}. {@code attempt} numbers a joining PC's connections from 1, so its host can tell an
     * abandoned one, still waiting in its queue, from the newest; 0 over Steam. The transports consume it.
     */
    record Resume(long token, long received, int attempt) implements Message {}

    /**
     * The answer to {@link Resume} (SOS-178): the answering PC has had {@code received} {@link Sequenced} messages from
     * the asking one, which sends again those after it; -1: it doesn't know the asking PC's connection any more (it
     * started again, or removed that player). The transports consume it.
     */
    record Resumed(long received) implements Message {}

    /**
     * A joined PC to the host: its player chose Quit to menu or Exit (SOS-79). The host saves the campaign on every PC as
     * soon as a save can start, and then the session ends; a repeated ask changes nothing.
     */
    record QuitAsk() implements Message {}

    /**
     * A joined PC to the host: its player chose Leave and freeze (spec §11 Freezing a player, SOS-194). The host saves
     * the campaign on every PC as soon as a save can start, with this player as its quitter; once it is done, that PC
     * leaves and the host freezes its human. A repeated ask changes nothing.
     */
    record FreezeAsk() implements Message {}

    /**
     * City viewing (spec §10, SOS-82), the viewer's PC to the host: its player, in slot {@code viewer}, asks to view the
     * city of the player in slot {@code owner}. The host refuses at once when the view couldn't begin, or passes the
     * ask to the owner's PC. Slots are the PCs' numbers.
     */
    record ViewAsk(int viewer, int owner) implements Message {}

    /**
     * The owner's PC to the host, then the host to the viewer's PC: the owner allowed the view or refused it
     * ({@code reason} says why; empty when allowed). The host refuses this way too, at once.
     */
    record ViewAnswer(int viewer, int owner, boolean allowed, String reason) implements Message {}

    /**
     * The viewer's PC to the host: its view ends, for {@code why} (the engine's {@code View.CLOSED} to
     * {@code View.DESYNC}: closed, not caught up, fell behind, lost step). The host logs the end in the engine.
     */
    record ViewClose(int viewer, int why) implements Message {}

    /**
     * The owner's PC to the host, then the host to the viewer's PC only: {@code bytes} at {@code offset} of the owner's
     * city for the view freezing at {@code step} ({@code Engine.viewOut}), {@code total} bytes in all, in order.
     */
    record ViewCity(int viewer, long step, int total, int offset, byte[] bytes) implements Message {
        public ViewCity {
            if (step < 0 || total < 0 || offset < 0 || offset + bytes.length > total)
                throw new IllegalArgumentException("SOSGSGMP: a view's city piece at " + offset + " of " + total + " bytes, step " + step);
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
            return o instanceof ViewCity c && viewer == c.viewer && step == c.step && total == c.total && offset == c.offset
                    && Arrays.equals(bytes, c.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(viewer, step, total, offset, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "ViewCity[viewer=" + viewer + ", step=" + step + ", " + offset + "+" + bytes.length + " of " + total + " bytes]";
        }
    }

    /**
     * The battle's PC to the host, then the host to the watcher's PC only: one piece of the battle's tape for the player
     * in slot {@code viewer} ({@code Engine.viewTapeOut}), in order (SOS-109).
     */
    record ViewTape(int viewer, byte[] bytes) implements Message {
        public ViewTape {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ViewTape t && viewer == t.viewer && Arrays.equals(bytes, t.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(viewer, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "ViewTape[viewer=" + viewer + ", " + bytes.length + " bytes]";
        }
    }

    /**
     * The owner's PC to the host, then the host to the viewer's PC only: more of the owner's view hashes for the viewer
     * in slot {@code viewer} ({@code Engine.viewHashOut}), in order.
     */
    record ViewHashes(int viewer, byte[] bytes) implements Message {
        public ViewHashes {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ViewHashes h && viewer == h.viewer && Arrays.equals(bytes, h.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(viewer, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "ViewHashes[viewer=" + viewer + ", " + bytes.length + " bytes]";
        }
    }

    /**
     * A joining PC to the host: {@code bytes} at {@code offset} of one of its files for the host's reports (SOS-133):
     * a log that still grows ({@code total} -1) or a whole file of {@code total} bytes, a save or a crash file.
     */
    record Report(String name, long total, long offset, byte[] bytes) implements Message {
        /** Longest file name in a report, in characters. */
        public static final int MAX_NAME = 160;
        /** Largest whole file in a report, in bytes. */
        public static final long MAX_TOTAL = 512L * 1024 * 1024;

        public Report {
            if (name.isEmpty() || name.length() > MAX_NAME || total < -1 || total > MAX_TOTAL || offset < 0
                    || total >= 0 && offset + bytes.length > total)
                throw new IllegalArgumentException("SOSGSGMP: a report piece of '" + name + "' at " + offset + "+" + bytes.length
                        + " of " + total + " bytes");
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
            return o instanceof Report r && name.equals(r.name) && total == r.total && offset == r.offset
                    && Arrays.equals(bytes, r.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, total, offset, Arrays.hashCode(bytes));
        }

        @Override
        public String toString() {
            return "Report[" + name + ", " + offset + "+" + bytes.length + " of " + total + " bytes]";
        }
    }

    /** A message from another protocol version; only its version is read. */
    record Unsupported(int version) implements Message {}
}
