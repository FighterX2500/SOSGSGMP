package sosgsgmp.net;

import java.util.HashMap;
import java.util.Map;

import sosgsgmp.log.Recorder;

/**
 * A {@link Node} whose messages go into the recorder (SOS-248) as the game thread sends them and polls them: the
 * message's type, its payload's size where it has one (engine streams, reports), and the peer. Never what a message
 * says: a chat line is a header only (spec §11). It records nothing until {@link #recordTo} names a recorder. Game
 * thread only, as the node's {@code send} and {@code poll} are.
 */
public final class RecordingNode implements Node {

    private final Node inner;
    /** Each message type's number: its place among {@link Message}'s permitted types, named in the recording. */
    private final Map<Class<?>, Integer> types = new HashMap<>();
    private final String typeNames;
    private Recorder recorder;

    public RecordingNode(Node inner) {
        this.inner = inner;
        StringBuilder b = new StringBuilder("message types:");
        Class<?>[] all = Message.class.getPermittedSubclasses();
        for (int i = 0; i < all.length; i++) {
            types.put(all[i], i);
            b.append(' ').append(i).append('=').append(all[i].getSimpleName());
        }
        typeNames = b.toString();
    }

    /** The node under a recording one, or the node itself. */
    public static Node under(Node node) {
        return node instanceof RecordingNode r ? r.inner : node;
    }

    /** Records into {@code r} from now on (null: nothing), its message types named first. */
    public void recordTo(Recorder r) {
        recorder = r;
        if (r != null)
            r.text(Recorder.TYPES, -1, typeNames);
    }

    private int type(Message m) {
        Integer t = types.get(m.getClass());
        return t == null ? -1 : t;
    }

    private static int size(Message m) {
        if (m instanceof Message.Stream s)
            return s.length();
        if (m instanceof Message.Report r)
            return r.length();
        return 0;
    }

    @Override
    public void send(int peer, Message m) {
        if (recorder != null)
            recorder.record(Recorder.NET_OUT, peer, size(m), type(m), 0);
        inner.send(peer, m);
    }

    @Override
    public Event poll() {
        Event e = inner.poll();
        if (recorder != null && e != null) {
            switch (e) {
                case Event.Received r -> recorder.record(Recorder.NET_IN, r.peer(), size(r.message()), type(r.message()), 0);
                case Event.Connected c -> recorder.record(Recorder.NET_CONNECT, c.peer(), 0, 0, 0);
                case Event.Disconnected d -> recorder.record(Recorder.NET_DISCONNECT, d.peer(), 0, 0, 0);
            }
        }
        return e;
    }

    @Override
    public void disconnect(int peer) {
        inner.disconnect(peer);
    }

    @Override
    public String describe() {
        return inner.describe();
    }

    @Override
    public long silentMs(int peer) {
        return inner.silentMs(peer);
    }

    @Override
    public int rttMs(int peer) {
        return inner.rttMs(peer);
    }

    @Override
    public void silence(int seconds) {
        inner.silence(seconds);
    }

    @Override
    public void cutAll() {
        inner.cutAll();
    }

    @Override
    public void close() {
        inner.close();
    }
}
