"""SOSGSGMP batch 13 item 5 (SOS-229): autosaves and the shared pause, with three games on this PC (host H, joiner J,
local joiner L) from battles3's saved positions, driven by the autopilot. Every check prints PASS or FAIL.

    python tools\\pause5.py run          every part below, in order
    python tools\\pause5.py run PART     one part: autosave (with its two resumes), pvp, npc

The positions are battles3's (build\\battles3\\snapshot): BASE for the autosaves, WAR for the battle between players,
ALLIES for the battle against NPCs. Prepare them once in this checkout:

    python tools\\raid3.py prep
    python tools\\battles3.py prep BASE
    python tools\\battles3.py prep WAR
    python tools\\battles3.py prep ALLIES

Before each part every game's Auto-Saving is set to its shortest interval, 1 real minute, with 3 autosave files
(LauncherSettings AUTO_SAVE_TIME 10, AUTO_SAVE_FILES 3); only the host's counts.

- autosave (BASE, 5x):
  1. 2 minutes of play: autosaves come;
  2. J pauses for 2.5 minutes, then unpauses;
  3. H pauses for 2.5 minutes, and J unpauses it;
  4. H views J's city for 75 s (an autosave comes due and waits for the view); H pauses and closes the view; 90 s
     paused; unpause: the waiting autosave is ordered only after it;
  5. a 5-minute pause (the test request's 15 minutes, shortened: five intervals prove the same);
  6. H pauses and presses Save Campaign; 30 s paused; unpause: it is written after the unpause; the run ends;
  7. resume (the newest save is step 6's) and play 2.5 minutes; resume again (the newest is now an autosave) and play
     1 minute.
- pvp (WAR, 25x): H and J beat L's army (battles3 B1). In the battle J pauses: every game stops; H unpauses. When the
  battle ends, J presses pause as soon as its own city is back: once settled (about 2 s) it is sent, and every game
  pauses; H unpauses.
- npc (ALLIES, 25x): H fights the NPC army alone (battles3 A1 without allies; J's and L's armies go home first). In the
  battle J pauses on the world map: the chat says the pause waits, and the world runs on. H pauses and unpauses its own
  battle: nobody else stops. When the battle ends, every game pauses; J unpauses.

Checks, from the games' states (autopilot `state`) and the host's playtest log: no autosave ordered while paused; a
paused world doesn't step; every campaign save done on every game with no problem; every "unpaused the game" line is a
press of the driver (no load unpauses); the chat lines; and battles3's own checks (same hashes to the end, no crash
file, every loading window closed). Results also go to build\\battles3\\results.txt and logs to build\\battles3\\logs.

Set APPDATA to a scratch folder holding both mods (gradlew installMod in each repository) and the user's profile, as
for twopc.py. Test tooling only: nothing here is part of the mod. Never commit the saves.
"""
import argparse
import datetime
import pathlib
import re
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import twopc  # noqa: E402
import battles3 as b3  # noqa: E402

NAME = {"H": "Host", "J": "Joiner", "L": "Local"}  # the players' names in the chat
INTERVAL_S = 60
WAITS = "The game pauses once the battles against NPCs end"


def say(*a):
    print("[pause5 %s]" % time.strftime("%H:%M:%S"), *a, flush=True)


def autosave_settings():
    """Every game's Auto-Saving: 1 minute, 3 files."""
    for w in b3.WHO:
        p = twopc.INST / b3.GAME[w] / "songsofsyx" / "settings" / "LauncherSettings.txt"
        s = p.read_bytes().decode("utf-8")
        for key, value in (("AUTO_SAVE_TIME", "10"), ("AUTO_SAVE_FILES", "3")):
            s, n = re.subn(r"(?m)^" + key + r": [^,\r\n]*,", key + ": " + value + ",", s)
            if n == 0:
                s = s.replace("{", "{\r\n" + key + ": " + value + ",", 1)
        p.write_bytes(s.encode("utf-8"))


def speed(x):
    i = b3.TWOPC.index("--speed")
    b3.TWOPC[i + 1] = str(x)


def start(position, tag, timeout=1500):
    b3.restore(position)
    autosave_settings()
    run = b3.Run(tag, timeout)
    run.position = position
    run.wait(lambda: all(run.events(w, "go") for w in b3.WHO), 300, "every game playing")
    run.windows = []  # (from, to, what): real times every game was paused
    run.unpauses = 0  # the driver's unpause presses: every "unpaused the game" line must be one
    return run


# ------------------------------------------------------------------ the games' pause


def st(run, w):
    s = b3.state(run, w)
    m = re.search(r"step (\d+)", s)
    return (int(m.group(1)) if m else -1), bool(re.search(r"speed [\d.]+ paused", s))


def press(run, w):
    run.cmd(w, "pause")


def all_paused(run, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        if all(st(run, w)[1] for w in b3.WHO):
            return True
        run.sleep(0.5)
    return False


def stand_still(run, seconds, what):
    """Every game paused and its step the same `seconds` apart."""
    a = {w: st(run, w) for w in b3.WHO}
    run.sleep(seconds)
    b = {w: st(run, w) for w in b3.WHO}
    ok = all(a[w][1] and b[w][1] and a[w][0] == b[w][0] for w in b3.WHO)
    run.check(ok, "%s: every game paused and still for %d s: %s" % (what, seconds, {w: (a[w], b[w]) for w in b3.WHO}))


def runs_on(run, ws, seconds, what):
    """The games `ws` step on over `seconds`."""
    a = {w: st(run, w)[0] for w in ws}
    run.sleep(seconds)
    b = {w: st(run, w)[0] for w in ws}
    ok = all(b[w] > a[w] for w in ws)
    run.check(ok, "%s: %s step on over %d s: %s" % (what, ", ".join(ws), seconds, {w: (a[w], b[w]) for w in ws}))


def pause_window(run, presser, unpauser, seconds, what, during=None):
    """`presser` pauses; every game must stop; `during` runs while paused; after `seconds` `unpauser` unpauses."""
    press(run, presser)
    ok = all_paused(run)
    run.check(ok, "%s: %s's pause stops every game" % (what, presser))
    began = time.time()
    if not ok:
        return
    if during:
        during()
    rest = seconds - (time.time() - began)
    if rest > 12:
        run.sleep(rest - 12)
        stand_still(run, 10, what)
    else:
        run.sleep(max(0, rest))
    run.windows.append((began, time.time(), what))
    press(run, unpauser)
    run.unpauses += 1
    run.sleep(5)
    run.check(not any(st(run, w)[1] for w in b3.WHO), "%s: %s's unpause runs every game" % (what, unpauser))


# ------------------------------------------------------------------ the host's log


def stamped(run, w, pattern):
    """(real time, match) of the lines of w's playtest log matching `pattern`; the log has the time of day only."""
    now = datetime.datetime.now()
    out = []
    for line in run.log(w).splitlines():
        m = re.match(r"(\d\d):(\d\d):(\d\d)\.(\d{3}) (.*)$", line)
        if not m:
            continue
        p = re.search(pattern, m.group(5))
        if not p:
            continue
        t = now.replace(hour=int(m.group(1)), minute=int(m.group(2)), second=int(m.group(3)), microsecond=int(m.group(4)) * 1000)
        if t > now + datetime.timedelta(minutes=1):
            t -= datetime.timedelta(days=1)
        out.append((t.timestamp(), p))
    return out


def no_autosave_while_paused(run):
    orders = stamped(run, "H", r"save (\d+) \(AUTO\) ordered for step")
    for a, b, what in run.windows:
        inside = [m.group(1) for t, m in orders if a + 1 < t < b]
        run.check(not inside, "%s: no autosave ordered while paused (%.0f s)%s" % (what, b - a,
                  "" if not inside else ": saves " + ", ".join(inside)))


def saves_done(run):
    done = stamped(run, "H", r"save (\d+) done on every PC(: .*)?$")
    bad = [m.group(1) + m.group(2) for _, m in done if m.group(2)]
    run.check(done and not bad, "every campaign save done on every game with no problem (%d saves)%s" % (len(done),
              "" if not bad else ": " + "; ".join(bad)))
    return done


def unpauses_are_presses(run):
    lines = stamped(run, "H", r"notification: .* unpaused the game\.")
    run.check(len(lines) <= run.unpauses, "every 'unpaused the game' line is a press of the driver: %d lines, %d presses"
              % (len(lines), run.unpauses))


def chat_has(run, text, since=0.0):
    return any(text in e.get("text", "") and e.get("t", 1e15) / 1000.0 >= since - 1 for w in b3.WHO
               for e in run.events(w, "chatline"))


# ------------------------------------------------------------------ the parts


def part_autosave():
    speed(5)
    run = start("BASE", "p5-autosave", timeout=3600)
    ok = False
    saved = None
    try:
        run.sleep(2 * INTERVAL_S + 10)
        n = len(stamped(run, "H", r"save \d+ \(AUTO\) ordered"))
        run.check(n >= 1, "two minutes of play: %d autosaves ordered" % n)
        pause_window(run, "J", "J", 150, "the joiner's pause")
        pause_window(run, "H", "J", 150, "the host's pause, the joiner unpauses")
        # a waiting autosave: H views J's city past the interval
        run.cmd("H", "target 1")
        r = run.cmd("H", "view")
        say("view:", r)
        try:
            run.wait(lambda: run.events("H", "viewstate", state=2), 180, "H viewing J's city")
            run.sleep(INTERVAL_S + 15)
            waits = stamped(run, "H", r"a campaign save \(AUTO\) waits for")
            run.check(waits, "an autosave came due during the view and waits: %s" % [m.group(0) for _, m in waits][-1:])

            def close():
                run.cmd("H", "closeview")
            pause_window(run, "H", "H", 90, "a pause with an autosave waiting", during=close)
            unpaused = time.time()
            run.wait(lambda: [e for e in run.events("H", "viewstate") if e.get("state") in (3, 4)], 180, "the view over")
            run.wait(lambda: [t for t, _ in stamped(run, "H", r"save \d+ \(AUTO\) ordered") if t > unpaused - 5] or run.sleep(2),
                     180, "the waiting autosave after the unpause")
            run.check(True, "the waiting autosave was ordered after the unpause")
        except b3.Failed as e:
            run.check(False, "the view and its waiting autosave: %s" % e)
        pause_window(run, "J", "H", 300, "a 5-minute pause (15 in the request, shortened)")
        # Save Campaign while paused: ordered at once, written once the game runs to its step
        press(run, "H")
        run.check(all_paused(run), "the pause for Save Campaign stops every game")
        began = time.time()
        run.cmd("H", "save")
        run.sleep(30)
        run.windows.append((began, time.time(), "Save Campaign while paused"))
        ordered = [m.group(1) for t, m in stamped(run, "H", r"save (\d+) \(SAVE\) ordered") if t > began - 2]
        run.check(ordered, "Save Campaign during the pause is ordered: save %s" % ordered)
        written_early = [m.group(1) for t, m in stamped(run, "H", r"save (\d+) written") if began < t < time.time()]
        run.check(not written_early, "nothing is written while paused%s" % ("" if not written_early else ": " + str(written_early)))
        press(run, "J")
        run.unpauses += 1
        saved = ordered[0] if ordered else None
        if saved:
            run.wait(lambda: stamped(run, "H", r"save %s done on every PC" % saved) or run.sleep(2), 300, "save %s done" % saved)
            run.check(True, "save %s written after the unpause and done on every game" % saved)
        no_autosave_while_paused(run)
        saves_done(run)
        unpauses_are_presses(run)
        ok = run.end(after=3)  # within the interval: the newest record is the Save Campaign's
    except b3.Failed as e:
        run.check(False, str(e))
        run.stop()
    passed = b3.finish("P5-autosave", "autosaves and pauses", run, ok)
    from_save = resume("P5-resume-save", "resume Save Campaign's save " + str(saved), saved, 150)
    from_auto = resume("P5-resume-auto", "resume the newest autosave", None, INTERVAL_S)
    return passed and from_save and from_auto


def resume(name, title, number, seconds):
    """The last run's folders resume their newest campaign save (`number`, when known) and play `seconds`."""
    t0 = time.time() * 1000
    for w in b3.WHO:  # the kept folders hold the last session's events: a reader counting lines would skip the new ones
        (b3.Run.home(w) / "autopilot" / "events.jsonl").unlink(missing_ok=True)
    run = b3.Run("p5-" + name.lower(), 1500)
    run.windows, run.unpauses = [], 0
    ok = False
    try:
        run.wait(lambda: all(any(e.get("t", 0) >= t0 for e in run.events(w, "go")) for w in b3.WHO) or run.sleep(1), 300,
                 "every game playing after the resume")
        got = [(e.get("number"), e.get("file", "")) for e in run.events("H", "resume")]
        if number:
            run.check(bool(got) and str(got[-1][0]) == str(number), "the host resumed save %s: %s" % (number, got))
        else:  # an autosave's file is named "... Auto-<stamp>"
            run.check(bool(got) and " Auto-" in got[-1][1], "the host resumed an autosave: %s" % got)
        run.sleep(seconds)
        saves_done(run)
        ok = run.end(after=2)
    except b3.Failed as e:
        run.check(False, str(e))
        run.stop()
    return b3.finish(name, title, run, ok)


def part_pvp():
    speed(25)
    run = start("WAR", "p5-pvp")
    ok = False
    original = b3.back_in_cities

    def in_battle():
        pause_window(run, "J", "H", 20, "in the battle between players")
        run.check(chat_has(run, NAME["J"] + " paused the game."), "the chat says the joiner paused the battle")

    def back(r, timeout=600):
        # J presses pause the moment its own city is back; the press counts once the speed settled
        r.wait(lambda: "battle none" in b3.state(r, "J"), 600, "J's city back")
        back_at = time.time()
        press(r, "J")
        ok2 = all_paused(r, 15)
        r.check(ok2, "a pause pressed right after the battle's load is sent once settled: every game paused %.0f s after J's city came back"
                % (time.time() - back_at))
        r.check(chat_has(r, NAME["J"] + " paused the game.", back_at), "the chat says the joiner paused, after the load")
        if ok2:
            r.windows.append((time.time(), time.time() + 5, "after the battle's load"))
            r.sleep(5)
            press(r, "H")
            r.unpauses += 1
        original(r, timeout)
    b3.back_in_cities = back
    try:
        b3.pvp_battle(run, ["H"], ["J"], "win", in_battle=in_battle)
        no_autosave_while_paused(run)
        saves_done(run)
        unpauses_are_presses(run)
        ok = run.end()
    except b3.Failed as e:
        run.check(False, str(e))
        run.stop()
    finally:
        b3.back_in_cities = original
    return b3.finish("P5-pvp", "the shared pause in a battle between players and after its load", run, ok)


def part_npc():
    speed(25)
    run = start("ALLIES", "p5-npc")
    ok = False
    outage, original = b3.battle_outage, b3.back_in_cities
    held = {}

    def in_battle(r):
        outage(r)
        held["at"] = time.time()
        press(r, "J")  # on the world map, while H fights the NPC army
        r.sleep(5)
        r.check(chat_has(r, WAITS, held["at"]), "the chat says the joiner's pause waits for the battle's end")
        runs_on(r, ["J", "L"], 8, "the world runs on during the battle against NPCs")
        press(r, "H")  # H's own battle
        r.sleep(3)
        r.check(st(r, "H")[1], "the fighting player's pause pauses their battle")
        runs_on(r, ["J", "L"], 6, "the fighting player's pause stops nobody else")
        r.check(not chat_has(r, NAME["H"] + " paused the game.", held["at"]), "the fighting player's battle pause isn't the shared pause")
        press(r, "H")

    def back(r, timeout=600):
        r.wait(lambda: "battle none" in b3.state(r, "H"), 900, "H's battle over")
        ended = time.time()
        ok2 = all_paused(r, 15)
        r.check(ok2, "the waiting pause stops every game once the battle against NPCs ended (%.0f s after)" % (time.time() - ended))
        if ok2:
            r.windows.append((time.time(), time.time() + 5, "after the battle against NPCs"))
            r.sleep(5)
            press(r, "J")
            r.unpauses += 1
        original(r, timeout)
    b3.battle_outage, b3.back_in_cities = in_battle, back
    try:
        for w in ("J", "L"):  # their armies go home, so only H fights and nobody is asked Join
            cx, cy = b3.capital(run, w)
            b3.place(run, w, cx + 2, cy)
        b3.npc_battle(run, [])
        no_autosave_while_paused(run)
        saves_done(run)
        unpauses_are_presses(run)
        ok = run.end()
    except b3.Failed as e:
        run.check(False, str(e))
        run.stop()
    finally:
        b3.battle_outage, b3.back_in_cities = outage, original
    return b3.finish("P5-npc", "the shared pause during another player's battle against NPCs", run, ok)


PARTS = {"autosave": part_autosave, "pvp": part_pvp, "npc": part_npc}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=("run",))
    ap.add_argument("part", nargs="?", default="", choices=("",) + tuple(PARTS))
    args = ap.parse_args()
    results = {}
    for name, part in PARTS.items():
        if args.part and name != args.part:
            continue
        say("part", name)
        results[name] = part()
    for name, passed in results.items():
        say("PART %s: %s" % (name, "PASS" if passed else "FAIL"))
    say("RESULT:", "PASS" if all(results.values()) else "FAIL")
    sys.exit(0 if all(results.values()) else 1)


if __name__ == "__main__":
    main()
