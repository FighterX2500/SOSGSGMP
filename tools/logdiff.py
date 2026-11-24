"""SOSGSGMP: compare two PCs' playtest logs (SOS-247): the lines every PC writes alike, and the desync reports.

    python tools\\logdiff.py <log A> <log B>            the "= " lines
    python tools\\logdiff.py <log A> <log B> --all      the "~ " lines too (kinds no twopc run has confirmed yet)
    python tools\\logdiff.py <log A> <log B> --context 5

A line every PC writes alike starts, after its time stamp, with a mark, a key and its text:
"21:04:05.678 = 123450 world: ...". The key is a step, or "d812" for a game day (what the log finds by polling).
Lines are grouped by key; at each key both logs must hold the same lines (in any order: two lines at one step may be
written in either order). Only the keys both logs cover are compared, from the later first key to the earlier last
one: a PC that started its log later or ran longer differs in nothing for that.
The first key that differs is printed with the lines around it in both files.

The desync reports ("this PC's engine found a desync at step N:" and the lines indented under it) are compared item
by item: each report line both PCs wrote is matched by its text before the last word, and the lines whose last word
(the hash) differs are printed.

Exit status: 0 when nothing differs, 1 when something does, 2 when a file can't be read.
Test tooling only: nothing here is part of the mod.
"""
import argparse
import re
import sys

LINE = re.compile(r"^\d\d:\d\d:\d\d\.\d{3} ([=~]) (d?\d+) (.*)$")
DESYNC = re.compile(r"^\d\d:\d\d:\d\d\.\d{3} this PC's engine found a desync at step (\d+)")
INDENTED = re.compile(r"^ {13}(.*)$")


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read().splitlines()
    except OSError as e:
        print("logdiff: can't read %s: %s" % (path, e), file=sys.stderr)
        raise SystemExit(2)


def same_lines(lines, marks):
    """{kind: {key number: [(text, line number)]}}, kind "step" or "day", for the lines with one of `marks`."""
    out = {"step": {}, "day": {}}
    for n, line in enumerate(lines, 1):
        m = LINE.match(line)
        if not m or m.group(1) not in marks:
            continue
        key = m.group(2)
        kind, number = ("day", int(key[1:])) if key.startswith("d") else ("step", int(key))
        out[kind].setdefault(number, []).append((m.group(3), n))
    return out


def first_difference(a, b):
    """(kind, key, A's texts, B's texts) of the first key both logs cover whose lines differ, or None."""
    for kind in ("step", "day"):
        ka, kb = a[kind], b[kind]
        if not ka or not kb:
            continue
        first, last = max(min(ka), min(kb)), min(max(ka), max(kb))
        for key in sorted(set(ka) | set(kb)):
            if key < first:
                continue
            if key > last:
                break
            ta = sorted(t for t, _ in ka.get(key, []))
            tb = sorted(t for t, _ in kb.get(key, []))
            if ta != tb:
                return kind, key, ka.get(key, []), kb.get(key, [])
    return None


def around(lines, numbers, context):
    """The lines around the given line numbers, numbered."""
    if not numbers:
        return ["    (no such line)"]
    lo = max(1, min(numbers) - context)
    hi = min(len(lines), max(numbers) + context)
    return ["%s%6d  %s" % (">" if n in numbers else " ", n, lines[n - 1]) for n in range(lo, hi + 1)]


def reports(lines):
    """{step: [report lines]} of each desync report in the log."""
    out = {}
    current = None
    for line in lines:
        m = DESYNC.match(line)
        if m:
            current = out.setdefault(int(m.group(1)), [])
            continue
        if current is not None:
            i = INDENTED.match(line)
            if i:
                current.append(i.group(1).strip())
            else:
                current = None
    return out


def report_items(report):
    """{item: value}: a report line split before its last word."""
    items = {}
    for line in report:
        head, _, last = line.rpartition(" ")
        if head:
            items[head] = last
    return items


def compare_reports(ra, rb):
    """Lines saying which items of the reports at the same step differ."""
    out = []
    for step in sorted(set(ra) & set(rb)):
        ia, ib = report_items(ra[step]), report_items(rb[step])
        differ = [k for k in ia if k in ib and ia[k] != ib[k]]
        out.append("desync reports at step %d: %d items both have, %d differ" % (step, len(set(ia) & set(ib)), len(differ)))
        for k in differ:
            out.append("    %s: A %s, B %s" % (k, ia[k], ib[k]))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--all", action="store_true", help="also the '~ ' lines, kinds not confirmed equal yet")
    ap.add_argument("--context", type=int, default=3, help="lines shown around a difference")
    args = ap.parse_args(argv)
    la, lb = read(args.a), read(args.b)
    marks = "=~" if args.all else "="
    a, b = same_lines(la, marks), same_lines(lb, marks)
    counted = sum(len(v) for k in a.values() for v in k.values()), sum(len(v) for k in b.values() for v in k.values())
    status = 0
    d = first_difference(a, b)
    if d is None:
        print("logdiff: the lines every PC writes alike are the same (%d in A, %d in B, over the keys both cover)" % counted)
    else:
        status = 1
        kind, key, at, bt = d
        print("logdiff: the first difference is at %s %d" % (kind, key))
        print("A %s:" % args.a)
        print("\n".join(around(la, [n for _, n in at], args.context)))
        print("B %s:" % args.b)
        print("\n".join(around(lb, [n for _, n in bt], args.context)))
    diffs = compare_reports(reports(la), reports(lb))
    if diffs:
        print("\n".join(diffs))
        if any(not line.endswith(" 0 differ") for line in diffs if line.startswith("desync")):
            status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())
