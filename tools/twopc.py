"""SOSGSGMP two-PC test (game-over-network.md section 6): the mod plays a whole game by itself on this PC (host) and
on a second PC (joiner), through the menu, the map, the factions, the load, the landing party and N steps of play.

    set SOSGSGMP_REMOTE=user@address        the second PC (ssh with the key below; its game in C:\\SOSTest\\game)
    set SOSGSGMP_HOST_ADDRESS=26.x.x.x       this PC's address as the second PC reaches it
    python tools\\twopc.py --steps 3600 --tag T1

Each PC gets its own data folder (both mods on, its own settings), as SOSDET's rig makes them; the games run with
SOSGSGMP_AUTOPILOT set, write their milestones to sosgsgmp-autopilot\\events.jsonl and quit at the last step. The runner
prints both PCs' events as they come and says whether both reached the end with the same hashes. Test tooling only:
nothing here is part of the mod.
"""
import argparse
import base64
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
INST = ROOT / "build" / "twopc" / "inst"
RUNS = ROOT / "build" / "twopc" / "runs"
REAL = pathlib.Path(os.environ["APPDATA"]) / "songsofsyx"
GAME = pathlib.Path(os.environ["SOS_GAME_DIR"])
MODS = ("SOSDET", "SOSGSGMP")  # as installed (gradlew installMod in each repository)
REMOTE = os.environ.get("SOSGSGMP_REMOTE", "")
HOST_ADDRESS = os.environ.get("SOSGSGMP_HOST_ADDRESS", "")
KEY = os.environ.get("SOSGSGMP_REMOTE_KEY", str(pathlib.Path.home() / ".ssh" / "sosdet_laptop"))
SSH = ["ssh", "-i", KEY, "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", "-o", "LogLevel=ERROR"]
REMOTE_ROOT = r"C:\SOSTest"
REMOTE_K = 2  # the remote data folder C:\SOSTest\inst\2 (its cache stays between runs)
EVENTS = pathlib.PurePath("sosgsgmp-autopilot") / "events.jsonl"
STEAM_APP_ID = "1162750"  # Songs of Syx; Steam reads it from SteamAppId when the game is not started by Steam


def log(msg):
    print("[twopc %s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def fail(msg):
    log("FAIL: " + msg)
    sys.exit(1)


def remote_ps(script):
    """Runs a PowerShell script on the second PC; returns its output (encoded, so nothing in it needs quoting)."""
    wrapped = "$ProgressPreference='SilentlyContinue'; $ErrorActionPreference='Stop'; try { %s } catch { $_ | Out-String; exit 1 }; exit 0" % script
    enc = base64.b64encode(wrapped.encode("utf-16-le")).decode()
    r = subprocess.run(SSH + [REMOTE, "powershell -NoProfile -NonInteractive -EncodedCommand " + enc], capture_output=True, text=True)
    if r.returncode != 0:
        fail("on the second PC: %s" % (r.stderr or r.stdout).strip()[-500:])
    return r.stdout.replace("\r", "")


def data_folder(appdata, size):
    """A data folder like the user's: profile, cache, both mods on, a window of `size`."""
    shutil.rmtree(appdata, ignore_errors=True)
    if appdata.exists():
        fail("%s is still there: a game, or its error dialog, still holds files in it" % appdata)
    data = appdata / "songsofsyx"
    (data / "settings").mkdir(parents=True)
    (data / "saves" / "saves").mkdir(parents=True)
    shutil.copytree(REAL / "saves" / "profile", data / "saves" / "profile")
    # each instance makes its own player key: two PCs with the same key can't be in one lobby
    (data / "saves" / "profile" / "SOSGSGMP-lobby.properties").unlink(missing_ok=True)
    if (REAL / "cache").is_dir():
        shutil.copytree(REAL / "cache", data / "cache")
    for m in MODS:
        if not (REAL / "mods" / m).is_dir():
            fail("mod %s isn't installed in %s (gradlew installMod)" % (m, REAL / "mods"))
        shutil.copytree(REAL / "mods" / m, data / "mods" / m)
    s = (REAL / "settings" / "LauncherSettings.txt").read_bytes().decode("utf-8")
    for key, value in (("SCREEN_MODE", "2"), ("WINDOW_WIDTH", str(size[0])), ("WIDOW_HEIGHT", str(size[1])),
                       ("WIN_AUTO_ICONIFY", "0")):
        s, n = re.subn(r"(?m)^" + key + r": [^,\r\n]*,", key + ": " + value + ",", s)
        if n != 1:
            fail("LauncherSettings.txt has no single %s line" % key)
    s, n = re.subn(r"MODS: \[[^\]]*\]", "MODS: [\r\n" + "".join('\t"%s",\r\n' % m for m in MODS) + "]", s)
    if n != 1:
        fail("LauncherSettings.txt has no MODS list")
    (data / "settings" / "LauncherSettings.txt").write_bytes(s.encode("utf-8"))
    return data


def send_remote(appdata):
    """The prepared data folder to the second PC, keeping its cache there if it has one."""
    rdir = "%s\\inst\\%d" % (REMOTE_ROOT, REMOTE_K)
    cached = remote_ps("$d = '%s\\songsofsyx'; New-Item -ItemType Directory -Force $d | Out-Null;"
                       " Get-ChildItem $d -Exclude cache | Remove-Item -Recurse -Force; Test-Path $d\\cache\\*" % rdir).strip() == "True"
    tar = [r"C:\Windows\System32\tar.exe", "-cf", "-", "-C", str(appdata)] + (["--exclude", "songsofsyx/cache"] if cached else []) + ["songsofsyx"]
    pack = subprocess.Popen(tar, stdout=subprocess.PIPE)
    unpack = subprocess.run(SSH + [REMOTE, 'tar -xf - -C "%s"' % rdir], stdin=pack.stdout, capture_output=True, text=True)
    pack.stdout.close()
    if pack.wait() != 0 or unpack.returncode != 0:
        fail("sending the data folder: %s" % unpack.stderr.strip())
    return rdir


def refresh_mods(data, rdir):
    """A resume keeps both data folders, but plays the mods as installed now, on both PCs."""
    for m in MODS:
        shutil.rmtree(data / "mods" / m, ignore_errors=True)
        shutil.copytree(REAL / "mods" / m, data / "mods" / m)
    remote_ps("Remove-Item -Recurse -Force -ErrorAction SilentlyContinue %s"
              % ", ".join("'%s\\songsofsyx\\mods\\%s'" % (rdir, m) for m in MODS))
    tar = [r"C:\Windows\System32\tar.exe", "-cf", "-", "-C", str(data.parent)] + ["songsofsyx/mods/" + m for m in MODS]
    pack = subprocess.Popen(tar, stdout=subprocess.PIPE)
    unpack = subprocess.run(SSH + [REMOTE, 'tar -xf - -C "%s"' % rdir], stdin=pack.stdout, capture_output=True, text=True)
    pack.stdout.close()
    if pack.wait() != 0 or unpack.returncode != 0:
        fail("sending the mods: %s" % unpack.stderr.strip())


def start_remote(rdir, autopilot):
    """The game on the second PC, in its logged-on session (a game started over ssh would have no screen)."""
    script = ("@echo off\r\nset APPDATA=%s\r\nset SOSGSGMP_AUTOPILOT=%s\r\nset SteamAppId=%s\r\ncd /d %s\\game\r\n"
              "jre\\bin\\java.exe -cp SongsOfSyx.jar init.Main\r\n" % (rdir, autopilot, STEAM_APP_ID, REMOTE_ROOT))
    path = rdir + "\\start.cmd"
    remote_ps("[IO.File]::WriteAllText('%s', '%s')" % (path, script.replace("'", "''")))
    remote_ps("Register-ScheduledTask -TaskName 'SOSGSGMP-twopc' -Action (New-ScheduledTaskAction -Execute '%s')"
              " -Principal (New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive) -Force | Out-Null;"
              " Start-ScheduledTask -TaskName 'SOSGSGMP-twopc'" % path)


def start_here(data, autopilot):
    """A joiner's game on this PC, in its own data folder."""
    env = dict(os.environ, APPDATA=str(data.parent), SteamAppId=STEAM_APP_ID, SOSGSGMP_AUTOPILOT=autopilot)
    subprocess.Popen([str(GAME / "jre" / "bin" / "java.exe"), "-cp", str(GAME / "SongsOfSyx.jar"), "init.Main"], cwd=str(GAME),
                     env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def remote_text(path):
    # read sharing writes, so the game's appends never find the file locked (laptop T7 crashed on that)
    out = remote_ps("if (Test-Path '%s') { $s = [IO.File]::Open('%s', 'Open', 'Read', 'ReadWrite');"
                    " try { (New-Object IO.StreamReader($s)).ReadToEnd() } finally { $s.Close() } }" % (path, path))
    # a line the game is still appending has no end yet: leave it for the next read
    return out[:out.rfind("\n") + 1]


def relaunched(launcher):
    """The game's own process: init.Main starts the game in a new Java process with its memory settings and exits."""
    for _ in range(60):
        out = subprocess.run(["powershell", "-NoProfile", "-Command",
                              "(Get-CimInstance Win32_Process -Filter \"ParentProcessId=%d AND Name='java.exe'\").ProcessId" % launcher.pid],
                             capture_output=True, text=True).stdout.split()
        if out:
            return int(out[0])
        if launcher.poll() is not None and launcher.returncode != 0:
            fail("the host's launcher failed (exit %d)" % launcher.returncode)
        time.sleep(1)
    fail("the host's game didn't start")


def game_running_here():
    out = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.ExecutablePath -like '%s\\*' }).ProcessId"
                          % str(GAME).replace("/", "\\")], capture_output=True, text=True).stdout.split()
    return bool(out)


def running(pid):
    out = subprocess.run(["tasklist", "/FI", "PID eq %d" % pid, "/NH"], capture_output=True, text=True).stdout
    return str(pid) in out


def events(text):
    out = []
    for line in text.splitlines():
        line = line.strip()
        if line:
            out.append(json.loads(line))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=3600, help="play this many steps after Go, then both games quit")
    ap.add_argument("--seed", type=int, default=12345)
    ap.add_argument("--speed", type=float, default=0, help="the host sets this game speed after Go (0: the start's 1x)")
    ap.add_argument("--port", type=int, default=47510)
    ap.add_argument("--corrupt", type=int, default=-1, help="the joiner breaks its world at this step (a test desync)")
    ap.add_argument("--on-desync", choices=("resync", "exit"), default="resync", help="the host's choice at a desync")
    ap.add_argument("--net", choices=("ip", "steam"), default="ip",
                    help="steam: a Steam lobby; both PCs need Steam running, logged on to two accounts that are friends")
    ap.add_argument("--save", type=int, default=-1, help="the host saves the campaign at this step; both PCs must save it")
    ap.add_argument("--resume", action="store_true",
                    help="keep both data folders and host their newest campaign save instead of a new game")
    ap.add_argument("--tag", default="twopc")
    ap.add_argument("--here", action="store_true",
                    help="no second PC: the joiner runs on this PC too (data folder inst\\2, over loopback)")
    ap.add_argument("--local-joiner", action="store_true",
                    help="a second joiner on this PC (data folder inst\\3, over loopback): three PCs (SOS-65)")
    ap.add_argument("--no-joiner", action="store_true", help="with --resume: the second PC's joiner isn't started")
    ap.add_argument("--players", type=int, default=0, help="the host starts with this many players (default: every PC started)")
    ap.add_argument("--start-after", type=int, default=0, help="the host keeps the lobby open this many seconds first")
    ap.add_argument("--crash", default="", help="'remote:STEP' or 'local:STEP': that joiner's game stops dead at STEP (SOS-65)")
    ap.add_argument("--on-gone", choices=("continue", "return"), default="continue",
                    help="the host's choice when a player left: continue without them, or return to the last save")
    ap.add_argument("--timeout", type=int, default=1800, help="seconds before the runner gives up")
    args = ap.parse_args()
    if not args.here and (not REMOTE or not HOST_ADDRESS):
        fail("set SOSGSGMP_REMOTE and SOSGSGMP_HOST_ADDRESS")
    crash_who, _, crash_at = args.crash.partition(":")
    if args.crash and (crash_who not in ("remote", "local") or not crash_at.isdigit()
                       or crash_who == "local" and not args.local_joiner or crash_who == "remote" and args.no_joiner):
        fail("--crash is remote:STEP or local:STEP, for a joiner that is started")
    if args.no_joiner and not args.resume:
        fail("--no-joiner is for --resume")
    who_all = ["host"] + ([] if args.no_joiner else ["join"]) + (["local"] if args.local_joiner else [])
    players = args.players or len(who_all)
    run = RUNS / args.tag
    shutil.rmtree(run, ignore_errors=True)
    run.mkdir(parents=True)

    if game_running_here() or not args.here and "java" in remote_ps("Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '%s\\game\\*' } | Select-Object -ExpandProperty Name" % REMOTE_ROOT):
        fail("a game still runs on this PC or the second one; close it first")
    if args.resume:
        # the campaign saves, records and player keys of the last run stay; only its events go
        local = INST / "1" / "songsofsyx"
        local3 = INST / "3" / "songsofsyx"
        rdir = "%s\\inst\\%d" % (REMOTE_ROOT, REMOTE_K)
        if not (local / "sosgsgmp-campaigns").is_dir():
            fail("no campaign records in %s: run with --save first" % local)
        (local / EVENTS).unlink(missing_ok=True)
        (local3 / EVENTS).unlink(missing_ok=True)
        if args.local_joiner:
            for m in MODS:
                shutil.rmtree(local3 / "mods" / m, ignore_errors=True)
                shutil.copytree(REAL / "mods" / m, local3 / "mods" / m)
        local2 = INST / str(REMOTE_K) / "songsofsyx"
        if args.here:
            (local2 / EVENTS).unlink(missing_ok=True)
            for m in MODS:
                shutil.rmtree(local2 / "mods" / m, ignore_errors=True)
                shutil.copytree(REAL / "mods" / m, local2 / "mods" / m)
        else:
            remote_ps("Remove-Item -Force -ErrorAction SilentlyContinue '%s\\songsofsyx\\%s'" % (rdir, str(EVENTS)))
            refresh_mods(local, rdir)
        log("resuming in the last run's data folders")
    else:
        local = data_folder(INST / "1", (1280, 720))
        local3 = data_folder(INST / "3", (1024, 640)) if args.local_joiner else INST / "3" / "songsofsyx"
        remote_data = data_folder(INST / str(REMOTE_K), (1024, 640) if args.here else (1600, 900))
        local2 = remote_data
        log("data folders ready; sending the joiner's to the second PC")
        rdir = None if args.here else send_remote(remote_data.parent)
    common = "port=%d,steps=%d,net=%s" % (args.port, args.steps, args.net)
    # Steam finds the App ID here when the game isn't started by Steam
    host_env = dict(os.environ, APPDATA=str(local.parent), SteamAppId=STEAM_APP_ID,
                    SOSGSGMP_AUTOPILOT="role=host,name=Host,players=%d,seed=%d,speed=%g,ondesync=%s,save=%d,resume=%d,ongone=%s,startafter=%d,%s"
                    % (players, args.seed, args.speed, args.on_desync, args.save, 1 if args.resume else 0, args.on_gone, args.start_after, common))
    java = GAME / "jre" / "bin" / "java.exe"
    host = subprocess.Popen([str(java), "-cp", str(GAME / "SongsOfSyx.jar"), "init.Main"], cwd=str(GAME), env=host_env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    game_pid = relaunched(host)
    log("host started here (pid %d)" % game_pid)
    joiner = "role=join,name=Joiner,address=%s,corrupt=%d,crash=%s,%s" % (
        "127.0.0.1" if args.here else HOST_ADDRESS, args.corrupt, crash_at if crash_who == "remote" else "-1", common)
    if not args.no_joiner and args.here:
        time.sleep(15)  # the host's lobby is open before the joiner here starts looking for it
        start_here(local2, joiner)
        log("joiner started on this PC (--here)")
    elif not args.no_joiner:
        start_remote(rdir, joiner)
        log("joiner started on the second PC")
    if args.local_joiner:
        time.sleep(20)  # the host's lobby is open before the second game here starts looking for it
        start_here(local3, "role=join,name=Local,address=127.0.0.1,crash=%s,%s"
                   % (crash_at if crash_who == "local" else "-1", common))
        log("the second joiner started on this PC")

    seen = {w: 0 for w in who_all}
    done = {}
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        texts = {"host": (local / EVENTS).read_text(encoding="utf-8") if (local / EVENTS).exists() else ""}
        if "join" in who_all:
            texts["join"] = (((local2 / EVENTS).read_text(encoding="utf-8") if (local2 / EVENTS).exists() else "")
                             if args.here else remote_text(rdir + "\\songsofsyx\\" + str(EVENTS)))
        if "local" in who_all:
            texts["local"] = (local3 / EVENTS).read_text(encoding="utf-8") if (local3 / EVENTS).exists() else ""
        for who, text in texts.items():
            evs = events(text)
            for e in evs[seen[who]:]:
                log("%s %s" % (who, json.dumps(e)))
                if e["event"] in ("done", "closed", "desyncexit", "crash"):
                    done[who] = e
            seen[who] = len(evs)
            (run / ("%s-events.jsonl" % who)).write_text(text, encoding="utf-8")
        if len(done) == len(who_all):
            break
        if "host" not in done and not running(game_pid):
            # its last event may have landed after this round's read: once more before failing
            last = events((local / EVENTS).read_text(encoding="utf-8")) if (local / EVENTS).exists() else []
            if not any(e["event"] in ("done", "closed", "desyncexit") for e in last):
                fail("the host's game ended without finishing; see %s" % (local / "logs"))
            continue
        time.sleep(5)
    else:
        fail("timed out after %d s" % args.timeout)

    evs = {w: events((run / ("%s-events.jsonl" % w)).read_text(encoding="utf-8")) for w in who_all}
    if args.crash:
        sys.exit(0 if gone_verdict(args, evs, done, local, crash_who, int(crash_at)) else 1)
    if args.no_joiner or args.local_joiner or (args.resume and players < len(who_all)):
        sys.exit(0 if resume_without_verdict(evs, done, who_all, args.no_joiner) else 1)
    after = -1
    desync_ok = True
    if args.corrupt >= 0:
        # a forced desync: both PCs hold at the same step naming the joiner (PC 1), then the host's choice on both
        holds = {w: [e for e in evs[w] if e["event"] == "desynchold"] for w in evs}
        desync_ok = all(holds.values()) and holds["host"][0]["holdStep"] == holds["join"][0]["holdStep"]             and holds["host"][0]["pc"] == 1
        if desync_ok:
            after = holds["host"][0]["holdStep"]  # the corrupted world is only repaired from the resync on
            log("desync held on both PCs at step %d (desync at %d, PC %d)" % (after, holds["host"][0]["desyncStep"],
                                                                            holds["host"][0]["pc"]))
        want = "resynced" if args.on_desync == "resync" else "desyncexit"
        desync_ok = desync_ok and all(any(e["event"] == want for e in evs[w]) for w in evs)
    hashes = {w: {e["step"]: e["hash"] for e in evs[w] if e["event"] == "hash" and e["step"] > after} for w in evs}
    common_steps = sorted(set(hashes["host"]) & set(hashes["join"]))
    differ = [s for s in common_steps if hashes["host"][s] != hashes["join"][s]]
    if args.corrupt >= 0 and args.on_desync == "exit":
        # Save and exit (SOS-68): both PCs load the host's resync at the hold step, save the campaign with the same
        # world, and end
        ok = desync_ok and len(done) == 2 and all(done[w]["event"] == "desyncexit" and done[w]["outcome"] == ""
                                                  and done[w]["resynced"] == after for w in done)
    else:
        ok = desync_ok and all(done[w]["event"] == "done" and done[w].get("desync", -1) < 0 for w in done)             and not differ and common_steps
    if args.save >= 0:
        saved = {w: [e for e in evs[w] if e["event"] == "save" and e["status"].startswith("Campaign saved")] for w in evs}
        log("campaign saved on: %s" % ", ".join(w for w in saved if saved[w]))
        ok = ok and all(saved.values())
    log("RESULT: %s: %d common hash steps, %d differ%s" % ("PASS" if ok else "FAIL", len(common_steps), len(differ),
                                                          (", first at step %d" % differ[0]) if differ else ""))
    sys.exit(0 if ok else 1)


def hashes_after(evs, w, before=None):
    return {e["step"]: e["hash"] for e in evs[w] if e["event"] == "hash" and (before is None or e["step"] < before)}


def compare(evs, a, b, before=None):
    """Hash steps both of `a` and `b` reached (before `before`), and those that differ."""
    ha, hb = hashes_after(evs, a, before), hashes_after(evs, b, before)
    common = sorted(set(ha) & set(hb))
    return common, [s for s in common if ha[s] != hb[s]]


def gone_verdict(args, evs, done, local, crash_who, crash_at):
    """A joiner's game stopped dead (SOS-65): every other PC held for it at one step, and the host's choice ran."""
    gone_who = "join" if crash_who == "remote" else "local"
    stay = [w for w in evs if w not in ("host", gone_who)]
    ok = done.get(gone_who, {}).get("event") == "crash"
    holds = {w: [e for e in evs[w] if e["event"] == "gone"] for w in ["host"] + stay}
    ok = ok and all(holds.values()) and len({h[0]["holdStep"] for h in holds.values()}) == 1
    if ok:
        log("every other PC held at step %d for %s" % (holds["host"][0]["holdStep"], holds["host"][0]["names"]))
    for w in stay + [gone_who]:
        common, differ = compare(evs, "host", w, before=crash_at if w == gone_who else None)
        log("host and %s: %d common hash steps, %d differ" % (w, len(common), len(differ)))
        ok = ok and common and not differ
    if args.on_gone == "return":
        want = {"host": "You chose to return"}
        want.update({w: "The host chose to return" for w in stay})
        for w, start in want.items():
            reason = done.get(w, {}).get("reason", "")
            log("%s closed: %s" % (w, reason))
            ok = ok and done.get(w, {}).get("event") == "closed" and reason.startswith(start)
    else:
        retired = [e for e in evs["host"] if e["event"] == "retired"]
        saved = [e for e in evs["host"] if e["event"] == "save" and e["status"].startswith("Campaign saved")
                 and retired and e["step"] >= retired[0]["step"]]
        log("host: retired %s; campaign saved after it: %s" % (retired[:1], bool(saved)))
        ok = ok and retired and saved and all(done[w]["event"] == "done" for w in ["host"] + stay)
        for w in stay:
            ok = ok and any(e["event"] == "retired" for e in evs[w])
        record = newest_record(local)
        log("host's newest campaign record: %s" % (record.name if record else "none"))
        ok = ok and record is not None and "removed=true" in record.read_text(encoding="utf-8")
    log("RESULT: %s" % ("PASS" if ok else "FAIL"))
    return bool(ok)


def resume_without_verdict(evs, done, who_all, joiner_missing):
    """A resume without a player, or with one the host removed earlier: the host plays on; a removed joiner is refused."""
    ok = done.get("host", {}).get("event") == "done"
    for w in who_all[1:]:
        e = done.get(w, {})
        log("%s ended: %s%s" % (w, e.get("event"), " (%s)" % e.get("reason") if e.get("event") == "closed" else ""))
        if e.get("event") == "done":
            common, differ = compare(evs, "host", w)
            ok = ok and common and not differ
        else:
            ok = ok and e.get("event") == "closed" and "removed you" in e.get("reason", "")  # a removed player
    retired = [e for e in evs["host"] if e["event"] == "retired"]
    log("host: removed at the start: %s" % [e["slot"] for e in retired])
    if joiner_missing:
        ok = ok and any(e["slot"] == 1 and e["step"] <= 300 for e in retired)  # the joiner's slot, removed in the first world part
    log("RESULT: %s" % ("PASS" if ok else "FAIL"))
    return bool(ok)


def newest_record(data):
    folder = data / "sosgsgmp-campaigns"
    records = sorted((p for p in folder.rglob("*") if p.is_file()), key=lambda p: p.stat().st_mtime) if folder.is_dir() else []
    return records[-1] if records else None


if __name__ == "__main__":
    main()
