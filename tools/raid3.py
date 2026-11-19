"""SOSGSGMP's standard three-game raider test (SOS-158): three games on this PC (host, joiner, local joiner) play the
owner's 2026-11-04 campaign from just before the raiders invade the host's capital, at 25x.

    python tools\\raid3.py prep      once: the three data folders from campaign save 77, played to step 5600 and saved
                                     there; a copy of them is kept in build\\raid3\\snapshot
    python tools\\raid3.py run       each test: the copy back, then the raid:
        - the raiders invade the host's capital (about step 6190, some 400 steps after the start) and the host attacks them (a sally);
        - the battle loads on the host; the joiner watches it for 30 s and leaves;
        - the host charges, routs the raiders (a test change on the host's PC only, so only while nobody watches)
          and closes the battle; it accepts the spoils;
        - 30 s after the battle's end window closed on every game, every game ends at one step.
      It prints each game's loading windows and freezes from its playtest log, and passes when the three games kept the
      same hashes and every game opened and closed the battle start, view start, view end and battle end windows.
    python tools\\raid3.py ally      SOS-223, from the same copy: an NPC's help against raiders. The host's own raid runs
                                     as it will, unanswered. Game 2's player (a joiner) is raided through its weakest NPC
                                     neighbour that trades with it (the nearest free NPC becomes that player's vassal
                                     first when there is none), asks that neighbour's help, and the raider destroys it,
                                     its land going to rebels; the run ends 5 minutes after every game shows the
                                     neighbour's capital held by nobody. It passes when the three games kept the same
                                     hashes (no desync) and every game showed the capital held by nobody.

Set APPDATA to a folder holding both mods (gradlew installMod in each repository) and the user's profile, as for
twopc.py; the campaign's records come from the user's own data folder (--source). Test tooling only: nothing here is
part of the mod. Never commit the saves.
"""
import argparse
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import twopc  # noqa: E402

SNAPSHOT = twopc.ROOT / "build" / "raid3" / "snapshot"
CAMPAIGN = "84ed6e7b50de7fff"
NUMBER = "77"
# the save takes up to ~280 steps at 25x and must be written before the raid begins at about step 6190 (SOS-162)
SAVE_STEP = 5600
# each game's data folder, the player in it, and where the campaign keeps that player's records in the source folder
PLAYERS = [("1", "e8986bac0a03f6c2", None, (1280, 720)),
           ("2", "a268a7e661719cf7", "1-Adam Smith", (1024, 640)),
           ("3", "a2dbf723d4e95234", "2-Limitless Gregg", (1024, 640))]
TWOPC = [sys.executable, str(twopc.ROOT / "tools" / "twopc.py"), "--here", "--local-joiner", "--resume", "--battles",
         "auto", "--speed", "25"]
KINDS = ("BATTLE_START", "VIEW_START", "VIEW_END", "BATTLE_END")


def say(*a):
    print("[raid3 %s]" % time.strftime("%H:%M:%S"), *a, flush=True)


def first(*paths):
    return next((p for p in paths if p.exists()), paths[0])


def prep(source):
    """The three data folders from the campaign's save NUMBER, one player each, played to SAVE_STEP and saved."""
    lobby = first(source / "sosgsgmp" / "lobby.properties", source / "saves" / "profile" / "SOSGSGMP-lobby.properties")
    lobby = lobby.read_text(encoding="latin-1")
    for folder, key, reports, size in PLAYERS:
        data = twopc.data_folder(twopc.INST / folder, size)
        if reports is None:
            records = first(source / "sosgsgmp" / "campaigns", source / "sosgsgmp-campaigns")
        else:
            records = first(source / "sosgsgmp" / "reports", source / "sosgsgmp-reports") / CAMPAIGN / reports
        manifest = records / ("%s-%s.txt" % (CAMPAIGN, NUMBER))
        save = re.search(r"(?m)^file=(.+)$", manifest.read_text(encoding="utf-8")).group(1).strip()
        if reports is None:
            save_from = first(source / "sosgsgmp" / "saves" / save, source / "saves" / "saves" / save)
        else:
            save_from = records / save
        (data / "sosgsgmp-campaigns").mkdir(parents=True, exist_ok=True)  # the older layout: the mod moves it (SOS-155)
        shutil.copy2(manifest, data / "sosgsgmp-campaigns" / manifest.name)
        shutil.copy2(save_from, data / "saves" / "saves" / save)
        v = int(key, 16)
        props = re.sub(r"(?m)^playerKey=.*$", "playerKey=%d" % (v - (1 << 64) if v >= (1 << 63) else v), lobby)
        (data / "saves" / "profile" / "SOSGSGMP-lobby.properties").write_text(props, encoding="latin-1")
        say("game", folder, "from", save)
    # the save takes a while at 25x: the run goes on until every game has written it
    r = subprocess.run(TWOPC + ["--save", str(SAVE_STEP), "--steps", str(SAVE_STEP + 1800), "--tag", "raid3prep",
                                "--timeout", "900"])
    if r.returncode != 0:
        sys.exit("raid3: the run to the save failed")
    for folder, *_ in PLAYERS:
        records = sorted((twopc.INST / folder / "songsofsyx" / "sosgsgmp" / "campaigns").glob(CAMPAIGN + "-*.txt"),
                         key=lambda p: int(p.stem.rsplit("-", 1)[1]))
        if not records or records[-1].stem.endswith("-" + NUMBER):
            sys.exit("raid3: game %s wrote no campaign save at step %d" % (folder, SAVE_STEP))
        say("game", folder, "saved", records[-1].name)
    events = [json.loads(l) for l in (twopc.RUNS / "raid3prep" / "host-events.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    saved = min((e["step"] for e in events if e["event"] == "save" and e["status"].startswith("Campaign saved")), default=-1)
    raided = min((e["step"] for e in events if e["event"] == "chatline" and "invaded by Raiders" in e["text"]), default=-1)
    if 0 <= raided <= saved:
        sys.exit("raid3: the raid began at step %d, before the save was written at step %d: save earlier" % (raided, saved))
    shutil.rmtree(SNAPSHOT, ignore_errors=True)
    for folder, *_ in PLAYERS:
        shutil.copytree(twopc.INST / folder, SNAPSHOT / folder,
                        ignore=shutil.ignore_patterns("logs", "screenshots", "autopilot", "errorLogs"))
    say("saved at step %d; copy kept in %s" % (SAVE_STEP, SNAPSHOT))


class Games:
    """The three games' autopilot events and commands, and their playtest logs."""

    def __init__(self):
        self.seen = {k: 0 for k, *_ in PLAYERS}

    @staticmethod
    def home(k):
        return twopc.INST / k / "songsofsyx" / "sosgsgmp"

    def send(self, k, *cmds):
        cmd = self.home(k) / "autopilot" / "command.txt"
        for _ in range(100):
            if not cmd.exists():
                break
            time.sleep(0.2)
        tmp = cmd.with_suffix(".tmp")  # renamed in whole: the game once read a half-written, empty file (battles3 B5)
        tmp.write_text("\n".join(cmds) + "\n", encoding="utf-8")
        tmp.replace(cmd)
        say("sent to game", k, cmds)

    def events(self, k):
        """The game's events since the last call."""
        f = self.home(k) / "autopilot" / "events.jsonl"
        if not f.exists():
            return []
        lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
        new, self.seen[k] = lines[self.seen[k]:], len(lines)
        out = []
        for line in new:
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
        return out

    def log(self, k):
        return "".join(p.read_text(encoding="utf-8", errors="replace") for p in sorted((self.home(k) / "logs").glob("*.log")))


def drive(games, run):
    """The raid's stages, from the games' events; returns when the run ends."""
    stage, at, step, closed_at = "wait-invasion", time.time(), 0, 0.0
    while run.poll() is None:
        time.sleep(1)
        now = time.time()
        for k, *_ in PLAYERS:
            for e in games.events(k):
                kind = e.get("event")
                step = max(step, e.get("step", 0)) if k == "1" else step
                text = json.dumps(e).lower()
                if kind in ("cmd", "battle", "loadingshot", "desync") or "invaded by raiders" in text:
                    say("game", k, json.dumps(e)[:220])
                if k == "1" and stage == "wait-invasion" and "invaded by raiders" in text:
                    stage, at = "invaded", now
                if k == "1" and kind == "battle" and e.get("what") == "start" and stage == "attacked":
                    stage, at = "started", now
                if k == "2" and kind == "viewstate" and e.get("state") == 2 and stage == "view-asked":
                    stage, at = "watching", now
                if k == "2" and kind == "viewstate" and e.get("state") in (3, 4) and stage in ("view-asked", "watching", "leaving"):
                    stage, at = "view-over", now
                if k == "1" and kind == "battle" and e.get("what") == "close" and stage in ("charging", "routing"):
                    stage, at, closed_at = "closed", now, now
                if k == "1" and kind == "cmd" and e.get("action") == "raid" and stage == "closed" and "MVictory2" in e.get("result", ""):
                    stage, at = "victory", now
        if stage == "invaded" and now - at > 3:
            games.send("1", "raid", "invasionattack")
            stage, at = "attacked", now
        elif stage == "attacked" and now - at > 60:
            say("no battle 60 s after Attack: Attack again")
            games.send("1", "raid", "invasionattack")
            at = now
        elif stage == "started" and now - at > 8:
            games.send("2", "view")
            stage, at = "view-asked", now
        elif stage == "view-asked" and now - at > 90:
            say("the view never ran: on without it")
            stage, at = "view-over", now
        elif stage == "watching" and now - at > 30:
            games.send("2", "closeview")
            stage, at = "leaving", now
        elif stage == "view-over" and now - at > 15:
            games.send("1", "charge")
            stage, at = "charging", now
        elif stage == "charging" and now - at > 30:
            games.send("1", "rout")
            stage, at = "routing", now
        elif stage == "closed" and now - at > 5:
            games.send("1", "raid")
            at = now
        elif stage == "victory" and now - at > 2:
            games.send("1", "victoryaccept")
            stage, at = "accepted", now
        elif (stage == "accepted" or stage in ("closed", "victory") and now - closed_at > 90) and all(
                re.search(r"loading closed [^:]*: BATTLE_END", games.log(k)) for k, *_ in PLAYERS):
            say("every game is back in its own city: the run ends in 30 s")
            stage, at = "back", now
        elif stage == "back" and now - at > 30:
            end = step + 300
            for k, *_ in PLAYERS:
                games.send(k, "endat %d" % end)  # one command per line, its arguments after it
            stage = "ending"


def report(games):
    """Each game's windows and freezes; true when every game opened and closed every window of the raid."""
    ok = True
    for k, *_ in PLAYERS:
        lines = [l for l in games.log(k).splitlines() if re.search(r"loading (opened|closed)|freeze:", l)
                 and "WORLD_" not in l]
        say("game %s:" % k)
        for l in lines:
            print("    " + l[:200])
        for kind in KINDS:
            opened = any(("opened: %s" % kind) in l for l in lines)
            closed = any(re.search(r"closed [^:]*: %s" % kind, l) for l in lines)
            if not (opened and closed):
                say("game %s: no %s window %s" % (k, kind, "closed" if opened else "opened"))
                ok = False
    return ok


def drive_ally(games, run):
    """SOS-223: game 2's player asks an NPC neighbour's help against a raider, which destroys it; true when every game
    then showed the neighbour's capital held by nobody. The run ends 5 minutes after that, or at once on a failure."""
    stage, at, step, cap, vassal, desyncs, polled = "start", time.time(), 0, None, False, 0, 0.0
    nobody = set()

    def end(why):
        say(why)
        for k, *_ in PLAYERS:
            games.send(k, "endat %d" % (step + 300))
        return "ending"

    while run.poll() is None:
        time.sleep(1)
        now = time.time()
        for k, *_ in PLAYERS:
            for e in games.events(k):
                kind = e.get("event")
                if k == "1":
                    step = max(step, e.get("step", 0))
                if kind in ("cmd", "desync", "desynchold"):
                    say("game", k, json.dumps(e)[:260])
                if kind == "desync":
                    desyncs += 1
                if kind != "cmd":
                    continue
                action, result = e.get("action", ""), e.get("result", "")
                if k == "2" and action == "raidally" and stage == "asked":
                    m = re.search(r"capital region (\d+)", result)
                    if m:
                        cap, stage, at = m.group(1), "raiding", now
                    elif "no NPC neighbour" in result and not vassal:
                        games.send("1", "givevassal 1")  # a vassal trades with its overlord, so the raid can pass
                        vassal, stage, at = True, "start", now
                    else:
                        stage = end("no raid through a neighbour: " + result)
                if k == "2" and action == "raid" and stage == "checking":
                    if "state ALLY_HELP" in result:
                        games.send("2", "allyhelp")
                        stage, at = "helped", now
                    else:
                        stage = end("the raid through the neighbour didn't start: " + result)
                if cap and action == "regionat " + cap and "of nobody" in result:
                    nobody.add(k)
        if stage == "start" and step > 0 and now - at > 10:
            games.send("2", "raidally")
            stage, at = "asked", now
        elif stage == "asked" and now - at > 60:
            stage = end("no answer to raidally")
        elif stage == "raiding" and now - at > 5:
            games.send("2", "raid")  # its state: ALLY_HELP, the neighbour's Help message shown
            stage, at = "checking", now
        elif stage == "checking" and now - at > 60:
            stage = end("no answer to raid")
        elif stage == "helped" and len(nobody) == len(PLAYERS):
            say("every game shows region %s held by nobody: the run ends in 5 minutes" % cap)
            games.send("2", "raid")  # its last messages: MessDestroy
            stage, at = "destroyed", now
        elif stage == "helped" and now - at > 240:
            stage = end("the neighbour still stands on games %s" % sorted({k for k, *_ in PLAYERS} - nobody))
        elif stage == "helped" and now - polled > 10:
            polled = now
            for k, *_ in PLAYERS:
                if k not in nobody:
                    games.send(k, "regionat " + cap)
        elif stage == "destroyed" and now - at > 300:
            stage = end("5 minutes after the neighbour fell")
    say("desyncs seen: %d" % desyncs)
    return len(nobody) == len(PLAYERS) and desyncs == 0


def run(ally):
    if not SNAPSHOT.is_dir():
        sys.exit("raid3: no saved start in %s: run `raid3.py prep` first" % SNAPSHOT)
    for folder, *_ in PLAYERS:
        shutil.rmtree(twopc.INST / folder, ignore_errors=True)
        shutil.copytree(SNAPSHOT / folder, twopc.INST / folder)
    games = Games()
    p = subprocess.Popen(TWOPC + ["--steps", "400000" if ally else "200000", "--tag", "raid3ally" if ally else "raid3",
                                  "--timeout", "1500"])
    if ally:
        ok = drive_ally(games, p)
        ok = p.wait() == 0 and ok
    else:
        drive(games, p)
        ok = p.wait() == 0
        ok = report(games) and ok
    say("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=("prep", "run", "ally"))
    ap.add_argument("--source", default=str(pathlib.Path(os.environ["USERPROFILE"]) / "AppData" / "Roaming" / "songsofsyx"),
                    help="the user's own data folder, which holds the campaign's records")
    ap.add_argument("--netem", action="append", default=[], metavar="WHO=SPEC",
                    help="test network conditions, passed to twopc.py (SOS-175): WHO host, join or local")
    args = ap.parse_args()
    TWOPC.extend(x for item in args.netem for x in ("--netem", item))
    if args.what == "prep":
        prep(pathlib.Path(args.source))
    else:
        run(args.what == "ally")
