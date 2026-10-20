"""Spike SOS-2: rebuild the untouched Songs of Syx source and compare it with the shipped jar.

Throwaway spike code (CLAUDE.md). Reads the game from SOS_GAME_DIR, never writes there.
Output: spikes/rebuild-check/build/ (git-ignored).

Usage: python rebuild.py [--compiler javac|ecj] [--ecj path/to/ecj.jar] [--src DIR] [--out DIR]
"""
import argparse
import collections
import json
import os
import pathlib
import shutil
import struct
import subprocess
import sys
import time
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / "build"

# Packages with source in the dump, and the class version the shipped jar uses for each.
UNIT_JAVA8 = {"snake2d": "snake2d", "integrations": "src/integrations"}
UNIT_JAVA16 = ["cutscene", "game", "init", "launcher", "menu", "script",
               "settlement", "util", "view", "world"]
SOURCE_PACKAGES = set(UNIT_JAVA8) | set(UNIT_JAVA16)
LIB_PREFIXES = ("org/", "com/", "club/")


def fail(msg):
    print("REBUILD-CHECK: " + msg, file=sys.stderr)
    sys.exit(1)


def game_dir():
    g = os.environ.get("SOS_GAME_DIR")
    if not g:
        fail("SOS_GAME_DIR is not set")
    g = pathlib.Path(g)
    if not (g / "SongsOfSyx.jar").is_file():
        fail("no SongsOfSyx.jar in SOS_GAME_DIR=%s" % g)
    return g


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    if not home:
        fail("JAVA_HOME is not set (need JDK 21+)")
    exe = pathlib.Path(home) / "bin" / (name + ".exe")
    if not exe.is_file():
        fail("missing %s" % exe)
    return str(exe)


def extract_libs(jar, dest):
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.startswith(LIB_PREFIXES) and not n.endswith("/"):
                z.extract(n, dest)


def java_files(root):
    return sorted(str(p) for p in root.rglob("*.java"))


def compile_unit(compiler, ecj, release, sources, classpath, dest, log):
    dest.mkdir(parents=True, exist_ok=True)
    argfile = dest.parent / (dest.name + ".sources.txt")
    argfile.write_text("\n".join('"%s"' % s.replace("\\", "/") for s in sources), encoding="utf-8")
    cp = os.pathsep.join(str(c) for c in classpath)
    if compiler == "javac":
        cmd = [java_tool("javac"), "--release", release, "-encoding", "UTF-8", "-g",
               "-proc:none", "-nowarn", "-Xlint:-options", "-Xmaxerrs", "100000",
               "-cp", cp, "-d", str(dest), "@" + str(argfile)]
    else:
        # -preserveAllLocals matches codegen.unusedLocal=preserve in the source's Eclipse settings.
        cmd = [java_tool("java"), "-jar", str(ecj), "--release", release, "-encoding", "UTF-8",
               "-g", "-preserveAllLocals", "-proceedOnError", "-nowarn", "-cp", cp,
               "-d", str(dest), "@" + str(argfile)]
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    secs = time.time() - t0
    log.write_text(r.stdout + r.stderr, encoding="utf-8")
    return r.returncode, secs


def copy_resources(src_root, dest):
    """Non-java files inside package folders (shader .txt files in snake2d)."""
    n = 0
    for p in src_root.rglob("*"):
        if p.is_file() and p.suffix != ".java":
            target = dest / p.relative_to(src_root.parent)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, target)
            n += 1
    return n


# ---------- class file parsing (structure only) ----------

ACC_SYNTHETIC = 0x1000
ACC_BRIDGE = 0x0040


def parse_class(b):
    def u1(o): return b[o]
    def u2(o): return struct.unpack_from(">H", b, o)[0]
    def u4(o): return struct.unpack_from(">I", b, o)[0]
    major = u2(6)
    count = u2(8)
    cp = [None] * count
    o = 10
    i = 1
    while i < count:
        tag = u1(o)
        if tag == 1:
            ln = u2(o + 1)
            cp[i] = ("utf8", b[o + 3:o + 3 + ln].decode("utf-8", "replace"))
            o += 3 + ln
        elif tag in (3, 4):
            o += 5
        elif tag in (5, 6):
            o += 9
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = ("ref", u2(o + 1))
            o += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            o += 5
        elif tag == 15:
            o += 4
        else:
            raise ValueError("bad cp tag %d" % tag)
        i += 1

    def utf(idx): return cp[idx][1]
    def cls(idx): return utf(cp[idx][1]) if idx else None

    access = u2(o)
    this = cls(u2(o + 2))
    sup = cls(u2(o + 4))
    n_if = u2(o + 6)
    o += 8
    ifs = sorted(cls(u2(o + 2 * k)) for k in range(n_if))
    o += 2 * n_if

    def members(o):
        n = u2(o)
        o += 2
        out = []
        for _ in range(n):
            acc, name, desc, na = u2(o), utf(u2(o + 2)), utf(u2(o + 4)), u2(o + 6)
            o += 8
            for _ in range(na):
                o += 6 + u4(o + 2)
            out.append((name, desc, acc))
        return out, o

    fields, o = members(o)
    methods, o = members(o)
    return {"major": major, "access": access & ~0x0020, "this": this, "super": sup,
            "interfaces": ifs, "fields": fields, "methods": methods}


def split_members(ms):
    """(declared, synthetic) member sets; synthetic = compiler-generated."""
    decl, syn = set(), set()
    for name, desc, acc in ms:
        if acc & (ACC_SYNTHETIC | ACC_BRIDGE) or name.startswith(("lambda$", "access$", "$SWITCH_TABLE$", "$assertionsDisabled", "$deserializeLambda$")):
            syn.add((name, desc))
        else:
            decl.add((name, desc, acc))
    return decl, syn


# ---------- compare ----------

def compare(shipped_jar, rebuilt_dir):
    shipped = {}
    with zipfile.ZipFile(shipped_jar) as z:
        for n in z.namelist():
            if n.endswith(".class") and n.split("/")[0] in SOURCE_PACKAGES:
                shipped[n] = z.read(n)
    rebuilt = {}
    for p in rebuilt_dir.rglob("*.class"):
        rebuilt[p.relative_to(rebuilt_dir).as_posix()] = p.read_bytes()

    res = collections.OrderedDict()
    res["shipped_classes"] = len(shipped)
    res["rebuilt_classes"] = len(rebuilt)
    res["missing_in_rebuild"] = sorted(set(shipped) - set(rebuilt))
    res["extra_in_rebuild"] = sorted(set(rebuilt) - set(shipped))
    common = sorted(set(shipped) & set(rebuilt))
    identical = 0
    version_mismatch, header_diff, declared_diff, synthetic_only = [], [], [], []
    by_pkg = collections.Counter()
    for n in common:
        a, b = shipped[n], rebuilt[n]
        if a == b:
            identical += 1
            by_pkg[n.split("/")[0]] += 1
            continue
        ca, cb = parse_class(a), parse_class(b)
        if ca["major"] != cb["major"]:
            version_mismatch.append((n, ca["major"], cb["major"]))
        if (ca["access"], ca["super"], ca["interfaces"]) != (cb["access"], cb["super"], cb["interfaces"]):
            header_diff.append(n)
        fa_d, fa_s = split_members(ca["fields"])
        fb_d, fb_s = split_members(cb["fields"])
        ma_d, ma_s = split_members(ca["methods"])
        mb_d, mb_s = split_members(cb["methods"])
        if fa_d != fb_d or ma_d != mb_d:
            declared_diff.append({
                "class": n,
                "only_shipped": sorted(map(str, (fa_d - fb_d) | (ma_d - mb_d))),
                "only_rebuilt": sorted(map(str, (fb_d - fa_d) | (mb_d - ma_d)))})
        elif fa_s != fb_s or ma_s != mb_s:
            synthetic_only.append(n)
    res["byte_identical"] = identical
    res["byte_identical_by_package"] = dict(by_pkg)
    res["compared"] = len(common)
    res["class_version_mismatch"] = version_mismatch
    res["class_header_diff"] = header_diff
    res["declared_member_diff"] = declared_diff
    res["synthetic_member_diff_only"] = len(synthetic_only)
    res["synthetic_member_diff_examples"] = synthetic_only[:20]
    return res


def assemble_jar(shipped_jar, rebuilt_dir, dest):
    """Shipped jar with every source-backed entry replaced by the rebuild."""
    with zipfile.ZipFile(shipped_jar) as zin, zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            top = info.filename.split("/")[0]
            if top in SOURCE_PACKAGES and not info.filename.endswith("/"):
                continue
            zout.writestr(info, zin.read(info.filename))
        for p in sorted(rebuilt_dir.rglob("*")):
            if p.is_file():
                zout.write(p, p.relative_to(rebuilt_dir).as_posix())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--compiler", choices=["javac", "ecj"], default="javac")
    ap.add_argument("--ecj")
    ap.add_argument("--src", help="source tree to build instead of the shipped one (for patched spikes)")
    ap.add_argument("--out", help="output folder instead of build/<compiler>")
    args = ap.parse_args()
    if args.compiler == "ecj" and not (args.ecj and pathlib.Path(args.ecj).is_file()):
        fail("--compiler ecj needs --ecj <ecj.jar>")

    game = game_dir()
    src = pathlib.Path(args.src) if args.src else game / "info" / "SongsOfSyx-sources"
    jar = game / "SongsOfSyx.jar"
    out = pathlib.Path(args.out) if args.out else OUT / args.compiler
    if out.exists():
        shutil.rmtree(out)
    libs, classes = out / "libcp", out / "classes"
    out.mkdir(parents=True)
    extract_libs(jar, libs)

    summary = collections.OrderedDict(compiler=args.compiler, game_dir=str(game))
    rc8 = {}
    for name, rel in UNIT_JAVA8.items():
        rc8[name] = compile_unit(args.compiler, args.ecj, "8", java_files(src / rel),
                                 [libs], classes, out / ("javac-%s.log" % name))
    srcs16 = [f for p in UNIT_JAVA16 for f in java_files(src / p)]
    rc16 = compile_unit(args.compiler, args.ecj, "16", srcs16, [libs, classes],
                        classes, out / "javac-game.log")
    summary["compile"] = {k: {"exit": v[0], "seconds": round(v[1], 1)} for k, v in rc8.items()}
    summary["compile"]["game"] = {"exit": rc16[0], "seconds": round(rc16[1], 1)}
    summary["resources_copied"] = copy_resources(src / "snake2d", classes)

    summary["compare"] = compare(jar, classes)
    assemble_jar(jar, classes, out / "SongsOfSyx-rebuilt.jar")
    (out / "report.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    c = summary["compare"]
    print(json.dumps(summary["compile"]))
    print("classes shipped=%d rebuilt=%d missing=%d extra=%d byte-identical=%d/%d"
          % (c["shipped_classes"], c["rebuilt_classes"], len(c["missing_in_rebuild"]),
             len(c["extra_in_rebuild"]), c["byte_identical"], c["compared"]))
    print("version-mismatch=%d header-diff=%d declared-member-diff=%d synthetic-only-diff=%d"
          % (len(c["class_version_mismatch"]), len(c["class_header_diff"]),
             len(c["declared_member_diff"]), c["synthetic_member_diff_only"]))
    print("report: %s" % (out / "report.json"))


if __name__ == "__main__":
    main()
