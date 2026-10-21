"""SOS-11 spike: one-screen summary of a matrix.sh folder. Every run is compared with the first (copy 1, run 1).

    python summary.py FOLDER
"""
import csv
import json
import pathlib
import re
import sys


def runs(folder):
    tags = sorted({p.stem for p in folder.glob("*.csv") if not re.search(r"-(gen|step\d+)(-divs)?$", p.stem)},
                  key=lambda t: [int(x) for x in re.findall(r"\d+", t)])
    return tags


def events(folder, tag):
    out = {}
    log = folder / (tag + ".drive.log")
    for line in (log.read_text(encoding="utf-8").splitlines() if log.exists() else []):
        if line.startswith("{"):
            e = json.loads(line)
            out[e["event"]] = e
        elif line.startswith("TIMEOUT"):
            out["timeout"] = line
    return out


def steps(folder, tag):
    with open(folder / (tag + ".csv"), encoding="utf-8") as f:
        return list(csv.DictReader(f))


def first(a, b, key):
    n = min(len(a), len(b))
    return next((a[i]["step"] for i in range(n) if a[i][key] != b[i][key]), None)


def trace_diff(folder, ref, tag):
    pa, pb = folder / (ref + "-trace.txt"), folder / (tag + "-trace.txt")
    if not pa.exists() or not pb.exists():
        return None
    # drop the draw counter in front of each line: it only offsets when copies drew a different amount before the run
    a = [re.sub(r"^\d+: | @\d+:", "", l) for l in pa.read_text(encoding="utf-8").splitlines()]
    b = [re.sub(r"^\d+: | @\d+:", "", l) for l in pb.read_text(encoding="utf-8").splitlines()]
    for i in range(min(len(a), len(b))):
        if a[i] != b[i]:
            step = next((l for l in reversed(a[:i]) if l.startswith("---")), "--- before step 1")
            return "line %d (%s)\n      ref: %s\n      run: %s" % (i + 1, step, a[i][:400], b[i][:400])
    return "identical" if len(a) == len(b) else "same first %d lines, lengths %d vs %d" % (min(len(a), len(b)), len(a), len(b))


def main(folder):
    folder = pathlib.Path(folder)
    tags = runs(folder)
    if not tags:
        sys.exit("no runs in %s" % folder)
    ref = tags[0]
    ra = steps(folder, ref)
    print("reference: %s" % ref)
    print("%-12s %-17s %-17s %6s %11s %9s %9s %8s  %s" % ("run", "generated", "reloaded", "steps", "men", "ms/step", "path ms", "foreign", "vs reference"))
    for tag in tags:
        e = events(folder, tag)
        g, f = e.get("generated", {}), e.get("finished", {})
        rb = steps(folder, tag)
        if tag == ref:
            verdict = "-"
        else:
            div = first(ra, rb, "hash")
            draws = first(ra, rb, "simDraws")
            if div is None and len(ra) == len(rb):
                verdict = "IDENTICAL"
            elif div is None:
                verdict = "identical for %d steps (lengths differ)" % min(len(ra), len(rb))
            else:
                verdict = "diverges at step %s; draw count at step %s" % (div, draws)
        # SOS-23 AI on the host: the reference must be the host (mode 1); every other copy a peer (mode 2) that applied
        # exactly as many logged changes as the host. A folder of peers only (-P) has no host: all want mode 2.
        ai, rai = f.get("ai"), events(folder, ref).get("finished", {}).get("ai")
        if ai or rai:
            want = 1 if tag == ref and not (rai and rai.get("mode") == 2) else 2
            if not ai or ai.get("mode") != want:
                verdict += " AI MODE %s (want %d)" % (ai and ai.get("mode"), want)
            elif tag != ref and (not rai or ai.get("applied") != rai.get("applied")):
                verdict += " AI APPLIED %s vs host %s" % (ai.get("applied"), rai and rai.get("applied"))
            elif tag == ref and not ai.get("entries"):
                verdict += " AI LOG EMPTY"
        if "ABORT" in (folder / (tag + ".drive.log")).read_text(encoding="utf-8"):
            verdict += " ABORTED"
        h = e.get("hash", {})
        print("%-12s %-17s %-17s %6s %11s %9s %9s %8s  %s" % (
            tag, g.get("stateHash", "?"), h.get("cityHash", h.get("stateHash", "-")), f.get("steps", "?"),
            "%s/%s" % (f.get("playerMen", "?"), f.get("enemyMen", "?")), f.get("simMsPerStep", "?"),
            f.get("pathMsPerStep", "-"), f.get("foreignDraws", "?"), verdict + (" " + e["timeout"] if "timeout" in e else "")))
    for tag in tags[1:]:
        t = trace_diff(folder, ref, tag)
        if t:
            print("trace %s vs %s: %s" % (tag, ref, t))


if __name__ == "__main__":
    main(sys.argv[1])
