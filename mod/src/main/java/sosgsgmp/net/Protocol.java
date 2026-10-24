package sosgsgmp.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import sosgsgmp.lobby.Lobby;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;

/**
 * Byte layout of {@link Message}s. Every message starts with the protocol version (a short) and its type (a byte),
 * so a PC can recognise and refuse another version; the layout of those two fields never changes.
 * {@link TcpNode} adds a 4-byte length in front of each message.
 */
public final class Protocol {

    /** Raise whenever any message's layout changes. */
    public static final int VERSION = 1;

    /** Largest message accepted, in bytes. */
    public static final int MAX_MESSAGE = 64 * 1024;

    private static final int HELLO = 1, WELCOME = 2, ROSTER = 3, REFUSED = 4, START = 5, LEAVE = 6;

    private Protocol() {}

    public static byte[] encode(Message m) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeShort(VERSION);
            switch (m) {
                case Message.Hello h -> {
                    out.writeByte(HELLO);
                    out.writeUTF(h.name());
                    out.writeLong(h.playerKey());
                }
                case Message.Welcome w -> {
                    out.writeByte(WELCOME);
                    out.writeInt(w.yourId());
                    out.writeUTF(w.settings().difficulty());
                    out.writeByte(w.settings().maxPlayers());
                }
                case Message.Roster r -> {
                    out.writeByte(ROSTER);
                    out.writeByte(r.players().size());
                    for (LobbyPlayer p : r.players()) {
                        out.writeInt(p.id());
                        out.writeUTF(p.name());
                        out.writeBoolean(p.host());
                    }
                }
                case Message.Refused f -> {
                    out.writeByte(REFUSED);
                    out.writeByte(f.reason().ordinal());
                }
                case Message.Start s -> out.writeByte(START);
                case Message.Leave l -> out.writeByte(LEAVE);
                case Message.Unsupported u -> throw new IllegalArgumentException("SOSGSGMP: can't send a message of protocol version " + u.version());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("SOSGSGMP: writing to memory failed", e);
        }
        return bytes.toByteArray();
    }

    /** Reads one message; throws {@link IOException} when it is malformed. */
    public static Message decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_MESSAGE)
            throw new IOException("SOSGSGMP: message of " + bytes.length + " bytes is over the limit of " + MAX_MESSAGE);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int version = in.readUnsignedShort();
        if (version != VERSION)
            return new Message.Unsupported(version);
        int type = in.readUnsignedByte();
        Message m = switch (type) {
            case HELLO -> new Message.Hello(name(in), in.readLong());
            case WELCOME -> new Message.Welcome(in.readInt(), settings(in));
            case ROSTER -> roster(in);
            case REFUSED -> new Message.Refused(refusal(in.readUnsignedByte()));
            case START -> new Message.Start();
            case LEAVE -> new Message.Leave();
            default -> throw new IOException("SOSGSGMP: unknown message type " + type);
        };
        if (in.available() > 0)
            throw new IOException("SOSGSGMP: " + in.available() + " extra bytes after message type " + type);
        return m;
    }

    private static String name(DataInputStream in) throws IOException {
        String n = in.readUTF();
        if (n.isEmpty() || n.length() > Lobby.MAX_NAME)
            throw new IOException("SOSGSGMP: player name of " + n.length() + " characters");
        return n;
    }

    private static LobbySettings settings(DataInputStream in) throws IOException {
        String difficulty = in.readUTF();
        int max = in.readUnsignedByte();
        try {
            return new LobbySettings(difficulty, max);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static Message.Roster roster(DataInputStream in) throws IOException {
        int n = in.readUnsignedByte();
        if (n < 1 || n > LobbySettings.MAX_PLAYERS)
            throw new IOException("SOSGSGMP: roster of " + n + " players");
        List<LobbyPlayer> players = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = in.readInt();
            String name = name(in);
            boolean host = in.readBoolean();
            if (id < 0)
                throw new IOException("SOSGSGMP: player id " + id);
            players.add(new LobbyPlayer(id, name, host));
        }
        return new Message.Roster(players);
    }

    private static Refusal refusal(int ordinal) throws IOException {
        Refusal[] all = Refusal.values();
        if (ordinal >= all.length)
            throw new IOException("SOSGSGMP: unknown refusal " + ordinal);
        return all[ordinal];
    }

}
