"""SOS-11 spike: compare two per-soldier dumps (det/<tag>-step<N>.csv) field by field.

    python diff_dumps.py TAG_A TAG_B STEP
"""
import collections
import csv
import os
import pathlib
import sys

DET = pathlib.Path(os.environ["APPDATA"]) / "songsofsyx" / "sosmp-spike" / "det"


def load(tag, step):
    with open(DET / ("%s-step%s.csv" % (tag, step)), encoding="utf-8") as f:
        return {row["i"]: row for row in csv.DictReader(f)}


def main(a, b, step):
    ra, rb = load(a, step), load(b, step)
    print("soldiers: %d vs %d; only in A: %d, only in B: %d"
          % (len(ra), len(rb), len(ra.keys() - rb.keys()), len(rb.keys() - ra.keys())))
    fields = collections.Counter()
    divs = collections.Counter()
    examples = []
    for i in sorted(ra.keys() & rb.keys(), key=int):
        diff = [k for k in ra[i] if ra[i][k] != rb[i][k]]
        if diff:
            fields.update(diff)
            divs[ra[i]["div"]] += 1
            if len(examples) < 6:
                examples.append((i, {k: (ra[i][k], rb[i][k]) for k in diff}))
    print("soldiers that differ: %d" % sum(divs.values()))
    print("fields that differ:", dict(fields))
    print("by division:", dict(sorted(divs.items(), key=lambda kv: int(kv[0]))))
    for i, d in examples:
        print("  entity %s: %s" % (i, d))


if __name__ == "__main__":
    main(*sys.argv[1:4])
