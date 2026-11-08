"""SOSGSGMP's three-game battle tests (SOS-162): battles, sieges, assaults, regions and NPC vassals with three players on
this PC (host H, joiner J, local joiner L), each scenario from its own saved position, at 25x.

    python tools\\battles3.py prep NAME     a starting position (BASE, ALLIES, WAR, REGIONS), saved, its copy kept in
                                           build\\battles3\\snapshot\\NAME; BASE comes from raid3's save 78
    python tools\\battles3.py run SCENARIO  the scenario from a fresh copy of its position; it ends 1 minute after its
                                           last action, then every game ends at one step (endat)
    python tools\\battles3.py all           every scenario in order
    python tools\\battles3.py list          the scenarios
    python tools\\battles3.py probe NAME    a position's facts (armies, relations, NPC armies), then the run ends

Each run prints and checks the pass conditions: the same hashes on every game to the end, no crash file, every loading
window that opened closed on every game, the result applied once at one step, and the scenario's own checks. Freezes
and holds over 1 s are listed from the playtest logs. A result line per scenario goes to build\\battles3\\results.txt,
and the GUI report to C:\\ClaudeFolder\\PTreport\\2026-11-08-battles\\<scenario>.pdf.

Set APPDATA to a folder holding both mods, as for raid3.py. Test tooling only: nothing here is part of the mod. Never
commit the saves.
"""
import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import twopc  # noqa: E402
import raid3  # noqa: E402

OUT = twopc.ROOT / "build" / "battles3"
SNAP = OUT / "snapshot"
RESULTS = OUT / "results.txt"
REPORTS = pathlib.Path(r"C:\ClaudeFolder\PTreport\2026-11-08-battles")
OUTAGE = []  # (who, seconds, cut) from --outage/--cut: that game's link carries nothing once the battle runs (SOS-176);
# with cut, its connections also break and must come back by themselves after the seconds (SOS-178)
LABEL = [""]  # --label: added to the scenario's name in its report, logs and result line, one per condition (SOS-176)
GAME = {"H": "1", "J": "2", "L": "3"}  # each player's data folder
SLOT = {"H": 0, "J": 1, "L": 2}
FIELD = (66, 93)  # open ground between the three capitals, where the NPC battles are fought
WHO = ("H", "J", "L")
TWOPC = [sys.executable, str(twopc.ROOT / "tools" / "twopc.py"), "--here", "--local-joiner", "--resume", "--battles",
         "auto", "--speed", "25"]


def say(*a):
    print("[battles3 %s]" % time.strftime("%H:%M:%S"), *a, flush=True)


class Failed(Exception):
    pass


class Run:
    """One run of the three games from the data folders in place: their events, commands and logs."""

    def __init__(self, tag, timeout=1500):
        self.tag = tag
        self.ev = {w: [] for w in WHO}
        self.seen = {w: 0 for w in WHO}
        self.shots = []  # (who, time, note): the driver's own screenshots
        self.notes = []  # the scenario's checks, as (ok, text)
        self.started = time.time()
        self.position = None
        for w in WHO:
            (self.home(w) / "autopilot" / "command.txt").unlink(missing_ok=True)
        self.crashes0 = self.crash_files()
        self.others = game_pids()  # games that were running before: never this run's
        if self.others:
            raise Failed("games from elsewhere are running (%s): wait for them" % sorted(self.others))
        self.p = subprocess.Popen(TWOPC + ["--steps", "400000", "--tag", tag, "--timeout", str(timeout)])

    @staticmethod
    def home(w):
        return twopc.INST / GAME[w] / "songsofsyx" / "sosgsgmp"

    @staticmethod
    def crash_files():
        return {str(p) for w in WHO for p in (twopc.INST / GAME[w] / "songsofsyx" / "logs").glob("error*")} | {
            str(p) for p in (twopc.GAME / "errorLogs").glob("*")}

    def poll(self):
        for w in WHO:
            f = self.home(w) / "autopilot" / "events.jsonl"
            if not f.exists():
                continue
            lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
            if lines and not lines[-1].endswith("}"):
                lines = lines[:-1]  # still being written
            for line in lines[self.seen[w]:]:
                try:
                    e = json.loads(line)
                except ValueError:
                    continue
                self.ev[w].append(e)
                if e.get("event") in ("cmd", "battle", "loadingshot", "desynchold", "viewstate", "save", "saveorder",
                                      "chatline", "gone", "retired") :
                    say(w, json.dumps(e)[:1500 if e.get("event") == "cmd" else 260])
            self.seen[w] = len(lines)
        new = self.crash_files() - self.crashes0
        if new:
            raise Failed("crash file: %s" % ", ".join(sorted(new)))
        if self.p.poll() is not None:
            raise Failed("the games ended early (twopc exit %d)" % self.p.returncode)

    def wait(self, pred, timeout, what):
        """Polls until pred() returns something true; returns it. Failed after timeout seconds."""
        end = time.time() + timeout
        while True:
            self.poll()
            r = pred()
            if r:
                return r
            if time.time() > end:
                raise Failed("timed out after %d s waiting for %s" % (timeout, what))
            time.sleep(0.5)

    def sleep(self, seconds):
        end = time.time() + seconds
        while time.time() < end:
            self.poll()
            time.sleep(0.5)

    def send(self, w, *lines):
        cmd = self.home(w) / "autopilot" / "command.txt"
        for _ in range(600):
            if not cmd.exists():
                break
            self.poll()
            time.sleep(0.2)
        tmp = cmd.with_suffix(".tmp")  # renamed in whole: the game once read a half-written, empty file (chain14 B5)
        tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
        tmp.replace(cmd)
        say("sent to", w, lines)

    def cmd(self, w, line, timeout=60):
        """One command on game w; its result."""
        clean = line.replace('"', "'")
        before = sum(1 for e in self.ev[w] if e.get("event") == "cmd" and e.get("action") == clean)
        self.send(w, line)

        def done():
            got = [e for e in self.ev[w] if e.get("event") == "cmd" and e.get("action") == clean]
            return got[before]["result"] if len(got) > before else None
        return self.wait(done, timeout, "%s: %s" % (w, line))

    def step(self, w="H"):
        steps = [e.get("step", 0) for e in self.ev[w] if isinstance(e.get("step"), int)]
        return max(steps) if steps else 0

    def events(self, w, kind, **match):
        return [e for e in self.ev[w] if e.get("event") == kind and all(e.get(k) == v for k, v in match.items())]

    def log(self, w):
        return "".join(p.read_text(encoding="utf-8", errors="replace") for p in sorted((self.home(w) / "logs").glob("*.log")))

    def shot(self, w, note):
        """A screenshot on w, unless a loading window is open there (a shot at its freeze would deadlock the game)."""
        r = self.cmd(w, "shot")
        if r.startswith("screenshot"):
            self.shots.append((w, time.time(), note))
        return r

    def check(self, ok, text):
        self.notes.append((bool(ok), text))
        say("CHECK", "ok" if ok else "FAILED", text)
        return ok

    def end(self, after=60):
        """The scenario's actions are done: every game ends at one step, `after` seconds from now."""
        self.sleep(after)
        end = max(self.step(w) for w in WHO) + 400
        for w in WHO:
            self.send(w, "endat %d" % end)
        while self.p.poll() is None:
            self.poll_quiet()
            time.sleep(1)
        return self.p.returncode == 0

    def poll_quiet(self):
        try:
            self.poll()
        except Failed:
            pass

    def stop(self):
        """Stops this run's games and runner, only their own processes, after a screenshot of each at the failure."""
        if self.p.poll() is None:
            for w in WHO:
                try:
                    self.shot(w, "%s when the scenario failed" % w)
                except Failed:
                    pass
            time.sleep(3)
            self.p.kill()
        for pid in game_pids() - self.others:
            subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)


def close_error_windows():
    """Vanilla's error window, which a game opens on exit after anything reached its stderr (B7: the battle planner's
    "nay1" lines), waits for a click and holds the data folders: closed after each run and before a restore."""
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*ErrorMessage*%s*' }"
                    " | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" % str(twopc.INST)], capture_output=True)


def error_output(w):
    """The ERROR LOG part of the dump a game wrote on exit when its stderr had output; empty when none."""
    f = twopc.INST / GAME[w] / "songsofsyx" / "logs" / "UnhandledDump.txt"
    if not f.exists():
        return []
    text = f.read_text(encoding="utf-8", errors="replace").split("|      STD OUT      |")[0]
    return [l.strip() for l in text.splitlines() if l.strip() and "|" not in l]


def game_pids():
    """The games running from the game folder on this PC."""
    out = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.ExecutablePath -like '%s\*' }).ProcessId"
                          % str(twopc.GAME)], capture_output=True, text=True).stdout.split()
    return {int(p) for p in out}


# ------------------------------------------------------------------ positions


def restore(name):
    src = raid3.SNAPSHOT if name == "78" else SNAP / name
    if not src.is_dir():
        sys.exit("battles3: no position %s in %s: prep it first" % (name, src))
    close_error_windows()
    time.sleep(1)
    for w in WHO:
        shutil.rmtree(twopc.INST / GAME[w], ignore_errors=True)
        shutil.copytree(src / GAME[w], twopc.INST / GAME[w])


def keep(name, run):
    """The data folders as a position, once every game wrote the campaign save the host ordered."""
    shutil.rmtree(SNAP / name, ignore_errors=True)
    for w in WHO:
        shutil.copytree(twopc.INST / GAME[w], SNAP / name / GAME[w],
                        ignore=shutil.ignore_patterns("logs", "screenshots", "autopilot", "errorLogs"))
    say("position %s kept in %s" % (name, SNAP / name))


def save_position(run, name, facts=None):
    """The host saves the campaign; every game writes it; then the run ends and the folders are kept."""
    r = run.cmd("H", "save")
    say("save:", r)
    run.wait(lambda: all(any(e["status"].startswith("Campaign saved") for e in run.events(w, "save")) for w in WHO),
             600, "every game's campaign save")
    run.sleep(5)
    ok = run.end(after=5)
    if not ok:
        raise Failed("the run that saved %s failed" % name)
    keep(name, run)
    (SNAP / name / "meta.json").write_text(json.dumps(facts or {}), encoding="utf-8")


def status(run, w):
    return run.cmd(w, "status")


def armies(run, w):
    """This player's armies, in order, as (index, men, x, y)."""
    s = status(run, w)
    return [(int(a), int(m), int(x), int(y)) for a, m, x, y in re.findall(r"mine (\d+) men (\d+) at (\d+),(\d+)", s)]


def raid_off(run):
    """The raiders who invade the host at about step 6190 of save 78: the host sallies, routs them (nobody watches)
    and accepts the spoils, as raid3 does, so no raid runs in any position."""
    run.wait(lambda: any("invaded by Raiders" in e.get("text", "") for e in run.events("H", "chatline")), 600,
             "the raid on the host")
    run.sleep(3)
    run.cmd("H", "invasionattack")
    run.wait(lambda: run.events("H", "battle", what="start"), 180, "the host's sally")
    run.sleep(10)
    run.cmd("H", "charge")
    run.sleep(20)
    run.cmd("H", "rout")
    run.wait(lambda: run.events("H", "battle", what="close"), 300, "the sally's end")
    run.wait(lambda: "MVictory2" in run.cmd("H", "raid"), 120, "the spoils message")
    run.cmd("H", "victoryaccept")
    run.wait(lambda: all(re.search(r"loading closed [^:]*: BATTLE_END", run.log(w)) for w in WHO), 300,
             "every game back in its city")


def template(run, w, men=200, merc=True):
    """The template army (Test List §5): 200 drafted soldiers and one mercenary band, in a new army, full supplies."""
    before = armies(run, w)
    old = {d for d, _ in re.findall(r"(\d+): men (\d+)", run.cmd(w, "divs"))}
    run.cmd(w, "recruit %d" % men)
    run.cmd(w, "army")
    new = run.wait(lambda: [a for a in armies(run, w) if a[0] not in [b[0] for b in before]] or run.sleep(2), 120,
                   "%s's new army" % w)
    pick = [a[0] for a in armies(run, w)].index(new[0][0])
    run.cmd(w, "pick %d" % pick)
    divs = run.wait(lambda: [d for d, m in re.findall(r"(\d+): men (\d+) stored \d+ army -1", run.cmd(w, "divs"))
                             if d not in old and int(m) > 0] or run.sleep(2), 60, "%s's drafted division" % w)
    run.cmd(w, "attach %s" % divs[0])
    mercs = run.cmd(w, "mercs")
    hire = [i for i, hired in re.findall(r"(\d+):[^;]* men \d+ cost \d+( hired)?;", mercs) if not hired]
    if hire and merc:
        run.cmd(w, "merc %s" % hire[0])
    run.wait(lambda: any(a[0] == new[0][0] and a[1] >= men * 3 // 4 for a in armies(run, w)) or run.sleep(3), 300,
             "%s's soldiers in the army" % w)
    run.sleep(10)
    run.cmd(w, "fill")
    return new[0][0], pick


def allies(run):
    """Save 78's three players are Colleagues (a pact): they become allies, and the host gives each other player
    1,500,000 denari with it, so every player can hire a mercenary band for the template army."""
    for proposer, target, credits in (("H", "J", 1500000), ("H", "L", 1500000), ("J", "L", 0)):
        run.cmd(proposer, "target %d" % SLOT[target])
        r = run.cmd(proposer, "ally %d" % credits)
        if not r.startswith("proposed"):
            raise Failed("%s's alliance with %s: %s" % (proposer, target, r))
        run.wait(lambda: run.cmd(target, "accept").startswith("accepted 1") or run.sleep(2), 120,
                 "%s accepts %s's alliance" % (target, proposer))
    run.wait(lambda: all(run.cmd("H", "diplo").count("=Allies") == 6 for _ in [0]) or run.sleep(3), 120,
             "the three players allied on the host")


def capital(run, w):
    """Player w's capital tile, from `route` (its first coordinates)."""
    m = re.search(r"route (\d+),(\d+) to", run.cmd(w, "route"))
    if not m:
        raise Failed("%s's capital not found" % w)
    return int(m.group(1)), int(m.group(2))


def vassals_of(diplo, slot):
    m = re.search(r"(?:^|; )%d [^:]*:([^;]*)" % slot, diplo)
    return {int(v) for v in re.findall(r"(\d+)=vassal", m.group(1))} if m else set()


def power(run, army):
    m = re.search(r"power (\d+)", run.cmd("H", "armypower %d" % army))
    return int(m.group(1)) if m else 0


def prep_allies(run):
    """P-ALLIES: BASE, and the three players at war with one NPC faction whose army is stronger than the three
    template armies together, the nearest to the host's capital that is nobody's vassal. Vanilla lets the weaker side
    of a world battle withdraw at once when it is the NPC's ("Enemy Retreats", no Fight prompt), so the NPC army must
    outweigh every player who fights it, A3's three included; the morale command decides who wins."""
    hx, hy = capital(run, "H")
    small = {w: template(run, w, men=80, merc=False)[0] for w in WHO}  # weaker than the strong NPC armies, three together
    ours = sum(power(run, small[w]) for w in WHO)
    diplo = run.cmd("H", "diplo")
    taken = set().union(*(vassals_of(diplo, SLOT[w]) for w in WHO))
    cands = sorted((a + (power(run, a[4]),) for a in npc_armies(run) if a[1] >= 1000 and a[0] not in taken), key=lambda a: -a[5])
    for a in cands[:6]:  # vanilla's power counts an army's supplies, which NPC armies often lack
        run.cmd("H", "fillarmy %d" % a[4])
    run.sleep(3)
    cands = [a[:5] + (power(run, a[4]),) for a in cands]
    say("our power %d; NPC armies (faction, men, x, y, army, power), the six strongest filled:" % ours, cands)
    strong = [a for a in cands if a[5] > ours * 1.3]
    if not strong:
        raise Failed("no NPC army stronger than 1.3 times our %d" % ours)
    f, men, x, y, _, pw = min(strong, key=lambda a: (a[2] - hx) ** 2 + (a[3] - hy) ** 2)
    say("the NPC army: faction %d, %d men, power %d at %d,%d" % (f, men, pw, x, y))
    for w in WHO:
        run.cmd(w, "npcwar %d" % f)
    return {"npc": f, "small": small}


def prep_war(run):
    """P-WAR: BASE; L cancels its treaties with H and J and declares war on both; H and J each get a second
    template army. L's army stands 6 tiles from its capital; H's and J's first armies next to it, their second ones
    next to L's capital."""
    war_on_l(run)
    lx, ly = capital(run, "L")
    return place_war(run, lx, ly)


def war_on_l(run):
    """H and J each get a second template army; L cancels its treaties with H and J and declares war on both."""
    for w in ("H", "J"):
        template(run, w)
    for t in ("H", "J"):
        run.cmd("L", "target %d" % SLOT[t])
        say("L cancels with", t, run.cmd("L", "cancel"))
    run.sleep(5)
    for t in ("H", "J"):
        run.cmd("L", "target %d" % SLOT[t])
        r = run.wait(lambda: (lambda r: r if r.startswith("war") else None)(run.cmd("L", "war")) or run.sleep(3), 300,
                     "L's war on %s" % t)
        say("L:", r)


def place_war(run, lx, ly):
    """P-WAR's armies: L's 6 tiles from its capital, H's and J's first armies next to it, their second ones next to L's
    capital; returns the position's facts."""
    field = place(run, "L", lx + 6, ly)
    m = {"lcap": [lx, ly], "lfield": [field[2], field[3]]}
    for i, w in enumerate(("H", "J")):
        mine = armies(run, w)
        by = sorted(mine, key=lambda a: -a[1])[:2]  # the two template armies
        run.cmd(w, "pick %d" % mine.index(by[0]))
        place_picked(run, w, field[2] + 2, field[3] + 2 * i - 1)
        run.cmd(w, "pick %d" % mine.index(by[1]))
        place_picked(run, w, lx - 3, ly + 2 * i - 1)
        m[w] = [by[0][0], by[1][0]]
    return m


def place_picked(run, w, x, y):
    """The picked army stands at x,y or a tile next to it."""
    r = run.cmd(w, "teleportnear %d %d" % (x, y))
    m = re.search(r"army (\d+) teleports to (\d+),(\d+)", r)
    if not m:
        raise Failed("%s's army can't stand near %d,%d: %s" % (w, x, y, r))
    index, tx, ty = (int(v) for v in m.groups())
    for _ in range(10):  # the teleport applies at its step, up to 400 steps on at world speed
        run.sleep(1)
        for a in armies(run, w):
            if a[0] == index and (a[2], a[3]) == (tx, ty):
                say(w, "army", index, "at", tx, ty)
                return a
    raise Failed("%s's army %d isn't at %d,%d after the teleport: %s" % (w, index, tx, ty, armies(run, w)))


PREPS = {"ALLIES": ("BASE", prep_allies), "WAR": ("BASE", prep_war), "REGIONS": ("BASE", lambda run: prep_regions(run))}


def prep(name):
    if name == "BASE":
        restore("78")
        run = Run("b3prep-" + name)
        try:
            run.wait(lambda: all(run.events(w, "go") for w in WHO), 300, "every game playing")
            raid_off(run)
            allies(run)
            for w in WHO:
                template(run, w)
            for w in WHO:
                say(w, status(run, w))
                say(w, run.cmd(w, "diplo"))
            save_position(run, name)
        except Failed as e:
            run.stop()
            sys.exit("battles3: prep %s failed: %s" % (name, e))
        return
    if name not in PREPS:
        sys.exit("battles3: no position %s (BASE, %s)" % (name, ", ".join(PREPS)))
    source, build = PREPS[name]
    restore(source)
    run = Run("b3prep-" + name)
    try:
        run.wait(lambda: all(run.events(w, "go") for w in WHO), 300, "every game playing")
        facts = dict(meta(source))
        facts.update(build(run))
        for w in WHO:
            say(w, status(run, w))
            say(w, run.cmd(w, "diplo"))
        save_position(run, name, facts)
    except Failed as e:
        run.stop()
        sys.exit("battles3: prep %s failed: %s" % (name, e))


def probe(name):
    restore(name)
    run = Run("b3probe-" + name)
    try:
        run.wait(lambda: all(run.events(w, "go") for w in WHO), 300, "every game playing")
        for w in WHO:
            for c in ("status", "diplo", "divs", "mercs", "npcs", "npcarmies", "state"):
                say(w, c, "=>", run.cmd(w, c))
        run.end(after=1)
    except Failed as e:
        run.stop()
        sys.exit("battles3: probe failed: %s" % e)


# ------------------------------------------------------------------ the report


PROMPTS = {"world.battle.ResolverPlayer$1": "the battle's result: spoils and captives, or the losses",
           "world.battle.ResolverPlayer$Manual$2": "the result of a battle fought on the map: spoils and captives, or the losses",
           "world.battle.ResolverPlayer$Spec": "the Fight prompt (Command, Auto, Retreat)",
           "world.battle.spec.WBattleSiege": "the siege prompt"}


def screenshots(run):
    """This run's screenshots on every game, oldest first, each with a note: the driver's, a loading window's (the
    autopilot shoots each once it drew) or the victory and defeat panel's (battles=auto shoots it)."""
    out = []
    for w in WHO:
        folder = twopc.INST / GAME[w] / "songsofsyx" / "screenshots"
        files = sorted((p for p in folder.glob("*.png") if p.stat().st_mtime >= run.started - 5), key=lambda p: p.stat().st_mtime)
        auto = [e for e in run.ev[w] if e.get("event") == "loadingshot"]
        prompts = [e for e in run.ev[w] if e.get("event") == "battle" and e.get("what") == "prompt shot"]
        mine = [(t, n) for who, t, n in run.shots if who == w]
        for f in files:
            t = f.stat().st_mtime
            near = [(abs(t - st), n) for st, n in mine if -4 <= t - st < 8]  # the file can be written before the command's answer
            note = min(near)[1] if near else None  # the nearest: two shots 3 s apart (C1) took the first one's note
            if note is None:
                ls = [e for e in auto if abs(e["t"] / 1000.0 - t) < 8]
                ps = [e for e in prompts if abs(e["t"] / 1000.0 - t) < 4]
                note = ("the loading window %s %s" % (ls[0]["kind"], ls[0]["key"])) if ls else (
                    "a battle prompt (%s), shot before the autopilot answers it" % PROMPTS.get(ps[0]["prompt"], ps[0]["prompt"])
                    if ps else "the battle's victory or defeat panel, shot before the autopilot closes it")
            out.append((t, w, f, note))
    return sorted(out, key=lambda x: x[0])


def pdf(name, title, run, lines):
    """One page per screenshot with its note under it, after a first page with the scenario's checks."""
    from PIL import Image, ImageDraw, ImageFont
    REPORTS.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 22)
    bold = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 30)
    W, H = 1700, 1200
    pages = []
    first = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(first)
    d.text((60, 50), title, font=bold, fill="black")
    y = 110
    for line in lines:
        for chunk in wrap(line, 130):
            d.text((60, y), chunk, font=font, fill="black")
            y += 30
        if y > H - 60:
            break
    pages.append(first)
    for t, w, f, note in screenshots(run):
        try:
            img = Image.open(f).convert("RGB")
        except OSError:
            continue
        img.thumbnail((W - 80, H - 170))
        page = Image.new("RGB", (W, H), "white")
        page.paste(img, (40, 40))
        d = ImageDraw.Draw(page)
        y = 50 + img.size[1]
        for chunk in wrap("%s, %s: %s" % (time.strftime("%H:%M:%S", time.localtime(t)), {"H": "Host", "J": "Joiner", "L": "Local"}[w], note), 130):
            d.text((40, y), chunk, font=font, fill="black")
            y += 30
        pages.append(page)
    pages_dir = REPORTS / "pages" / name  # the pages as pictures too, to look at each one
    pages_dir.mkdir(parents=True, exist_ok=True)
    for old in pages_dir.glob("*.jpg"):
        old.unlink()
    for i, pg in enumerate(pages):
        pg.resize((W * 3 // 4, H * 3 // 4)).save(pages_dir / ("%02d.jpg" % i), quality=80)
    target = REPORTS / ("%s.pdf" % name)
    pages[0].save(target, save_all=True, append_images=pages[1:], resolution=100)
    say("report:", target, "(%d screenshots)" % (len(pages) - 1))
    return target


def wrap(text, n):
    out, line = [], ""
    for word in text.split(" "):
        if len(line) + len(word) + 1 > n:
            out.append(line)
            line = word
        else:
            line = (line + " " + word).strip()
    return out + [line]


def finish(name, title, run, ok):
    """The pass conditions every scenario shares (Test List §6), the report and the result line; True on a pass."""
    name += LABEL[0]
    lines = ["Scenario %s: %s" % (name, title), "Run %s, %s to %s." % (run.tag, time.strftime("%H:%M", time.localtime(run.started)), time.strftime("%H:%M"))]
    run.check(ok, "twopc: the run reached its end step on every game with the same hashes, no desync")
    run.check(not (run.crash_files() - run.crashes0), "no crash file")
    time.sleep(2)  # the games' exit writes the dump
    for w in WHO:
        err = error_output(w)
        if err:  # vanilla's own diagnostics pass; an exception fails
            run.check(not any("Exception" in l or l.startswith("at ") for l in err),
                      "%s: no exception in the game's error output (%d lines: %s)" % (w, len(err), " / ".join(err[:4])))
    close_error_windows()
    for w in WHO:
        log = run.log(w)
        opened = re.findall(r"loading opened: (\w+ \d+)", log)
        closed = set(re.findall(r"loading closed [^:]*: (\w+ \d+)", log))
        missing = [o for o in opened if o not in closed]
        run.check(not missing, "%s: every loading window that opened closed%s" % (w, (" (open: %s)" % ", ".join(missing)) if missing else ""))
        held = [l.split(" ", 1)[1] for l in log.splitlines() if re.search(r"freeze: held (\d+\.\d+) s", l)
                and float(re.search(r"held (\d+\.\d+) s", l).group(1)) > 1.0]
        lines.append("%s: freezes and holds over 1 s: %s" % (w, "; ".join(held[:12]) + (" (+%d more)" % (len(held) - 12) if len(held) > 12 else "") if held else "none"))
    results = {w: re.findall(r"local battle result: slot (\d+)'s battle engaged at step (\d+) applies at step (\d+)", run.log(w)) for w in WHO}
    backs = {w: re.findall(r"battle over: back in this PC's own city at step (\d+)", run.log(w)) for w in WHO}
    if any(results.values()):
        same = all(sorted(results[w]) == sorted(results["H"]) for w in WHO)
        once = all(len(set(r)) == len(r) for r in results.values())
        run.check(same and once, "each battle against NPCs' result applied once, at one step on every game: %s" % results["H"])
    if any(backs.values()):
        run.check(all(backs[w] == backs["H"] for w in WHO), "each battle between players ended at one step on every game: %s" % backs)
    passed = all(o for o, _ in run.notes)
    for o, text in run.notes:
        lines.append(("PASS  " if o else "FAIL  ") + text)
    lines.append("RESULT: " + ("PASS" if passed else "FAIL"))
    try:
        pdf(name, "%s: %s" % (name, title), run, lines)
    except Exception as e:  # noqa: BLE001 the report is a convenience; the run's verdict stands without it
        say("the report failed:", e)
    with RESULTS.open("a", encoding="utf-8") as f:
        f.write("%s %s %s: %s; %s\n" % (time.strftime("%Y-%m-%d %H:%M"), name, "PASS" if passed else "FAIL", title,
                                        "; ".join(t for o, t in run.notes if not o) or "all checks passed"))
    keep_logs(name, run)
    say("RESULT:", name, "PASS" if passed else "FAIL")
    return passed


def keep_logs(name, run):
    """Each game's playtest log, kept in build/battles3/logs/<scenario>: the next run's start wipes the data folders."""
    for w in WHO:
        folder = OUT / "logs" / name / w
        shutil.rmtree(folder, ignore_errors=True)
        folder.mkdir(parents=True)
        for p in (run.home(w) / "logs").glob("*.log"):
            shutil.copy2(p, folder / p.name)


SCENARIOS = {}


# ------------------------------------------------------------------ scenario helpers


def meta(name):
    f = SNAP / name / "meta.json"
    return json.loads(f.read_text(encoding="utf-8")) if f.exists() else {}


def start(position, tag):
    restore(position)
    run = Run(tag)
    run.wait(lambda: all(run.events(w, "go") for w in WHO), 300, "every game playing")
    return run


def template_army(run, w):
    """The player's template army (its largest; P-ALLIES's small army for the NPC battles): the army commands act on it
    from now; returns (index, men, x, y)."""
    mine = armies(run, w)
    if not mine:
        raise Failed("%s has no army" % w)
    small = meta(run.position).get("small", {}).get(w) if getattr(run, "position", None) else None
    best = next((a for a in mine if a[0] == small), None) or max(mine, key=lambda a: a[1])
    run.cmd(w, "pick %d" % mine.index(best))
    return best


def npc_armies(run):
    """The NPC armies with men, as (faction, men, x, y, army)."""
    return [tuple(int(v) for v in m) for m in re.findall(r"(\d+) (\d+) (\d+) (\d+) (\d+);", run.cmd("H", "npcarmies"))]


def npc_army(run, faction):
    found = [a for a in npc_armies(run) if a[0] == faction]
    if not found:
        raise Failed("NPC faction %d has no army" % faction)
    return max(found, key=lambda a: a[1])


def place(run, w, x, y):
    """The template army stands at x,y, or the nearest tile it can stand on around it."""
    template_army(run, w)
    return place_picked(run, w, x, y)


def place_near(run, w, x, y, reach=3):
    """w's template army stands within `reach` tiles of x,y: the spots around it in turn, as a teleport to a tile no
    army can stand on lands on the nearest free one, which can be far off. An army already on a good spot stays: in
    ALLIES L's stands on 63,94, and with H on 65,94 and J on 64,92 no other good tile is left (chain9 A3, A4)."""
    def good(a):
        # x,y and the tile east of it stay free: the NPC army is teleported there last (A3: L on 67,93 sent it to 64,90)
        # and no more than a tile north: 64,90 lies across a river (chain9 A3: within 3, but L wasn't asked Join)
        return max(abs(a[2] - x), abs(a[3] - y)) <= reach and a[3] >= y - 1 and (a[2], a[3]) not in ((x, y), (x + 1, y))
    a = template_army(run, w)
    if good(a):
        return a
    # first the spots where B1's armies stood around L's army at 67,93, all within 4 tiles along the map
    # 63,94 (-3,+1): L's army there in ALLIES was asked Join with the NPC army on 67,93 (A1); 64,90 was not (a river)
    for dx, dy in ((-2, 1), (-3, 1), (-1, 2), (-2, -1), (0, 2), (0, -2), (2, 0), (2, 2), (2, -2), (-2, 2), (-2, -2)):
        a = place(run, w, x + dx, y + dy)
        if good(a):
            return a
    raise Failed("%s's army found no tile within %d of %d,%d (last %s)" % (w, reach, x, y, a))


def resolved(run):
    """H's battle result accepted already: vanilla resolved it with no Fight prompt."""
    return [e["what"] for e in run.events("H", "battle") if e.get("what") in ("victory accept", "defeat accept")]


def state(run, w):
    return run.cmd(w, "state")


def wait_state(run, w, text, timeout, what):
    return run.wait(lambda: text in state(run, w) or run.sleep(1), timeout, what)


def back_in_cities(run, timeout=600):
    """Every game logged the end of its battle windows: each player is back in their own city."""
    def done():
        for w in WHO:
            log = run.log(w)
            opened = re.findall(r"loading opened: ((?:BATTLE|VIEW)_\w+ \d+)", log)
            closed = set(re.findall(r"loading closed [^:]*: ((?:BATTLE|VIEW)_\w+ \d+)", log))
            if any(o not in closed for o in opened):
                return False
        return all("battle none" in state(run, w) for w in WHO)
    run.wait(lambda: done() or run.sleep(2), timeout, "every game back in its own city")


def got_spoils(run, w, seconds=60):
    """w's spoils or captives, waiting for them: each winner's arrive once that winner accepted its result window."""
    end = time.time() + seconds
    while time.time() < end:
        got = spoils(run, w)
        if got:
            return got
        run.sleep(2)
    return []


def captives(run, w):
    """The captives player w received: what a loser must never get (a destroyed army's own supplies go home as
    vanilla's spoils shipments too, so goods alone don't tell a winner)."""
    return [l for l in spoils(run, w) if re.search(r" [1-9]\d* (Human|Captive)", l)]


def spoils(run, w):
    """The spoils and captives player w received in this run, from its playtest log's transfers."""
    who = {"H": "Host", "J": "Joiner", "L": "Local"}[w]  # every PC's log lists every player's transfers
    return [l for l in run.log(w).splitlines()
            if re.search(r"transfer step \d+: %s " % who, l) and re.search(r"spoils|captive|Spoils|Captive", l)]


# ------------------------------------------------------------------ A: battles against NPC armies (P-ALLIES)


def met(run, joiners, seconds):
    """Whether H's battle began within `seconds`: a prompt or a battle on H, a Join prompt, or a resolved battle."""
    end = time.time() + seconds
    while time.time() < end:
        sts = [state(run, w) for w in ["H"] + joiners]
        if any("prompt none" not in st or "battle none" not in st or "ally asked true" in st for st in sts) or resolved(run):
            return True
        run.sleep(1)
    return False


def link_down(run, w, seconds, cut):
    """`w`'s link carries nothing for `seconds`; with `cut`, its connections also break (SOS-178). Returns the answers."""
    r = run.cmd(w, "netoutage %d" % seconds) if seconds else "no outage"
    if cut:
        r += "; " + run.cmd(w, "netcut")
    return r


def battle_outage(run):
    """--outage's and --cut's games lose their link for its seconds, as the battle runs (SOS-176, SOS-178)."""
    for w, seconds, cut in OUTAGE:
        r = link_down(run, w, seconds, cut)
        say(w, "outage:", r)
        run.notes.append((True, "%s's link carried nothing for %d s%s once the battle ran: %s"
                          % (w, seconds, ", its connection cut," if cut else "", r)))


def npc_battle(run, joiners, watcher=None, outcome="win", retreat=None, save=False, answer=True, fight=True):
    """H attacks the position's NPC army; `joiners` are asked Join and answer it (or not, answer=False); `watcher`
    views the battle for 20 s; the side wins (the enemy's morale held) or loses (its own); `retreat` leaves halfway.
    The NPC army may strike first at one of the joiners standing by: whoever it fights leads, the others are asked."""
    m = meta(run.position)
    idx = npc_army(run, m["npc"])[4]
    run.cmd("H", "fillarmy %d" % idx)  # its power counts its supplies: stronger than its attackers, it doesn't withdraw
    # the fighters stand on open ground, where B1's allies were all within 4 tiles along the map (a river between
    # armies keeps them apart); the NPC army comes last, as the world runs fast and it walks off meanwhile
    for w in ["H"] + joiners:
        place_near(run, w, FIELD[0], FIELD[1])
    for w in WHO:  # the NPC faction may have made peace since the position was saved (its AI offers it)
        run.cmd(w, "npcwar %d" % m["npc"])
    run.sleep(2)
    say("relations:", run.cmd("H", "diplo"))
    if not fight:
        run.cmd("H", "auto off")
    say(run.cmd("H", "teleportarmy %d %d %d" % (idx, FIELD[0], FIELD[1])))
    x, y = npc_army(run, m["npc"])[2:4]
    say("the NPC army of faction %d stands at %d,%d, power %d" % (m["npc"], x, y, power(run, idx)))
    hx, hy = template_army(run, "H")[2:4]
    run.cmd("H", "attacknpc")
    if not met(run, joiners, 25):
        # vanilla starts a field battle only when two armies stand next to each other across a passable edge; an
        # intercept that ends next to a fortified army across a blocked one just fortifies, so the NPC army comes over
        idx = npc_army(run, m["npc"])[4]
        say("no battle 25 s after the intercept: the NPC army moves next to H:", run.cmd("H", "teleportarmy %d %d %d" % (idx, hx - 1, hy)))
        run.sleep(2)
        say("H attacks again:", run.cmd("H", "attacknpc"))
        run.notes.append((True, "the NPC army was teleported next to H: H's intercept started no battle in 25 s"))
    lead = run.wait(lambda: next((w for w in ["H"] + joiners if run.events(w, "battle", what="prompt shot")
                                  or run.events(w, "battle", what="fight") or "prompt Spec" in state(run, w)), None)
                    or resolved(run) or run.sleep(1), 120, "the battle's Fight prompt")
    if resolved(run):
        raise Failed("the battle was resolved at once, with no prompt: %s" % resolved(run))
    if lead != "H":
        run.notes.append((True, "the NPC army struck %s first, so %s leads the battle and H is asked Join" % (lead, lead)))
        say("the NPC army struck", lead, "first: it leads")
    joiners = [w for w in ["H"] + joiners if w != lead]
    run.sleep(2)
    say("the ally question:", run.cmd("H", "allies"))
    if retreat == lead:
        retreat = joiners[0]
    for w in joiners:
        run.wait(lambda: "ally asked true" in state(run, w) or resolved(run) or run.sleep(1), 120, "%s's Join prompt" % w)
        if resolved(run):
            raise Failed("the battle was resolved at once, with no prompt: %s" % resolved(run))
        run.shot(w, "%s's Join / Stay out prompt" % w)
        if answer:
            run.check("Join" in run.cmd(w, "join"), "%s answered Join" % w)
    if not fight:
        run.wait(lambda: re.search(r"prompt (?!none)", state(run, lead)) or run.sleep(1), 120, "H's Fight prompt")  # Spec vs NPCs
        asked = time.time()
        run.wait(lambda: "prompt none" in state(run, lead) or run.sleep(1), 120, "H's prompt to time out")
        run.check(time.time() - asked >= 20, "H's Fight prompt closed by itself %.0f s after the Join prompt" % (time.time() - asked))
        run.cmd("H", "auto on")
        run.sleep(20)
        back_in_cities(run)
        return retreat
    wait_state(run, lead, "battle deploying", 180, "the battle at deployment")
    run.shot(lead, "%s's deployment" % lead)
    run.wait(lambda: "battle running" in state(run, lead) or run.sleep(1), 180, "the battle started")
    run.sleep(5)
    battle_outage(run)
    for i, w in enumerate([lead] + joiners):
        before = speed_of(run, lead)
        r = run.cmd(w, "speed 3")
        say(w, "speed vote:", r)
        if len(joiners) >= 2 and i < 2:
            run.sleep(3)
            after = speed_of(run, lead)
            if i == 0:
                run.check(after == before, "one vote of three keeps the speed: %.1f to %.1f" % (before, after))
            else:
                run.check(after != before, "two votes of three change the speed: %.1f to %.1f" % (before, after))
    run.sleep(3)
    run.shot(lead, "%s's battle after the commanders voted for 3x" % lead)
    for w in joiners:
        run.shot(w, "%s's view of the battle as an ally" % w)
    if watcher:
        run.cmd(watcher, "target %d" % SLOT[lead])
        r = run.cmd(watcher, "view")
        run.check("asked" in r, "%s asked to watch H's battle: %s" % (watcher, r))
        run.wait(lambda: run.events(watcher, "viewstate", state=2), 180, "%s watching" % watcher)
        run.sleep(15)
        run.shot(watcher, "%s watching H's battle" % watcher)
        run.cmd(watcher, "closeview")
        run.wait(lambda: [e for e in run.events(watcher, "viewstate") if e.get("state") in (3, 4)], 180, "%s's view over" % watcher)
        run.sleep(5)
    if save:
        r = run.cmd("H", "save")
        run.sleep(5)
        saves = run.events("H", "save")
        run.check(not any(e["status"].startswith("Campaign saved") for e in saves),
                  "H's Save Campaign during the battle waits: %s" % [e["status"] for e in saves][-2:])
    if retreat:
        run.sleep(20)
        run.check("Retreat" in run.cmd(retreat, "retreat"), "%s ordered the retreat" % retreat)
        run.sleep(10)
        run.shot(retreat, "%s after retreating" % retreat)
    run.cmd(lead, "charge")
    for w in joiners:
        if w != retreat:
            run.cmd(w, "charge")
    run.sleep(10)
    side = "enemy" if outcome == "win" else "player"
    r = run.cmd(lead, "morale %s" % side)
    run.check("held" in r, "the %s side's morale held below zero: %s" % (side, r))
    run.wait(lambda: run.events(lead, "battle", what="close"), 600, "the battle over")
    back_in_cities(run)
    run.sleep(5)
    return retreat


def a_battle(name, title, joiners, watcher=None, outcome="win", retreat=None, save=False, answer=True, fight=True):
    def go():
        run = start("ALLIES", "b3-" + name)
        run.position = "ALLIES"
        ok = False
        try:
            before = {w: template_army(run, w) for w in WHO}
            npc_before = npc_army(run, meta("ALLIES")["npc"])
            left = npc_battle(run, joiners, watcher, outcome, retreat, save, answer, fight)
            if retreat and left != retreat:
                say("the retreat went to", left, "as", retreat, "led the battle")
            after = {w: next((a for a in armies(run, w) if a[0] == before[w][0]), (before[w][0], 0, 0, 0)) for w in WHO}
            npc_after = [a for a in npc_armies(run) if a[0] == meta("ALLIES")["npc"]]
            for w in WHO:
                say(w, "army before", before[w], "after", after[w], "spoils", spoils(run, w))
            fought = ["H"] + ([w for w in joiners] if answer else [])
            for w in fought:
                # a win by morale can end before contact: then the winners' spoils (below) show who fought
                lost = after[w][1] < before[w][1] if outcome == "lose" else after[w][1] <= before[w][1]
                run.check(lost or not fight, "%s lost men (none needed in a win): %d to %d" % (w, before[w][1], after[w][1]))
            if outcome == "win" and fight:
                for w in fought:
                    if w != left:
                        run.check(got_spoils(run, w), "%s received spoils or captives" % w)
                if left:
                    run.check(not captives(run, left), "%s, who retreated, received no captives" % left)
            elif fight:
                for w in fought:
                    run.check(not captives(run, w), "%s, beaten, received no captives: %s" % (w, captives(run, w)[:3]))
                run.check(npc_after, "the NPC army stays: %s (was %s)" % (npc_after, npc_before))
            if not answer:
                run.check(after[joiners[0]][1] == before[joiners[0]][1], "%s, who never answered, kept its men" % joiners[0])
            if save:
                run.wait(lambda: any(e["status"].startswith("Campaign saved") for e in run.events("H", "save")), 600,
                         "the waiting save after the battle")
                run.check(True, "the campaign save went through after the battle")
            ok = run.end()
        except Failed as e:
            run.check(False, str(e))
            run.stop()
        finish(name, title, run, ok)
        if save and ok:
            resume_check(name, after)
    return go


def resume_check(name, after):
    """A8: the campaign saved after the battle resumes with the battle's losses in it."""
    t0 = time.time() * 1000  # the resume reuses the last run's folders: their old "go" events don't count
    run = Run("b3-%s-resume" % name)
    try:
        run.wait(lambda: all(any(e.get("t", 0) >= t0 for e in run.events(w, "go")) for w in WHO) or run.sleep(1), 300,
                 "every game playing after the resume")
        for w in ("H", "J"):
            # the resume's run has no position, so the template would be the largest army: find the battle's by index
            a = next((a for a in armies(run, w) if a[0] == after[w][0]), (after[w][0], 0, 0, 0))
            run.check(a[1] == after[w][1], "%s's army after the resume has the battle's survivors: %d (%d before the save)" % (w, a[1], after[w][1]))
        ok = run.end(after=20)
    except Failed as e:
        run.check(False, str(e))
        run.stop()
        ok = False
    finish(name + "-resume", "the A8 save resumed", run, ok)


SCENARIOS.update({
    "A1": ("ALLIES", a_battle("A1", "H and J beat the NPC army; L watches", ["J"], watcher="L"),
           "H and J fight the NPC army together and win; L watches"),
    "A2": ("ALLIES", a_battle("A2", "H and J lose to the NPC army", ["J"], outcome="lose"), "H and J fight it and lose"),
    "A6": ("ALLIES", a_battle("A6", "J retreats; H fights on and wins", ["J"], retreat="J"), "J retreats halfway, H wins"),
    "A7": ("ALLIES", a_battle("A7", "J never answers Join; H's prompt times out", ["J"], answer=False, fight=False),
           "J never answers Join; H's Fight prompt unanswered auto-resolves"),
    "A8": ("ALLIES", a_battle("A8", "a save during the battle, then a resume", ["J"], save=True),
           "H saves during A1's battle; the save waits; resume"),
})


# ------------------------------------------------------------------ B: battles between players (P-WAR)


def pick_army(run, w, index):
    """Player w's army with this world index becomes the one the army commands act on."""
    mine = armies(run, w)
    at = [a[0] for a in mine].index(index)
    run.cmd(w, "pick %d" % at)
    return mine[at]


def pvp_battle(run, attackers, joiners, outcome, answers=True, start_answers=True, stay=(), before_attack=None, in_battle=None):
    """attackers[0]'s field army attacks L's field army; `joiners` answer Join (or never, answers=False); the battle
    runs on every PC; H's side wins (L's morale held) or L wins (H's side's)."""
    m = meta("REGIONS" if getattr(run, "position", None) == "REGIONS" else "WAR")  # REGIONS has its own armies
    first = attackers[0]
    lx, ly = [a for a in armies(run, "L")][0][2:4]
    for w, dx in (("H", -2), ("J", 2)):  # both within reach of the battle, so J is asked (prep's places can be 4 tiles off)
        pick_army(run, w, m[w][0])
        place_picked(run, w, lx + dx, ly)
    pick_army(run, first, m[first][0])
    run.cmd(first, "target 2")
    if before_attack:
        before_attack()
    r = run.cmd(first, "attack")
    run.check("intercepting" in r, "%s attacks L's army: %s" % (first, r))
    if not answers:
        run.cmd("L", "auto off")
    if not start_answers:
        for w in joiners:
            run.cmd(w, "auto off")
    for w in joiners:
        wait_state(run, w, "ally asked true", 120, "%s's Join prompt" % w)
        run.shot(w, "%s's Join / Stay out prompt" % w)
        run.check("Join" in run.cmd(w, "join"), "%s answered Join" % w)
    for w in stay:
        wait_state(run, w, "ally asked true", 120, "%s's Join prompt" % w)
        run.check("Stay out" in run.cmd(w, "stayout"), "%s answered Stay out" % w)
    run.wait(lambda: (lambda st: re.search(r"prompt (?!none)", st) or "battle none" not in st)(state(run, "L")) or run.sleep(1), 120,
             "L's Fight prompt")  # battles=auto may answer it before this looks
    if not answers:
        run.shot("L", "L's Fight prompt, left unanswered")
        asked = time.time()
        wait_state(run, "L", "battle deploying", 120, "the battle after L's 30 seconds")
        run.check(time.time() - asked >= 25, "the battle began %.0f s after L's unanswered prompt" % (time.time() - asked))
        run.cmd("L", "auto on")
    wait_state(run, "H", "battle deploying", 180, "the battle's deployment on H")
    for w in WHO:
        run.shot(w, "%s at deployment" % w)
    seen = []
    for _ in range(8):  # when L's PC learns which army it commands: the commanders table at its step
        st = state(run, "L")
        seen.append(re.sub(r".*battle (\w+).*step (\d+).*own army (-?\d+), table (\w+).*", r"\2 \1 own \3 table \4", st))
        if "battle deploying" not in st:
            break
        run.sleep(1)
    say("L's own army over the deployment:", seen)
    run.check(any("deploying own 1" in v for v in seen) or not any("deploying" in v for v in seen),
              "L deploys its own army, army 1: %s" % seen)
    run.shot("L", "L's Army panel at deployment: L's own divisions")
    if not start_answers:
        began = time.time()
        run.wait(lambda: "battle running" in state(run, "H") or run.sleep(2), 240, "the battle's start without J's Start")
        run.check(time.time() - began >= 100, "the battle started %.0f s into deployment without J's Start" % (time.time() - began))
        for w in joiners:
            run.cmd(w, "auto on")
    run.wait(lambda: "battle running" in state(run, "H") or run.sleep(1), 240, "the battle started")
    run.sleep(5)
    st = state(run, "L")
    run.check("own army 1" in st, "L commands army 1 once the battle runs: %s" % st)
    if in_battle:
        in_battle()
    battle_outage(run)
    for w in WHO:
        say(w, "speed vote:", run.cmd(w, "speed 3"))
    run.sleep(3)
    for w in WHO:
        run.shot(w, "%s in the battle after the speed votes" % w)
    for w in attackers + joiners + ["L"]:
        run.cmd(w, "charge")
    run.sleep(15)
    r = run.cmd("H", "morale %s" % ("theirs" if outcome == "win" else "mine"))
    run.check("held" in r, "morale: %s" % r)
    run.wait(lambda: run.events("H", "battle", what="close") or run.sleep(2), 600, "the battle over")
    back_in_cities(run)
    run.sleep(5)


def b_battle(name, title, attackers, joiners, outcome="win", answers=True, start_answers=True, stay=()):
    def go():
        run = start("WAR", "b3-" + name)
        run.position = "WAR"
        ok = False
        m = meta("WAR")
        try:
            before = {w: [a for a in armies(run, w)] for w in WHO}
            pvp_battle(run, attackers, joiners, outcome, answers, start_answers, stay)
            for w in stay:
                run.check(sorted(a[1] for a in armies(run, w)) == sorted(a[1] for a in before[w]), "%s, who stayed out, kept its men" % w)
            after = {w: [a for a in armies(run, w)] for w in WHO}
            for w in WHO:
                say(w, "armies before", before[w], "after", after[w], "spoils", spoils(run, w))
            winners = attackers + joiners if outcome == "win" else ["L"]
            losers = ["L"] if outcome == "win" else attackers + joiners
            for w in winners:
                run.check(got_spoils(run, w), "%s, a winner, received spoils or captives" % w)
            for w in losers:
                run.check(not captives(run, w), "%s, beaten, received no captives: %s" % (w, captives(run, w)[:3]))
            ok = run.end()
        except Failed as e:
            run.check(False, str(e))
            run.stop()
        finish(name, title, run, ok)
    return go


SCENARIOS.update({
    "B1": ("WAR", b_battle("B1", "H and J beat L's army in the field", ["H"], ["J"]), "H and J attack L's army; they win"),
    "B2": ("WAR", b_battle("B2", "L beats H's and J's armies in the field", ["H"], ["J"], outcome="lose"),
           "the same battle; L wins"),
    "B3": ("WAR", b_battle("B3", "1 against 1: H beats L's army; J stays out and watches", ["H"], [], stay=["J"]),
           "H attacks L's army alone; J watches; H wins"),
    "B4": ("WAR", b_battle("B4", "nobody answers: L's Fight and J's Start time out", ["H"], ["J"], answers=False,
                           start_answers=False), "L never presses Fight; J never presses Start"),
})


# ------------------------------------------------------------------ B6-B10: sieges, sallies and assaults (P-WAR)


def besiege(run, besiegers):
    """Each besieger's second army besieges L's capital; returns when every PC sees it besieged."""
    m = meta("WAR")
    for w in besiegers:
        pick_army(run, w, m[w][1])
        run.cmd(w, "target 2")
        r = run.cmd(w, "besiege")
        run.check("besieging" in r, "%s besieges L's capital: %s" % (w, r))
    run.wait(lambda: "my capital besieged true" in status(run, "L") or run.sleep(2), 240, "L's capital besieged")
    run.sleep(3)
    run.shot("L", "L's capital besieged: the siege notice")


def sally(run, with_army, outcome, joiners=()):
    """L sallies out of its capital (with a garrison raised first when with_army); the besiegers win or lose. The
    besiegers other than H (`joiners`) are asked Join and answer it (chain10 B6: J was asked, never answered, stayed out)."""
    if with_army:
        # a garrison: no army can reach the capital's side once it's besieged (chain9 B6: every tile within 2 taken)
        run.check("join" in run.cmd("L", "recruit 120"), "L's city raises a garrison of 120")
        run.sleep(10)
    r = run.wait(lambda: (lambda r: r if not r.startswith("refused") else None)(run.cmd("L", "sally")) or run.sleep(3), 120,
                 "L's sally accepted")
    run.check(r.startswith("sallying"), "L sallies out: %s" % r)
    for w in joiners:
        wait_state(run, w, "ally asked true", 120, "%s's Join prompt" % w)
        run.check("Join" in run.cmd(w, "join"), "%s answered Join" % w)
    try:
        wait_state(run, "H", "battle deploying", 240, "the sally's battle at deployment")
    except Failed:
        for w in WHO:  # chain8 B6: no battle after "sallying out"; whose prompt waits tells why
            say(w, "at the sally's time-out:", state(run, w))
            run.shot(w, "%s when the sally's battle never came" % w)
        raise
    for w in WHO:
        run.shot(w, "%s at the sally's deployment" % w)
    run.wait(lambda: "battle running" in state(run, "H") or run.sleep(1), 240, "the sally's battle started")
    run.sleep(5)
    for w in WHO:
        run.cmd(w, "charge")
    run.sleep(10)
    run.shot("H", "the sally's battle on H")
    r = run.cmd("H", "morale %s" % ("theirs" if outcome == "win" else "mine"))
    run.check("held" in r, "morale: %s" % r)
    run.wait(lambda: run.events("H", "battle", what="close") or run.sleep(2), 600, "the sally over")
    back_in_cities(run)
    run.sleep(5)


def assault(run, stormer, terms, wait=75):
    """The siege waits `wait` s of real time, then siegewait and Storm; the assault runs until the throne is taken,
    and the chooser picks `terms`."""
    run.sleep(wait)
    run.cmd(stormer, "target 2")
    run.cmd(stormer, "siegewait")
    run.sleep(5)
    m = meta("WAR")
    pick_army(run, stormer, m[stormer][1])
    r = run.wait(lambda: (lambda r: r if not r.startswith("refused") else None)(run.cmd(stormer, "storm")) or run.sleep(3), 180,
                 "%s's Storm accepted" % stormer)
    run.check("Storm" in r, "%s storms L's capital: %s" % (stormer, r))
    run.wait(lambda: "now " in run.cmd("H", "assault") and "now null" not in run.cmd("H", "assault") or run.sleep(3), 300,
             "the assault running")
    run.sleep(15)
    for w in WHO:
        run.shot(w, "%s during the assault" % w)
    taken = run.wait(lambda: (lambda r: r if "awaiting terms null" not in r else None)(run.cmd("H", "assault")) or run.sleep(5),
                     900, "the throne taken")
    say("assault:", taken)
    chooser = next((w for w in WHO if "terms true" in state(run, w)), None)
    run.check(chooser is not None, "a winner chooses the terms: %s" % chooser)
    if chooser is None:
        return None
    run.sleep(3)
    run.shot(chooser, "%s's assault terms window" % chooser)
    r = run.cmd(chooser, "terms " + terms)
    run.check(r == "terms chosen", "%s chose %s: %s" % (chooser, terms, r))
    run.sleep(10)
    for w in WHO:
        run.shot(w, "%s after the terms" % w)
    run.wait(lambda: all("terms false" in state(run, w) for w in WHO) and "now null" in run.cmd("H", "assault") or run.sleep(5), 600,
             "the assault over")
    run.sleep(10)
    return chooser


def b_siege(name, title, besiegers, sally_outcome=None, with_army=False, terms=None):
    def go():
        run = start("WAR", "b3-" + name)
        run.position = "WAR"
        ok = False
        try:
            l_before = status(run, "L")
            besiege(run, besiegers)
            if sally_outcome:
                sally(run, with_army, sally_outcome, [w for w in besiegers if w != "H"])
                if sally_outcome == "win":
                    for w in besiegers:
                        run.check(got_spoils(run, w), "%s, a winner of the sally, received spoils or captives" % w)
            if terms:
                chooser = assault(run, besiegers[0], terms)
                diplo = {w: run.cmd(w, "diplo") for w in WHO}
                if terms.startswith("vassalage"):
                    run.check(all(re.search(r"0 [^:]*:[^;]*2=vassal", d) for d in diplo.values()),
                              "L is H's vassal on every PC: %s" % diplo["H"])
                if terms.startswith("eliminate"):
                    run.check("spectating true" in status(run, "L"), "L can only spectate: %s" % status(run, "L")[:200])
                for w in besiegers:
                    say(w, "transfers:", [l for l in run.log(w).splitlines() if "transfer step" in l][-8:])
                say("L before:", l_before[:200], "after:", status(run, "L")[:200])
            ok = run.end()
        except Failed as e:
            run.check(False, str(e))
            run.stop()
        finish(name, title, run, ok)
    return go


def b11():
    """B11: L sallies with nobody: no garrison, its army far off. L hears why, and no battle prompt opens (SOS-170)."""
    run = start("WAR", "b3-B11")
    run.position = "WAR"
    ok = False
    try:
        besiege(run, ["H"])
        t0 = time.time() * 1000
        r = run.wait(lambda: (lambda r: r if not r.startswith("refused") else None)(run.cmd("L", "sally")) or run.sleep(3), 120,
                     "L's sally accepted")
        run.check(r.startswith("sallying"), "L sallies out: %s" % r)
        run.wait(lambda: any("Nobody sallies out" in e.get("text", "") and e.get("t", 0) >= t0
                             for e in run.events("L", "chatline")) or run.sleep(1), 60, "L's notice of a sally with nobody")
        run.check(True, "L hears that nobody sallies out")
        run.check(not any("Nobody sallies out" in e.get("text", "") for w in ("H", "J") for e in run.events(w, "chatline")),
                  "only L hears it")
        run.check("battle none" in state(run, "L") and "sally -1" in state(run, "L"), "no battle for L: %s" % state(run, "L"))
        for w in WHO:
            run.shot(w, "%s after L's sally with nobody" % w)
        ok = run.end()
    except Failed as e:
        run.check(False, str(e))
        run.stop()
    finish("B11", "a sally with nobody", run, ok)


SCENARIOS.update({
    "B11": ("WAR", b11, "H besieges L's capital; L sallies with no soldiers in or beside it and is told why"),
    "B6": ("WAR", b_siege("B6", "siege and sally: L's garrison and army sally; H and J win", ["H", "J"], "win", with_army=True),
           "H and J besiege L's capital; L sallies with its army; H and J win"),
    "B7": ("WAR", b_siege("B7", "assault, Sack", ["H", "J"], terms="sack 60 40:20:20"),
           "H and J besiege, siegewait, storm and Sack"),
    "B8": ("WAR", b_siege("B8", "assault, Eliminate", ["H", "J"], terms="eliminate 90 50:20:20"),
           "as B7, Eliminate"),
    "B9": ("WAR", b_siege("B9", "assault, Vassalage", ["H", "J"], terms="vassalage 25"), "as B7, Vassalage"),
    "B10": ("WAR", b_siege("B10", "1 against a city: L's sally loses, H storms and sacks", ["H"], "win", with_army=True,
                                terms="sack 50 30:10:10"),
            "H alone besieges; L sallies and loses; H storms (Sack)"),
})


# ------------------------------------------------------------------ C: regions and NPC vassals (P-REGIONS)


def prep_regions(run):
    """P-REGIONS: P-WAR's war, and every player gets the NPC region nearest its capital and the nearest free NPC faction
    as a vassal; then H's and J's first armies stand next to L's new region town. Built from BASE: P-WAR's armies stand
    next to L's, so its battle started at once and held the gifts (chain10: L's region not given, J's vassal given to L
    too)."""
    war_on_l(run)
    facts = {}
    for w in WHO:
        r = run.cmd(w, "giveregion %d" % SLOT[w])
        m = re.search(r"region (\d+) ", r)
        if not m:
            raise Failed("%s's region: %s" % (w, r))
        facts["region" + w] = int(m.group(1))
        run.sleep(3)
        r = run.cmd(w, "givevassal %d" % SLOT[w])
        m = re.search(r"\((\d+)\) becomes", r)
        if not m:
            raise Failed("%s's vassal: %s" % (w, r))
        facts["vassal" + w] = int(m.group(1))
        run.sleep(5)  # applied before the next player's nearest free NPC is chosen
    if len({facts["vassal" + w] for w in WHO}) < len(WHO):
        raise Failed("one NPC faction was given to two players: %s" % {w: facts["vassal" + w] for w in WHO})
    run.sleep(5)
    say("relations:", run.cmd("H", "diplo"))
    town = run.cmd("L", "regionat %d" % facts["regionL"])
    m = re.search(r"at (\d+),(\d+)", town)
    if not m:
        raise Failed("L's region town: %s" % town)
    tx, ty = int(m.group(1)), int(m.group(2))
    facts["townL"] = [tx, ty]
    for i, w in enumerate(("H", "J")):
        mine = armies(run, w)
        by = sorted(mine, key=lambda a: -a[1])[:2]  # the two template armies, as P-WAR's
        run.cmd(w, "pick %d" % mine.index(by[0]))
        place_picked(run, w, tx + 2, ty + 2 * i - 1)
        facts[w] = [by[0][0], by[1][0]]
    # L's field army out of its capital, as P-WAR's (C4 attacks it), last: placed first it walked home (chain12)
    lx, ly = capital(run, "L")
    field = place(run, "L", lx + 6, ly)
    facts.update({"lcap": [lx, ly], "lfield": [field[2], field[3]]})
    return facts


def c_region_siege(name, title, answer):
    """H and J besiege L's region town; L is asked to sally: Fight (answer 'fight'), Fall Back ('fallback') or no
    answer ('none'). H and J win the sally battle or the siege goes on; then the besiegers' prompt and the capture."""
    def go():
        run = start("REGIONS", "b3-" + name)
        run.position = "REGIONS"
        ok = False
        m = meta("REGIONS")
        try:
            if answer == "fallback":
                run.cmd("L", "fallback")
            if answer == "none":
                run.cmd("L", "auto off")
            if answer != "fight":
                run.cmd("H", "stormnext")  # chain11 C3: waited out, the low-order region rebelled before any capture
            for w in ("H", "J"):
                pick_army(run, w, m[w][0])
                r = run.cmd(w, "besiegeregion %d" % m["regionL"])
                run.check("besieging" in r, "%s besieges L's region town: %s" % (w, r))
            run.wait(lambda: re.search(r"prompt (?!none)", state(run, "L")) or run.sleep(1), 240, "L's sally prompt")
            run.shot("L", "L's sally prompt for its region town")
            if answer == "fight":
                wait_state(run, "J", "ally asked true", 120, "J's Join prompt")  # chain11 C2: J never answered, stayed out
                run.check("Join" in run.cmd("J", "join"), "J answered Join")
                wait_state(run, "H", "battle deploying", 240, "the sally battle at deployment")
                for w in WHO:
                    run.shot(w, "%s at the region sally's deployment" % w)
                run.wait(lambda: "battle running" in state(run, "H") or run.sleep(1), 240, "the sally battle started")
                run.sleep(5)
                for w in WHO:
                    run.cmd(w, "charge")
                run.sleep(10)
                run.check("held" in run.cmd("H", "morale theirs"), "L's side's morale held")
                run.wait(lambda: run.events("H", "battle", what="close") or run.sleep(2), 600, "the sally battle over")
                back_in_cities(run)
            else:
                asked = time.time()
                run.wait(lambda: "prompt none" in state(run, "L") or run.sleep(1), 120, "L's prompt closed")
                if answer == "none":
                    run.check(time.time() - asked >= 25, "L's unanswered prompt closed after %.0f s" % (time.time() - asked))
                    run.cmd("L", "auto on")
            owner = run.wait(lambda: (lambda r: r if not re.search(r"of Mordor", r) else None)(run.cmd("H", "regionat %d" % m["regionL"]))
                             or run.sleep(5), 900, "L's region town taken")
            # a besieger's: chain11 C3 passed on "of nobody", the region having rebelled
            run.check(re.search(r"of (Ukorina|Petrovich)", owner), "L's region town is a besieger's: %s" % owner)
            run.sleep(5)
            for w in WHO:
                run.shot(w, "%s after the region town's capture" % w)
            ok = run.end()
        except Failed as e:
            run.check(False, str(e))
            run.stop()
        finish(name, title, run, ok)
    return go


def c1():
    run = start("REGIONS", "b3-C1")
    run.position = "REGIONS"
    ok = False
    m = meta("REGIONS")
    try:
        diplo = {w: run.cmd(w, "diplo") for w in WHO}
        run.check(len(set(diplo.values())) == 1, "the relations are the same on every PC: %s" % diplo["H"])
        for w in WHO:
            v = m["vassal" + w]
            run.check(re.search(r"(?:^|; )%d [^:]*:[^;]*\b%d=vassal" % (SLOT[w], v), diplo["H"]),
                      "%s's vassal %d shows in diplomacy" % (w, v))
            r = run.cmd(w, "regionat %d" % m["region" + w])
            run.check(re.search(r"of (Ukorina|Petrovich|Mordor)", r), "%s's region %d: %s" % (w, m["region" + w], r))
            run.cmd(w, "ui region %d" % m["region" + w])
            run.sleep(2)
            run.shot(w, "%s's region panel" % w)
            run.cmd(w, "ui diplomacy")
            run.sleep(2)
            run.shot(w, "%s's diplomacy window" % w)
        # a vassal acts against nobody its overlord allies or trades with: no war between it and the overlord's allies
        for w in WHO:
            v = m["vassal" + w]
            line = re.search(r"(?:^|; )%d [^:]*:([^;]*)" % SLOT[w], diplo["H"]).group(1)
            friends = [int(o) for o, rel in re.findall(r"(\d+)=(Allies|Trade|Colleagues)", line)]
            for f in friends:
                fl = re.search(r"(?:^|; )%d [^:]*:([^;]*)" % f, diplo["H"])
                run.check(not (fl and re.search(r"\b%d=war" % v, fl.group(1))), "%s's vassal %d isn't at war with %s's friend %d" % (w, v, w, f))
        run.sleep(30)
        ok = run.end()
    except Failed as e:
        run.check(False, str(e))
        run.stop()
    finish("C1", "every player has a region and an NPC vassal", run, ok)


SCENARIOS.update({
    "C1": ("REGIONS", c1, "every player has a region and an NPC vassal, the same on every PC"),
    "C2": ("REGIONS", c_region_siege("C2", "L's region town: L sallies, H and J win, then the capture", "fight"),
           "H and J besiege L's region town; L sallies and loses"),
    "C3": ("REGIONS", c_region_siege("C3", "L's region town: L falls back; H and J take it", "fallback"),
           "L presses Fall Back; the siege goes on, H and J take the town"),
    "C3b": ("REGIONS", c_region_siege("C3b", "L's region town: no answer; H and J take it", "none"),
            "L doesn't answer for 30 s; H and J take the town"),
})


# ------------------------------------------------------------------ A3-A5: three players against NPCs (SOS-160)


def speed_of(run, w):
    m = re.search(r"speed (\d+\.\d)", state(run, w))
    return float(m.group(1)) if m else -1.0


def a_town(name, title):
    """A5: H, J and L besiege the NPC faction's town next to them and H storms it. Vanilla V71 has no real-time attack
    on a town (WBattleSiege.engage throws), so the Storm is vanilla's auto-resolve: the town is taken and the result
    applies once on every PC."""
    def go():
        run = start("ALLIES", "b3-" + name)
        run.position = "ALLIES"
        ok = False
        try:
            f = meta("ALLIES")["npc"]
            run.position = None  # each player's largest army: the small ones of ALLIES lose to a 947-man garrison
            template_army(run, "H")
            town = run.cmd("H", "townof %d" % f)
            m = re.search(r"region (\d+) at (\d+),(\d+)", town)
            if not m:
                raise Failed("no town of faction %d: %s" % (f, town))
            reg, tx, ty = (int(v) for v in m.groups())
            say("the town:", town)
            for w, (dx, dy) in zip(WHO, ((2, 0), (-2, 0), (0, 2))):
                place(run, w, tx + dx, ty + dy)
            # chain10: the three largest armies (about 700 men) lost the Storm to a 947-man garrison; vanilla counts
            # every army within 4 tiles on the attackers' side, so each player adds two template armies beside its
            # first (chain11: one each, 3 tiles out, made 853 against 955; no mercenaries: their pay ran short)
            for _ in range(2):
                for w, (dx, dy) in zip(WHO, ((2, 0), (-2, 0), (0, 2))):
                    template(run, w, merc=False)
                    place_picked(run, w, tx + dx, ty + dy)
                    run.cmd(w, "fill")
            # H last, its Storm armed just before: chain9 stormed at H's own first prompt, before J and L besieged
            for w in ("J", "L", "H"):
                if w == "H":
                    run.sleep(5)
                    run.cmd("H", "stormnext")
                template_army(run, w)
                r = run.cmd(w, "besiegeregion %d" % reg)
                run.check("besieging" in r, "%s besieges the town: %s" % (w, r))
            owner = run.wait(lambda: (lambda r: r if re.search(r"of (Ukorina|Petrovich|Mordor)", r) else None)(
                run.cmd("H", "regionat %d" % reg)) or run.sleep(5), 600, "the town taken")
            run.check(re.search(r"of (Ukorina|Petrovich|Mordor)", owner), "the town is a player's: %s" % owner)
            run.sleep(10)
            for w in WHO:
                run.shot(w, "%s after the town's capture" % w)
            ok = run.end()
        except Failed as e:
            run.check(False, str(e))
            run.stop()
        finish(name, title, run, ok)
    return go


SCENARIOS.update({
    "A3": ("ALLIES", a_battle("A3", "H, J and L beat the NPC army together", ["J", "L"]),
           "H, J and L fight the NPC army together and win (SOS-160)"),
    "A4": ("ALLIES", a_battle("A4", "H, J and L lose to the NPC army", ["J", "L"], outcome="lose"),
           "H, J and L fight it and lose (SOS-160)"),
    "A5": ("ALLIES", a_town("A5", "H, J and L besiege the NPC town and H storms it"),
           "H, J and L take the NPC faction's town (auto-resolved: V71 has no real-time town attack)"),
})


# ------------------------------------------------------------------ B5 and C4


def b5():
    """B5: J fights an NPC battle (J at war with the NPC army nearest its first army); meanwhile L's field army attacks
    J's second army. L's and J's prompt for that waits until J's NPC battle ends, then opens with the 30-second rule."""
    run = start("WAR", "b3-B5")
    run.position = "WAR"
    ok = False
    m = meta("WAR")
    try:
        jx, jy = pick_army(run, "J", m["J"][0])[2:4]
        diplo = run.cmd("H", "diplo")
        taken = set().union(*(vassals_of(diplo, SLOT[w]) for w in WHO))
        mine = power(run, m["J"][0])
        cands = [a for a in npc_armies(run) if a[1] >= 1000 and a[0] not in taken and power(run, a[4]) > mine * 1.3]
        if not cands:
            raise Failed("no NPC army stronger than J's (vanilla lets a weaker one withdraw at once)")
        # the strongest: armypower isn't vanilla's measure, and a weaker army withdraws with no prompt (chain10: J's 200
        # men beat 1023 five times over); it is set beside J anyway
        f, men, x, y, idx = max(cands, key=lambda a: a[1])
        say("J's NPC army: faction %d, %d men at %d,%d" % (f, men, x, y))
        run.cmd("J", "npcwar %d" % f)
        run.sleep(5)
        pick_army(run, "J", m["J"][0])
        a = place_picked(run, "J", x + 2, y)
        # the NPC army walks off while J's intercept chases it (chain9: 45,103 to 34,107 in 240 s): set it beside J last
        run.cmd("H", "fillarmy %d" % idx)
        say(run.cmd("H", "teleportarmy %d %d %d" % (idx, a[2] - 1, a[3])))
        run.cmd("L", "auto off")
        run.check("intercepting" in run.cmd("J", "attacknpc"), "J attacks the NPC army")
        if resolved(run):
            raise Failed("the battle was resolved at once, with no prompt: %s" % resolved(run))
        try:
            run.wait(lambda: "battle running" in state(run, "J") or run.events("J", "battle", what="victory accept")
                     or run.sleep(1), 240, "J's NPC battle started")
            if run.events("J", "battle", what="victory accept"):
                raise Failed("the NPC army withdrew from J with no battle: vanilla found it the weaker")
        except Failed:
            # chain8: J never met the army; where both stood tells a chase from a block
            say("J's armies", armies(run, "J"), "the NPC's", [a for a in npc_armies(run) if a[0] == f])
            raise
        run.cmd("J", "auto off")
        pick_army(run, "J", m["J"][1])
        # out in the field, 4 tiles from L's: chain12 found it at home (68,89), where L's intercept never went
        jx2, jy2 = place_picked(run, "J", m["lfield"][0], m["lfield"][1] + 4)[2:4]
        pick_army(run, "L", armies(run, "L")[0][0])
        place_picked(run, "L", jx2 + 3, jy2)
        run.cmd("L", "target 1")
        r = run.cmd("L", "attack")
        run.check("intercepting" in r, "L attacks J's second army during J's NPC battle: %s" % r)
        early = []
        def over():
            for w in ("J", "L"):
                if re.search(r"prompt (Spec|WBattle)", state(run, w)):  # a Fight prompt, not J's own result window
                    early.append(w)
            if "battle over" in state(run, "J") and not run.events("J", "battle", what="close"):
                run.cmd("J", "close")  # J's battles answer by their time-outs: nothing else presses Close (chain11 B5)
            return run.events("J", "battle", what="close")
        run.sleep(20)
        run.shot("L", "L while J's NPC battle runs, its attack under way")
        run.cmd("J", "charge")
        run.sleep(10)
        run.check("held" in run.cmd("J", "morale theirs"), "the NPC side's morale held")
        run.wait(lambda: over() or run.sleep(2), 600, "J's NPC battle over")
        run.check(not early, "no Fight prompt opened during J's NPC battle: %s" % sorted(set(early)))
        run.wait(lambda: re.search(r"prompt (Spec|WBattle)", state(run, "J")) or re.search(r"prompt (?!none)", state(run, "L")) or run.sleep(1), 240,
                 "the prompt for L's attack after J's battle")
        asked = time.time()
        for w in ("J", "L"):
            run.shot(w, "%s's prompt for L's attack, after J's NPC battle" % w)
        run.wait(lambda: "battle deploying" in state(run, "J") or "battle running" in state(run, "J") or run.sleep(1), 240,
                 "the battle after the unanswered prompts")
        run.check(time.time() - asked >= 25, "the battle began %.0f s after the unanswered prompt" % (time.time() - asked))
        for w in ("J", "L"):
            run.cmd(w, "auto on")
        run.wait(lambda: "battle running" in state(run, "J") or run.sleep(2), 240, "L's battle started")
        run.sleep(5)
        for w in ("J", "L"):
            run.cmd(w, "charge")
        run.sleep(10)
        run.check("held" in run.cmd("J", "morale theirs"), "L's morale held")
        run.wait(lambda: len(run.events("J", "battle", what="close")) >= 2 or run.sleep(2), 600, "L's battle over")
        back_in_cities(run)
        run.sleep(5)
        ok = run.end()
    except Failed as e:
        run.check(False, str(e))
        run.stop()
    finish("B5", "a prompt during an NPC battle waits, then the 30-second rule", run, ok)


def c4():
    """C4: H's NPC vassal's army stands beside L's field army when H and J attack it (B1); it joins as an NPC ally
    under the host's AI, and the result applies once everywhere."""
    run = start("REGIONS", "b3-C4")
    run.position = "REGIONS"
    ok = False
    m = meta("REGIONS")
    try:
        v = m["vassalH"]
        va = [a for a in npc_armies(run) if a[0] == v]
        if not va:
            raise Failed("H's vassal %d has no army" % v)
        va = max(va, key=lambda a: a[1])
        def beside_l():  # right before H's attack: set beside L at the load, it had walked off to 74,71 (chain13)
            lx, ly = armies(run, "L")[0][2:4]
            say("the vassal's army:", va, run.cmd("H", "teleportarmy %d %d %d" % (va[4], lx, ly + 2)))
            run.sleep(2)
        theirs = {}
        def sides():  # H's side holds H's and J's armies and the vassal's: a win by morale may cost the vassal no men
            hj = sum(x[1] for w in ("H", "J") for x in armies(run, w) if x[0] == m[w][0])  # the battle's two armies
            r = run.cmd("H", "battlemen")
            got = re.search(r"player (\d+)", r)
            theirs["hj"], theirs["side"] = hj, int(got.group(1)) if got else 0
            run.check(theirs["side"] >= hj + va[1] // 2, "the vassal's army fights on H's side: H's side %d men, H's and J's "
                      "armies %d, the vassal's %d (%s)" % (theirs["side"], hj, va[1], r))
        pvp_battle(run, ["H"], ["J"], "win", before_attack=beside_l, in_battle=sides)
        say("the vassal's army after:", [a for a in npc_armies(run) if a[4] == va[4]])
        for w in ("H", "J"):
            run.check(got_spoils(run, w), "%s, a winner, received spoils or captives" % w)
        run.check(not captives(run, "L"), "L, beaten, received no captives")
        ok = run.end()
    except Failed as e:
        run.check(False, str(e))
        run.stop()
    finish("C4", "an NPC vassal's army joins B1's battle", run, ok)


SCENARIOS.update({
    "B5": ("WAR", b5, "J's NPC battle; L attacks J's other army meanwhile; that prompt waits, then 30 s"),
    "C4": ("REGIONS", c4, "H's NPC vassal's army joins H and J's battle against L's army"),
})


def agreed_speeds(run):
    """H's agreed speed over the run, from its status events: (lowest, last); the governor lowers it for a slow peer."""
    agreed = [e["agreed"] for e in run.events("H", "status") if not e.get("paused") and e.get("agreed", 0) > 0]
    agreed = agreed[next((i for i, v in enumerate(agreed) if v > 1.0), len(agreed)):]  # from the host's --speed on
    return (min(agreed), agreed[-1]) if agreed else (-1.0, -1.0)


def n_world(name, title, cut=None):
    """SOS-176's world under --netem's conditions: the world at speed, J views H's city, H saves the campaign; then each
    --outage in turn, or `cut`'s game loses its connection. Today's behaviour is the baseline the reconnect work beats."""
    def go():
        run = start("ALLIES", "b3-" + name)
        run.position = "ALLIES"
        ok = False
        try:
            run.sleep(20)
            run.cmd("J", "target 0")
            r = run.cmd("J", "view")
            run.check("asked" in r, "J asked to view H's city: %s" % r)
            run.wait(lambda: run.events("J", "viewstate", state=2), 180, "J viewing H's city")
            run.sleep(15)
            run.shot("J", "J viewing H's city")
            run.cmd("J", "closeview")
            run.wait(lambda: [e for e in run.events("J", "viewstate") if e.get("state") in (3, 4)], 180, "J's view over")
            run.sleep(5)
            run.cmd("H", "save")
            run.wait(lambda: any(e["status"].startswith("Campaign saved") for e in run.events("H", "save")), 600,
                     "H's campaign save")
            run.check(True, "the campaign save went through")
            for w, seconds, cut in OUTAGE:
                r = link_down(run, w, seconds, cut)
                run.check(("outage" in r or not seconds) and ("cut" in r or not cut),
                          "%s's link carries nothing for %d s%s from step %d: %s"
                          % (w, seconds, ", its connection cut," if cut else "", run.step(w), r))
                run.sleep(min(5, max(1, seconds / 2)))
                for v in WHO:
                    run.shot(v, "%s during %s's %d s %s" % (v, w, seconds, "cut" if cut else "outage"))
                run.sleep(seconds + 25)
            if cut:
                r = run.cmd(cut, "netcut")
                run.check("cut" in r, "%s's connection is cut at step %d: %s" % (cut, run.step(cut), r))
                run.sleep(10)
                for v in WHO:
                    run.shot(v, "%s 10 s after %s's cut" % (v, cut))
                run.sleep(50)
            low, last = agreed_speeds(run)
            run.check(True, "H's agreed speed: lowest %.1f, last %.1f (the host asked 25)" % (low, last))
            ok = run.end()
        except Failed as e:
            run.check(False, str(e))
            run.stop()
        finish(name, title, run, ok)
    return go


SCENARIOS.update({
    "N1": ("ALLIES", n_world("N1", "the world under --netem: a view, a save, then each --outage"),
           "SOS-176: the world at speed under --netem, J views H's city, H saves, then each --outage"),
    "N2": ("ALLIES", n_world("N2", "the world under --netem, then J's connection is cut", cut="J"),
           "SOS-176: as N1, then J's connection is cut (netcut; needs --netem join=...)"),
})


def main():
    global REPORTS
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=("prep", "run", "all", "list", "probe"))
    ap.add_argument("name", nargs="?", default="")
    ap.add_argument("--netem", action="append", default=[], metavar="WHO=SPEC",
                    help="test network conditions, passed to twopc.py (SOS-175): WHO host, join or local")
    ap.add_argument("--outage", action="append", default=[], metavar="WHO:SECONDS",
                    help="once the battle runs, WHO's (H, J or L) link carries nothing for SECONDS, 60 at most (SOS-176);"
                         " needs --netem for that game. Repeatable")
    ap.add_argument("--cut", action="append", default=[], metavar="WHO:SECONDS",
                    help="that game's connections break once the battle runs (or in N1/N2's outage turn), with no link for SECONDS (0-60) first; SOS-178")
    ap.add_argument("--reports", default="", help="the PDF reports' folder instead of %s" % REPORTS)
    ap.add_argument("--label", default="", help="added to the scenario's name in its report, logs and result line")
    args = ap.parse_args()
    LABEL[0] = args.label
    if args.reports:
        REPORTS = pathlib.Path(args.reports)
    for item in args.outage:
        who, _, seconds = item.partition(":")
        if who not in WHO or not seconds.isdigit():
            sys.exit("battles3: --outage is WHO:SECONDS with WHO H, J or L: '%s'" % item)
        OUTAGE.append((who, int(seconds), False))
    for item in args.cut:
        who, _, seconds = item.partition(":")
        if who not in WHO or not seconds.isdigit() or int(seconds) > 60:
            sys.exit("battles3: --cut is WHO:SECONDS with WHO H, J or L and SECONDS 0 to 60: '%s'" % item)
        OUTAGE.append((who, int(seconds), True))
    TWOPC.extend(x for item in args.netem for x in ("--netem", item))
    OUT.mkdir(parents=True, exist_ok=True)
    if args.what == "prep":
        prep(args.name)
    elif args.what == "probe":
        probe(args.name)
    elif args.what == "run":
        if args.name not in SCENARIOS:
            sys.exit("battles3: no scenario %s (list shows them)" % args.name)
        SCENARIOS[args.name][1]()
    elif args.what == "all":
        for k, (pos, go, _) in SCENARIOS.items():
            go()
    elif args.what == "list":
        for k, (pos, _, text) in SCENARIOS.items():
            print("%-4s %-8s %s" % (k, pos, text))


if __name__ == "__main__":
    main()
