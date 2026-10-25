"""SOSGSGMP playtest logs (SOS-63): one zip with this PC's and the second PC's logs, for a bug report.

    python tools\\collect.py                          this PC only
    python tools\\collect.py --remote user@address    and the second PC (ssh with the key below)

From each PC it takes the mod's playtest logs (%APPDATA%\\songsofsyx\\sosgsgmp-logs) and the game's logs and crash
files (%APPDATA%\\songsofsyx\\logs), changed in the last --hours, and on this PC the game folder's errorLogs too. The
second PC's data folder is its %APPDATA% unless --remote-appdata names another (a twopc.py run's is
C:\\SOSTest\\inst\\2). Test tooling only: nothing here is part of the mod.
"""
import argparse
import base64
import io
import os
import pathlib
import subprocess
import sys
import tarfile
import time
import zipfile

KEY = os.environ.get("SOSGSGMP_REMOTE_KEY", str(pathlib.Path.home() / ".ssh" / "sosdet_laptop"))
SSH = ["ssh", "-i", KEY, "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", "-o", "LogLevel=ERROR"]
FOLDERS = ("sosgsgmp-logs", "logs")  # under <APPDATA>\songsofsyx


def local_files(appdata, game, since):
    """(name in the zip, path) of this PC's files changed since `since`."""
    roots = [(f, pathlib.Path(appdata) / "songsofsyx" / f) for f in FOLDERS]
    if game:
        roots.append(("errorLogs", pathlib.Path(game) / "errorLogs"))
    for name, root in roots:
        if not root.is_dir():
            continue
        for p in sorted(root.rglob("*")):
            if p.is_file() and p.stat().st_mtime >= since:
                yield "this-pc/%s/%s" % (name, p.relative_to(root).as_posix()), p


def remote_ps(remote, script):
    """A PowerShell script on the second PC; its output (encoded, so nothing in it needs quoting)."""
    wrapped = "$ProgressPreference='SilentlyContinue'; $ErrorActionPreference='Stop'; %s" % script
    enc = base64.b64encode(wrapped.encode("utf-16-le")).decode()
    r = subprocess.run(SSH + [remote, "powershell -NoProfile -NonInteractive -EncodedCommand " + enc], capture_output=True)
    if r.returncode != 0:
        sys.exit("collect: on the second PC: %s" % (r.stderr or r.stdout).decode(errors="replace").strip()[-500:])
    return r.stdout


def remote_files(remote, appdata, since):
    """(name in the zip, bytes) of the second PC's files changed since `since`, as one tar over ssh."""
    if not appdata:
        appdata = remote_ps(remote, "$env:APPDATA").decode().strip()
    data = appdata + "\\songsofsyx"
    # copied first: the game may still be writing its log, and tar refuses a file in use
    tmp = "$env:TEMP\\sosgsgmp-collect"
    script = ("$t = \"%s\"; Remove-Item -Recurse -Force $t -ErrorAction SilentlyContinue; New-Item -ItemType Directory $t | Out-Null;"
              " $since = [DateTimeOffset]::FromUnixTimeSeconds(%d).LocalDateTime;"
              " foreach ($f in @(%s)) { $d = Join-Path '%s' $f; if (Test-Path $d) {"
              " Get-ChildItem $d -Recurse -File | Where-Object { $_.LastWriteTime -ge $since } | ForEach-Object {"
              " $to = Join-Path (Join-Path $t $f) $_.FullName.Substring($d.Length + 1);"
              " New-Item -ItemType Directory -Force (Split-Path $to) | Out-Null;"
              " $s = [IO.File]::Open($_.FullName, 'Open', 'Read', 'ReadWrite'); $o = [IO.File]::Create($to);"
              " try { $s.CopyTo($o) } finally { $s.Close(); $o.Close() } } } };"
              " & C:\\Windows\\System32\\tar.exe -cf - -C $t ." % (tmp, int(since), ",".join("'%s'" % f for f in FOLDERS), data))
    with tarfile.open(fileobj=io.BytesIO(remote_ps(remote, script))) as tar:
        for m in tar.getmembers():
            if m.isfile():
                yield "second-pc/" + m.name.lstrip("./"), tar.extractfile(m).read()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hours", type=float, default=24, help="only files changed in the last this many hours")
    ap.add_argument("--remote", default=os.environ.get("SOSGSGMP_REMOTE", ""), help="the second PC, user@address")
    ap.add_argument("--remote-appdata", default="", help="the second PC's data folder, if not its %%APPDATA%%")
    ap.add_argument("--appdata", default=os.environ.get("APPDATA", ""), help="this PC's data folder")
    ap.add_argument("--game", default=os.environ.get("SOS_GAME_DIR", ""), help="this PC's game folder, for errorLogs")
    ap.add_argument("--out", default="", help="the zip (default: playtest-logs-<time>.zip here)")
    a = ap.parse_args()
    since = time.time() - a.hours * 3600
    out = pathlib.Path(a.out or time.strftime("playtest-logs-%Y%m%d-%H%M%S.zip"))
    n = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, p in local_files(a.appdata, a.game, since):
            z.write(p, name)
            n += 1
        if a.remote:
            for name, data in remote_files(a.remote, a.remote_appdata, since):
                z.writestr(name, data)
                n += 1
    print("collect: %d files in %s" % (n, out.resolve()))
    for name in sorted(zipfile.ZipFile(out).namelist()):
        print("  " + name)


if __name__ == "__main__":
    main()
