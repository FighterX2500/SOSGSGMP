package sosgsgmp.session;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.net.Message;
import sosgsgmp.net.Protocol;
import sosgsgmp.net.StreamKind;

/**
 * The game's engine streams between this PC and the others (game-over-network.md §1, §2): which streams this PC makes
 * and takes, each stream's offsets, and exactly-once delivery to the engine. Plain Java; the session sends and
 * receives the messages, the {@link EnginePort} calls the engine.
 *
 * <p>The host makes the AI log and takes every peer's orders; a peer makes orders and takes the host's AI log; every PC
 * makes its city reports and hashes and takes every other PC's. PC numbers are the slots; the host's is 0 in a new
 * game, and whatever slot it has when a campaign resumes.
 *
 * <p>Each stream is compressed once, where it is made (SOS-96): one Deflater per stream this PC makes, flushed after
 * every call so each piece inflates at once; the offsets count compressed bytes, and the host relays the pieces as they
 * came. A receiver keeps one Inflater per stream and maker, fed only the bytes the exactly-once check lets through.
 * Consecutive city reports are nearly the same, so in Deflate's window they shrink 13-16 times. A link lives for one
 * game in one process and its streams start with it, so nothing is ever reset.
 */
public final class EngineLink {

    private static final int KINDS = StreamKind.values().length;

    private final boolean host;
    /** The host's PC: its slot, 0 for a new game, any slot when a campaign resumes (SOS-64). */
    private final int hostPc;
    private final int localPc;
    private final EnginePort port;
    /** Bytes of each stream this PC sent so far. */
    private final long[] sent = new long[KINDS];
    /** Bytes of each stream, by the PC that made it, fed to the engine so far. */
    private final long[][] fed = new long[KINDS][LobbySettings.MAX_PLAYERS];
    private final Deflater[] deflaters = new Deflater[KINDS];
    private final Inflater[][] inflaters = new Inflater[KINDS][LobbySettings.MAX_PLAYERS];
    private final byte[] buffer = new byte[64 * 1024];
    /** The engine's bytes before compression: made here, and taken from the other PCs. */
    private long rawOut;
    private long rawIn;

    public EngineLink(boolean host, int localPc, int hostPc, EnginePort port) {
        if (localPc < 0 || localPc >= LobbySettings.MAX_PLAYERS || hostPc < 0 || host != (localPc == hostPc))
            throw new IllegalArgumentException("SOSGSGMP: engine link for PC " + localPc + (host ? " as the host" : " as a peer")
                    + " of host PC " + hostPc);
        this.host = host;
        this.hostPc = hostPc;
        this.localPc = localPc;
        this.port = port;
    }

    /** This PC's engine's step. */
    long step() {
        return port.step();
    }

    /** Whether this PC makes stream {@code kind}. */
    boolean makes(StreamKind kind) {
        return switch (kind) {
            case AI_LOG -> host;
            case ORDERS -> !host;
            case REPORTS, HASHES, BATTLE -> true;
        };
    }

    /** Whether this PC takes stream {@code kind} made by PC {@code pc}. */
    boolean takes(StreamKind kind, int pc) {
        if (pc == localPc)
            return false;
        return switch (kind) {
            case AI_LOG -> !host && pc == hostPc;
            case ORDERS -> host;
            case REPORTS, HASHES, BATTLE -> true;
        };
    }

    /** What this PC's engine made since the last call, in pieces to send (to every other PC through the host). */
    List<Message.Stream> out() {
        List<Message.Stream> pieces = new ArrayList<>();
        long step = port.step();
        for (StreamKind kind : StreamKind.values()) {
            if (!makes(kind))
                continue;
            byte[] raw = port.out(kind);
            if (raw.length == 0)
                continue;
            rawOut += raw.length;
            byte[] bytes = deflate(kind, raw);
            for (int from = 0; from < bytes.length; from += Protocol.MAX_STREAM_PIECE) {
                int to = Math.min(bytes.length, from + Protocol.MAX_STREAM_PIECE);
                pieces.add(new Message.Stream(kind, localPc, sent[kind.ordinal()], step, Arrays.copyOfRange(bytes, from, to)));
                sent[kind.ordinal()] += to - from;
            }
        }
        return pieces;
    }

    /** Why a piece from the network breaks the protocol: one this PC doesn't take, or one past a gap; null if it doesn't. */
    String problem(Message.Stream s) {
        if (!takes(s.kind(), s.pc()))
            return "PC " + localPc + " takes no " + s.kind() + " of PC " + s.pc();
        long have = fed[s.kind().ordinal()][s.pc()];
        if (s.offset() > have)
            return s + " leaves a gap: PC " + localPc + " has " + have + " bytes";
        return null;
    }

    /**
     * A piece from the network that has no {@link #problem}: its new bytes go to the engine. Returns whether it had any
     * (bytes already fed are dropped, so a repeated piece changes nothing).
     */
    boolean in(Message.Stream s) {
        String problem = problem(s);
        if (problem != null)
            throw new IllegalArgumentException("SOSGSGMP: " + problem);
        long have = fed[s.kind().ordinal()][s.pc()];
        long end = s.offset() + s.length();
        if (end <= have)
            return false;
        byte[] bytes = s.bytes();
        byte[] fresh = s.offset() == have ? bytes : Arrays.copyOfRange(bytes, (int) (have - s.offset()), bytes.length);
        byte[] raw = inflate(s.kind(), s.pc(), fresh);
        fed[s.kind().ordinal()][s.pc()] = end;
        rawIn += raw.length;
        if (raw.length > 0)
            port.in(s.kind(), s.pc(), raw);
        return true;
    }

    /** {@code raw}, compressed in stream {@code kind} and flushed: the receiver inflates all of it at once. */
    private byte[] deflate(StreamKind kind, byte[] raw) {
        Deflater d = deflaters[kind.ordinal()];
        if (d == null)
            d = deflaters[kind.ordinal()] = new Deflater(Deflater.BEST_SPEED);
        d.setInput(raw);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
        int n;
        do {
            n = d.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
            out.write(buffer, 0, n);
        } while (n == buffer.length);
        return out.toByteArray();
    }

    /** The engine's bytes in {@code compressed}, the next bytes of stream {@code kind} made by PC {@code pc}. */
    private byte[] inflate(StreamKind kind, int pc, byte[] compressed) {
        Inflater i = inflaters[kind.ordinal()][pc];
        if (i == null)
            i = inflaters[kind.ordinal()][pc] = new Inflater();
        i.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4 + 64);
        try {
            while (true) {
                int n = i.inflate(buffer);
                if (n > 0)
                    out.write(buffer, 0, n);
                else if (i.needsInput() || i.finished() || i.needsDictionary())
                    break;
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("SOSGSGMP: PC " + pc + "'s " + kind + " doesn't inflate at byte " + fed[kind.ordinal()][pc], e);
        }
        if (i.finished() || i.needsDictionary())
            throw new IllegalArgumentException("SOSGSGMP: PC " + pc + "'s " + kind + " isn't one flushed Deflate stream");
        return out.toByteArray();
    }

    /** The bytes this link moved so far, for the playtest log (SOS-96). */
    Traffic traffic() {
        long wireOut = 0;
        for (long b : sent)
            wireOut += b;
        long wireIn = 0;
        for (long[] byPc : fed)
            for (long b : byPc)
                wireIn += b;
        return new Traffic(rawOut, wireOut, rawIn, wireIn);
    }

    /** The engine's bytes and the compressed bytes on the network, made here ({@code out}) and taken ({@code in}). */
    public record Traffic(long rawOut, long wireOut, long rawIn, long wireIn) {
    }

    /** The host relays a peer's reports, hashes and battle saves to the other peers; the rest goes only to its one taker. */
    static boolean relayed(StreamKind kind) {
        return kind == StreamKind.REPORTS || kind == StreamKind.HASHES || kind == StreamKind.BATTLE;
    }
}
