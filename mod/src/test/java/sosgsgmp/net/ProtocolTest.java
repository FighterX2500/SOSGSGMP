package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.campaign.CampaignStoreTest;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.lobby.Builds;
import sosgsgmp.lobby.GameFiles;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;
import sosgsgmp.setup.MapStatus;
import sosgsgmp.setup.MapStep.PlayerState;
import sosgsgmp.setup.MapStep.PlayerStatus;

class ProtocolTest {

    @Test
    void everyMessageRoundTrips() throws IOException {
        List<Message> all = List.of(
            new Message.Hello("Ann", -42L, new Builds("0.71.44", 1, 2, new GameFiles(3, List.of()))),
            new Message.Welcome(3, new LobbySettings("hard", 16)),
            new Message.Welcome(4, new LobbySettings("hard", 16).withFullTransferLog(false)),
            new Message.Welcome(5, new LobbySettings("hard", 16).withLivingWorld(true)),
            new Message.Roster(List.of(new LobbyPlayer(0, "Ann", true), new LobbyPlayer(5, "Bøb", false))),
            new Message.Refused(Refusal.FULL),
            new Message.Hello("Bob", 7L, new Builds("0.71.44", 1, 2, new GameFiles(-5, List.of(
                    new GameFiles.Mod("SOSDET", "1.0-beta", 11), new GameFiles.Mod("Région rework", "0.71.44.17", Long.MIN_VALUE))))),
            new Message.Refused(Refusal.WRONG_FILES, List.of("Mod 'X' 1.2: you have it enabled, the host doesn't.", "")),
            new Message.Start(),
            new Message.Leave(),
            new Message.MapBuild(1, 512),
            new Message.BuildReady(Integer.MAX_VALUE),
            new Message.MapGenerate(3, 2, "", 100, Integer.MAX_VALUE),
            new Message.MapGenerate(4, 2, "Ödland map", 0, 0),
            new Message.MapTerrain(3, 0, 1, new byte[0]),
            new Message.MapTerrain(3, 2, 3, new byte[] {1, -2, 3}),
            new Message.MapTerrain(Integer.MAX_VALUE, Pieces.MAX_PIECES - 1, Pieces.MAX_PIECES, new byte[Pieces.PIECE_BYTES]),
            new Message.Pick(new FactionPick("HUMAN", "Ånn's Realm", "Ruler", new byte[] {7, 8}, 511, 0)),
            new Message.Pick(new FactionPick("CANTOR", "B", "Ruler", new byte[0], FactionPick.NONE, FactionPick.NONE)),
            new Message.Ready(true),
            new Message.Ready(false),
            new Message.Picks(List.of(new FactionStep.PlayerPick(0, new FactionPick("HUMAN", "A", "Ruler", new byte[] {1}, 3, 4), null, true),
                new FactionStep.PlayerPick(7, new FactionPick("HUMAN", "B", "Ruler", new byte[] {2}, FactionPick.NONE, FactionPick.NONE), "too close", false))),
            new Message.SlotMap(1, List.of(new FactionStep.PlayerSlot(0, 0), new FactionStep.PlayerSlot(7, 15), new FactionStep.PlayerSlot(8, 37))),
            new Message.WorldPart(1, 0, 2, new byte[] {9, 8, 7}),
            new Message.SettlePart(1, 3, new byte[] {7, 8, 9}),
            new Message.Running(false),
            new Message.Running(true),
            new Message.JoinPick(new FactionPick("HUMAN", "Newcomers", "Ruler", new byte[] {1, 2}, 251, 25)),
            new Message.JoinPicked(""),
            new Message.JoinPicked("Too close to another player's capital."),
            new Message.Restart(4, CampaignStoreTest.CAMPAIGN_FOR_TESTS, 9, new FactionPick("HUMAN", "Newcomers", "", new byte[0], 251, 25), 2500.5),
            new Message.Restarted(),
            new Message.Loaded(1, Long.MIN_VALUE),
            new Message.StartSpot(1),
            new Message.WorldPlayers(1, List.of(new FactionStep.PlayerProgress(0, FactionStep.Progress.READY),
                new FactionStep.PlayerProgress(7, FactionStep.Progress.MISMATCH))),
            new Message.Go(Integer.MAX_VALUE),
            new Message.Cursor(7, sosgsgmp.session.Cursors.WORLD, -3, Integer.MAX_VALUE),
            new Message.Cursor(3, sosgsgmp.session.Cursors.city(15), 4, 5),
            new Message.Cursor(4, sosgsgmp.session.Cursors.battle(15), 6, 7),
            new Message.Cursor(5, sosgsgmp.session.Cursors.BATTLE, 8, 9),
            new Message.Stream(StreamKind.AI_LOG, 0, 0, 0, new byte[0]),
            new Message.Stream(StreamKind.HASHES, 15, Long.MAX_VALUE, 7, new byte[Protocol.MAX_STREAM_PIECE]),
            new Message.Cursor(0, sosgsgmp.session.Cursors.OFF, 0, 0),
            new Message.Pause(7, true),
            new Message.Loading(sosgsgmp.session.Loading.Kind.VIEW_END.ordinal(), Long.MAX_VALUE, "Bob is returning…", true),
            new Message.Loading(0, 0, "", false),
            new Message.Chat(7, "Привет, world"),
            new Message.Chat(0, "x".repeat(sosgsgmp.session.Chat.MAX_TEXT)),
            new Message.Resync(1, 0, 0, 1, new byte[0]),
            new Message.Resync(Integer.MAX_VALUE, Long.MAX_VALUE, 2, 3, new byte[Pieces.PIECE_BYTES]),
            new Message.Welcome(3, LobbySettings.resuming(new LobbySettings.Resume(CampaignStoreTest.CAMPAIGN_FOR_TESTS, 12))),
            new Message.SaveAt(new SaveOrder(1, 0, CampaignRecord.Kind.SAVE, 0, CampaignStoreTest.CAMPAIGN_FOR_TESTS, false)),
            new Message.SaveAt(new SaveOrder(Integer.MAX_VALUE, Long.MAX_VALUE, CampaignRecord.Kind.AUTO, 10, CampaignStoreTest.CAMPAIGN_FOR_TESTS, true)),
            new Message.Saved(2, Long.MIN_VALUE, ""),
            new Message.Saved(2, 0, "past the save step"),
            new Message.SaveDone(5, ""),
            new Message.SaveAt(new SaveOrder(6, 90, CampaignRecord.Kind.AUTO, 3,
                    CampaignStoreTest.CAMPAIGN_FOR_TESTS.removing(java.util.Set.of(1)), false)),
            new Message.PlayerGone(7, 0),
            new Message.PlayerGone(0, Long.MAX_VALUE),
            new Message.GoneChoice(1234, Message.GoneChoice.CONTINUE),
            new Message.GoneChoice(1235, Message.GoneChoice.FREEZE),
            new Message.SaveAt(new SaveOrder(9, 130, CampaignRecord.Kind.SAVE, 0,
                    CampaignStoreTest.CAMPAIGN_FOR_TESTS.freezing(java.util.Set.of(1)), false, 1)),
            new Message.FreezeAsk(),
            new Message.PlayerLost(2, 530, 5100),
            new Message.PlayerBack(2, 530),
            new Message.Heartbeat(1794149400266L, 0, 0, 0),
            new Message.Heartbeat(5, 1794149400266L, 37, 912),
            new Message.Sequenced(1, new Message.Leave()),
            new Message.Sequenced(Long.MAX_VALUE, new Message.Chat(3, "seq")),
            new Message.Resume(0x1234_5678_9ABC_DEF0L, 0, 1),
            new Message.Resume(-7, 41, 0),
            new Message.Resumed(-1),
            new Message.Resumed(1_000_000),
            new Message.GoneChoice(0, Message.GoneChoice.RETURN),
            new Message.SaveAt(new SaveOrder(7, 120, CampaignRecord.Kind.SAVE, 0, CampaignStoreTest.CAMPAIGN_FOR_TESTS, true, 5)),
            new Message.SaveAt(new SaveOrder(8, 120, CampaignRecord.Kind.SAVE, 0, CampaignStoreTest.CAMPAIGN_FOR_TESTS, true, 0)),
            new Message.QuitAsk(),
            new Message.ViewAsk(0, 15),
            new Message.ViewAnswer(15, 0, true, ""),
            new Message.ViewAnswer(3, 1, false, "Its player is viewing another city."),
            new Message.ViewClose(3, 0),
            new Message.ViewClose(15, Protocol.MAX_VIEW_CLOSE),
            new Message.ViewCity(3, 0, 0, 0, new byte[0]),
            new Message.ViewCity(3, Long.MAX_VALUE, Protocol.MAX_VIEW_CITY, Protocol.MAX_VIEW_CITY - Protocol.MAX_STREAM_PIECE,
                    new byte[Protocol.MAX_STREAM_PIECE]),
            new Message.ViewHashes(1, new byte[] {1, -2, 3}),
            new Message.ViewTape(1, new byte[] {4, -5, 6}),
            new Message.ViewHashes(15, new byte[Protocol.MAX_STREAM_PIECE]),
            new Message.Report("20261102-232627-join-Adam_Smith.log", -1, 0, new byte[] {7, 8}),
            new Message.Report("MP campaign-1a19dc070d3-47002c-ce336261-22d.save", Message.Report.MAX_TOTAL,
                    Message.Report.MAX_TOTAL - Protocol.MAX_STREAM_PIECE, new byte[Protocol.MAX_STREAM_PIECE]),
            new Message.SaveDone(5, "Bob couldn't save: disk full\nCid's world differs from the host's"),
            new Message.MapResult(3, -1L, MapStatus.OK),
            new Message.MapResult(4, 0L, MapStatus.WRONG_BUILD),
            new Message.MapConfirm(3, Long.MIN_VALUE),
            new Message.MapPlayers(2, 0, List.of(new PlayerStatus(0, PlayerState.BUILDING))),
            new Message.MapPlayers(2, 3, List.of(new PlayerStatus(0, PlayerState.GENERATED), new PlayerStatus(7, PlayerState.MISMATCH),
                new PlayerStatus(9, PlayerState.ERROR))));
        for (Message m : all)
            assertEquals(m, Protocol.decode(Protocol.encode(m)));
    }

    @Test
    void everyMessageStartsWithTheVersion() {
        byte[] b = Protocol.encode(new Message.Start());
        assertEquals(Protocol.VERSION, ((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
    }

    @Test
    void anotherVersionIsRecognisedWithoutReadingTheRest() throws IOException {
        byte[] b = {0, 99, 1, 2, 3};
        assertEquals(new Message.Unsupported(99), Protocol.decode(b));
    }

    @Test
    void aVersionOneMessageIsUnsupported() throws IOException {
        byte[] start = Protocol.encode(new Message.Start());
        start[1] = 1;
        assertEquals(new Message.Unsupported(1), Protocol.decode(start));
    }

    @Test
    void mapMessagesWithBadValuesThrow() {
        assertRefused(new Message.MapBuild(0, 256));
        assertRefused(new Message.MapBuild(1, 127));
        assertRefused(new Message.MapBuild(1, 513));
        assertRefused(new Message.BuildReady(-1));
        assertRefused(new Message.MapGenerate(0, 1, "", 50, 1));
        assertRefused(new Message.MapGenerate(1, 0, "", 50, 1));
        assertRefused(new Message.MapGenerate(1, 1, "x".repeat(65), 50, 1));
        assertRefused(new Message.MapGenerate(1, 1, "", 101, 1));
        assertRefused(new Message.MapGenerate(1, 1, "", 50, -1));
        assertRefused(new Message.MapConfirm(0, 5));
        assertRefused(new Message.MapTerrain(0, 0, 1, new byte[1]));
        assertRefused(new Message.Pick(new FactionPick("HUMAN", "A", "Ruler", new byte[0], 512, 3)));
        assertRefused(new Message.Picks(List.of()));
        assertRefused(new Message.Picks(List.of(new FactionStep.PlayerPick(-1, new FactionPick("HUMAN", "A", "Ruler", new byte[0], 1, 1), null, false))));
        assertRefused(new Message.Picks(List.of(new FactionStep.PlayerPick(1, new FactionPick("HUMAN", "A", "Ruler", new byte[0], 1, 1), "x".repeat(257), false))));
        assertRefused(new Message.SlotMap(0, List.of(new FactionStep.PlayerSlot(0, 0))));
        assertRefused(new Message.SlotMap(1, List.of(new FactionStep.PlayerSlot(0, LobbySettings.MAX_SLOTS))));
        assertRefused(new Message.WorldPart(0, 0, 1, new byte[1]));
        assertRefused(new Message.WorldPart(1, 2, 2, new byte[1]));
        assertRefused(new Message.Loaded(0, 1));
        assertRefused(new Message.StartSpot(0));
        assertRefused(new Message.Go(0));
        assertRefused(new Message.WorldPlayers(1, List.of()));
        assertRefused(new Message.MapTerrain(1, 1, 1, new byte[1]));
        assertRefused(new Message.MapTerrain(1, 0, 0, new byte[1]));
        assertRefused(new Message.MapTerrain(1, 0, Pieces.MAX_PIECES + 1, new byte[1]));
        assertRefused(new Message.MapTerrain(1, 0, 1, new byte[Pieces.PIECE_BYTES + 1]));
        assertRefused(new Message.MapResult(0, 5, MapStatus.OK));

        byte[] result = Protocol.encode(new Message.MapResult(1, 5, MapStatus.OK));
        result[result.length - 1] = (byte) MapStatus.values().length;
        assertThrows(IOException.class, () -> Protocol.decode(result));
        List<PlayerStatus> one = List.of(new PlayerStatus(0, PlayerState.READY));
        assertRefused(new Message.MapPlayers(0, 1, one));
        assertRefused(new Message.MapPlayers(1, -1, one));
        assertRefused(new Message.MapPlayers(1, 1, List.of()));
        assertRefused(new Message.MapPlayers(1, 1, Collections.nCopies(17, one.get(0))));
        assertRefused(new Message.MapPlayers(1, 1, List.of(new PlayerStatus(-1, PlayerState.READY))));
        byte[] players = Protocol.encode(new Message.MapPlayers(1, 1, one));
        players[players.length - 1] = (byte) PlayerState.values().length;
        assertThrows(IOException.class, () -> Protocol.decode(players));

        byte[] stream = Protocol.encode(new Message.Stream(StreamKind.ORDERS, 1, 0, 0, new byte[] {1}));
        byte[] badKind = stream.clone();
        badKind[3] = (byte) StreamKind.values().length; // after the version (2 bytes) and the type
        assertThrows(IOException.class, () -> Protocol.decode(badKind));
        byte[] badPc = stream.clone();
        badPc[4] = (byte) LobbySettings.MAX_SLOTS;
        assertThrows(IOException.class, () -> Protocol.decode(badPc));
        assertThrows(IllegalArgumentException.class, () -> new Message.Stream(StreamKind.ORDERS, 1, -1, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Message.Stream(StreamKind.ORDERS, 1, 0, 0, new byte[Protocol.MAX_STREAM_PIECE + 1]));

        assertThrows(IllegalArgumentException.class, () -> new Message.Resync(1, -1, 0, 1, new byte[0]));
        byte[] resync = Protocol.encode(new Message.Resync(1, 5, 0, 1, new byte[] {1}));
        byte[] badStep = resync.clone();
        badStep[3] = (byte) 0x80; // the step's top byte, after the version and the type: negative
        assertThrows(IOException.class, () -> Protocol.decode(badStep));
        assertRefused(new Message.Resync(1, 5, 1, 1, new byte[] {1}));

        byte[] build = Protocol.encode(new Message.MapBuild(1, 256));
        assertThrows(IOException.class, () -> Protocol.decode(Arrays.copyOf(build, build.length - 1)));
    }

    /** City viewing's messages (SOS-82): a slot, a reason, a piece or a city out of range is refused. */
    @Test
    void viewMessagesWithBadValuesThrow() {
        assertRefused(new Message.ViewAsk(LobbySettings.MAX_SLOTS, 0));
        assertRefused(new Message.ViewAsk(0, LobbySettings.MAX_SLOTS));
        assertRefused(new Message.Cursor(0, sosgsgmp.session.Cursors.BATTLE + 1, 0, 0));
        assertRefused(new Message.ViewAnswer(1, 2, false, "x".repeat(257)));
        assertRefused(new Message.ViewClose(1, Protocol.MAX_VIEW_CLOSE + 1));
        assertRefused(new Message.ViewHashes(1, new byte[Protocol.MAX_STREAM_PIECE + 1]));
        assertRefused(new Message.ViewCity(1, 5, Protocol.MAX_STREAM_PIECE * 2, 0, new byte[Protocol.MAX_STREAM_PIECE + 1]));
        assertThrows(IllegalArgumentException.class, () -> new Message.ViewCity(1, 5, 3, 2, new byte[2]), "past its total");
        assertThrows(IllegalArgumentException.class, () -> new Message.ViewCity(1, -1, 0, 0, new byte[0]));
        byte[] city = Protocol.encode(new Message.ViewCity(1, 5, 10, 0, new byte[] {1}));
        byte[] tooBig = city.clone();
        tooBig[12] = (byte) 0x7F; // the total's top byte, after the version, the type, the slot and the step
        assertThrows(IOException.class, () -> Protocol.decode(tooBig));
    }

    /** Encoding doesn't check values (only decoding does), so a bad value reaches the decoder as it would from a bad peer. */
    /** A save order names a quitter only when it ends the session; a decoded one that breaks this is refused (SOS-79). */
    @Test
    void reportPiecesWithBadValuesThrow() {
        assertThrows(IllegalArgumentException.class, () -> new Message.Report("", -1, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Message.Report("a.save", 1, 0, new byte[2]));
        assertThrows(IllegalArgumentException.class, () -> new Message.Report("a.log", -2, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Message.Report("a.log", -1, -1, new byte[0]));
    }

    @Test
    void aQuitterWithoutAnExitLeavesFrozen() throws IOException {
        assertTrue(new SaveOrder(1, 0, CampaignRecord.Kind.SAVE, 0, CampaignStoreTest.CAMPAIGN_FOR_TESTS, false, 3).freezes());
        assertFalse(new SaveOrder(1, 0, CampaignRecord.Kind.SAVE, 0, CampaignStoreTest.CAMPAIGN_FOR_TESTS, true, 3).freezes());
        assertFalse(new SaveOrder(1, 0, CampaignRecord.Kind.SAVE, 0, CampaignStoreTest.CAMPAIGN_FOR_TESTS, false).freezes());
        assertThrows(IllegalArgumentException.class,
                () -> new SaveOrder(1, 0, CampaignRecord.Kind.SAVE, 0, CampaignStoreTest.CAMPAIGN_FOR_TESTS, true, -2));
        byte[] b = Protocol.encode(new Message.SaveAt(new SaveOrder(1, 0, CampaignRecord.Kind.SAVE, 0, CampaignStoreTest.CAMPAIGN_FOR_TESTS, true, 3)));
        b[b.length - 5] = 0; // the exit flag, just before the quitter
        assertTrue(((Message.SaveAt) Protocol.decode(b)).order().freezes(), "a quitter on a save that plays on freezes");
    }

    /** SOS-158: a loading window of an unknown kind, a negative key or too long a text is refused. */
    @Test
    void aLoadingWindowWithBadValuesIsRefused() {
        assertRefused(new Message.Loading(sosgsgmp.session.Loading.Kind.values().length, 1, "x", true));
        assertRefused(new Message.Loading(0, -1, "x", true));
        assertRefused(new Message.Loading(0, 1, "x".repeat(257), true));
    }

    private static void assertRefused(Message m) {
        byte[] b = Protocol.encode(m);
        assertThrows(IOException.class, () -> Protocol.decode(b), m.toString());
    }

    @Test
    void malformedMessagesThrow() {
        byte[] hello = Protocol.encode(new Message.Hello("Ann", 1, new Builds("0.71.44", 1, 2, new GameFiles(3, List.of()))));
        assertThrows(IOException.class, () -> Protocol.decode(Arrays.copyOf(hello, hello.length - 1)));
        assertThrows(IOException.class, () -> Protocol.decode(Arrays.copyOf(hello, hello.length + 1)));
        assertThrows(IOException.class, () -> Protocol.decode(new byte[] {0, (byte) Protocol.VERSION, 77}));
        assertThrows(IOException.class, () -> Protocol.decode(new byte[] {0, (byte) Protocol.VERSION, 4, 99}));
        byte[] tooMany = Protocol.encode(new Message.Welcome(1, new LobbySettings("hard", 16)));
        tooMany[tooMany.length - 1] = 17;
        assertThrows(IOException.class, () -> Protocol.decode(tooMany));
    }
}
