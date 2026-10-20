"""SOS-9: classify every FACTIONS.player() call site by where it runs.

Throwaway planning script (CLAUDE.md). Reads the codegraph index of the game source
(read-only) and the source files, and writes a CSV of every call site with its
classification. Rerun after rebuilding the index:

    python classify_player_refs.py > ../../docs/plan/single-player-callsites.csv
    python classify_player_refs.py --with-code > <scratch>/callsites-with-code.csv   (local only)

Method:
1. Call sites: every line in the source matching FACTIONS.player() or its alias
   GAME.player() (grep, so the list
   is complete), mapped to the innermost method that contains it (codegraph nodes).
2. Reachability: breadth-first search over codegraph 'calls' edges (including its
   dispatch heuristics), plus nested anonymous/inner class methods of a reached
   method, from three sets of roots:
     WORLD  - update/afterTick of the host-run GameResources (WORLD, FACTIONS, EVENTS,
              NOBLES, RAIDING, TOURISM) and their resource lists
     CITY   - update/afterTick of SETT and the other isBattle resources
     UI     - VIEW update/render and every view.* class
3. Class of a call site:
     host-world   reached from WORLD roots only, or from WORLD and CITY
     city         reached from CITY roots only
     ui           in a view.* / util.gui package, or reached from UI roots only
     setup        init.*, menu.*, launcher.*, world generation (*Gen*, *Generator*)
     unreached    none of the above (static callbacks, listeners, save/load)
   The package is used as a tie-breaker; see 'reason' in the CSV.
"""
import collections
import csv
import os
import pathlib
import re
import sqlite3
import sys

SRC = pathlib.Path(os.environ.get("SOS_GAME_DIR", r"C:\ClaudeFolder\Songs of Syx")) / "info" / "SongsOfSyx-sources"
DB = SRC / ".codegraph" / "codegraph.db"
PATTERN = re.compile(r"(?:FACTIONS|GAME)\.player\(\)")
P = r"(?:FACTIONS|GAME)\.player\(\)"


def use_kind(code):
    """What the call site does with the player (a rough regex reading of one line)."""
    if re.search(r"DIP\.\w+\(.*" + P + r"|DIP\.get\(|DipStance|\.overlord\(|ROPINION", code):
        return "counterpart"
    if re.search(r"[!=]=\s*" + P + r"|" + P + r"\s*[!=]=", code):
        return "is-human"
    if re.search(P + r"\.(credits|level|titles|race|emissaries|trade|tech|offensivePower|bonus|res)\(", code):
        return "player-state"
    if re.search(P + r"\.(realm|armies|capitolRegion)\(", code):
        return "realm"
    if re.search(P + r"\.(seller|buyer)\(", code):
        return "goods"
    return "argument"

WORLD_ROOTS = [
    ("world/WORLD.java", "update"), ("world/WORLD.java", "afterTick"),
    ("game/faction/FACTIONS.java", "update"),
    ("game/events/EVENTS.java", "update"),
    ("game/nobility/NOBLES.java", "update"),
    ("game/raiding/RAIDING.java", "update"),
    ("game/tourism/TOURISM.java", "update"),
]
CITY_ROOTS = [
    ("settlement/main/SETT.java", "update"), ("settlement/main/SETT.java", "afterTick"),
    ("game/battle/Armies.java", "update"),
    ("game/time/TIME.java", "update"),
    ("game/time/Intervals.java", "update"),
    ("game/boosting/tmp/TmpBoosting.java", "update"),
    ("game/event/engine/EVENT_HANDLER.java", "update"),
    ("game/values/GCOUNTS.java", "update"),
]
UI_ROOTS = [("view/main/VIEW.java", "update"), ("view/main/VIEW.java", "render")]
SETUP_PKG = ("init/", "menu/", "launcher/", "cutscene/")


def main():
    db = sqlite3.connect("file:%s?mode=ro" % DB, uri=True)
    nodes = {}
    by_file = collections.defaultdict(list)
    for nid, kind, qn, fp, s, e in db.execute(
            "select id, kind, qualified_name, file_path, start_line, end_line from nodes "
            "where kind in ('method','function','class','interface','enum')"):
        nodes[nid] = (kind, qn, fp, s, e)
        if kind in ("method", "function"):
            by_file[fp].append(nid)

    calls = collections.defaultdict(set)
    for s, t in db.execute("select source, target from edges where kind='calls'"):
        calls[s].add(t)
    # Methods nested inside a method (anonymous classes): reached with their parent.
    nested = collections.defaultdict(set)
    for fp, ids in by_file.items():
        for a in ids:
            _, _, _, s1, e1 = nodes[a]
            for b in ids:
                if a != b:
                    _, _, _, s2, e2 = nodes[b]
                    if s1 < s2 and e2 <= e1:
                        nested[a].add(b)

    def roots(spec):
        out = set()
        for fp, name in spec:
            for nid in by_file.get(fp, []):
                if nodes[nid][1].endswith("::" + name):
                    out.add(nid)
        if not out:
            raise SystemExit("no roots found for %s" % (spec,))
        return out

    def reach(start):
        seen, todo = set(start), list(start)
        while todo:
            n = todo.pop()
            for m in calls.get(n, set()) | nested.get(n, set()):
                if m not in seen and m in nodes:
                    seen.add(m)
                    todo.append(m)
        return seen

    world = reach(roots(WORLD_ROOTS))
    city = reach(roots(CITY_ROOTS))
    ui = reach(roots(UI_ROOTS))
    print("# reachable methods: world=%d city=%d ui=%d" % (len(world), len(city), len(ui)), file=sys.stderr)

    def innermost(fp, line):
        best = None
        for nid in by_file.get(fp, []):
            _, _, _, s, e = nodes[nid]
            if s <= line <= e and (best is None or s >= nodes[best][3]):
                best = nid
        return best

    w = csv.writer(sys.stdout, lineterminator="\n")
    with_code = "--with-code" in sys.argv  # local use only: never commit game source
    w.writerow(["file", "line", "method", "class", "reason", "use"] + (["code"] if with_code else []))
    counts = collections.Counter()
    for f in sorted(SRC.rglob("*.java")):
        rel = f.relative_to(SRC).as_posix()
        for i, text in enumerate(f.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if not PATTERN.search(text):
                continue
            m = innermost(rel, i)
            qn = nodes[m][1] if m else "(field initializer or static block)"
            inw, inc, inu = m in world, m in city, m in ui
            if rel.startswith(("view/", "util/gui/")):
                cls, why = "ui", "package"
            elif rel.startswith(SETUP_PKG) or re.search(r"Gen|Generator|Initer", rel):
                cls, why = "setup", "package"
            elif inw:
                cls, why = "host-world", "reached from world" + (" and city" if inc else "")
            elif inc:
                cls, why = "city", "reached from city"
            elif inu:
                cls, why = "ui", "reached from UI only"
            elif rel.startswith("settlement/"):
                cls, why = "city", "package (unreached)"
            elif rel.startswith(("world/", "game/")):
                cls, why = "unreached", "package world/game, no path from a loop root"
            else:
                cls, why = "unreached", "no path from a loop root"
            counts[cls] += 1
            w.writerow([rel, i, qn, cls, why, use_kind(text)] + ([text.strip()[:160]] if with_code else []))
    print("# " + ", ".join("%s=%d" % kv for kv in counts.most_common()), file=sys.stderr)


if __name__ == "__main__":
    main()
