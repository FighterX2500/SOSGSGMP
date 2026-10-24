package sosgsgmp.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;

class ProtocolTest {

    @Test
    void everyMessageRoundTrips() throws IOException {
        List<Message> all = List.of(
            new Message.Hello("Ann", -42L),
            new Message.Welcome(3, new LobbySettings("hard", 16)),
            new Message.Roster(List.of(new LobbyPlayer(0, "Ann", true), new LobbyPlayer(5, "Bøb", false))),
            new Message.Refused(Refusal.FULL),
            new Message.Start(),
            new Message.Leave());
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
