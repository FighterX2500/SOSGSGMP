package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.LobbyState;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.net.Pieces;
import sosgsgmp.net.Protocol;
import sosgsgmp.net.StreamKind;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep.PlayerPick;
import sosgsgmp.setup.FactionStep.Progress;
import sosgsgmp.setup.FactionWorld;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStatus;
import sosgsgmp.setup.MapStep.PlayerState;
import sosgsgmp.setup.MapWorld;

/**
 * A real host and two joining PCs over localhost, from Start Lobby through the map step and the picks, with a fake
 * world in place of the game. Each PC's frame updates its session and, when a build is pending, "rebuilds" at once.
 */
class LoopbackSetupTest {

    private static final MapOptions A = new MapOptions(256, MapOptions.RANDOM, 50, 11);
    private static final MapOptions B = new MapOptions(256, "Continent", 20, 22);

    /**
     * Terrain as a function of the options, sent as bytes that take several pieces; a per-PC offset fakes a PC whose
     * loaded map comes out different, and {@code refuse} one whose game can't load it.
     */
    private static final class FakeWorld implements MapWorld, FactionWorld {
        final List<MapOptions> generated = new ArrayList<>();
        final List<MapOptions> loaded = new ArrayList<>();
        long offset;
        boolean refuse;
        int locks;
        final List<List<FactionPick>> built = new ArrayList<>();
        String difficulty;

        @Override
        public Terrain generate(MapOptions options) {
            generated.add(options);
            return new Terrain(hash(options), bytes(options));
        }

        @Override
        public long load(MapOptions options, byte[] terrain) {
            if (refuse)
                throw new IllegalArgumentException("fake: this game can't load it");
            MapOptions sent = options(terrain);
            assertEquals(options, sent, "the terrain belongs to the generation it came with");
            loaded.add(sent);
            return hash(sent);
        }

        private long hash(MapOptions o) {
            return o.hashCode() * 31L + offset;
        }

        private static byte[] bytes(MapOptions o) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(b)) {
                out.writeShort(o.size());
                out.writeUTF(o.mapType());
                out.writeByte(o.latitude());
                out.writeInt(o.seed());
                out.write(new byte[2 * Pieces.PIECE_BYTES]);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return b.toByteArray();
        }

        private static MapOptions options(byte[] terrain) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(terrain))) {
                return new MapOptions(in.readUnsignedShort(), in.readUTF(), in.readUnsignedByte(), in.readInt());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void lock() {
            locks++;
        }

        /** The engine's rule as far as these tests need it: no closer than 6 tiles. */
        @Override
        public String locationProblem(int x, int y, int[] otherX, int[] otherY) {
            for (int i = 0; i < otherX.length; i++)
                if (Math.hypot(x - otherX[i], y - otherY[i]) < 6)
                    return "too close";
            return null;
        }

        /** A save over three pieces whose bytes follow the humans' names, so every PC can check it got this one. */
        @Override
        public byte[] buildWorld(List<FactionPick> humans, String difficulty) {
            built.add(humans);
            this.difficulty = difficulty;
            return save(humans);
        }

        static byte[] save(List<FactionPick> humans) {
            byte[] s = new byte[2 * Pieces.PIECE_BYTES + 77];
            for (int i = 0; i < s.length; i++)
                s[i] = (byte) (i * 31 + humans.toString().hashCode());
            return s;
        }
    }

    private final List<LobbySession> lobbies = new ArrayList<>();
    private final List<SetupSession> setups = new ArrayList<>();
    private final FakeWorld hostWorld = new FakeWorld();
    private final FakeWorld bobWorld = new FakeWorld();
    private final FakeWorld cidWorld = new FakeWorld();
    private HostedLobby lobby;
    private SetupSession host;
    private SetupSession bob;
    private SetupSession cid;

    @AfterEach
    void closeAll() {
        for (SetupSession s : setups)
            s.leave();
        for (LobbySession s : lobbies)
            s.leave();
    }

    /** Ann hosts, Bob and Cid join, Ann starts the lobby, and every PC hands its lobby over to the setup. */
    private void startSetup() throws Exception {
        HostedLobby h = new HostedLobby(new LobbySettings("normal", 4), "Ann", 0);
        lobby = h;
        lobbies.add(h);
        JoinedLobby jb = join(h, "Bob");
        // Cid joins once the host has Bob, so the ids follow the names: Ann 0, Bob 1, Cid 2
        untilLobbies("Bob joined", () -> h.players().size() == 2);
        JoinedLobby jc = join(h, "Cid");
        untilLobbies("everyone joined", () -> h.players().size() == 3 && jb.players().size() == 3 && jc.players().size() == 3);
        h.start();
        untilLobbies("everyone started", () -> jb.state() == LobbyState.STARTED && jc.state() == LobbyState.STARTED);
        host = h.handOver(hostWorld, hostWorld);
        bob = jb.handOver(bobWorld, bobWorld);
        cid = jc.handOver(cidWorld, cidWorld);
        setups.addAll(List.of(host, bob, cid));
    }

    private JoinedLobby join(HostedLobby h, String name) throws IOException {
        JoinedLobby j = new JoinedLobby("127.0.0.1", h.port(), name, name.hashCode());
        lobbies.add(j);
        return j;
    }

    private void untilLobbies(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (LobbySession s : lobbies)
                s.update();
            if (condition.getAsBoolean())
                return;
            Thread.sleep(5);
        }
        fail("timed out waiting for: " + what);
    }

    /** Runs every PC's frame, as the map stage would, until the condition holds. */
    private void until(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (SetupSession s : setups) {
                s.update();
                if (!s.closed() && s.step().pendingBuild() > 0)
                    s.built();
            }
            if (condition.getAsBoolean())
                return;
            Thread.sleep(5);
        }
        fail("timed out waiting for: " + what);
    }

    /** The host builds at {@code size}; returns once every PC has the build and the host knows it. */
    private void build(int size) throws InterruptedException {
        host.build(size);
        int buildId = host.step().buildId();
        until("build " + buildId + " everywhere", () -> host.step().state(1) == PlayerState.READY && host.step().state(2) == PlayerState.READY
                && host.step().canGenerate() && bob.step().buildId() == buildId && cid.step().buildId() == buildId);
    }

    @Test
    void buildGenerateAndConfirmReachEveryPc() throws Exception {
        startSetup();
        assertEquals(List.of("Ann", "Bob", "Cid"), bob.players().stream().map(LobbyPlayer::name).toList());
        build(256);
        assertEquals(256, bob.step().size());

        host.generate(A);
        until("matching results", host.step()::canConfirm);
        assertEquals(List.of(A), hostWorld.generated);
        assertEquals(List.of(A), bobWorld.loaded);
        assertEquals(List.of(A), cidWorld.loaded);
        assertTrue(bobWorld.generated.isEmpty(), "a joining PC loads the host's map, never generates its own");
        assertEquals(PlayerState.MATCH, host.step().state(1));
        assertEquals(host.step().hash(), bob.step().hash());

        host.confirm();
        until("confirmed everywhere", () -> bob.step().confirmed() && cid.step().confirmed());
        assertEquals(1, hostWorld.locks);
        assertEquals(1, bobWorld.locks);
        assertEquals(host.step().hash(), cid.step().confirmedHash());
        assertFalse(host.step().canGenerate());
    }

    /** Through the map step to the confirm on every PC. */
    private void confirmMap() throws Exception {
        startSetup();
        build(256);
        host.generate(A);
        until("matching results", host.step()::canConfirm);
        host.confirm();
        until("factions everywhere", () -> bob.factions() != null && cid.factions() != null);
    }

    private static FactionPick pick(String name, int x, int y) {
        return new FactionPick("HUMAN", name, new byte[] {1, 2, 3}, x, y);
    }

    private static List<String> names(SetupSession s) {
        return s.factions().table().stream().map(p -> p.pick().name()).toList();
    }

    @Test
    void picksReachEveryPcInTheHostsOrder() throws Exception {
        confirmMap();
        assertThrows(IllegalStateException.class, () -> host.factions().onPicks(List.of()), "the host decides; it follows no table");
        cid.pick(pick("Cid", 40, 40));
        bob.pick(pick("Bob", FactionPick.NONE, FactionPick.NONE));
        host.pick(pick("Ann", 10, 10));
        until("every pick on every PC", () -> names(bob).size() == 3 && names(cid).size() == 3);
        assertEquals(List.of("Ann", "Bob", "Cid"), names(bob), "the host first, then the join order");
        assertEquals(host.factions().table(), cid.factions().table());
        assertFalse(host.factions().canInitialize(), "Bob has no location");

        bob.pick(pick("Bob", 20, 10));
        until("Bob's location on the host", host.factions()::canInitialize);
    }

    @Test
    void aLocationTooCloseIsClearedOnEveryPc() throws Exception {
        confirmMap();
        host.pick(pick("Ann", 10, 10));
        until("Ann's pick on Bob's PC", () -> names(bob).size() == 1);
        assertEquals("too close", bob.locationProblem(12, 12), "Bob's PC checks before it sends");
        assertEquals(null, bob.locationProblem(30, 30));
        bob.pick(pick("Bob", 12, 12));
        until("Bob's refusal on Cid's PC", () -> names(cid).size() == 2);
        PlayerPick refused = cid.factions().table().get(1);
        assertFalse(refused.pick().hasLocation());
        assertEquals("too close", refused.refused());
        assertEquals(refused, bob.factions().table().get(1), "Bob sees why too");
        assertEquals(pick("Bob", 12, 12), bob.factions().ownPick(), "this PC's own pick stays as sent until it changes it");
    }

    @Test
    void aPlayerLeavingDuringThePicksLeavesEveryTable() throws Exception {
        confirmMap();
        host.pick(pick("Ann", 10, 10));
        bob.pick(pick("Bob", 20, 10));
        cid.pick(pick("Cid", 30, 10));
        until("three picks on Bob's PC", () -> names(bob).size() == 3);
        cid.leave();
        until("Cid gone from Bob's table", () -> names(bob).equals(List.of("Ann", "Bob")));
        assertTrue(host.factions().canInitialize());
    }

    /** Through the picks, every player with a location, to the host's Initialize World; returns the host's save. */
    private byte[] initialize() throws Exception {
        confirmMap();
        host.pick(pick("Ann", 10, 10));
        bob.pick(pick("Bob", 20, 10));
        cid.pick(pick("Cid", 30, 10));
        until("every location on the host", host.factions()::canInitialize);
        host.initialize();
        return host.takeWorld();
    }

    /** Runs the frames until this PC has the whole save, and takes it. */
    private byte[] world(SetupSession s) throws InterruptedException {
        byte[][] got = new byte[1][];
        until("the world on a joining PC", () -> (got[0] = s.takeWorld()) != null);
        return got[0];
    }

    @Test
    void initializeBuildsOnceAndSendsTheSaveToEveryPc() throws Exception {
        byte[] save = initialize();
        assertEquals(1, hostWorld.built.size());
        assertEquals(List.of("Ann", "Bob", "Cid"), hostWorld.built.get(0).stream().map(FactionPick::name).toList(), "slot order");
        assertEquals("normal", hostWorld.difficulty, "the lobby's difficulty");
        assertTrue(bobWorld.built.isEmpty() && cidWorld.built.isEmpty(), "only the host builds");
        assertArrayEquals(FakeWorld.save(hostWorld.built.get(0)), save);
        assertEquals(null, host.takeWorld(), "taken once");
        assertArrayEquals(save, world(bob));
        assertArrayEquals(save, world(cid));
        assertEquals(1, bob.factions().slotOf(1));
        assertEquals(2, cid.factions().slotOf(2));
        assertFalse(host.factions().canInitialize(), "the picks are locked");
    }

    @Test
    void everyPcsLoadReachesEveryPc() throws Exception {
        initialize();
        world(bob);
        world(cid);
        host.worldLoaded(77);
        bob.worldLoaded(77);
        cid.worldLoaded(78);
        until("every load on the host", () -> host.factions().progress(1) == Progress.LANDING && host.factions().progress(2) == Progress.MISMATCH);
        until("Cid's mismatch on Bob's PC", () -> bob.factions().progress(2) == Progress.MISMATCH && bob.factions().progress(0) == Progress.LANDING);
        until("Bob loaded on Cid's PC", () -> cid.factions().progress(1) == Progress.LANDING);
    }

    @Test
    void theGameStartsEverywhereOnceEveryLandingPartyIsPlaced() throws Exception {
        initialize();
        world(bob);
        world(cid);
        host.worldLoaded(77);
        bob.worldLoaded(77);
        cid.worldLoaded(77);
        bob.startSpot();
        host.startSpot();
        until("Ann and Bob ready on Cid's PC", () -> cid.factions().progress(0) == Progress.READY && cid.factions().progress(1) == Progress.READY);
        assertFalse(host.factions().started() || cid.factions().started(), "Cid hasn't placed");
        until("Cid still landing on Bob's PC", () -> bob.factions().progress(2) == Progress.LANDING);
        cid.startSpot();
        until("started everywhere", () -> host.factions().started() && bob.factions().started() && cid.factions().started());
    }

    @Test
    void theEngineStreamsReachTheirTakersOnceEach() throws Exception {
        EngineLinkTest.FakePort hp = new EngineLinkTest.FakePort();
        EngineLinkTest.FakePort bp = new EngineLinkTest.FakePort();
        EngineLinkTest.FakePort cp = new EngineLinkTest.FakePort();
        playing(hp, bp, cp);
        int b = host.factions().slotOf(bob.localPlayerId());
        int c = host.factions().slotOf(cid.localPlayerId());
        hp.make(StreamKind.AI_LOG, (byte) 1, (byte) 2, (byte) 3);
        hp.make(StreamKind.HASHES, (byte) 9);
        bp.make(StreamKind.ORDERS, (byte) 4, (byte) 5);
        bp.make(StreamKind.REPORTS, (byte) 6);
        cp.make(StreamKind.HASHES, (byte) 7);
        playUntil("every stream at its takers", () -> cp.took(StreamKind.AI_LOG, 0).length == 3 && bp.took(StreamKind.AI_LOG, 0).length == 3
                && hp.took(StreamKind.ORDERS, b).length == 2 && cp.took(StreamKind.REPORTS, b).length == 1
                && bp.took(StreamKind.HASHES, c).length == 1 && bp.took(StreamKind.HASHES, 0).length == 1 && cp.took(StreamKind.HASHES, 0).length == 1
                && hp.took(StreamKind.REPORTS, b).length == 1 && hp.took(StreamKind.HASHES, c).length == 1);
        assertArrayEquals(new byte[] {1, 2, 3}, cp.took(StreamKind.AI_LOG, 0));
        assertArrayEquals(new byte[] {4, 5}, hp.took(StreamKind.ORDERS, b));
        assertEquals(0, cp.took(StreamKind.ORDERS, b).length, "orders go to the host only");
        assertEquals(0, bp.took(StreamKind.REPORTS, b).length, "a PC gets none of its own back");
        hp.make(StreamKind.AI_LOG, (byte) 8);
        playUntil("more of the log", () -> cp.took(StreamKind.AI_LOG, 0).length == 4);
        assertArrayEquals(new byte[] {1, 2, 3, 8}, cp.took(StreamKind.AI_LOG, 0), "in order, each byte once");
    }

    @Test
    void aJoinersBattleSaveReachesEveryOtherPcWhole() throws Exception {
        EngineLinkTest.FakePort hp = new EngineLinkTest.FakePort();
        EngineLinkTest.FakePort bp = new EngineLinkTest.FakePort();
        EngineLinkTest.FakePort cp = new EngineLinkTest.FakePort();
        playing(hp, bp, cp);
        int b = host.factions().slotOf(bob.localPlayerId());
        byte[] save = new byte[Protocol.MAX_STREAM_PIECE * 3 + 11];
        for (int i = 0; i < save.length; i++)
            save[i] = (byte) (i * 7);
        bp.make(StreamKind.BATTLE, BattleSaves.frame(900, save));
        playUntil("the battle save at the host and Cid", () -> hp.took(StreamKind.BATTLE, b).length == save.length + 12
                && cp.took(StreamKind.BATTLE, b).length == save.length + 12);
        BattleSaves at = new BattleSaves();
        at.in(b, cp.took(StreamKind.BATTLE, b));
        assertArrayEquals(save, at.take(900), "relayed by the host, whole and in order");
    }

    /** Every PC loaded the world, landed and plays with these engines. */
    private void playing(EnginePort hp, EnginePort bp, EnginePort cp) throws Exception {
        initialize();
        world(bob);
        world(cid);
        for (SetupSession s : List.of(host, bob, cid))
            s.worldLoaded(77);
        for (SetupSession s : List.of(host, bob, cid))
            s.startSpot();
        until("started everywhere", () -> host.factions().started() && bob.factions().started() && cid.factions().started());
        host.play(hp);
        bob.play(bp);
        cid.play(cp);
    }

    @Test
    void theHostsWorldResyncReachesEveryPcWholeAndOnce() throws Exception {
        playing(new EngineLinkTest.FakePort(), new EngineLinkTest.FakePort(), new EngineLinkTest.FakePort());
        byte[] save = new byte[Pieces.PIECE_BYTES * 2 + 17];
        for (int i = 0; i < save.length; i++)
            save[i] = (byte) i;
        WorldResync sent = new WorldResync(1234, new byte[] {5, 6, 7}, save);
        host.resync(sent);
        WorldResync own = host.takeResync();
        assertEquals(1234, own.step());
        assertNull(host.takeResync(), "taken once");
        WorldResync[] got = new WorldResync[2];
        playUntil("the resync at both joiners", () -> {
            if (got[0] == null)
                got[0] = bob.takeResync();
            if (got[1] == null)
                got[1] = cid.takeResync();
            return got[0] != null && got[1] != null;
        });
        for (WorldResync r : got) {
            assertEquals(1234, r.step());
            assertArrayEquals(new byte[] {5, 6, 7}, r.state());
            assertArrayEquals(save, r.save());
        }
        playUntil("a few more rounds", () -> true);
        assertNull(bob.takeResync(), "taken once, and nothing more came");
        assertThrows(IllegalStateException.class, () -> bob.resync(sent), "only the host resyncs");
    }

    @Test
    void saveAndExitReachesEveryPc() throws Exception {
        playing(new EngineLinkTest.FakePort(), new EngineLinkTest.FakePort(), new EngineLinkTest.FakePort());
        assertEquals(-1, bob.exitStep());
        host.desyncExit(900);
        assertEquals(900, host.exitStep());
        playUntil("save and exit at both joiners", () -> bob.exitStep() == 900 && cid.exitStep() == 900);
        assertThrows(IllegalStateException.class, () -> cid.desyncExit(900), "only the host chooses");
    }

    /** {@link #until}, with every playing PC sending what its engine made each round. */
    private void playUntil(String what, BooleanSupplier condition) throws InterruptedException {
        until(what, () -> {
            for (SetupSession s : setups)
                if (s.playing())
                    s.exchange();
            return condition.getAsBoolean();
        });
    }

    @Test
    void aWorldThatDiffersNeverStarts() throws Exception {
        initialize();
        world(bob);
        world(cid);
        host.worldLoaded(77);
        bob.worldLoaded(77);
        cid.worldLoaded(78);
        host.startSpot();
        bob.startSpot();
        cid.startSpot();
        until("every start spot on the host", () -> host.factions().progress(1) == Progress.READY && host.factions().progress(2) == Progress.MISMATCH);
        until("Bob sees Cid's mismatch", () -> bob.factions().progress(2) == Progress.MISMATCH);
        assertFalse(host.factions().started());
        assertFalse(bob.factions().started());
    }

    @Test
    void aPlayerLeavingAfterTheBuildEndsTheSetup() throws Exception {
        initialize();
        world(bob);
        cid.leave();
        until("the setup closed everywhere", () -> host.closed() && bob.closed());
        assertEquals("Cid left after the world was built; the game can't start without them.", host.closedReason());
    }

    @Test
    void picksBeforeTheConfirmAreRefused() throws Exception {
        startSetup();
        assertThrows(IllegalStateException.class, () -> host.pick(pick("Ann", 10, 10)));
        assertThrows(IllegalStateException.class, () -> bob.pick(pick("Bob", 10, 10)));
    }

    @Test
    void everyGenerationRunsInOrderOnEveryPc() throws Exception {
        startSetup();
        build(256);
        host.generate(A);
        host.generate(B);
        until("results for the second", host.step()::canConfirm);
        assertEquals(List.of(A, B), bobWorld.loaded);
        assertEquals(List.of(A, B), cidWorld.loaded);
        assertEquals(B, cid.step().options());
    }

    @Test
    void aPcWithAnotherHashBlocksConfirm() throws Exception {
        startSetup();
        cidWorld.offset = 1;
        build(256);
        host.generate(A);
        until("Cid's result", () -> host.step().state(2) == PlayerState.MISMATCH && host.step().state(1) == PlayerState.MATCH);
        assertFalse(host.step().canConfirm());
    }

    @Test
    void aJoiningPcSeesTheOtherPlayersProgress() throws Exception {
        startSetup();
        cidWorld.offset = 1;
        build(256);
        until("Bob sees Cid ready", () -> bob.step().state(2) == PlayerState.READY);
        host.generate(A);
        // Bob's own match and Cid's mismatch reach Bob in separate messages: wait for all three
        until("Bob sees Cid's mismatch, his own match and the host's map", () -> bob.step().state(2) == PlayerState.MISMATCH
                && bob.step().state(1) == PlayerState.MATCH && bob.step().state(0) == PlayerState.GENERATED);
        until("Cid sees its own mismatch", () -> cid.step().state(2) == PlayerState.MISMATCH && cid.step().state(1) == PlayerState.MATCH);

        cid.leave();
        until("Cid gone from Bob's rows", () -> bob.players().size() == 2 && bob.step().state(2) == null);
    }

    @Test
    void aPcThatCantLoadTheTerrainIsAnError() throws Exception {
        startSetup();
        bobWorld.refuse = true;
        build(256);
        host.generate(A);
        until("Bob's result", () -> host.step().state(1) == PlayerState.ERROR);
        assertEquals(MapStatus.BAD_TERRAIN, bob.step().status());
        assertFalse(host.step().canConfirm());
    }

    @Test
    void aGenerateAtANewSizeRebuildsEveryPcThenGenerates() throws Exception {
        startSetup();
        build(256);
        host.generate(A);
        until("matching results", host.step()::canConfirm);

        MapOptions big = new MapOptions(512, MapOptions.RANDOM, 30, 99);
        host.generate(big);
        assertEquals(big, host.choices(), "the host's choices outlive its rebuild");
        until("matching results at 512", () -> host.step().size() == 512 && host.step().canConfirm());
        assertEquals(2, bob.step().buildId());
        assertEquals(512, cid.step().size());
        assertEquals(List.of(A, big), hostWorld.generated);
        assertEquals(List.of(A, big), bobWorld.loaded);
        assertEquals(List.of(A, big), cidWorld.loaded);
        assertEquals(big, bob.choices());
        assertThrows(IllegalArgumentException.class, () -> host.generate(new MapOptions(512, "Continent", 50, 1)),
                "a preset at a size other than 256 is refused before any rebuild");
    }

    @Test
    void aSizeChangeRebuildsEveryPc() throws Exception {
        startSetup();
        build(256);
        host.generate(A);
        until("matching results", host.step()::canConfirm);

        build(512);
        assertEquals(2, bob.step().buildId());
        assertEquals(512, cid.step().size());
        assertFalse(host.step().canConfirm());
        MapOptions big = new MapOptions(512, MapOptions.RANDOM, 50, 11);
        host.generate(big);
        until("matching results at 512", host.step()::canConfirm);
        assertEquals(List.of(A, big), bobWorld.loaded);
    }

    @Test
    void aCursorReachesEveryOtherPcAndGoesWhenItsPlayerLeaves() throws Exception {
        confirmMap();
        bob.cursor(true, 120, 340);
        until("Bob's cursor on the host and on Cid's PC", () -> at(host, 1, 120, 340) && at(cid, 1, 120, 340));
        assertEquals(0, bob.cursors().count(), "a PC doesn't see its own");
        host.cursor(true, 5, 6);
        until("Ann's cursor on Bob's PC", () -> at(bob, 0, 5, 6));
        bob.cursor(false, 0, 0);
        until("Bob's cursor off the map on Cid's PC", () -> cid.cursors().count() == 1);
        cid.cursor(true, 1, 1);
        until("Cid's cursor on the host", () -> at(host, 2, 1, 1));
        cid.leave();
        until("Cid's cursor gone with Cid", () -> host.cursors().count() == 0 && bob.players().size() == 2 && !at(bob, 2, 1, 1));
    }

    /** Whether {@code s} shows player {@code id}'s cursor at {@code x, y}. */
    private static boolean at(SetupSession s, int id, int x, int y) {
        Cursors c = s.cursors();
        for (int i = 0; i < c.count(); i++)
            if (c.playerId(i) == id)
                return c.x(i) == x && c.y(i) == y;
        return false;
    }

    @Test
    void aPlayerLeavingUnblocksConfirmAndLeavesEveryRoster() throws Exception {
        startSetup();
        cidWorld.offset = 1;
        build(256);
        host.generate(A);
        until("Cid's mismatch", () -> host.step().state(2) == PlayerState.MISMATCH);

        cid.leave();
        until("Cid gone", () -> host.players().size() == 2 && bob.players().size() == 2);
        assertTrue(host.step().canConfirm());
    }

    @Test
    void aLatePcIsRefused() throws Exception {
        startSetup();
        JoinedLobby late = join(lobby, "Late");
        until("Late refused", () -> {
            late.update();
            return late.state() == LobbyState.CLOSED;
        });
        assertEquals(Refusal.STARTED.text, late.closedReason());
        assertEquals(3, host.players().size());
    }

    @Test
    void theHostLeavingClosesTheSetupEverywhere() throws Exception {
        startSetup();
        build(256);
        host.leave();
        until("closed everywhere", () -> bob.closed() && cid.closed());
        assertEquals("The host closed the game.", bob.closedReason());
    }
}
