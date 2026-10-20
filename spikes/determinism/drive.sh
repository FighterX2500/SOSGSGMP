#!/bin/bash
# Usage: bash drive.sh "<fixedStep> <syncThreads> <syncPaths> <splitRnd>" <tag> [battleSeconds=180] [speed=3] [dumpSteps e.g. 1,2]
# Needs the patched game running with the SosMpDetSpike mod enabled and a save named sosmp-det-base.
# SOS-11 driver: every run = load the same save, set switches, generate from the seed, run.
D="$APPDATA/songsofsyx/sosmp-spike"; R="$D/results.jsonl"; SAVE="sosmp-det-base"
send() { local want="$2"; local n=$(wc -l < "$R"); echo "$1" > "$D/command.txt"
  for i in $(seq 1 900); do sleep 1; if tail -n +$((n+1)) "$R" | grep -qE "$want|\"error\""; then tail -n +$((n+1)) "$R" | grep -E "$want|\"error\"" | tail -1; return; fi; done; echo "TIMEOUT: $1"; }
run1() { local mode="$1" tag="$2" secs="${3:-180}" speed="${4:-3}" dumps="${5:-}"
  send "load $SAVE" '"loaded"' >/dev/null; sleep 5; send "mode $mode" '"mode"' >/dev/null
  send "generate 12345 2000" '"generated"'; send "run $secs $tag $speed $dumps" '"finished"'; }
run1 "$@"
