package sosgsgmp.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * An in-memory Steam for tests: users, friends-only lobbies and P2P packets. A packet from a user this one hasn't
 * accepted asks for a session first and waits until it is accepted, as Steam's P2P does. Callbacks are queued and
 * delivered in {@link Port#runCallbacks()}. Every port runs on its own node's thread, so all of it is synchronized.
 */
public final class FakeSteam {

    private long nextLobby = 1000;
    private final Map<Long, Set<Long>> lobbies = new HashMap<>();
    private final Map<Long, Port> users = new HashMap<>();
    /** As the newer messages may: an accept before the first packet does nothing, and no session request arrives. */
    private boolean silentSessions;

    public synchronized void silentSessions() {
        silentSessions = true;
    }

    /** A user's port; each call to the node's supplier should make a new one for a new user id. */
    public synchronized Port port(long user) {
        Port p = new Port(user);
        users.put(user, p);
        return p;
    }

    public final class Port implements SteamPort {
        private final long self;
        private Listener listener;
        private final Queue<Runnable> callbacks = new ArrayDeque<>();
        private final Set<Long> accepted = new HashSet<>();
        private final Map<Long, Queue<byte[]>> waiting = new HashMap<>();
        private final Queue<long[]> order = new ArrayDeque<>();
        private final Queue<byte[]> packets = new ArrayDeque<>();
        boolean closed;

        Port(long self) {
            this.self = self;
        }

        @Override
        public void listener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public long self() {
            return self;
        }

        @Override
        public void createLobby(int maxMembers) {
            synchronized (FakeSteam.this) {
                long id = nextLobby++;
                Set<Long> members = new LinkedHashSet<>();
                members.add(self);
                lobbies.put(id, members);
                later(() -> listener.lobbyCreated(id, null));
                later(() -> listener.lobbyEntered(id, null));
            }
        }

        @Override
        public void joinLobby(long lobby) {
            synchronized (FakeSteam.this) {
                Set<Long> members = lobbies.get(lobby);
                if (members == null) {
                    later(() -> listener.lobbyEntered(lobby, "no such lobby"));
                    return;
                }
                for (long m : members)
                    users.get(m).later(() -> users.get(m).listener.memberJoined(lobby, self));
                members.add(self);
                later(() -> listener.lobbyEntered(lobby, null));
            }
        }

        @Override
        public void leaveLobby(long lobby) {
            synchronized (FakeSteam.this) {
                Set<Long> members = lobbies.get(lobby);
                if (members == null || !members.remove(self))
                    return;
                for (long m : members)
                    users.get(m).later(() -> users.get(m).listener.memberLeft(lobby, self));
            }
        }

        @Override
        public long lobbyOwner(long lobby) {
            synchronized (FakeSteam.this) {
                Set<Long> members = lobbies.get(lobby);
                return members == null || members.isEmpty() ? 0 : members.iterator().next();
            }
        }

        @Override
        public void lobbyData(long lobby, String key, String value) {
        }

        @Override
        public void invite(long lobby) {
        }

        @Override
        public List<FriendLobby> friendLobbies() {
            synchronized (FakeSteam.this) {
                List<FriendLobby> found = new ArrayList<>();
                for (Map.Entry<Long, Set<Long>> e : lobbies.entrySet())
                    if (!e.getValue().isEmpty() && !e.getValue().contains(self))
                        found.add(new FriendLobby("user" + e.getValue().iterator().next(), e.getKey()));
                return found;
            }
        }

        /** Test: as if this user accepted an invite to {@code lobby} in Steam's overlay. */
        public void acceptInvite(long lobby) {
            synchronized (FakeSteam.this) {
                later(() -> listener.joinRequested(lobby));
            }
        }

        @Override
        public void acceptSession(long user) {
            synchronized (FakeSteam.this) {
                if (silentSessions && !waiting.containsKey(user))
                    return;
                accepted.add(user);
                Queue<byte[]> q = waiting.remove(user);
                if (q != null)
                    for (byte[] b : q) {
                        order.add(new long[] {user});
                        packets.add(b);
                    }
            }
        }

        @Override
        public void closeSession(long user) {
            synchronized (FakeSteam.this) {
                accepted.remove(user);
            }
        }

        @Override
        public boolean send(long user, byte[] bytes) {
            synchronized (FakeSteam.this) {
                Port to = users.get(user);
                if (to == null || to.closed)
                    return false;
                to.deliver(self, bytes.clone());
                return true;
            }
        }

        private void deliver(long from, byte[] bytes) {
            if (accepted.contains(from)) {
                order.add(new long[] {from});
                packets.add(bytes);
                return;
            }
            boolean first = !waiting.containsKey(from);
            waiting.computeIfAbsent(from, k -> new ArrayDeque<>()).add(bytes);
            if (first && !silentSessions)
                later(() -> listener.sessionRequested(from));
        }

        @Override
        public byte[] read(long[] from) {
            synchronized (FakeSteam.this) {
                long[] f = order.poll();
                if (f == null)
                    return null;
                from[0] = f[0];
                return packets.poll();
            }
        }

        @Override
        public void runCallbacks() {
            List<Runnable> now;
            synchronized (FakeSteam.this) {
                now = new ArrayList<>(callbacks);
                callbacks.clear();
            }
            for (Runnable r : now)
                r.run();
        }

        private void later(Runnable r) {
            callbacks.add(r);
        }

        @Override
        public void close() {
            synchronized (FakeSteam.this) {
                closed = true;
            }
        }
    }
}
