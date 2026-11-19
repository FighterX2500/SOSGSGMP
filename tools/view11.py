"""SOSGSGMP batch 13 item 11 + 12 (SOS-222): three games on this PC resume the owner's 2026-11-17 campaign at its save
of 01:45 (FighterX2500 hosts, Fray2 in slot 1, Adam Smasher in slot 2) and the host views Fray2's city three times.

    python tools\\view11.py [--source DIR] [--mods-from SOSDET_DIR] [--first-run S] [--adam-view S]

    - each game's data folder holds the campaign's record and save of that number, as its player: the host's from the
      source's campaigns, each joiner's from the host's reports of its files (as twopc.py --campaign takes them);
    - the games resume it at speed 5; after Go every game traces its world draws (autopilot `trace desync`);
    - 1 minute of play (--first-run: longer, as the playtest's 40 minutes since the last whole load); with --adam-view
      the host first views Adam Smasher's city that long, as at 01:55, and the world runs 30 s; then three times: the host views Fray2's city for 2 minutes, or until the view loses step, then
      the world runs 5 minutes; the host resyncs every desync (twopc's default), and more than 5 in one stretch end the run;
    - every game ends at one step (`endat`).

It prints a line per view (held, lost step, or how it ended) and per world desync (its step, and which games wrote
trace-<step>.txt), then the files to keep. Set APPDATA to a scratch folder holding both mods (gradlew installMod in
each repository) and the user's profile, as for twopc.py; the campaign comes from the user's own data folder
(--source), read only. Test tooling only: nothing here is part of the mod. Never commit the saves.
"""
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import twopc  # noqa: E402
from raid3 import Games  # noqa: E402  the three games' events and commands, data folders 1 to 3

CAMPAIGN = "6a3dfa272df0eaf1"
SAVE = "MP FighterX2500s campaign-1a1eb424f09-47002c-ce336261-b0b.save"  # 01:45, before the 01:59 view
# each game's data folder and its window: 1 the host (slot 0), 2 the joiner (slot 1, Fray2), 3 the local joiner (slot 2)
FOLDERS = (("1", (1280, 720)), ("2", (1024, 640)), ("3", (1024, 640)))
NAMES = {"1": "host FighterX2500", "2": "Fray2", "3": "Adam Smasher"}
VIEWS = 3
VIEW_S, WORLD_S, VIEW_START_S, AFTER_ADAM_S = 120, 300, 180, 30
OWNERS = {1: "Fray2", 2: "Adam Smasher"}
MAX_DESYNCS = 5  # resyncs in one stretch of world before the run ends
STATES = {0: "scheduled", 1: "loading", 2: "running", 3: "cancelled", 4: "ended"}
WHY = {-1: "open", 0: "closed", 1: "not caught up", 2: "fell behind", 3: "lost step", 4: "ended by the game"}


def say(*a):
    print("[view11 %s]" % time.strftime("%H:%M:%S"), *a, flush=True)


def number(source):
    """The campaign's record number of SAVE in the host's records, then the newest number at or below it that every
    player's record and save exist for: the host's in its campaigns, each joiner's in the host's reports."""
    records = source / "sosgsgmp" / "campaigns"
    host = [p for p in records.glob(CAMPAIGN + "-*.txt") if re.search(r"(?m)^file=" + re.escape(SAVE) + r"\s*$",
                                                                       p.read_text(encoding="utf-8"))]
    if not host:
        sys.exit("view11: no record of %s in %s" % (SAVE, records))
    wanted = int(host[0].stem.rsplit("-", 1)[1])
    for n in range(wanted, 0, -1):
        if all_have(source, n):
            if n != wanted:
                say("save %d isn't there for every player: number %d instead" % (wanted, n))
            return n
    sys.exit("view11: no save number at or below %d that every player has" % wanted)


def all_have(source, n):
    home = source / "sosgsgmp"
    record = home / "campaigns" / ("%s-%d.txt" % (CAMPAIGN, n))
    if not record.is_file():
        return False
    fields = dict(l.split("=", 1) for l in record.read_text(encoding="utf-8").splitlines() if "=" in l)
    for i in range(int(fields["players"])):
        slot = int(fields["player.%d.slot" % i])
        if slot == int(fields["slot"]):
            rec, saves = record, home / "saves"
        else:
            rec = home / "reports" / CAMPAIGN / ("%d-%s" % (slot, fields["player.%d.name" % i])) / record.name
            saves = rec.parent
        if not rec.is_file():
            return False
        save = dict(l.split("=", 1) for l in rec.read_text(encoding="utf-8").splitlines() if "=" in l)["file"].strip()
        if not (saves / save).is_file():
            return False
    return True


def prep(source):
    """The three data folders, each with the campaign's record and save as its player (twopc.py's --campaign)."""
    n = number(source)
    real = twopc.REAL
    twopc.REAL = source  # campaign_files reads <REAL>/sosgsgmp; the data folders' profile, cache and mods stay APPDATA's
    try:
        seeds = twopc.campaign_files("%s:%d" % (CAMPAIGN, n))
    finally:
        twopc.REAL = real
    if [s[3] for s in seeds] != [0, 1, 2]:
        sys.exit("view11: the campaign's slots are %s, not 0, 1 and 2" % [s[3] for s in seeds])
    for (folder, size), seed in zip(FOLDERS, seeds):
        data = twopc.data_folder(twopc.INST / folder, size)
        twopc.seed_campaign(data, seed)
        say("game %s (%s, slot %d): %s" % (folder, NAMES[folder], seed[3], seed[2].name))
    return n


class Drive:
    """The run's stages from the games' events, until every game has ended."""

    def __init__(self, games, first_run, adam_view):
        self.games = games
        self.first_run = first_run
        # the views, in order: (owner slot, seconds held, seconds of world after)
        self.plan = ([(2, adam_view, AFTER_ADAM_S)] if adam_view > 0 else []) + [(1, VIEW_S, WORLD_S)] * VIEWS
        self.stage, self.at, self.step = "wait-go", time.time(), 0
        self.views = []  # per view: its verdict
        self.desyncs = {}  # desync step: the stage it came in
        self.traces = {}  # desync step: the games that wrote its trace
        self.stretch = 0  # world desyncs since the last view
        self.view = None  # the host's view as last seen: (freeze, state, why)

    def event(self, k, e):
        kind = e.get("event")
        if k == "1":
            self.step = max(self.step, e.get("step", 0))
        if kind == "go" and k == "1" and self.stage == "wait-go":
            for g, _ in FOLDERS:
                self.games.send(g, "trace desync")
            self.stage, self.at = "first-run", time.time()
        elif kind == "viewstate" and k == "1" and e.get("viewer") == 0 and e.get("owner") == self.owner():
            self.view = (e.get("freeze"), e.get("state"), e.get("why"))
            say("view of %s's city: %s at step %s (freeze %s, thaw %s, %s)" % (OWNERS[self.owner()], STATES.get(e.get("state")), e.get("step"),
                e.get("freeze"), e.get("thaw"), WHY.get(e.get("why"))))
        elif kind == "desynchold" and k == "1":
            d = e.get("desyncStep")
            if d not in self.desyncs:
                self.desyncs[d] = self.stage
                self.stretch += 1
                say("world desync at step %s against PC %s (%s; %d in this stretch)" % (d, e.get("pc"), self.stage, self.stretch))
        elif kind == "trace" and "desync" in e:
            self.traces.setdefault(e["desync"], set()).add(k)
        elif kind in ("cmd", "resynced", "done", "desyncexit", "crash", "mismatch"):
            say("game", k, json.dumps(e)[:200])

    def tick(self):
        now = time.time()
        if self.stretch > MAX_DESYNCS and self.stage != "ending":
            say("more than %d desyncs in one stretch: the run ends" % MAX_DESYNCS)
            self.end()
        elif self.stage == "first-run" and now - self.at > self.first_run:
            self.ask()
        elif self.stage in ("asked", "loading", "viewing") and self.view_state() in (3, 4):
            self.over(("cancelled: " if self.view_state() == 3 else "") + WHY.get(self.view[2], str(self.view[2])))
        elif self.stage == "asked" and self.view_state() in (1, 2):
            self.stage, self.at = "loading", now
        elif self.stage == "asked" and now - self.at > VIEW_START_S:
            self.over("never ran")
        elif self.stage == "loading" and self.view_state() == 2:
            self.stage, self.at = "viewing", now
        elif self.stage == "viewing" and now - self.at > self.plan[len(self.views)][1]:
            self.games.send("1", "closeview")
            self.stage = "closing"
        elif self.stage == "closing" and self.view_state() in (3, 4):
            self.over("held %d s" % self.plan[len(self.views)][1] if self.view[2] == 0 else WHY.get(self.view[2], str(self.view[2])))
        elif self.stage == "world" and now - self.at > self.plan[len(self.views) - 1][2]:
            if len(self.views) < len(self.plan):
                self.ask()
            else:
                self.end()

    def view_state(self):
        return self.view[1] if self.view else None

    def owner(self):
        return self.plan[min(len(self.views), len(self.plan) - 1)][0]

    def ask(self):
        self.games.send("1", "target %d" % self.owner(), "view")
        self.stage, self.at, self.view, self.stretch = "asked", time.time(), None, 0

    def over(self, verdict):
        self.views.append("%s's city: %s" % (OWNERS[self.owner()], verdict))
        say("VIEW %d: %s" % (len(self.views), self.views[-1]))
        self.stage, self.at, self.stretch = "world", time.time(), 0

    def end(self):
        end = self.step + 300
        for k, _ in FOLDERS:
            self.games.send(k, "endat %d" % end)
        self.stage = "ending"


def report(d):
    for i, v in enumerate(d.views, 1):
        say("VIEW %d: %s" % (i, v))
    if len(d.views) < len(d.plan):
        say("only %d of %d views ran" % (len(d.views), len(d.plan)))
    for step in sorted(d.desyncs):
        wrote = sorted(d.traces.get(step, ()))
        say("DESYNC at step %s (%s): trace-%s.txt from %s" % (step, d.desyncs[step], step,
            ", ".join(NAMES[k] for k in wrote) if wrote else "no game"))
    if not d.desyncs:
        say("no world desync")
    say("files to keep:")
    for k, _ in FOLDERS:
        data = twopc.INST / k / "songsofsyx"
        print("    %s: %s, %s, %s, %s" % (NAMES[k], data / "sosgsgmp" / "logs", data / "sosgsgmp" / "autopilot",
                                       data / "logs", data / "errorLogs"))
    print("    the runner's copy of the events: %s" % (twopc.RUNS / "view11"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", default=str(pathlib.Path(os.environ["USERPROFILE"]) / "AppData" / "Roaming" / "songsofsyx"),
                    help="the user's own data folder, which holds the campaign's records and the joiners' reports")
    ap.add_argument("--mods-from", default="", metavar="SOSDET_DIR", help="passed to twopc.py: the branch builds")
    ap.add_argument("--first-run", type=int, default=60, metavar="S", help="seconds of play before the first view")
    ap.add_argument("--adam-view", type=int, default=0, metavar="S",
                    help="first the host views Adam Smasher's city this many seconds, as at 01:55 (0: no)")
    args = ap.parse_args()
    if pathlib.Path(args.source).resolve() == twopc.REAL.resolve():
        sys.exit("view11: set APPDATA to a scratch folder; --source is the real one, read only")
    if args.mods_from:  # the data folders' mods too, not only those twopc copies at the resume
        twopc.MOD_DIRS["SOSDET"] = pathlib.Path(args.mods_from) / "build" / "mod" / "SOSDET"
        twopc.MOD_DIRS["SOSGSGMP"] = twopc.ROOT / "build" / "mod" / "SOSGSGMP"
    n = prep(pathlib.Path(args.source))
    say("campaign %s, save number %d: the three games resume it" % (CAMPAIGN, n))
    cmd = [sys.executable, str(twopc.ROOT / "tools" / "twopc.py"), "--here", "--local-joiner", "--resume", "--battles",
           "auto", "--speed", "5", "--steps", "2000000", "--tag", "view11", "--timeout",
           str(3600 + args.first_run + args.adam_view)]
    if args.mods_from:
        cmd += ["--mods-from", args.mods_from]
    games = Games()
    d = Drive(games, args.first_run, args.adam_view)
    p = subprocess.Popen(cmd)
    while p.poll() is None:
        time.sleep(1)
        for k, _ in FOLDERS:
            for e in games.events(k):
                d.event(k, e)
        d.tick()
    for k, _ in FOLDERS:
        for e in games.events(k):
            d.event(k, e)
    report(d)
    say("twopc.py exited with %d" % p.returncode)


if __name__ == "__main__":
    main()
