package sosgsgmp.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
            byte[] bytes = port.out(kind);
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
        port.in(s.kind(), s.pc(), fresh);
        fed[s.kind().ordinal()][s.pc()] = end;
        return true;
    }

    /** The host relays a peer's reports, hashes and battle saves to the other peers; the rest goes only to its one taker. */
    static boolean relayed(StreamKind kind) {
        return kind == StreamKind.REPORTS || kind == StreamKind.HASHES || kind == StreamKind.BATTLE;
    }
}
