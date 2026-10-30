package sosgsgmp.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.CampaignRecord;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.lobby.Lobby;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStatus;
import sosgsgmp.setup.MapStep;

/**
 * Byte layout of {@link Message}s. Every message starts with the protocol version (a short) and its type (a byte),
 * so a PC can recognise and refuse another version; the layout of those two fields never changes.
 * {@link TcpNode} adds a 4-byte length in front of each message.
 */
public final class Protocol {

    /** Raise whenever any message's layout changes. */
    public static final int VERSION = 17;

    /** Largest message accepted, in bytes. */
    public static final int MAX_MESSAGE = 64 * 1024;

    private static final int HELLO = 1, WELCOME = 2, ROSTER = 3, REFUSED = 4, START = 5, LEAVE = 6;
    private static final int MAP_BUILD = 7, BUILD_READY = 8, MAP_GENERATE = 9, MAP_RESULT = 10, MAP_CONFIRM = 11, MAP_PLAYERS = 12;
    private static final int MAP_TERRAIN = 13;
    private static final int PICK = 14, PICKS = 15, SLOT_MAP = 16, WORLD_PART = 17, LOADED = 18, START_SPOT = 19, GO = 20, WORLD_PLAYERS = 21;
    private static final int CURSOR = 22, STREAM = 23, RESYNC = 24; // 25 was Save and exit's own message, until SOS-68
    private static final int SAVE_AT = 26, SAVED = 27, SAVE_DONE = 28;
    private static final int PLAYER_GONE = 29, GONE_CHOICE = 30, QUIT_ASK = 31;
    private static final int VIEW_ASK = 32, VIEW_ANSWER = 33, VIEW_CLOSE = 34, VIEW_CITY = 35, VIEW_HASHES = 36;
    private static final int CHAT = 37, PAUSE = 38;
    /** The largest city a view carries, in bytes ({@link Message.ViewCity}). */
    public static final int MAX_VIEW_CITY = 512 * 1024 * 1024;
    /** The largest reason a viewer's PC ends its view for ({@link Message.ViewClose}): the engine's closed to lost step. */
    public static final int MAX_VIEW_CLOSE = 3;
    /** Largest piece of an engine stream in one message, in bytes: well under {@link #MAX_MESSAGE}. */
    public static final int MAX_STREAM_PIECE = 48 * 1024;
    /** Longest reason a location was cleared, in characters. */
    private static final int MAX_REASON = 256;
    /** Longest outcome of a campaign save, in characters: a line per PC that didn't save. */
    private static final int MAX_OUTCOME = 4096;

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
                    out.writeBoolean(w.settings().fullTransferLog());
                    LobbySettings.Resume resume = w.settings().resume();
                    out.writeBoolean(resume != null);
                    if (resume != null) {
                        writeCampaign(out, resume.campaign());
                        out.writeInt(resume.number());
                    }
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
                case Message.QuitAsk q -> out.writeByte(QUIT_ASK);
                case Message.MapBuild b -> {
                    out.writeByte(MAP_BUILD);
                    out.writeInt(b.buildId());
                    out.writeShort(b.size());
                }
                case Message.BuildReady b -> {
                    out.writeByte(BUILD_READY);
                    out.writeInt(b.buildId());
                }
                case Message.MapGenerate g -> {
                    out.writeByte(MAP_GENERATE);
                    out.writeInt(g.genId());
                    out.writeInt(g.buildId());
                    out.writeUTF(g.mapType());
                    out.writeByte(g.latitude());
                    out.writeInt(g.seed());
                }
                case Message.MapTerrain t -> {
                    out.writeByte(MAP_TERRAIN);
                    writePiece(out, t.genId(), t.index(), t.count(), t.bytes());
                }
                case Message.MapResult r -> {
                    out.writeByte(MAP_RESULT);
                    out.writeInt(r.genId());
                    out.writeLong(r.hash());
                    out.writeByte(r.status().ordinal());
                }
                case Message.MapConfirm c -> {
                    out.writeByte(MAP_CONFIRM);
                    out.writeInt(c.genId());
                    out.writeLong(c.hash());
                }
                case Message.MapPlayers p -> {
                    out.writeByte(MAP_PLAYERS);
                    out.writeInt(p.buildId());
                    out.writeInt(p.genId());
                    out.writeByte(p.players().size());
                    for (MapStep.PlayerStatus s : p.players()) {
                        out.writeInt(s.playerId());
                        out.writeByte(s.state().ordinal());
                    }
                }
                case Message.Pick p -> {
                    out.writeByte(PICK);
                    writePick(out, p.pick());
                }
                case Message.Picks p -> {
                    out.writeByte(PICKS);
                    out.writeByte(p.picks().size());
                    for (FactionStep.PlayerPick pp : p.picks()) {
                        out.writeInt(pp.playerId());
                        writePick(out, pp.pick());
                        out.writeUTF(pp.refused() == null ? "" : pp.refused());
                    }
                }
                case Message.SlotMap s -> {
                    out.writeByte(SLOT_MAP);
                    out.writeInt(s.transfer());
                    out.writeByte(s.slots().size());
                    for (FactionStep.PlayerSlot ps : s.slots()) {
                        out.writeInt(ps.playerId());
                        out.writeByte(ps.slot());
                    }
                }
                case Message.WorldPart w -> {
                    out.writeByte(WORLD_PART);
                    writePiece(out, w.transfer(), w.index(), w.count(), w.bytes());
                }
                case Message.Loaded l -> {
                    out.writeByte(LOADED);
                    out.writeInt(l.transfer());
                    out.writeLong(l.hash());
                }
                case Message.StartSpot s -> {
                    out.writeByte(START_SPOT);
                    out.writeInt(s.transfer());
                }
                case Message.WorldPlayers w -> {
                    out.writeByte(WORLD_PLAYERS);
                    out.writeInt(w.transfer());
                    out.writeByte(w.players().size());
                    for (FactionStep.PlayerProgress pp : w.players()) {
                        out.writeInt(pp.playerId());
                        out.writeByte(pp.progress().ordinal());
                    }
                }
                case Message.Go g -> {
                    out.writeByte(GO);
                    out.writeInt(g.transfer());
                }
                case Message.Stream s -> {
                    out.writeByte(STREAM);
                    out.writeByte(s.kind().ordinal());
                    out.writeByte(s.pc());
                    out.writeLong(s.offset());
                    out.writeLong(s.step());
                    out.writeInt(s.length());
                    out.write(s.bytes());
                }
                case Message.Cursor c -> {
                    out.writeByte(CURSOR);
                    out.writeInt(c.playerId());
                    out.writeShort(c.place()); // Cursors.OFF, WORLD or a city (SOS-58)
                    out.writeInt(c.x());
                    out.writeInt(c.y());
                }
                case Message.Pause p -> {
                    out.writeByte(PAUSE);
                    out.writeInt(p.playerId());
                    out.writeBoolean(p.paused());
                }
                case Message.Chat c -> {
                    out.writeByte(CHAT);
                    out.writeInt(c.playerId());
                    out.writeUTF(c.text());
                }
                case Message.Resync r -> {
                    out.writeByte(RESYNC);
                    out.writeLong(r.step());
                    writePiece(out, r.transfer(), r.index(), r.count(), r.bytes());
                }
                case Message.SaveAt s -> {
                    out.writeByte(SAVE_AT);
                    SaveOrder o = s.order();
                    out.writeInt(o.number());
                    out.writeLong(o.step());
                    out.writeByte(o.kind().ordinal());
                    out.writeByte(o.keep());
                    writeCampaign(out, o.campaign());
                    out.writeBoolean(o.exit());
                    out.writeInt(o.quitter());
                }
                case Message.Saved s -> {
                    out.writeByte(SAVED);
                    out.writeInt(s.number());
                    out.writeLong(s.hash());
                    out.writeUTF(s.problem());
                }
                case Message.SaveDone d -> {
                    out.writeByte(SAVE_DONE);
                    out.writeInt(d.number());
                    out.writeUTF(d.outcome());
                }
                case Message.PlayerGone g -> {
                    out.writeByte(PLAYER_GONE);
                    out.writeInt(g.playerId());
                    out.writeLong(g.holdStep());
                }
                case Message.GoneChoice c -> {
                    out.writeByte(GONE_CHOICE);
                    out.writeLong(c.holdStep());
                    out.writeBoolean(c.continued());
                }
                case Message.ViewAsk a -> {
                    out.writeByte(VIEW_ASK);
                    out.writeByte(a.viewer());
                    out.writeByte(a.owner());
                }
                case Message.ViewAnswer a -> {
                    out.writeByte(VIEW_ANSWER);
                    out.writeByte(a.viewer());
                    out.writeByte(a.owner());
                    out.writeBoolean(a.allowed());
                    out.writeUTF(a.reason());
                }
                case Message.ViewClose c -> {
                    out.writeByte(VIEW_CLOSE);
                    out.writeByte(c.viewer());
                    out.writeByte(c.why());
                }
                case Message.ViewCity c -> {
                    out.writeByte(VIEW_CITY);
                    out.writeByte(c.viewer());
                    out.writeLong(c.step());
                    out.writeInt(c.total());
                    out.writeInt(c.offset());
                    byte[] data = c.bytes();
                    out.writeInt(data.length);
                    out.write(data);
                }
                case Message.ViewHashes h -> {
                    out.writeByte(VIEW_HASHES);
                    out.writeByte(h.viewer());
                    byte[] data = h.bytes();
                    out.writeInt(data.length);
                    out.write(data);
                }
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
            case MAP_BUILD -> new Message.MapBuild(number(in, "build"), size(in));
            case BUILD_READY -> new Message.BuildReady(number(in, "build"));
            case MAP_GENERATE -> new Message.MapGenerate(number(in, "generation"), number(in, "build"), mapType(in), latitude(in), seed(in));
            case MAP_TERRAIN -> {
                Piece p = piece(in, "generation", "terrain");
                yield new Message.MapTerrain(p.number(), p.index(), p.count(), p.data());
            }
            case MAP_RESULT -> new Message.MapResult(number(in, "generation"), in.readLong(), status(in.readUnsignedByte()));
            case MAP_CONFIRM -> new Message.MapConfirm(number(in, "generation"), in.readLong());
            case MAP_PLAYERS -> mapPlayers(in);
            case PICK -> new Message.Pick(pick(in));
            case PICKS -> picks(in);
            case SLOT_MAP -> slotMap(in);
            case WORLD_PART -> {
                Piece p = piece(in, "transfer", "world");
                yield new Message.WorldPart(p.number(), p.index(), p.count(), p.data());
            }
            case LOADED -> new Message.Loaded(number(in, "transfer"), in.readLong());
            case START_SPOT -> new Message.StartSpot(number(in, "transfer"));
            case WORLD_PLAYERS -> worldPlayers(in);
            case GO -> new Message.Go(number(in, "transfer"));
            case STREAM -> stream(in);
            case CURSOR -> new Message.Cursor(playerId(in), cursorPlace(in.readShort()), in.readInt(), in.readInt());
            case PAUSE -> new Message.Pause(playerId(in), in.readBoolean());
            case CHAT -> new Message.Chat(playerId(in), text(in, sosgsgmp.session.Chat.MAX_TEXT, "chat message"));
            case RESYNC -> {
                long step = step(in, "resync");
                Piece p = piece(in, "transfer", "resync");
                yield new Message.Resync(p.number(), step, p.index(), p.count(), p.data());
            }
            case SAVE_AT -> saveAt(in);
            case SAVED -> new Message.Saved(number(in, "save"), in.readLong(), text(in, MAX_REASON, "save problem"));
            case SAVE_DONE -> new Message.SaveDone(number(in, "save"), text(in, MAX_OUTCOME, "save outcome"));
            case PLAYER_GONE -> new Message.PlayerGone(playerId(in), step(in, "player gone hold"));
            case GONE_CHOICE -> new Message.GoneChoice(step(in, "gone choice hold"), in.readBoolean());
            case QUIT_ASK -> new Message.QuitAsk();
            case VIEW_ASK -> new Message.ViewAsk(slot(in), slot(in));
            case VIEW_ANSWER -> new Message.ViewAnswer(slot(in), slot(in), in.readBoolean(), text(in, MAX_REASON, "view answer"));
            case VIEW_CLOSE -> {
                int viewer = slot(in);
                int why = in.readUnsignedByte();
                if (why > MAX_VIEW_CLOSE)
                    throw new IOException("SOSGSGMP: a view closed for reason " + why);
                yield new Message.ViewClose(viewer, why);
            }
            case VIEW_CITY -> viewCity(in);
            case VIEW_HASHES -> {
                int viewer = slot(in);
                yield new Message.ViewHashes(viewer, data(in, MAX_STREAM_PIECE, "view hashes"));
            }
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
        boolean fullTransferLog = in.readBoolean();
        LobbySettings.Resume resume = null;
        try {
            if (in.readBoolean())
                resume = new LobbySettings.Resume(campaign(in), in.readInt());
            return new LobbySettings(difficulty, max, resume, fullTransferLog);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static String text(DataInputStream in, int max, String what) throws IOException {
        String s = in.readUTF();
        if (s.length() > max)
            throw new IOException("SOSGSGMP: " + what + " of " + s.length() + " characters");
        return s;
    }

    /** A campaign as save orders and resume lobbies carry it (SOS-64). */
    private static void writeCampaign(DataOutputStream out, Campaign c) throws IOException {
        out.writeLong(c.id());
        out.writeUTF(c.name());
        out.writeInt(c.seed());
        out.writeUTF(c.difficulty());
        out.writeByte(c.players().size());
        for (Campaign.Player p : c.players()) {
            out.writeLong(p.key());
            out.writeUTF(p.name());
            out.writeByte(p.slot());
            out.writeBoolean(p.removed());
        }
    }

    private static Campaign campaign(DataInputStream in) throws IOException {
        long id = in.readLong();
        String name = text(in, Campaign.MAX_NAME, "campaign name");
        int seed = in.readInt();
        String difficulty = in.readUTF();
        int n = players(in, "campaign");
        List<Campaign.Player> players = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long key = in.readLong();
            String name2 = name(in);
            int slot = in.readUnsignedByte();
            if (slot >= LobbySettings.MAX_PLAYERS)
                throw new IOException("SOSGSGMP: campaign slot " + slot);
            players.add(new Campaign.Player(key, name2, slot, in.readBoolean()));
        }
        try {
            return new Campaign(id, name, seed, difficulty, players);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static Message.SaveAt saveAt(DataInputStream in) throws IOException {
        int number = number(in, "save");
        long step = step(in, "save");
        int kind = in.readUnsignedByte();
        CampaignRecord.Kind[] kinds = CampaignRecord.Kind.values();
        if (kind >= kinds.length)
            throw new IOException("SOSGSGMP: unknown save kind " + kind);
        int keep = in.readUnsignedByte();
        Campaign campaign = campaign(in);
        boolean exit = in.readBoolean();
        int quitter = in.readInt();
        try {
            return new Message.SaveAt(new SaveOrder(number, step, kinds[kind], keep, campaign, exit, quitter));
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

    /** A build or generation number: they count from 1. */
    private static int number(DataInputStream in, String what) throws IOException {
        int n = in.readInt();
        if (n < 1)
            throw new IOException("SOSGSGMP: " + what + " number " + n);
        return n;
    }

    private static long step(DataInputStream in, String what) throws IOException {
        long step = in.readLong();
        if (step < 0)
            throw new IOException("SOSGSGMP: " + what + " at step " + step);
        return step;
    }

    private static int size(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        if (size < MapOptions.MIN_SIZE || size > MapOptions.MAX_SIZE)
            throw new IOException("SOSGSGMP: map size " + size);
        return size;
    }

    private static String mapType(DataInputStream in) throws IOException {
        String t = in.readUTF();
        if (t.length() > MapOptions.MAX_MAP_TYPE)
            throw new IOException("SOSGSGMP: map type of " + t.length() + " characters");
        return t;
    }

    private static int latitude(DataInputStream in) throws IOException {
        int lat = in.readUnsignedByte();
        if (lat > MapOptions.MAX_LATITUDE)
            throw new IOException("SOSGSGMP: latitude " + lat);
        return lat;
    }

    private static int seed(DataInputStream in) throws IOException {
        int seed = in.readInt();
        if (seed < 0)
            throw new IOException("SOSGSGMP: seed " + seed);
        return seed;
    }

    /** One piece of a transfer ({@link Pieces}), as {@link Message.MapTerrain} and {@link Message.WorldPart} carry it. */
    private record Piece(int number, int index, int count, byte[] data) {}

    private static void writePiece(DataOutputStream out, int number, int index, int count, byte[] data) throws IOException {
        out.writeInt(number);
        out.writeShort(index);
        out.writeShort(count);
        out.writeInt(data.length);
        out.write(data);
    }

    private static Piece piece(DataInputStream in, String numbered, String what) throws IOException {
        int number = number(in, numbered);
        int index = in.readUnsignedShort();
        int count = in.readUnsignedShort();
        if (count < 1 || count > Pieces.MAX_PIECES || index >= count)
            throw new IOException("SOSGSGMP: " + what + " piece " + index + " of " + count);
        int length = in.readInt();
        if (length < 0 || length > Pieces.PIECE_BYTES)
            throw new IOException("SOSGSGMP: " + what + " piece of " + length + " bytes");
        byte[] data = new byte[length];
        in.readFully(data);
        return new Piece(number, index, count, data);
    }

    private static void writePick(DataOutputStream out, FactionPick p) throws IOException {
        out.writeUTF(p.race());
        out.writeUTF(p.name());
        out.writeUTF(p.ruler()); // SOS-98
        byte[] banner = p.banner();
        out.writeShort(banner.length);
        out.write(banner);
        out.writeShort(p.x());
        out.writeShort(p.y());
    }

    private static FactionPick pick(DataInputStream in) throws IOException {
        String race = in.readUTF();
        String name = in.readUTF();
        String ruler = in.readUTF(); // SOS-98
        int length = in.readUnsignedShort();
        if (length > FactionPick.MAX_BANNER)
            throw new IOException("SOSGSGMP: banner of " + length + " bytes");
        byte[] banner = new byte[length];
        in.readFully(banner);
        int x = in.readShort();
        int y = in.readShort();
        if (x >= MapOptions.MAX_SIZE || y >= MapOptions.MAX_SIZE)
            throw new IOException("SOSGSGMP: location " + x + "," + y);
        try {
            return new FactionPick(race, name, ruler, banner, x, y);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static int players(DataInputStream in, String what) throws IOException {
        int n = in.readUnsignedByte();
        if (n < 1 || n > LobbySettings.MAX_PLAYERS)
            throw new IOException("SOSGSGMP: " + what + " of " + n + " players");
        return n;
    }

    private static Message.Stream stream(DataInputStream in) throws IOException {
        int kind = in.readUnsignedByte();
        if (kind >= StreamKind.values().length)
            throw new IOException("SOSGSGMP: stream kind " + kind);
        int pc = in.readUnsignedByte();
        if (pc >= LobbySettings.MAX_PLAYERS)
            throw new IOException("SOSGSGMP: stream of PC " + pc);
        long offset = in.readLong();
        long step = in.readLong();
        if (offset < 0 || step < 0)
            throw new IOException("SOSGSGMP: stream offset " + offset + ", step " + step);
        int n = in.readInt();
        if (n < 0 || n > MAX_STREAM_PIECE)
            throw new IOException("SOSGSGMP: stream piece of " + n + " bytes");
        byte[] bytes = new byte[n];
        in.readFully(bytes);
        return new Message.Stream(StreamKind.values()[kind], pc, offset, step, bytes);
    }

    /** A player's slot: their PC's number. */
    private static int slot(DataInputStream in) throws IOException {
        int slot = in.readUnsignedByte();
        if (slot >= LobbySettings.MAX_PLAYERS)
            throw new IOException("SOSGSGMP: slot " + slot);
        return slot;
    }

    private static byte[] data(DataInputStream in, int max, String what) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max)
            throw new IOException("SOSGSGMP: " + what + " of " + n + " bytes");
        byte[] bytes = new byte[n];
        in.readFully(bytes);
        return bytes;
    }

    private static Message.ViewCity viewCity(DataInputStream in) throws IOException {
        int viewer = slot(in);
        long step = step(in, "view city");
        int total = in.readInt();
        int offset = in.readInt();
        if (total < 0 || total > MAX_VIEW_CITY || offset < 0 || offset > total)
            throw new IOException("SOSGSGMP: a view's city piece at " + offset + " of " + total + " bytes");
        byte[] bytes = data(in, Math.min(MAX_STREAM_PIECE, total - offset), "view city piece");
        return new Message.ViewCity(viewer, step, total, offset, bytes);
    }

    private static int cursorPlace(int place) throws IOException {
        if (place < sosgsgmp.session.Cursors.OFF || place > LobbySettings.MAX_PLAYERS)
            throw new IOException("SOSGSGMP: cursor place " + place);
        return place;
    }

    private static int playerId(DataInputStream in) throws IOException {
        int id = in.readInt();
        if (id < 0)
            throw new IOException("SOSGSGMP: player id " + id);
        return id;
    }

    private static Message.Picks picks(DataInputStream in) throws IOException {
        int n = players(in, "picks");
        List<FactionStep.PlayerPick> all = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = playerId(in);
            FactionPick p = pick(in);
            String refused = in.readUTF();
            if (refused.length() > MAX_REASON)
                throw new IOException("SOSGSGMP: reason of " + refused.length() + " characters");
            all.add(new FactionStep.PlayerPick(id, p, refused.isEmpty() ? null : refused));
        }
        return new Message.Picks(all);
    }

    private static Message.SlotMap slotMap(DataInputStream in) throws IOException {
        int transfer = number(in, "transfer");
        int n = players(in, "slot map");
        List<FactionStep.PlayerSlot> all = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = playerId(in);
            int slot = in.readUnsignedByte();
            if (slot >= LobbySettings.MAX_PLAYERS)
                throw new IOException("SOSGSGMP: slot " + slot);
            all.add(new FactionStep.PlayerSlot(id, slot));
        }
        return new Message.SlotMap(transfer, all);
    }

    private static Message.WorldPlayers worldPlayers(DataInputStream in) throws IOException {
        int transfer = number(in, "transfer");
        int n = players(in, "progress");
        FactionStep.Progress[] states = FactionStep.Progress.values();
        List<FactionStep.PlayerProgress> all = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = playerId(in);
            int ordinal = in.readUnsignedByte();
            if (ordinal >= states.length)
                throw new IOException("SOSGSGMP: unknown progress " + ordinal);
            all.add(new FactionStep.PlayerProgress(id, states[ordinal]));
        }
        return new Message.WorldPlayers(transfer, all);
    }

    private static Message.MapPlayers mapPlayers(DataInputStream in) throws IOException {
        int buildId = number(in, "build");
        int genId = in.readInt();
        if (genId < 0)
            throw new IOException("SOSGSGMP: generation number " + genId);
        int n = in.readUnsignedByte();
        if (n < 1 || n > LobbySettings.MAX_PLAYERS)
            throw new IOException("SOSGSGMP: progress of " + n + " players");
        MapStep.PlayerState[] states = MapStep.PlayerState.values();
        List<MapStep.PlayerStatus> players = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = in.readInt();
            if (id < 0)
                throw new IOException("SOSGSGMP: player id " + id);
            int ordinal = in.readUnsignedByte();
            if (ordinal >= states.length)
                throw new IOException("SOSGSGMP: unknown player state " + ordinal);
            players.add(new MapStep.PlayerStatus(id, states[ordinal]));
        }
        return new Message.MapPlayers(buildId, genId, players);
    }

    private static MapStatus status(int ordinal) throws IOException {
        MapStatus[] all = MapStatus.values();
        if (ordinal >= all.length)
            throw new IOException("SOSGSGMP: unknown map status " + ordinal);
        return all[ordinal];
    }

}
