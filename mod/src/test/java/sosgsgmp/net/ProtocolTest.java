package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

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
            new Message.Hello("Ann", -42L),
            new Message.Welcome(3, new LobbySettings("hard", 16)),
            new Message.Roster(List.of(new LobbyPlayer(0, "Ann", true), new LobbyPlayer(5, "Bøb", false))),
            new Message.Refused(Refusal.FULL),
            new Message.Start(),
            new Message.Leave(),
            new Message.MapBuild(1, 512),
            new Message.BuildReady(Integer.MAX_VALUE),
            new Message.MapGenerate(3, 2, "", 100, Integer.MAX_VALUE),
            new Message.MapGenerate(4, 2, "Ödland map", 0, 0),
            new Message.MapTerrain(3, 0, 1, new byte[0]),
            new Message.MapTerrain(3, 2, 3, new byte[] {1, -2, 3}),
            new Message.MapTerrain(Integer.MAX_VALUE, Pieces.MAX_PIECES - 1, Pieces.MAX_PIECES, new byte[Pieces.PIECE_BYTES]),
            new Message.Pick(new FactionPick("HUMAN", "Ånn's Realm", new byte[] {7, 8}, 511, 0)),
            new Message.Pick(new FactionPick("CANTOR", "B", new byte[0], FactionPick.NONE, FactionPick.NONE)),
            new Message.Picks(List.of(new FactionStep.PlayerPick(0, new FactionPick("HUMAN", "A", new byte[] {1}, 3, 4), null),
                new FactionStep.PlayerPick(7, new FactionPick("HUMAN", "B", new byte[] {2}, FactionPick.NONE, FactionPick.NONE), "too close"))),
            new Message.SlotMap(1, List.of(new FactionStep.PlayerSlot(0, 0), new FactionStep.PlayerSlot(7, 15))),
            new Message.WorldPart(1, 0, 2, new byte[] {9, 8, 7}),
            new Message.Loaded(1, Long.MIN_VALUE),
            new Message.StartSpot(1),
            new Message.WorldPlayers(1, List.of(new FactionStep.PlayerProgress(0, FactionStep.Progress.READY),
                new FactionStep.PlayerProgress(7, FactionStep.Progress.MISMATCH))),
            new Message.Go(Integer.MAX_VALUE),
            new Message.Cursor(7, true, -3, Integer.MAX_VALUE),
            new Message.Cursor(0, false, 0, 0),
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
        assertRefused(new Message.Pick(new FactionPick("HUMAN", "A", new byte[0], 512, 3)));
        assertRefused(new Message.Picks(List.of()));
        assertRefused(new Message.Picks(List.of(new FactionStep.PlayerPick(-1, new FactionPick("HUMAN", "A", new byte[0], 1, 1), null))));
        assertRefused(new Message.Picks(List.of(new FactionStep.PlayerPick(1, new FactionPick("HUMAN", "A", new byte[0], 1, 1), "x".repeat(257)))));
        assertRefused(new Message.SlotMap(0, List.of(new FactionStep.PlayerSlot(0, 0))));
        assertRefused(new Message.SlotMap(1, List.of(new FactionStep.PlayerSlot(0, 16))));
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

        byte[] build = Protocol.encode(new Message.MapBuild(1, 256));
        assertThrows(IOException.class, () -> Protocol.decode(Arrays.copyOf(build, build.length - 1)));
    }

    /** Encoding doesn't check values (only decoding does), so a bad value reaches the decoder as it would from a bad peer. */
    private static void assertRefused(Message m) {
        byte[] b = Protocol.encode(m);
        assertThrows(IOException.class, () -> Protocol.decode(b), m.toString());
    }

    @Test
    void malformedMessagesThrow() {
        byte[] hello = Protocol.encode(new Message.Hello("Ann", 1));
        assertThrows(IOException.class, () -> Protocol.decode(Arrays.copyOf(hello, hello.length - 1)));
        assertThrows(IOException.class, () -> Protocol.decode(Arrays.copyOf(hello, hello.length + 1)));
        assertThrows(IOException.class, () -> Protocol.decode(new byte[] {0, (byte) Protocol.VERSION, 77}));
        assertThrows(IOException.class, () -> Protocol.decode(new byte[] {0, (byte) Protocol.VERSION, 4, 9}));
        byte[] tooMany = Protocol.encode(new Message.Welcome(1, new LobbySettings("hard", 16)));
        tooMany[tooMany.length - 1] = 17;
        assertThrows(IOException.class, () -> Protocol.decode(tooMany));
    }
}
