"""SOS-11 spike: compare two per-step hash logs (det/<tag>.csv) and report the first divergence.

    python compare.py TAG_A TAG_B
"""
import csv
import os
import pathlib
import sys

DET = pathlib.Path(os.environ["APPDATA"]) / "songsofsyx" / "sosmp-spike" / "det"


def load(tag):
    with open(DET / (tag + ".csv"), encoding="utf-8") as f:
        return list(csv.DictReader(f))


def main(a, b):
    ra, rb = load(a), load(b)
    n = min(len(ra), len(rb))
    first = next((i for i in range(n) if ra[i]["hash"] != rb[i]["hash"]), None)
    timing = next((i for i in range(n) if ra[i]["battleTime"] != rb[i]["battleTime"]), None)
    print("steps: %s=%d %s=%d" % (a, len(ra), b, len(rb)))
    print("first step with different battle time: %s" % ("none" if timing is None else ra[timing]["step"]))
    if first is None:
        print("IDENTICAL for all %d compared steps (battle time %s s); men at end %s/%s vs %s/%s"
              % (n, ra[n - 1]["battleTime"], ra[n - 1]["playerMen"], ra[n - 1]["enemyMen"],
                 rb[n - 1]["playerMen"], rb[n - 1]["enemyMen"]))
    else:
        r, s = ra[first], rb[first]
        print("FIRST DIVERGENCE at step %s (battle time %s s / %s s); simDraws %s vs %s; men %s/%s vs %s/%s"
              % (r["step"], r["battleTime"], s["battleTime"], r["simDraws"], s["simDraws"],
                 r["playerMen"], r["enemyMen"], s["playerMen"], s["enemyMen"]))
        same_draws = next((i for i in range(n) if ra[i]["simDraws"] != rb[i]["simDraws"]), None)
        print("first step with different simulation RNG draw count: %s" % ("none" if same_draws is None else ra[same_draws]["step"]))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
