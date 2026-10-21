"""SOS-23 spike: timing evals of matrix.sh runs, lockstep build against the baseline (untouched game).

    python evals.py [-b BASELINE_FOLDER] RUN_FOLDER [RUN_FOLDER ...]

For every run with a <tag>-timing.txt (patch 05): the game update time per frame, normalised to 1/60 s of game time
(in the lockstep build that is the time per step), as median, p95, p99, p99.9, max and mean; steps over 100 ms per
10 minutes of game time, and steps over 1 s; plus CPU per game second by thread group (from the finished event), which
also counts the battle and path threads vanilla runs beside the update.
Frames that advanced no game time are left out of the percentiles but counted in the mean. At speed 1 a frame is about
one step; a frame with several steps is averaged over them.

With -b, every run is checked against the owner's pass rule (2026-10-21): p99.9 at most 20 ms, no step over 1 s, and
steps over 100 ms no more often than in the baseline (per 10 minutes of game time). The baseline is the first run in
BASELINE_FOLDER; give the matching baseline (city or field battle) for the runs.
"""
import csv
import json
import pathlib
import sys

STEP_MS = 1000 / 60
P999_MAX_MS = 20
STALL_MS = 100
MAX_MS = 1000


def pct(xs, p):
    if not xs:
        return float("nan")
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p / 100 * len(xs)))]


def finished(folder, tag):
    for line in (folder / (tag + ".drive.log")).read_text(encoding="utf-8").splitlines():
        if line.startswith("{") and '"finished"' in line:
            return json.loads(line)
    return {}


def run(folder, timing):
    tag = timing.name[: -len("-timing.txt")]
    per_step, total_ms, game_s = [], 0.0, 0.0
    with open(timing, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            ms = int(r["updateNanos"]) / 1e6
            gs = float(r["gameSeconds"])
            total_ms += ms
            game_s += gs
            if gs > 0:
                per_step.append(ms / (gs * 60))
    f = finished(folder, tag)
    cpu = {k: v / max(game_s, 1e-9) for k, v in f.get("cpuMs", {}).items()}
    stalls = sum(1 for x in per_step if x > STALL_MS)
    return {"tag": tag, "frames": len(per_step), "gameS": game_s, "p50": pct(per_step, 50), "p95": pct(per_step, 95),
            "p99": pct(per_step, 99), "p999": pct(per_step, 99.9), "max": max(per_step, default=float("nan")),
            "mean": total_ms / max(game_s * 60, 1e-9), "over": sum(1 for x in per_step if x > STEP_MS) / max(len(per_step), 1),
            "stalls": stalls, "stalls10": stalls * 600 / max(game_s, 1e-9), "over1s": sum(1 for x in per_step if x > MAX_MS),
            "cpu": cpu, "men": "%s/%s" % (f.get("playerMen", "?"), f.get("enemyMen", "?")), "ai": f.get("ai")}


def rows_of(folder):
    d = pathlib.Path(folder)
    return [run(d, t) for t in sorted(d.glob("*-timing.txt"))]


def verdict(r, base):
    fails = []
    if not r["p999"] <= P999_MAX_MS:
        fails.append("p99.9 %.1f > %d ms" % (r["p999"], P999_MAX_MS))
    if r["over1s"] > 0:
        fails.append("%d step(s) over 1 s" % r["over1s"])
    if r["stalls10"] > base["stalls10"] + 1e-9:
        fails.append(">100 ms %.1f vs baseline %.1f per 10 min" % (r["stalls10"], base["stalls10"]))
    return "PASS" if not fails else "FAIL: " + "; ".join(fails)


def main(args):
    base = None
    if args[:1] == ["-b"]:
        base_rows = rows_of(args[1])
        if not base_rows:
            sys.exit("no timing file in baseline folder " + args[1])
        base = base_rows[0]
        args = args[2:]
    rows = ([base] if base else []) + [r for d in args for r in rows_of(d)]
    print("ms of game update per 1/60 s of game time (lockstep: per step); budget for 1x = %.1f ms" % STEP_MS)
    print("%-16s %6s %6s %6s %6s %6s %7s %7s %6s %6s %5s %4s  %s" % (
        "run", "game s", "frames", "p50", "p95", "p99", "p99.9", "max", "mean", ">16.7", ">100", ">1s", "men"))
    for r in rows:
        print("%-16s %6.0f %6d %6.2f %6.2f %6.2f %7.2f %7.1f %6.2f %5.1f%% %5d %4d  %s" % (
            r["tag"], r["gameS"], r["frames"], r["p50"], r["p95"], r["p99"], r["p999"], r["max"], r["mean"],
            100 * r["over"], r["stalls"], r["over1s"], r["men"]))
    if base:
        print("\npass rule: p99.9 <= %d ms, no step over 1 s, steps over 100 ms per 10 min <= baseline (%s: %.1f)" % (
            P999_MAX_MS, base["tag"], base["stalls10"]))
        for r in rows[1:]:
            print("%-16s %s" % (r["tag"], verdict(r, base)))
    print("\nCPU ms per game second, by thread group (process = whole game, incl. rendering)")
    for r in rows:
        top = sorted(r["cpu"].items(), key=lambda kv: -kv[1])[:6]
        print("%-16s %s" % (r["tag"], "  ".join("%s=%.0f" % kv for kv in top)))
    ai = [r for r in rows if r["ai"]]
    if ai:
        print("\nAI general (SOS-23): mode 1 = host, 2 = peer; entries logged / applied, per kind")
        for r in ai:
            print("%-16s %s" % (r["tag"], json.dumps(r["ai"])))


if __name__ == "__main__":
    main(sys.argv[1:])
