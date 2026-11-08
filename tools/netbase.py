"""SOS-176: today's behaviour under bad network conditions, as a baseline for the reconnect work (SOS-177 to SOS-180).

Runs battles3 scenarios one after another, each under one condition: the world (N1, N2), a battle between players
(B1) and a battle against NPCs with an ally (A1). The conditions are twopc's --netem presets and battles3's --outage.
Each case's report, logs and result line carry its label (build/battles3/results.txt, build/battles3/logs/<name><label>).

    python tools\\netbase.py            every case
    python tools\\netbase.py N1-300ms   the cases whose name starts so
    python tools\\netbase.py --list
    python tools\\netbase.py summary    a markdown table of the cases run so far
"""
import pathlib
import re
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPORTS = r"C:\ClaudeFolder\PTreport\2026-11-08-network"

# (label, scenario, battles3 arguments); outages need --netem on that game ("on": nothing added)
CASES = [
    ("-none", "N1", []),
    ("-150ms", "N1", ["--netem", "join=150ms"]),
    ("-300ms", "N1", ["--netem", "join=300ms"]),
    ("-loss2", "N1", ["--netem", "join=loss2"]),
    ("-loss10", "N1", ["--netem", "join=loss10"]),
    ("-all300ms", "N1", ["--netem", "host=300ms", "--netem", "join=300ms", "--netem", "local=300ms"]),
    ("-joutages", "N1", ["--netem", "join=on", "--outage", "J:3", "--outage", "J:15", "--outage", "J:60"]),
    ("-houtages", "N1", ["--netem", "host=on", "--outage", "H:15", "--outage", "H:60"]),
    ("-jcut", "N2", ["--netem", "join=on"]),
    ("-300ms", "B1", ["--netem", "join=300ms"]),
    ("-loss10", "A1", ["--netem", "join=loss10"]),
    ("-j15", "B1", ["--netem", "join=on", "--outage", "J:15"]),
    ("-j60", "B1", ["--netem", "join=on", "--outage", "J:60"]),
    ("-h15", "A1", ["--netem", "host=on", "--outage", "H:15"]),
]


LOGS = HERE.parent / "build" / "battles3" / "logs"
RESULTS = HERE.parent / "build" / "battles3" / "results.txt"
HELD = re.compile(r"freeze: held (\d+\.\d+) s at step (\d+)(?:, during (\w+))?")
AGREED = re.compile(r"speed agreed (\d+\.\d+)")


def case_summary(case):
    """One case's row from its games' playtest logs: desync, drops, holds over 1 s, H's agreed speed."""
    row = {"case": case, "result": "?", "desync": [], "dropped": [], "holds": {}, "speed": ""}
    for line in RESULTS.read_text(encoding="utf-8").splitlines() if RESULTS.exists() else []:
        if (" %s " % case) in line:
            row["result"] = line.split(" ", 3)[3]  # the last run of the case wins
    for w in ("H", "J", "L"):
        text = "".join(p.read_text(encoding="utf-8", errors="replace") for p in sorted((LOGS / case / w).glob("*.log")))
        holds = [(float(m.group(1)), int(m.group(2)), m.group(3) or "") for m in HELD.finditer(text) if float(m.group(1)) > 1.0]
        row["holds"][w] = holds
        if "desync hold:" in text or "found a desync" in text:
            row["desync"].append(w)
        pending = []  # a departure counts as a drop when play went on after it: at the end every game quits
        for line in text.splitlines():
            if re.search(r"network: .* left at step|session closed: ", line):
                pending.append("%s: %s" % (w, line.split(" ", 1)[1][:120]))
            elif re.search(r" step \d+ hash ", line):
                row["dropped"] += pending
                pending = []
        if "session closed: " in "".join(pending):  # this game's own session ended early
            row["dropped"] += [d for d in pending if "session closed" in d]
        if w == "H":
            agreed = [float(v) for v in AGREED.findall(text)]
            agreed = agreed[next((i for i, v in enumerate(agreed) if v > 1.0), len(agreed)):]
            row["speed"] = "%.1f to %.1f, last %.1f" % (min(agreed), max(agreed), agreed[-1]) if agreed else "?"
            link = re.findall(r"speed: speed (\d+\.\d+)x, the connection between the PCs can't keep up", text)
            row["speed"] += "; lowered for the connection %d times%s" % (len(link), " (to %s)" % ", ".join(link[:6]) if link else "")
    return row


def summary():
    """A markdown table of every case run so far, for docs/plan/network-baseline.md."""
    print("| Case | Result | Desync | Dropped | Holds over 1 s (H / J / L: count, longest; outside loading windows) | H's agreed speed |")
    print("|---|---|---|---|---|---|")
    for label, name, _ in CASES:
        case = name + label
        if not (LOGS / case).is_dir():
            continue
        r = case_summary(case)

        def holds(w):
            h = r["holds"][w]
            if not h:
                return "none"
            plain = [x for x in h if not x[2]]  # not during a loading window (view, battle)
            return "%d, %.1f s; %d outside loading (longest %.1f s)" % (
                len(h), max(x[0] for x in h), len(plain), max((x[0] for x in plain), default=0))
        print("| %s | %s | %s | %s | %s | %s |" % (case, r["result"].replace("|", "/"), ", ".join(r["desync"]) or "no",
                                              "<br>".join(r["dropped"]) or "no",
                                              "<br>".join("%s: %s" % (w, holds(w)) for w in ("H", "J", "L")), r["speed"]))


def main():
    want = sys.argv[1:]
    if want == ["summary"]:
        summary()
        return
    if want == ["--list"]:
        for label, name, args in CASES:
            print("%-14s %s" % (name + label, " ".join(args)))
        return
    for label, name, args in CASES:
        if want and not any((name + label).startswith(w) for w in want):
            continue
        print("netbase: %s%s %s" % (name, label, " ".join(args)), flush=True)
        subprocess.run([sys.executable, "-u", str(HERE / "battles3.py"), "run", name, "--label=" + label,
                        "--reports", REPORTS] + args)


if __name__ == "__main__":
    main()
