#!/bin/bash
# Usage: bash drive.sh "<fixedStep> <syncThreads> <syncPaths> <splitRnd>" <tag> [battleSeconds=180] [speed=3] [dumpSteps e.g. 1,2 or -] [traceSteps=0]
# Needs the patched game running with the SosMpDetSpike mod enabled and a save named sosmp-det-base.
# Talks to the copy whose data folder is %APPDATA% (matrix.sh sets APPDATA per copy).
# RELOAD=1: after generating, reload the battle save and re-seed (seed 777) before the run.
# GEN_ONLY=1: only load the base save and generate (writes the battle save __battle).
# BATTLE=<save>: skip generation; load that battle save, re-seed (seed 777) and run.
# SOS-11 driver: every run = load the same save, set switches, generate from the seed, run.
D="$APPDATA/songsofsyx/sosmp-spike"; R="$D/results.jsonl"; SAVE="sosmp-det-base"
send() { local want="$2"; local n=$(wc -l < "$R"); echo "$1" > "$D/command.txt"
  for i in $(seq 1 900); do sleep 1; if tail -n +$((n+1)) "$R" | grep -qE "$want|\"error\""; then tail -n +$((n+1)) "$R" | grep -E "$want|\"error\"" | tail -1; return; fi; done; echo "TIMEOUT: $1"; }
run1() { local mode="$1" tag="$2" secs="${3:-180}" speed="${4:-3}" dumps="${5:--}" trace="${6:-0}"
  send "load $SAVE" '"loaded"' >/dev/null; sleep 5; send "mode $mode" '"mode"' >/dev/null
  send "generate 12345 2000 $tag" '"generated"'
  if [ "${RELOAD:-0}" = 1 ]; then  # start from the freshly loaded battle save, re-seeded, as every lockstep peer would
    send reload '"loaded"' >/dev/null; sleep 5; send "seed 777" '"seeded"' >/dev/null; send hash '"hash"'; fi
  send "run $secs $tag $speed $dumps $trace" '"finished"'; }
gen_only() { send "load $SAVE" '"loaded"' >/dev/null; sleep 5; send "mode $1" '"mode"' >/dev/null
  send "generate 12345 2000 gen" '"generated"'; }
from_battle() { local mode="$1" tag="$2" secs="${3:-180}" speed="${4:-3}" dumps="${5:--}" trace="${6:-0}"
  send "mode $mode" '"mode"' >/dev/null; send "loadbattle $BATTLE" '"loaded"' >/dev/null; sleep 5
  send "seed 777" '"seeded"' >/dev/null; send hash '"hash"'
  send "run $secs $tag $speed $dumps $trace" '"finished"'; }
if [ "${GEN_ONLY:-0}" = 1 ]; then gen_only "$1"
elif [ -n "${BATTLE:-}" ]; then from_battle "$@"
else run1 "$@"; fi
