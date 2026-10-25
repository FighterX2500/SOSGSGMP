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


def start_remote(rdir, autopilot):
    """The game on the second PC, in its logged-on session (a game started over ssh would have no screen)."""
    script = ("@echo off\r\nset APPDATA=%s\r\nset SOSGSGMP_AUTOPILOT=%s\r\nset SteamAppId=%s\r\ncd /d %s\\game\r\n"
              "jre\\bin\\java.exe -cp SongsOfSyx.jar init.Main\r\n" % (rdir, autopilot, STEAM_APP_ID, REMOTE_ROOT))
    path = rdir + "\\start.cmd"
    remote_ps("[IO.File]::WriteAllText('%s', '%s')" % (path, script.replace("'", "''")))
    remote_ps("Register-ScheduledTask -TaskName 'SOSGSGMP-twopc' -Action (New-ScheduledTaskAction -Execute '%s')"
              " -Principal (New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive) -Force | Out-Null;"
              " Start-ScheduledTask -TaskName 'SOSGSGMP-twopc'" % path)


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
    ap.add_argument("--tag", default="twopc")
    ap.add_argument("--timeout", type=int, default=1800, help="seconds before the runner gives up")
    args = ap.parse_args()
    if not REMOTE or not HOST_ADDRESS:
        fail("set SOSGSGMP_REMOTE and SOSGSGMP_HOST_ADDRESS")
    run = RUNS / args.tag
    shutil.rmtree(run, ignore_errors=True)
    run.mkdir(parents=True)

    if game_running_here() or "java" in remote_ps("Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '%s\\game\\*' } | Select-Object -ExpandProperty Name" % REMOTE_ROOT):
        fail("a game still runs on this PC or the second one; close it first")
    local = data_folder(INST / "1", (1280, 720))
    remote_data = data_folder(INST / str(REMOTE_K), (1600, 900))
    log("data folders ready; sending the joiner's to the second PC")
    rdir = send_remote(remote_data.parent)
    common = "port=%d,steps=%d,net=%s" % (args.port, args.steps, args.net)
    # Steam finds the App ID here when the game isn't started by Steam
    host_env = dict(os.environ, APPDATA=str(local.parent), SteamAppId=STEAM_APP_ID,
                    SOSGSGMP_AUTOPILOT="role=host,name=Host,players=2,seed=%d,speed=%g,ondesync=%s,%s" % (args.seed, args.speed, args.on_desync, common))
    java = GAME / "jre" / "bin" / "java.exe"
    host = subprocess.Popen([str(java), "-cp", str(GAME / "SongsOfSyx.jar"), "init.Main"], cwd=str(GAME), env=host_env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    game_pid = relaunched(host)
    log("host started here (pid %d)" % game_pid)
    start_remote(rdir, "role=join,name=Joiner,address=%s,corrupt=%d,%s" % (HOST_ADDRESS, args.corrupt, common))
    log("joiner started on the second PC")

    seen = {"host": 0, "join": 0}
    done = {}
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        texts = {"host": (local / EVENTS).read_text(encoding="utf-8") if (local / EVENTS).exists() else "",
                 "join": remote_text(rdir + "\\songsofsyx\\" + str(EVENTS))}
        for who, text in texts.items():
            evs = events(text)
            for e in evs[seen[who]:]:
                log("%s %s" % (who, json.dumps(e)))
                if e["event"] in ("done", "closed", "desyncexit"):
                    done[who] = e
            seen[who] = len(evs)
            (run / ("%s-events.jsonl" % who)).write_text(text, encoding="utf-8")
        if len(done) == 2:
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

    evs = {w: events((run / ("%s-events.jsonl" % w)).read_text(encoding="utf-8")) for w in ("host", "join")}
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
        # the joiner may take the host's exit in the frame its hold appears, before logging the hold: its exit at the
        # host's hold step shows it held there
        hold = [e for e in evs["host"] if e["event"] == "desynchold"]
        ok = bool(hold) and hold[0]["pc"] == 1 and all(done[w]["event"] == "desyncexit" and done[w]["step"] == hold[0]["holdStep"]
                                                     for w in ("host", "join") if w in done) and len(done) == 2
    else:
        ok = desync_ok and all(done[w]["event"] == "done" and done[w].get("desync", -1) < 0 for w in done)             and not differ and common_steps
    log("RESULT: %s: %d common hash steps, %d differ%s" % ("PASS" if ok else "FAIL", len(common_steps), len(differ),
                                                          (", first at step %d" % differ[0]) if differ else ""))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
