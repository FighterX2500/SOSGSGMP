package sosgsgmp.session;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.lobby.Builds;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;
import sosgsgmp.net.ConditionedNode;
import sosgsgmp.net.Message;
import sosgsgmp.net.Pieces;
import sosgsgmp.net.Node;
import sosgsgmp.setup.FactionPick;
import sosgsgmp.setup.FactionStep;
import sosgsgmp.setup.FactionWorld;
import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStatus;
import sosgsgmp.setup.MapStep;
import sosgsgmp.setup.MapWorld;
import sosgsgmp.report.ReportStore;

/** The setup hosted on this PC: the host decides the builds and generations and collects every PC's result. */
public final class HostedSetup implements SetupSession {

    private final Node node;
    private final MapWorld world;
    private final FactionWorld factionWorld;
    /** The lobby's difficulty, set on the world the host builds. */
    private final String difficulty;
    /** The lobby's switch for the playtest log (SOS-81). */
    private final boolean fullTransferLog;
    /** The lobby's world: a living world, where the other players settle on the host's built world (SOS-187). */
    private final boolean livingWorld;
    /** A living world built around the host, until this PC takes it to load and settle on; null otherwise. */
    private byte[] settleWorld;
    /** Connected peers in the setup, to their player ids. */
    private final Map<Integer, Integer> joined;
    private final List<LobbyPlayer> players;
    private final MapStep step;
    /** The players' progress as last sent to the joining PCs. */
    private List<MapStep.PlayerStatus> sentStatuses = List.of();
    /** The options of the latest Generate; null before the first. */
    private MapOptions choices;
    /** A Generate at a new size: its options, generated once this PC's game is rebuilt at that size; else null. */
    private MapOptions waiting;
    /** The factions step from the confirm on; null before. */
    private FactionStep factions;
    /** The picks as last sent to the joining PCs. */
    private List<FactionStep.PlayerPick> sentPicks = List.of();
    /** Every player's progress after the build, as last sent to the joining PCs. */
    private List<FactionStep.PlayerProgress> sentProgress = List.of();
    /** The world this PC built, until it takes it to load; null otherwise. */
    private byte[] save;
    private final Cursors cursors = new Cursors();
    private final Notes notes = new Notes();
    /** Players whose PC left the running game, and the hold for them (spec §11 Disconnects, SOS-65). */
    private final Departures departures = new Departures();
    /** Each player's key, by player id: who they are in the campaign (SOS-64). */
    private final Map<Integer, Long> keys;
    private Campaign campaign;
    /** The open campaign save, until every PC reported on it; null otherwise. */
    private SaveRound saveRound;
    /** This PC's own copy of the latest order, until it takes it. */
    private SaveOrder saveOrder;
    /** The first joined player who asked to quit, until this PC takes it; -1 when none (SOS-79). */
    private int quitAsk = -1;
    /** A player asked to quit: the session ends with that ask's save, so every later ask changes nothing. */
    private boolean quitAsked;
    private int doneNumber;
    private String doneOutcome;
    /** The engine's streams once this PC plays; null before. */
    private EngineLink link;
    /** Slots of spectators whose PC left, for the engine. */
    private final java.util.ArrayDeque<Integer> spectatorsLeft = new java.util.ArrayDeque<>();
    /** City viewing's requests once this PC plays; null before (SOS-82). */
    private ViewRequests views;
    /** The latest world resync's transfer number; 0 before the first. */
    private int resyncTransfer;
    /** The world resync this PC sent, until it takes it to load; null otherwise. */
    private WorldResync resync;
    private final Chat chat;
    /** Where joining PCs' reports go; null until {@link #reportsTo} (SOS-133). */
    private Path reportsDir;
    /** Each joining player's reports this session, by player id. */
    private final Map<Integer, ReportStore> reports = new HashMap<>();
    private final Pauses pauses = new Pauses();
    /** The loading window on every PC (SOS-158). */
    private final Loading loading = new Loading();
    /** The number of the setup's latest loading window: its key. */
    private int loadingKey;
    /** The generation the open World generation window waits for; 0 until it runs. */
    private int loadingGenId;
    /** Generate or Initialize, run once the loading window has been drawn, so it shows during the freeze; else null. */
    private Runnable deferred;
    private int deferFrames;
    private boolean closed;
    private String closedReason;
    private int changes;
    private final Builds builds;

    /**
     * Takes over a started lobby's connection, its joined peers (peer to player id), its players (the host first), their
     * keys, its settings, its chat and this PC's builds. A resume starts at the world-loaded step: every player has the campaign's slot, and every
     * PC loads its own save (SOS-64).
     */
    HostedSetup(Node node, Map<Integer, Integer> joined, List<LobbyPlayer> players, Map<Integer, Long> keys, MapWorld world,
            FactionWorld factionWorld, LobbySettings settings, Chat chat, Builds builds) {
        this.node = node;
        this.chat = chat;
        this.builds = builds;
        this.world = world;
        this.factionWorld = factionWorld;
        this.difficulty = settings.difficulty();
        this.fullTransferLog = settings.fullTransferLog();
        this.livingWorld = settings.livingWorld();
        this.joined = new HashMap<>(joined);
        this.players = new ArrayList<>(players);
        this.keys = new HashMap<>(keys);
        this.step = MapStep.host(players.get(0).id(), joined.values());
        if (settings.resume() != null) {
            campaign = settings.resume().campaign();
            Map<Integer, Integer> slots = new HashMap<>();
            for (LobbyPlayer p : players)
                slots.put(p.id(), campaign.player(keys.get(p.id())).slot());
            // whoever of the campaign is missing is removed at the start (spec §11 Resuming), and saves say so from now
            campaign = campaign.removing(Campaign.missingSlots(campaign, slots.values()));
            factions = FactionStep.resumed(players.get(0).id(), slots);
            announceLoading(Loading.Kind.WORLD_INITIALIZATION, ++loadingKey, "Loading the campaign. Every PC loads the world.", true);
            sendAll(new Message.SlotMap(factions.transfer(), factions.slotMap()));
        }
    }

    @Override
    public void update() {
        Node.Event e;
        while (!closed && !waitingToPlay() && (e = node.poll()) != null) {
            switch (e) {
                case Node.Event.Connected c -> {
                    // A late PC: refused when it says hello.
                    notes.add("peer " + c.peer() + " connected after the start");
                }
                case Node.Event.Received r -> received(r.peer(), r.message());
                case Node.Event.Disconnected d -> {
                    notes.add(peerName(d.peer()) + " disconnected: " + d.reason());
                    removeJoined(d.peer());
                }
            }
        }
        checkSilence();
        if (deferred != null && --deferFrames <= 0) {
            Runnable r = deferred;
            deferred = null;
            r.run();
        }
        closeSetupLoading();
        loading.frame(nowMs());
    }

    /** The setup's loading window closes on every PC once every PC is done: results in, or the world loaded. */
    private void closeSetupLoading() {
        Loading.Window w = loading.shown();
        if (w == null)
            return;
        boolean done = switch (w.kind()) {
            case WORLD_GENERATION -> loadingGenId != 0 && step.resultsIn(loadingGenId);
            case WORLD_INITIALIZATION -> factions != null && factions.allLoaded();
            default -> false; // play's windows close in the game, by the steps every PC has passed
        };
        if (done)
            announceLoading(w.kind(), w.key(), "", false);
    }

    @Override
    public Loading loading() {
        return loading;
    }

    @Override
    public void announceLoading(Loading.Kind kind, long key, String text, boolean open) {
        if (loading.wasClosed(kind, key) || open && loading.isOpen(kind, key))
            return;
        if (open)
            loading.open(kind, key, text, nowMs());
        else
            loading.close(kind, key, nowMs(), "by the host");
        sendAll(new Message.Loading(kind.ordinal(), key, text, open));
    }

    /** Runs {@code action} after the loading window has been drawn: two updates later, a frame between them. */
    private void afterLoadingShows(Runnable action) {
        deferred = action;
        deferFrames = 2;
    }

    /** The step a player-gone or lost hold counts its lead from: the host's, or later when a peer may run further. */
    private long holdBase() {
        return Math.max(link.step(), link.stepPastPeers() - Departures.LEAD);
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    @Override
    public Chat chat() {
        return chat;
    }

    @Override
    public void say(String text) {
        String out = chat.outgoing(text, System.nanoTime());
        if (out == null)
            return;
        chat.heard(localPlayerId(), players, out);
        relay(new Message.Chat(localPlayerId(), out));
    }

    /** Every joined PC gets it, its sender too: the host's order is every PC's. */
    private void relay(Message m) {
        for (int peer : joined.keySet())
            node.send(peer, m);
    }

    @Override
    public Pauses pauses() {
        return pauses;
    }

    @Override
    public void pause(boolean paused) {
        heard(new Message.Pause(localPlayerId(), paused));
    }

    private void heard(Message.Pause p) {
        pauses.heard(p);
        relay(p);
    }

    private void received(int peer, Message m) {
        Integer id = joined.get(peer);
        switch (m) {
            case Message.BuildReady b when id != null -> {
                if (step.onBuildReady(id, b.buildId()))
                    changed();
            }
            case Message.MapResult r when id != null -> {
                if (step.onResult(id, r.genId(), r.hash(), r.status()))
                    changed();
            }
            case Message.Ready r when id != null && factions != null -> {
                if (factions.onReady(id, r.ready()))
                    picksChanged();
            }
            case Message.Pick p when id != null && factions != null -> {
                if (factions.onPick(id, p.pick()))
                    picksChanged();
            }
            case Message.Loaded l when id != null && factions != null -> {
                if (factions.onLoaded(id, l.transfer(), l.hash()))
                    progressChanged();
            }
            case Message.StartSpot s when id != null && factions != null -> {
                if (factions.onStartSpot(id, s.transfer())) {
                    progressChanged();
                    goWhenReady();
                }
            }
            case Message.Stream s when id != null && link != null -> stream(peer, id, s);
            case Message.ViewAsk v when id != null && views != null -> view(id, v);
            case Message.ViewAnswer v when id != null && views != null -> view(id, v);
            case Message.ViewClose v when id != null && views != null -> view(id, v);
            case Message.ViewCity v when id != null && views != null -> view(id, v);
            case Message.ViewTape v when id != null && views != null -> view(id, v);
            case Message.ViewHashes v when id != null && views != null -> view(id, v);
            case Message.Report r when id != null -> {
                if (link != null)
                    report(id, r);
            }
            case Message.Cursor c when id != null -> {
                cursors.set(id, c.place(), c.x(), c.y());
                Message relayed = new Message.Cursor(id, c.place(), c.x(), c.y());
                for (Map.Entry<Integer, Integer> p : joined.entrySet())
                    if (p.getKey() != peer)
                        node.send(p.getKey(), relayed);
            }
            case Message.Pause p when id != null -> heard(new Message.Pause(id, p.paused()));
            case Message.Chat c when id != null -> {
                chat.heard(id, players, c.text());
                relay(new Message.Chat(id, Chat.clean(c.text())));
            }
            case Message.Saved s when id != null && saveRound != null && s.number() == saveRound.order.number() -> {
                if (saveRound.report(id, s.hash(), s.problem().isEmpty() ? null : s.problem()))
                    saveDoneWhenComplete();
            }
            case Message.QuitAsk q when id != null && link != null -> {
                if (!quitAsked) {
                    quitAsked = true;
                    quitAsk = id;
                }
            }
            case Message.Leave l when id != null -> {
                notes.add(name(id) + " left");
                node.disconnect(peer);
                removeJoined(peer);
            }
            case Message.Hello h when id == null -> refuse(peer, Refusal.STARTED);
            case Message.Unsupported u when id == null -> refuse(peer, Refusal.WRONG_VERSION);
            default -> {
                // Anything else breaks the protocol: drop the PC.
                notes.add(peerName(peer) + " sent " + m.getClass().getSimpleName() + ", which doesn't belong here: dropped");
                node.disconnect(peer);
                removeJoined(peer);
            }
        }
    }

    /** A peer's piece: checked, fed to the engine, and its reports and hashes relayed to the other peers. */
    private void stream(int peer, int id, Message.Stream s) {
        String problem = s.pc() != factions.slotOf(id) ? "a stream of PC " + s.pc() + " from PC " + factions.slotOf(id) : link.problem(s);
        if (problem != null) {
            closedByPeer(id, name(id) + " sent a broken game stream: " + problem);
            return;
        }
        if (!link.in(s) || !EngineLink.relayed(s.kind()))
            return;
        for (int other : joined.keySet())
            if (other != peer)
                node.send(other, s);
    }

    /** A peer's view message: routed by the host; one that breaks the protocol is noted and dropped. */
    private void view(int id, Message m) {
        String problem = views.received(factions.slotOf(id), m);
        if (problem != null)
            notes.add(name(id) + " sent a view message that doesn't fit, dropped: " + problem);
    }

    /** Sends to the PC in {@code slot}: the peer whose player has it. */
    private void sendToSlot(int slot, Message m) {
        for (Map.Entry<Integer, Integer> p : joined.entrySet())
            if (factions.slotOf(p.getValue()) == slot) {
                node.send(p.getKey(), m);
                return;
            }
        notes.add("a view message for slot " + slot + ", whose PC is gone: dropped");
    }

    private void removeJoined(int peer) {
        Integer id = joined.remove(peer);
        if (id == null)
            return;
        if (link != null) {
            gone(id);
            return;
        }
        if (factions != null && factions.initialized()) {
            // Their slot is in the world every PC loads: the game can't start without them (a resume can: spec §11).
            closedByPeer(id, name(id) + (factions.started() ? " left; the game can't go on without them."
                    : " left after the world was built; the game can't start without them."));
            return;
        }
        players.removeIf(p -> p.id() == id);
        cursors.removePlayer(id);
        step.remove(id);
        sendAll(new Message.Roster(players));
        changed();
        if (factions != null && factions.remove(id))
            picksChanged();
    }

    /**
     * Player {@code id}'s PC left the running game (spec §11 Disconnects): every PC holds at one step until this PC's
     * player chooses ({@link #chooseGone}). Nothing more comes from that PC, so nothing of it is relayed.
     */
    private void gone(int id) {
        Campaign.Player who = campaign == null ? null : campaign.inSlot(factions.slotOf(id));
        if (who != null && who.eliminated()) {
            spectatorGone(id, who.slot());
            return;
        }
        Departures.Gone g = new Departures.Gone(id, name(id), factions.slotOf(id));
        players.removeIf(p -> p.id() == id);
        cursors.removePlayer(id);
        long hold = departures.left(g, holdBase());
        views.gone(g.slot()); // an ask or a view they are in ends (SOS-82)
        notes.add(g.name() + " left at step " + link.step() + "; every PC holds at " + hold);
        sendAll(new Message.PlayerGone(id, hold));
        if (saveRound != null && saveRound.report(id, 0, "left the game")) // the open save waits for them no more
            saveDoneWhenComplete();
        changes++;
    }

    /**
     * In play, a player's PC silent for {@link Departures#LOST_MS} with its connection open is lost: every PC holds as
     * for one gone, and waits. Heard again before the host chose, they are back, and the hold ends once nobody else is
     * in it (SOS-177). A spectator holds nobody.
     */
    private void checkSilence() {
        if (link == null || closed || departures.choice() != null)
            return;
        for (Map.Entry<Integer, Integer> p : joined.entrySet()) {
            int id = p.getValue();
            long silent = node.silentMs(p.getKey());
            if (departures.lost(id)) {
                long hold = departures.holdStep();
                if (silent < Departures.BACK_MS && departures.back(id)) {
                    notes.add(name(id) + " is heard again at step " + link.step() + (departures.waiting() ? "" : "; the hold at " + hold + " ends"));
                    sendAll(new Message.PlayerBack(id, hold));
                    changes++;
                }
            } else if (silent >= Departures.LOST_MS && !inHold(id) && !spectator(id)) {
                Departures.Gone g = new Departures.Gone(id, name(id), factions.slotOf(id));
                long hold = departures.lost(g, holdBase(), nowMs() - silent);
                notes.add(g.name() + " silent for " + silent / 1000 + " s at step " + link.step() + "; every PC holds at " + hold);
                sendAll(new Message.PlayerLost(id, hold, silent));
                changes++;
            }
        }
    }

    @Override
    public int rttMs(int playerId) {
        for (Map.Entry<Integer, Integer> p : joined.entrySet())
            if (p.getValue() == playerId)
                return node.rttMs(p.getKey());
        return -1;
    }

    private boolean inHold(int id) {
        for (Departures.Gone g : departures.gone())
            if (g.playerId() == id)
                return true;
        return false;
    }

    private boolean spectator(int id) {
        Campaign.Player who = campaign == null ? null : campaign.inSlot(factions.slotOf(id));
        return who != null && who.eliminated();
    }

    /**
     * Host, Continue without them: a lost player's PC may still be there, so it is told it was removed and dropped, and
     * leaves as one gone does.
     */
    private void dropLost(Departures.Gone g) {
        for (Map.Entry<Integer, Integer> p : new ArrayList<>(joined.entrySet()))
            if (p.getValue() == g.playerId()) {
                refuse(p.getKey(), Refusal.REMOVED);
                joined.remove(p.getKey());
            }
        players.removeIf(p -> p.id() == g.playerId());
        cursors.removePlayer(g.playerId());
        views.gone(g.slot());
        if (saveRound != null && saveRound.report(g.playerId(), 0, "was removed"))
            saveDoneWhenComplete();
        notes.add(g.name() + ", lost, is removed");
    }

    /** A spectator's PC left: nobody holds and nobody is asked (spec §11); the engine hears of it from the next frame. */
    private void spectatorGone(int id, int slot) {
        notes.add(name(id) + ", a spectator, left at step " + link.step());
        chat.notice(name(id) + " stopped watching and left the game.");
        players.removeIf(p -> p.id() == id);
        cursors.removePlayer(id);
        views.gone(slot);
        spectatorsLeft.add(slot);
        sendAll(new Message.Roster(players));
        if (saveRound != null && saveRound.report(id, 0, "left the game"))
            saveDoneWhenComplete();
        changes++;
    }

    @Override
    public void dropSpectator(int slot) {
        for (Map.Entry<Integer, Integer> p : new ArrayList<>(joined.entrySet()))
            if (factions.slotOf(p.getValue()) == slot) {
                node.send(p.getKey(), new Message.Refused(Refusal.TOO_SLOW));
                node.disconnect(p.getKey());
                removeJoined(p.getKey());
                return;
            }
    }

    @Override
    public Integer takeSpectatorLeft() {
        return spectatorsLeft.poll();
    }

    @Override
    public void eliminated(int slot) {
        campaign = campaign.eliminating(slot);
    }

    @Override
    public Departures departures() {
        return departures;
    }

    @Override
    public void chooseGone(Departures.Choice choice) {
        if (!departures.waiting())
            throw new IllegalStateException("SOSGSGMP: a choice with no player gone, or chosen already");
        long hold = departures.holdStep();
        departures.chosen(hold, choice);
        sendAll(new Message.GoneChoice(hold, choice == Departures.Choice.CONTINUE));
        changes++;
        if (choice == Departures.Choice.RETURN) {
            leave();
            closedReason = "You chose to return to the last save. Resume the campaign from Load Saved Lobby.";
        } else {
            for (Departures.Gone g : departures.gone())
                if (departures.lost(g.playerId()))
                    dropLost(g);
            campaign = campaign.removing(slotsOf(departures.gone()));
        }
    }

    static List<Integer> slotsOf(List<Departures.Gone> gone) {
        List<Integer> slots = new ArrayList<>();
        for (Departures.Gone g : gone)
            slots.add(g.slot());
        return slots;
    }

    /** Closes the setup for everyone because of player {@code id}: they left, or broke the protocol. */
    private void closedByPeer(int id, String reason) {
        leave();
        closedReason = reason;
    }

    /** A peer for the log: its player's name, or its peer number before it joined. */
    private String peerName(int peer) {
        Integer id = joined.get(peer);
        return id != null ? name(id) + " (peer " + peer + ")" : "peer " + peer;
    }

    @Override
    public void reportsTo(Path dir) {
        reportsDir = dir;
    }

    /** A piece of player {@code id}'s files: into their folder of this campaign's reports. */
    private void report(int id, Message.Report r) {
        if (reportsDir == null)
            return;
        ReportStore store = reports.computeIfAbsent(id, i -> new ReportStore(reportsDir
                .resolve(campaign == null ? "new game" : String.format("%016x", campaign.id()))
                .resolve(factions.slotOf(i) + "-" + ReportStore.clean(name(i))), name(i)));
        String line = store.accept(r);
        if (line != null)
            notes.add(line);
    }

    private String name(int id) {
        for (LobbyPlayer p : players)
            if (p.id() == id)
                return p.name();
        return "A player";
    }

    /** A player's progress after the build changed: count it, and send it to the joining PCs when it differs. */
    private void progressChanged() {
        changes++;
        List<FactionStep.PlayerProgress> now = factions.progress();
        if (now.isEmpty() || now.equals(sentProgress))
            return;
        sentProgress = now;
        sendAll(new Message.WorldPlayers(factions.transfer(), now));
    }

    /** The table of picks changed: count it, and send it to the joining PCs when it differs from what was sent. */
    private void picksChanged() {
        changes++;
        List<FactionStep.PlayerPick> now = factions.table();
        if (now.isEmpty() || now.equals(sentPicks))
            return;
        chat.readyChanged(sentPicks, now, players);
        sentPicks = now;
        sendAll(new Message.Picks(now));
    }

    /** A player's progress may have changed: count it, and tell the joining PCs if it did. */
    private void changed() {
        changes++;
        sendStatuses(false);
    }

    /** Sends every player's progress when it differs from what was sent last, or {@code always}. */
    private void sendStatuses(boolean always) {
        if (step.buildId() == 0)
            return;
        List<MapStep.PlayerStatus> now = step.statuses();
        if (!always && now.equals(sentStatuses))
            return;
        sentStatuses = now;
        sendAll(new Message.MapPlayers(step.buildId(), step.genId(), now));
    }

    private void refuse(int peer, Refusal refusal) {
        node.send(peer, new Message.Refused(refusal));
        node.disconnect(peer);
    }

    private void sendAll(Message m) {
        for (int peer : joined.keySet())
            node.send(peer, m);
    }

    private boolean waitingToPlay() {
        return factions != null && factions.started() && link == null;
    }

    @Override
    public void play(EnginePort port) {
        if (factions == null || !factions.started() || link != null)
            throw new IllegalStateException("SOSGSGMP: play before the start, or twice");
        int pc = factions.slotOf(localPlayerId());
        link = new EngineLink(true, pc, pc, port);
        views = new ViewRequests(true, pc, pc, this::sendToSlot, System::nanoTime);
    }

    @Override
    public EngineLink.Traffic traffic() {
        return link == null ? new EngineLink.Traffic(0, 0, 0, 0) : link.traffic();
    }

    @Override
    public ConditionedNode testNetwork() {
        return node instanceof ConditionedNode c ? c : null;
    }

    @Override
    public boolean playing() {
        return link != null;
    }

    @Override
    public ViewRequests views() {
        return views;
    }

    @Override
    public void exchange() {
        if (closed)
            return;
        for (Message.Stream s : link.out())
            sendAll(s);
    }

    @Override
    public void resync(WorldResync r) {
        if (link == null || closed)
            throw new IllegalStateException("SOSGSGMP: a resync outside the game");
        resyncTransfer++;
        List<byte[]> pieces = Pieces.split(r.pack());
        for (int i = 0; i < pieces.size(); i++)
            sendAll(new Message.Resync(resyncTransfer, r.step(), i, pieces.size(), pieces.get(i)));
        resync = r;
        changes++;
    }

    @Override
    public WorldResync takeResync() {
        WorldResync r = resync;
        resync = null;
        return r;
    }

    @Override
    public Campaign campaign() {
        return campaign;
    }

    @Override
    public void orderSave(SaveOrder order) {
        if (link == null || closed)
            throw new IllegalStateException("SOSGSGMP: a campaign save outside the game");
        if (saveRound != null)
            throw new IllegalStateException("SOSGSGMP: save " + order.number() + " while save " + saveRound.order.number() + " is open");
        List<Integer> ids = new ArrayList<>();
        for (LobbyPlayer p : players)
            ids.add(p.id());
        saveRound = new SaveRound(order, localPlayerId(), ids);
        saveOrder = order;
        sendAll(new Message.SaveAt(order));
        changes++;
    }

    @Override
    public SaveOrder takeSaveOrder() {
        SaveOrder o = saveOrder;
        saveOrder = null;
        return o;
    }

    @Override
    public void askQuit() {
        throw new IllegalStateException("SOSGSGMP: the host quits through its own campaign save");
    }

    @Override
    public int takeQuitAsk() {
        int id = quitAsk;
        quitAsk = -1;
        return id;
    }

    @Override
    public void saved(int number, long hash, String problem) {
        if (saveRound == null || saveRound.order.number() != number)
            throw new IllegalStateException("SOSGSGMP: saved for order " + number + ", which isn't open");
        saveRound.report(localPlayerId(), hash, problem);
        saveDoneWhenComplete();
    }

    /** Every PC reported on the open save: every PC, this one too, gets the outcome and plays on. */
    private void saveDoneWhenComplete() {
        if (!saveRound.complete())
            return;
        String outcome = saveRound.outcome(this::name);
        doneNumber = saveRound.order.number();
        doneOutcome = outcome;
        sendAll(new Message.SaveDone(doneNumber, outcome));
        saveRound = null;
        changes++;
    }

    @Override
    public String takeSaveDone(int number) {
        if (doneOutcome == null || doneNumber != number)
            return null;
        String o = doneOutcome;
        doneOutcome = null;
        return o;
    }

    @Override
    public void cursor(int place, int x, int y) {
        if (!closed && cursors.own(place, x, y, System.nanoTime()))
            sendAll(new Message.Cursor(localPlayerId(), place, x, y));
    }

    @Override
    public Cursors cursors() {
        return cursors;
    }

    @Override
    public boolean isHost() {
        return true;
    }

    @Override
    public int localPlayerId() {
        return step.localPlayerId();
    }

    @Override
    public List<LobbyPlayer> players() {
        return List.copyOf(players);
    }

    @Override
    public MapStep step() {
        return step;
    }

    @Override
    public void build(int size) {
        int buildId = step.build(size);
        sendAll(new Message.MapBuild(buildId, size));
        changes++;
        sendStatuses(true);
    }

    @Override
    public void built() {
        step.built(step.pendingBuild());
        changed();
        if (waiting != null) {
            MapOptions o = waiting;
            waiting = null;
            generateNow(o);
        }
    }

    /**
     * Opens the World generation window on every PC, then, once it shows: at the current size, generates here and sends
     * the generation and its terrain to every joining PC. At another size, every PC rebuilds its game first, and the
     * generation runs when this PC's rebuild is done ({@link #built}). A Generate while one waits to run changes nothing.
     */
    @Override
    public void generate(MapOptions options) {
        if (!options.isRandom() && !MapOptions.presetsAllowed(options.size()))
            throw new IllegalArgumentException("SOSGSGMP: map type '" + options.mapType() + "' at size " + options.size() + "; presets only at " + MapOptions.DEFAULT_SIZE);
        if (deferred != null)
            return;
        choices = options; // the panel shows the choices at once
        loadingGenId = 0;
        announceLoading(Loading.Kind.WORLD_GENERATION, ++loadingKey, "The host is generating the world.", true);
        afterLoadingShows(() -> generateNow(options));
    }

    private void generateNow(MapOptions options) {
        choices = options;
        if (options.size() != step.size()) {
            waiting = options;
            build(options.size());
            return;
        }
        int genId = step.generate(options);
        loadingGenId = genId;
        sendAll(new Message.MapGenerate(genId, step.buildId(), options.mapType(), options.latitude(), options.seed()));
        MapWorld.Terrain terrain = world.generate(options);
        step.generated(genId, terrain.hash(), MapStatus.OK);
        List<byte[]> pieces = Pieces.split(terrain.bytes());
        for (int i = 0; i < pieces.size(); i++)
            sendAll(new Message.MapTerrain(genId, i, pieces.size(), pieces.get(i)));
        changes++;
        sendStatuses(true);
    }

    @Override
    public MapOptions choices() {
        return choices;
    }

    @Override
    public void confirm() {
        step.confirm();
        sendAll(new Message.MapConfirm(step.genId(), step.confirmedHash()));
        world.lock();
        List<Integer> joiners = new ArrayList<>();
        for (LobbyPlayer p : players.subList(1, players.size()))
            joiners.add(p.id());
        factions = livingWorld ? FactionStep.host(step.localPlayerId(), joiners, factionWorld::locationProblem, factionWorld::settleProblem)
                : FactionStep.host(step.localPlayerId(), joiners, factionWorld::locationProblem);
        changed();
    }

    @Override
    public FactionStep factions() {
        return factions;
    }

    @Override
    public boolean fullTransferLog() {
        return fullTransferLog;
    }

    @Override
    public void pick(FactionPick pick) {
        if (factions == null)
            throw new IllegalStateException("SOSGSGMP: picks before the map is confirmed");
        factions.ownPick(pick);
        if (factions.onPick(step.localPlayerId(), pick))
            picksChanged();
    }

    @Override
    public void ready(boolean ready) {
        if (factions == null)
            throw new IllegalStateException("SOSGSGMP: ready before the map is confirmed");
        if (factions.onReady(step.localPlayerId(), ready))
            picksChanged();
    }

    @Override
    public String locationProblem(int x, int y) {
        if (factions == null)
            throw new IllegalStateException("SOSGSGMP: a location before the map is confirmed");
        return factions.locationProblem(x, y, factions.settling() ? factionWorld::settleProblem : factionWorld::locationProblem);
    }

    /**
     * Opens the World initialization window on every PC, then, once it shows: builds the world here (the game holds
     * this thread for a few seconds; nothing arrives meanwhile), locks the picks and sends every joining PC the slots and
     * the save in pieces. A build that fails locks nothing. An Initialize while one waits to run changes nothing.
     */
    @Override
    public void initialize() {
        if (factions == null || !(factions.canInitialize() || factions.canBuildForSettling()))
            throw new IllegalStateException("SOSGSGMP: the world can't be initialized now");
        if (deferred != null)
            return;
        int key = ++loadingKey;
        if (factions.canBuildForSettling()) {
            announceLoading(Loading.Kind.WORLD_INITIALIZATION, key,
                    "Building the world around the host. Every PC loads it, and the other players pick their spot on it.", true);
            afterLoadingShows(() -> buildForSettlingNow(key));
            return;
        }
        announceLoading(Loading.Kind.WORLD_INITIALIZATION, key, "Initializing the world. Every PC loads it.", true);
        afterLoadingShows(() -> initializeNow(key));
    }

    /**
     * A living world's first Initialize (SOS-187), once the loading window shows: the world is built around the host
     * alone, the others' Ready goes, and every PC gets the save to settle on, this one too. The window closes once it is
     * sent: each PC's load shows the game's own loading screen.
     */
    private void buildForSettlingNow(int key) {
        if (factions == null || !factions.canBuildForSettling()) { // a player left meanwhile: the host presses again
            announceLoading(Loading.Kind.WORLD_INITIALIZATION, key, "", false);
            return;
        }
        List<FactionPick> humans = factions.humans();
        byte[] built = factionWorld.buildForSettling(humans.get(0), humans.size());
        factions.beginSettling();
        List<byte[]> pieces = Pieces.split(built);
        for (int i = 0; i < pieces.size(); i++)
            sendAll(new Message.SettlePart(i, pieces.size(), pieces.get(i)));
        settleWorld = built;
        announceLoading(Loading.Kind.WORLD_INITIALIZATION, key, "", false);
        picksChanged();
    }

    @Override
    public byte[] takeSettleWorld() {
        byte[] s = settleWorld;
        settleWorld = null;
        return s;
    }

    private void initializeNow(int key) {
        if (factions == null || !factions.canInitialize()) { // a player left meanwhile: the host initializes again
            announceLoading(Loading.Kind.WORLD_INITIALIZATION, key, "", false);
            return;
        }
        byte[] built = factions.living() ? factionWorld.settle(factions.humans(), difficulty)
                : factionWorld.buildWorld(factions.humans(), difficulty);
        int transfer = factions.initialize();
        List<Campaign.Player> inCampaign = new ArrayList<>();
        for (LobbyPlayer p : players)
            inCampaign.add(new Campaign.Player(keys.get(p.id()), p.name(), factions.slotOf(p.id())));
        campaign = new Campaign(Campaign.idOf(built), Campaign.nameFor(players.get(0).name()), choices.seed(), difficulty, inCampaign);
        sendAll(new Message.SlotMap(transfer, factions.slotMap()));
        List<byte[]> pieces = Pieces.split(built);
        for (int i = 0; i < pieces.size(); i++)
            sendAll(new Message.WorldPart(transfer, i, pieces.size(), pieces.get(i)));
        save = built;
        progressChanged();
    }

    @Override
    public byte[] takeWorld() {
        byte[] s = save;
        save = null;
        return s;
    }

    @Override
    public void worldLoaded(long hash) {
        factions.loaded(hash);
        progressChanged();
    }

    @Override
    public void startSpot() {
        factions.startSpot();
        progressChanged();
        goWhenReady();
    }

    /** Every PC loaded the same world and placed its landing party: the game starts on every PC. */
    private void goWhenReady() {
        if (!factions.canGo())
            return;
        factions.go();
        sendAll(new Message.Go(factions.transfer()));
        changes++;
    }

    @Override
    public void leave() {
        if (closed)
            return;
        sendAll(new Message.Leave());
        node.close();
        closed = true;
        closedReason = "You closed the game.";
        changes++;
    }

    @Override
    public int changes() {
        return changes;
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public String closedReason() {
        return closedReason;
    }

    @Override
    public String network() {
        return node.describe();
    }

    @Override
    public Builds builds() {
        return builds;
    }

    @Override
    public String takeNote() {
        return notes.take();
    }
}
