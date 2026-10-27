package sosgsgmp.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sosgsgmp.campaign.Campaign;
import sosgsgmp.campaign.SaveOrder;
import sosgsgmp.lobby.LobbyPlayer;
import sosgsgmp.lobby.LobbySettings;
import sosgsgmp.lobby.Refusal;
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

/** The setup hosted on this PC: the host decides the builds and generations and collects every PC's result. */
public final class HostedSetup implements SetupSession {

    private final Node node;
    private final MapWorld world;
    private final FactionWorld factionWorld;
    /** The lobby's difficulty, set on the world the host builds. */
    private final String difficulty;
    /** The lobby's switch for the playtest log (SOS-81). */
    private final boolean fullTransferLog;
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
    /** The latest world resync's transfer number; 0 before the first. */
    private int resyncTransfer;
    /** The world resync this PC sent, until it takes it to load; null otherwise. */
    private WorldResync resync;
    private boolean closed;
    private String closedReason;
    private int changes;

    /**
     * Takes over a started lobby's connection, its joined peers (peer to player id), its players (the host first), their
     * keys and its settings. A resume starts at the world-loaded step: every player has the campaign's slot, and every
     * PC loads its own save (SOS-64).
     */
    HostedSetup(Node node, Map<Integer, Integer> joined, List<LobbyPlayer> players, Map<Integer, Long> keys, MapWorld world,
            FactionWorld factionWorld, LobbySettings settings) {
        this.node = node;
        this.world = world;
        this.factionWorld = factionWorld;
        this.difficulty = settings.difficulty();
        this.fullTransferLog = settings.fullTransferLog();
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
            case Message.Cursor c when id != null -> {
                cursors.set(id, c.onMap(), c.x(), c.y());
                Message relayed = new Message.Cursor(id, c.onMap(), c.x(), c.y());
                for (Map.Entry<Integer, Integer> p : joined.entrySet())
                    if (p.getKey() != peer)
                        node.send(p.getKey(), relayed);
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
        Departures.Gone g = new Departures.Gone(id, name(id), factions.slotOf(id));
        players.removeIf(p -> p.id() == id);
        cursors.removePlayer(id);
        long hold = departures.left(g, link.step());
        notes.add(g.name() + " left at step " + link.step() + "; every PC holds at " + hold);
        sendAll(new Message.PlayerGone(id, hold));
        if (saveRound != null && saveRound.report(id, 0, "left the game")) // the open save waits for them no more
            saveDoneWhenComplete();
        changes++;
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
    }

    @Override
    public boolean playing() {
        return link != null;
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
    public void cursor(boolean onMap, int x, int y) {
        if (!closed && cursors.own(onMap, x, y, System.nanoTime()))
            sendAll(new Message.Cursor(localPlayerId(), onMap, x, y));
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
            generate(o);
        }
    }

    /**
     * At the current size, generates here and sends the generation and its terrain to every joining PC. At another
     * size, every PC rebuilds its game first, and the generation runs when this PC's rebuild is done ({@link #built}).
     */
    @Override
    public void generate(MapOptions options) {
        if (!options.isRandom() && !MapOptions.presetsAllowed(options.size()))
            throw new IllegalArgumentException("SOSGSGMP: map type '" + options.mapType() + "' at size " + options.size() + "; presets only at " + MapOptions.DEFAULT_SIZE);
        choices = options;
        if (options.size() != step.size()) {
            waiting = options;
            build(options.size());
            return;
        }
        int genId = step.generate(options);
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
        factions = FactionStep.host(step.localPlayerId(), joiners, factionWorld::locationProblem);
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
    public String locationProblem(int x, int y) {
        if (factions == null)
            throw new IllegalStateException("SOSGSGMP: a location before the map is confirmed");
        return factions.locationProblem(x, y, factionWorld::locationProblem);
    }

    /**
     * Builds the world here (the game holds this thread for a few seconds; nothing arrives meanwhile), then locks the
     * picks and sends every joining PC the slots and the save in pieces. A build that fails locks nothing.
     */
    @Override
    public void initialize() {
        if (factions == null || !factions.canInitialize())
            throw new IllegalStateException("SOSGSGMP: the world can't be initialized now");
        byte[] built = factionWorld.buildWorld(factions.humans(), difficulty);
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
    public String takeNote() {
        return notes.take();
    }
}
