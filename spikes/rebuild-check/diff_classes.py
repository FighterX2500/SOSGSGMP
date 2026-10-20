"""Spike SOS-2: explain the classes that are not byte-identical after a rebuild.

For every differing class, writes javap -c -p output of both versions and a unified diff
to build/<compiler>/diff/. Prints one summary line per class.

Usage: python diff_classes.py [--compiler ecj|javac]
"""
import argparse
import collections
import difflib
import os
import pathlib
import re
import subprocess
import zipfile

HERE = pathlib.Path(__file__).resolve().parent


def javap(root, cls):
    exe = pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / "javap.exe"
    r = subprocess.run([str(exe), "-c", "-p", "-cp", str(root), cls],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    # Constant pool indices (#123) shift whenever anything shifts; hide them.
    text = re.sub(r"#\d+(,\s*\d+)?", "#", r.stdout)
    return [re.sub(r"\s*//.*", "", l) for l in text.splitlines()]


def member_blocks(lines):
    """Members (header + code) as a multiset, so a pure reordering compares equal."""
    body = "\n".join(lines).split("{", 1)[1].rsplit("}", 1)[0]
    return collections.Counter(b.strip() for b in re.split(r"\n\s*\n", body) if b.strip())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--compiler", default="ecj")
    args = ap.parse_args()
    out = HERE / "build" / args.compiler
    classes = out / "classes"
    diff = out / "diff"
    shipped_root = diff / "shipped"
    jar = pathlib.Path(os.environ["SOS_GAME_DIR"]) / "SongsOfSyx.jar"

    names = []
    with zipfile.ZipFile(jar) as z:
        for p in sorted(classes.rglob("*.class")):
            n = p.relative_to(classes).as_posix()
            data = z.read(n)
            if data != p.read_bytes():
                names.append(n)
                t = shipped_root / n
                t.parent.mkdir(parents=True, exist_ok=True)
                t.write_bytes(data)

    order_only = 0
    for n in names:
        cls = n[:-len(".class")].replace("/", ".")
        a, b = javap(shipped_root, cls), javap(classes, cls)
        d = list(difflib.unified_diff(a, b, "shipped/" + n, "rebuilt/" + n, lineterm="", n=2))
        f = diff / (n.replace("/", ".") + ".diff")
        f.write_text("\n".join(d) + "\n", encoding="utf-8")
        changed = sum(1 for l in d if l[:1] in "+-" and not l.startswith(("+++", "---")))
        same = member_blocks(a) == member_blocks(b)
        order_only += same
        print("%-75s lines-changed=%-4d %s" % (n, changed, "member order only" if same else "CODE DIFFERS"))
    print("%d classes differ, %d of them only in member order; diffs in %s"
          % (len(names), order_only, diff))


if __name__ == "__main__":
    main()
