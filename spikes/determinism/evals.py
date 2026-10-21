"""SOS-23 spike: timing evals of matrix.sh runs, lockstep build against the baseline (untouched game).

    python evals.py RUN_FOLDER [RUN_FOLDER ...]

For every run with a <tag>-timing.txt (patch 05): the game update time per frame, normalised to 1/60 s of game time
(in the lockstep build that is the time per step), as median, p95, p99, max and mean; plus CPU per game second by
thread group (from the finished event), which also counts the battle and path threads vanilla runs beside the update.
Frames that advanced no game time are left out of the percentiles but counted in the mean.
"""
import csv
import json
import pathlib
import sys

STEP_MS = 1000 / 60


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
    return {"tag": tag, "frames": len(per_step), "gameS": game_s, "p50": pct(per_step, 50), "p95": pct(per_step, 95),
            "p99": pct(per_step, 99), "max": max(per_step, default=float("nan")),
            "mean": total_ms / max(game_s * 60, 1e-9), "over": sum(1 for x in per_step if x > STEP_MS) / max(len(per_step), 1),
            "cpu": cpu, "men": "%s/%s" % (f.get("playerMen", "?"), f.get("enemyMen", "?"))}


def main(folders):
    rows = []
    for d in map(pathlib.Path, folders):
        for t in sorted(d.glob("*-timing.txt")):
            rows.append(run(d, t))
    print("ms of game update per 1/60 s of game time (lockstep: per step); budget for 1x = %.1f ms" % STEP_MS)
    print("%-14s %7s %6s %7s %7s %7s %7s %7s %7s  %s" % ("run", "game s", "frames", "p50", "p95", "p99", "max", "mean", ">16.7", "men"))
    for r in rows:
        print("%-14s %7.0f %6d %7.2f %7.2f %7.2f %7.1f %7.2f %6.1f%%  %s" % (
            r["tag"], r["gameS"], r["frames"], r["p50"], r["p95"], r["p99"], r["max"], r["mean"], 100 * r["over"], r["men"]))
    print("\nCPU ms per game second, by thread group (process = whole game, incl. rendering)")
    for r in rows:
        top = sorted(r["cpu"].items(), key=lambda kv: -kv[1])[:6]
        print("%-14s %s" % (r["tag"], "  ".join("%s=%.0f" % kv for kv in top)))


if __name__ == "__main__":
    main(sys.argv[1:])
