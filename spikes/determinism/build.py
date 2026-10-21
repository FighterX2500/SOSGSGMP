"""SOS-11 spike: build a patched copy of the game for the determinism experiments.

Throwaway spike code (CLAUDE.md). Never writes to the game folder. Work folder: build/ (git-ignored).

    python build.py prepare [PATCH ...]   fresh copy of the source + apply patches (default: all, in name order)
    python build.py diff NAME FILE...     write patches/NAME.patch from my edits in build/src vs. the pristine source
    python build.py compile               ECJ build (ADR 0001) -> build/out/SongsOfSyx-rebuilt.jar

Patches carry two lines of context only, so they hold as little game source as possible.
"""
import os
import pathlib
import shutil
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
# SOS-23: SOSMP_BUILD=build-baseline keeps a second build (e.g. "prepare 05-timing 50-autoload") apart
BUILD = HERE / os.environ.get("SOSMP_BUILD", "build")
SRC = BUILD / "src"
PATCHES = HERE / "patches"
REBUILD = HERE.parent / "rebuild-check" / "rebuild.py"
ECJ = HERE.parent / "rebuild-check" / "tools" / "ecj-3.40.0.jar"


def pristine():
    g = os.environ.get("SOS_GAME_DIR")
    if not g:
        sys.exit("SOSMP-SPIKE: SOS_GAME_DIR is not set")
    return pathlib.Path(g) / "info" / "SongsOfSyx-sources"


def prepare(names):
    if SRC.exists():
        shutil.rmtree(SRC)
    shutil.copytree(pristine(), SRC, ignore=shutil.ignore_patterns(".codegraph", "bin", "jars", ".settings"))
    patches = [PATCHES / (n if n.endswith(".patch") else n + ".patch") for n in names] if names \
        else sorted(PATCHES.glob("*.patch"))
    for p in patches:
        r = subprocess.run(["patch", "-p1", "--forward", "--batch", "-d", str(SRC), "-i", str(p)],
                           capture_output=True, text=True)
        print("applied %s: %s" % (p.name, "ok" if r.returncode == 0 else "FAILED"))
        if r.returncode != 0:
            sys.exit(r.stdout + r.stderr)


def diff(name, files):
    PATCHES.mkdir(exist_ok=True)
    out = []
    for f in files:
        r = subprocess.run(["diff", "-u", "-N", "-U2", "--strip-trailing-cr", "--label", "a/" + f, "--label", "b/" + f,
                            str(pristine() / f), str(SRC / f)], capture_output=True, text=True)
        if r.returncode > 1:
            sys.exit(r.stderr)
        out.append(r.stdout)
    (PATCHES / (name + ".patch")).write_text("".join(out), encoding="utf-8", newline="\n")
    print("wrote patches/%s.patch" % name)


def compile_():
    r = subprocess.run([sys.executable, str(REBUILD), "--compiler", "ecj", "--ecj", str(ECJ),
                        "--src", str(SRC), "--out", str(BUILD / "out")])
    sys.exit(r.returncode)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "prepare":
        prepare(sys.argv[2:])
    elif cmd == "diff":
        diff(sys.argv[2], sys.argv[3:])
    elif cmd == "compile":
        compile_()
    else:
        sys.exit(__doc__)
