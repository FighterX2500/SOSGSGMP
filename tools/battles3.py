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
GAME = {"H": "1", "J": "2", "L": "3"}  # each player's data folder
SLOT = {"H": 0, "J": 1, "L": 2}
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
                    say(w, json.dumps(e)[:260])
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
        cmd.write_text("\n".join(lines) + "\n", encoding="utf-8")
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
        """Stops this run's games and runner: only their own processes."""
        if self.p.poll() is None:
            self.p.kill()
        for pid in game_pids() - self.others:
            subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)


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


def template(run, w):
    """The template army (Test List §5): 200 drafted soldiers and one mercenary band, in a new army, full supplies."""
    before = armies(run, w)
    old = {d for d, _ in re.findall(r"(\d+): men (\d+)", run.cmd(w, "divs"))}
    run.cmd(w, "recruit 200")
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
    if hire:
        run.cmd(w, "merc %s" % hire[0])
    run.wait(lambda: any(a[0] == new[0][0] and a[1] >= 150 for a in armies(run, w)) or run.sleep(3), 300,
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


def prep_allies(run):
    """P-ALLIES: BASE, and the three players at war with one NPC faction whose army is about two template armies
    strong (450 to 900 men), the nearest to the host's capital that is nobody's vassal."""
    hx, hy = capital(run, "H")
    diplo = run.cmd("H", "diplo")
    taken = set().union(*(vassals_of(diplo, SLOT[w]) for w in WHO))
    cands = [a for a in npc_armies(run) if 450 <= a[1] <= 900 and a[0] not in taken]
    if not cands:
        raise Failed("no NPC army of 450 to 900 men")
    f, men, x, y = min(cands, key=lambda a: (a[2] - hx) ** 2 + (a[3] - hy) ** 2)
    say("the NPC army: faction %d, %d men at %d,%d" % (f, men, x, y))
    for w in WHO:
        run.cmd(w, "npcwar %d" % f)
    return {"npc": f}


def prep_war(run):
    """P-WAR: BASE; L cancels its treaties with H and J and declares war on both; H and J each get a second
    template army. L's army stands 6 tiles from its capital; H's and J's first armies next to it, their second ones
    next to L's capital."""
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
    lx, ly = capital(run, "L")
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
    for dx, dy in ((0, 0), (1, 0), (0, 1), (-1, 0), (0, -1), (1, 1), (-1, -1), (2, 0), (0, 2)):
        run.cmd(w, "teleport %d %d" % (x + dx, y + dy))
        run.sleep(2)
        if re.search(r"mine \d+ men \d+ at %d,%d " % (x + dx, y + dy), status(run, w)):
            return x + dx, y + dy
    raise Failed("%s's army can't stand near %d,%d" % (w, x, y))


PREPS = {"ALLIES": ("BASE", prep_allies), "WAR": ("BASE", prep_war)}


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


def screenshots(run):
    """This run's screenshots on every game, oldest first, each with a note: the driver's, a loading window's (the
    autopilot shoots each once it drew) or the victory and defeat panel's (battles=auto shoots it)."""
    out = []
    for w in WHO:
        folder = twopc.INST / GAME[w] / "songsofsyx" / "screenshots"
        files = sorted((p for p in folder.glob("*.png") if p.stat().st_mtime >= run.started - 5), key=lambda p: p.stat().st_mtime)
        auto = [e for e in run.ev[w] if e.get("event") == "loadingshot"]
        mine = [(t, n) for who, t, n in run.shots if who == w]
        for f in files:
            t = f.stat().st_mtime
            note = next((n for st, n in mine if 0 <= t - st < 8), None)
            if note is None:
                ls = [e for e in auto if abs(e["t"] / 1000.0 - t) < 8]
                note = ("the loading window %s %s" % (ls[0]["kind"], ls[0]["key"])) if ls else "the battle's victory or defeat panel, or a window the run opened"
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
    lines = ["Scenario %s: %s" % (name, title), "Run %s, %s to %s." % (run.tag, time.strftime("%H:%M", time.localtime(run.started)), time.strftime("%H:%M"))]
    run.check(ok, "the three games kept the same hashes to the end, no desync (twopc)")
    run.check(not (run.crash_files() - run.crashes0), "no crash file")
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
    say("RESULT:", name, "PASS" if passed else "FAIL")
    return passed


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
    """The player's template army (its largest): the army commands act on it from now; returns (index, men, x, y)."""
    mine = armies(run, w)
    if not mine:
        raise Failed("%s has no army" % w)
    best = max(mine, key=lambda a: a[1])
    run.cmd(w, "pick %d" % mine.index(best))
    return best


def npc_armies(run):
    """The NPC armies with men, as (faction, men, x, y)."""
    return [tuple(int(v) for v in m) for m in re.findall(r"(\d+) (\d+) (\d+) (\d+);", run.cmd("H", "npcarmies"))]


def npc_army(run, faction):
    found = [a for a in npc_armies(run) if a[0] == faction]
    if not found:
        raise Failed("NPC faction %d has no army" % faction)
    return max(found, key=lambda a: a[1])


def place(run, w, x, y):
    """The template army stands at x,y, or the nearest tile it can stand on around it."""
    for dx, dy in ((0, 0), (1, 0), (0, 1), (-1, 0), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1), (2, 0), (0, 2)):
        run.cmd(w, "teleport %d %d" % (x + dx, y + dy))
        run.sleep(2)
        a = template_army(run, w)
        if (a[2], a[3]) == (x + dx, y + dy):
            say(w, "army at", a[2], a[3])
            return a
    raise Failed("%s's army can't stand near %d,%d" % (w, x, y))


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


def spoils(run, w):
    """The spoils and captives player w received in this run, from its playtest log's transfers."""
    return [l for l in run.log(w).splitlines() if "transfer step" in l and re.search(r"spoils|captive|Spoils|Captive", l)]


# ------------------------------------------------------------------ A: battles against NPC armies (P-ALLIES)


def npc_battle(run, joiners, watcher=None, outcome="win", retreat=None, save=False, answer=True, fight=True):
    """H attacks the position's NPC army; `joiners` are asked Join and answer it (or not, answer=False); `watcher`
    views H's battle for 20 s; the side wins (the enemy's morale held) or loses (its own); `retreat` leaves halfway."""
    m = meta(run.position)
    x, y = npc_army(run, m["npc"])[2:]
    say("the NPC army of faction %d stands at %d,%d" % (m["npc"], x, y))
    place(run, "H", x + 2, y)
    for i, w in enumerate(joiners):
        place(run, w, x + 2, y + 2 * (i + 1))
    if not fight:
        run.cmd("H", "auto off")
    template_army(run, "H")
    run.cmd("H", "attacknpc")
    for w in joiners:
        wait_state(run, w, "ally asked true", 120, "%s's Join prompt" % w)
        run.shot(w, "%s's Join / Stay out prompt" % w)
        if answer:
            run.check("Join" in run.cmd(w, "join"), "%s answered Join" % w)
    if not fight:
        run.wait(lambda: "prompt WBattle" in state(run, "H") or run.sleep(1), 120, "H's Fight prompt")
        asked = time.time()
        run.wait(lambda: "prompt none" in state(run, "H") or run.sleep(1), 120, "H's prompt to time out")
        run.check(time.time() - asked >= 20, "H's Fight prompt closed by itself %.0f s after the Join prompt" % (time.time() - asked))
        run.cmd("H", "auto on")
        run.sleep(20)
        back_in_cities(run)
        return
    wait_state(run, "H", "battle deploying", 180, "H's battle at deployment")
    run.shot("H", "H's deployment")
    run.wait(lambda: "battle running" in state(run, "H") or run.sleep(1), 180, "H's battle started")
    run.sleep(5)
    for w in ["H"] + joiners:
        r = run.cmd(w, "speed 3")
        say(w, "speed vote:", r)
    run.sleep(3)
    run.shot("H", "H's battle after both commanders voted for 3x")
    for w in joiners:
        run.shot(w, "%s's view of the battle as an ally" % w)
    if watcher:
        run.cmd(watcher, "target 0")
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
    run.cmd("H", "charge")
    for w in joiners:
        if w != retreat:
            run.cmd(w, "charge")
    run.sleep(10)
    side = "enemy" if outcome == "win" else "player"
    r = run.cmd("H", "morale %s" % side)
    run.check("held" in r, "the %s side's morale held below zero: %s" % (side, r))
    run.wait(lambda: run.events("H", "battle", what="close"), 600, "H's battle over")
    back_in_cities(run)
    run.sleep(5)


def a_battle(name, title, joiners, watcher=None, outcome="win", retreat=None, save=False, answer=True, fight=True):
    def go():
        run = start("ALLIES", "b3-" + name)
        run.position = "ALLIES"
        ok = False
        try:
            before = {w: template_army(run, w) for w in WHO}
            npc_before = npc_army(run, meta("ALLIES")["npc"])
            npc_battle(run, joiners, watcher, outcome, retreat, save, answer, fight)
            after = {w: template_army(run, w) for w in WHO}
            npc_after = [a for a in npc_armies(run) if a[0] == meta("ALLIES")["npc"]]
            for w in WHO:
                say(w, "army before", before[w], "after", after[w], "spoils", spoils(run, w))
            fought = ["H"] + ([w for w in joiners] if answer else [])
            for w in fought:
                run.check(after[w][1] < before[w][1] or not fight, "%s lost men: %d to %d" % (w, before[w][1], after[w][1]))
            if outcome == "win" and fight:
                for w in fought:
                    if w != retreat:
                        run.check(spoils(run, w), "%s received spoils or captives" % w)
                if retreat:
                    run.check(not spoils(run, retreat), "%s, who retreated, received no spoils" % retreat)
            elif fight:
                for w in fought:
                    run.check(not spoils(run, w), "%s, beaten, received nothing" % w)
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
        if save:
            resume_check(name, after)
    return go


def resume_check(name, after):
    """A8: the campaign saved after the battle resumes with the battle's losses in it."""
    run = Run("b3-%s-resume" % name)
    try:
        run.wait(lambda: all(run.events(w, "go") for w in WHO), 300, "every game playing after the resume")
        for w in ("H", "J"):
            a = template_army(run, w)
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


def pvp_battle(run, attackers, joiners, outcome, answers=True, start_answers=True, stay=()):
    """attackers[0]'s field army attacks L's field army; `joiners` answer Join (or never, answers=False); the battle
    runs on every PC; H's side wins (L's morale held) or L wins (H's side's)."""
    m = meta("WAR")
    first = attackers[0]
    pick_army(run, first, m[first][0])
    run.cmd(first, "target 2")
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
    run.wait(lambda: "prompt WBattle" in state(run, "L") or run.sleep(1), 120, "L's Fight prompt")
    if not answers:
        run.shot("L", "L's Fight prompt, left unanswered")
        asked = time.time()
        wait_state(run, "L", "battle deploying", 120, "the battle after L's 30 seconds")
        run.check(time.time() - asked >= 25, "the battle began %.0f s after L's unanswered prompt" % (time.time() - asked))
        run.cmd("L", "auto on")
    wait_state(run, "H", "battle deploying", 180, "the battle's deployment on H")
    for w in WHO:
        run.shot(w, "%s at deployment" % w)
    if not start_answers:
        began = time.time()
        run.wait(lambda: "battle running" in state(run, "H") or run.sleep(2), 240, "the battle's start without J's Start")
        run.check(time.time() - began >= 100, "the battle started %.0f s into deployment without J's Start" % (time.time() - began))
        for w in joiners:
            run.cmd(w, "auto on")
    run.wait(lambda: "battle running" in state(run, "H") or run.sleep(1), 240, "the battle started")
    run.sleep(5)
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
                run.check(spoils(run, w), "%s, a winner, received spoils or captives" % w)
            for w in losers:
                run.check(not spoils(run, w), "%s, beaten, received nothing" % w)
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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=("prep", "run", "all", "list", "probe"))
    ap.add_argument("name", nargs="?", default="")
    args = ap.parse_args()
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
