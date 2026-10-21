#!/bin/bash
# SOS-11 spike: one command for a determinism test on several game copies at once.
#   bash matrix.sh [-n copies=4] [-m "fixedStep syncThreads syncPaths splitRnd"] [-r runs=1] [-s battleSeconds=180]
#                  [-v "speed per copy"] [-t traceSteps=0] [-d dumpSteps] [-p prefix=X] [-k keep copies open] [-b force build]
#                  [-L start from the reloaded battle save, re-seeded]
#                  [-G copy 1 generates once; every copy loads that battle save, re-seeded (the lockstep model)]
#                  [-C saveFile: SOS-23 city run; every copy loads that save (from the user's saves) as sosmp-city,
#                   holds the world still, re-seeds and runs the city for -s seconds]
#                  [-I men: with -C, an invasion of that many men starts at step 1]
#                  [-B: SOS-23 baseline: the untouched game (only patches 05-timing and 50-autoload), vanilla stepping]
#                  [-A: SOS-23 AI general on the host: copy 1 runs first as host (general on its own thread, order
#                   log); then copies 2..n replay that log with the general off]
#                  [-P orderLog: SOS-23 every copy replays this host order log with the general off (peer evals)]
#                  [-S battleSave: like -G, but every copy loads this battle save (-G keeps its own as battle.save)]
# Each copy gets its own data folder (build/inst/<k>/songsofsyx), so copies never share saves or results and the
# user's own settings are only read. Every copy plays the same battle (seed 12345, 2000 v 2000) from the same save,
# at its own game speed; all logs are compared with copy 1, run 1. Logs and summary.txt end up in build/matrix/<prefix>.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
export SOS_GAME_DIR="${SOS_GAME_DIR:-C:\\ClaudeFolder\\Songs of Syx}"
export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.12.101-hotspot}"
REAL="$APPDATA/songsofsyx"
SAVE=sosmp-det-base
N=4 MODE="0.016666666666666666 1 1 1" RUNS=1 SECS=180 SPEEDS="3 1 5 2 4 6" TRACE=0 DUMPS=- PREFIX=X KEEP=0 BUILD=0 RELOAD=0 SHARED=0 CITY= INVADE= BASE=0 AIHOST=0 AIPEER= BSAVE=
while getopts "n:m:r:s:v:t:d:p:C:I:P:S:kbLGBA" o; do
  case $o in n) N=$OPTARG;; m) MODE=$OPTARG;; r) RUNS=$OPTARG;; s) SECS=$OPTARG;; v) SPEEDS=$OPTARG;; t) TRACE=$OPTARG;;
    d) DUMPS=$OPTARG;; p) PREFIX=$OPTARG;; C) CITY=$OPTARG;; I) INVADE=$OPTARG;; k) KEEP=1;; b) BUILD=1;; L) RELOAD=1;; G) SHARED=1;; B) BASE=1;;
    A) AIHOST=1;; P) AIPEER=$OPTARG;; S) BSAVE=$OPTARG;; *) sed -n 2,6p "$0"; exit 2;; esac
done
AILOG=ailog-host.bin  # SOS-23: the order log's name in each copy's sosmp-spike folder
[ -n "$CITY" ] && SAVE=sosmp-city
INST="$HERE/build/inst"; OUT="$HERE/build/matrix/$PREFIX"
JAR="$HERE/build/out/SongsOfSyx-rebuilt.jar"; HJAR="$HERE/harness/build/sosmp-det.jar"
log() { echo "[matrix $(date +%H:%M:%S)] $*"; }
ps() { powershell -NoProfile -ExecutionPolicy Bypass "$@"; }
stop_copies() {
  ps -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*SongsOfSyx-rebuilt.jar*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
}

# 1. Build only what changed since the last build.
if [ $BUILD = 1 ] || [ ! -f "$JAR" ] || [ -n "$(find "$HERE/patches" -newer "$JAR")" ]; then
  log "building the patched game"
  python "$HERE/build.py" prepare >/dev/null || exit 1
  python "$HERE/build.py" compile | tail -2
  [ -f "$JAR" ] || exit 1
fi
if [ $BUILD = 1 ] || [ ! -f "$HJAR" ] || [ -n "$(find "$HERE/harness/src" -newer "$HJAR")" ]; then
  log "building the harness"
  ps -File "$(cygpath -w "$HERE/harness/build.ps1")" >/dev/null || exit 1
fi
BJAR="$HERE/build-baseline/out/SongsOfSyx-rebuilt.jar"
if [ $BASE = 1 ] && { [ $BUILD = 1 ] || [ ! -f "$BJAR" ] || [ -n "$(find "$HERE/patches/05-timing.patch" "$HERE/patches/50-autoload.patch" -newer "$BJAR")" ]; }; then
  log "building the baseline game (untouched source + timing + autoload)"
  SOSMP_BUILD=build-baseline python "$HERE/build.py" prepare 05-timing 50-autoload >/dev/null || exit 1
  SOSMP_BUILD=build-baseline python "$HERE/build.py" compile | tail -2
  [ -f "$BJAR" ] || exit 1
fi
JARARG=(); [ $BASE = 1 ] && JARARG=(-Jar "$(cygpath -w "$BJAR")")

# 2. One data folder per copy: settings (windowed, muted, mod on, no auto-minimise), the mod, the base save.
stop_copies
rm -rf "$OUT"; mkdir -p "$OUT"
for k in $(seq 1 "$N"); do
  D="$INST/$k/songsofsyx"
  rm -rf "$INST/$k"
  mkdir -p "$D/settings" "$D/saves/saves" "$D/mods/SosMpDetSpike/V71/script" "$D/sosmp-spike"
  cp -r "$REAL/saves/profile" "$D/saves/"
  if [ -n "$CITY" ]; then cp "$REAL/saves/saves/$CITY" "$D/saves/saves/$SAVE.save" || exit 1
  else cp "$REAL/saves/saves/$SAVE.save" "$D/saves/saves/"; fi
  [ -d "$REAL/cache" ] && cp -r "$REAL/cache" "$D/"
  cp "$HERE/harness/_Info.txt" "$D/mods/SosMpDetSpike/"
  cp "$HJAR" "$D/mods/SosMpDetSpike/V71/script/"
  python - "$REAL/settings/LauncherSettings.txt" "$D/settings/LauncherSettings.txt" <<'EOF'
import re, sys
s = open(sys.argv[1], 'rb').read().decode('utf-8')
for key, val in (('SCREEN_MODE', '2'), ('WINDOW_WIDTH', '10'), ('WIDOW_HEIGHT', '10'), ('WIN_AUTO_ICONIFY', '0'),
                 ('VOLUME_MASTER', '0')):
    s, n = re.subn(r'(?m)^' + key + r': [^,\r\n]*,', key + ': ' + val + ',', s)
    assert n == 1, key
s, n = re.subn(r'MODS: \[[^\]]*\]', 'MODS: [\r\n\t"SosMpDetSpike",\r\n]', s)
assert n == 1, 'MODS'
open(sys.argv[2], 'wb').write(s.encode('utf-8'))
EOF
done

# 3. Start every copy; each loads the base save by itself (patch 50-autoload).
for k in $(seq 1 "$N"); do
  ps -File "$(cygpath -w "$HERE/run.ps1")" -AppData "$(cygpath -w "$INST/$k")" -Autoload "$SAVE" "${JARARG[@]}" || exit 1
  sleep 3
done
for k in $(seq 1 "$N"); do
  R="$INST/$k/songsofsyx/sosmp-spike/results.jsonl"
  for i in $(seq 1 300); do grep -qs '"loaded"' "$R" && break; sleep 2; done
  grep -qs '"loaded"' "$R" || { log "copy $k did not load"; [ $KEEP = 1 ] || stop_copies; exit 1; }
done
log "$N copies loaded; mode '$MODE', $RUNS run(s) of $SECS s, speeds '$SPEEDS', trace $TRACE, reload $RELOAD, shared battle $SHARED, city ${CITY:-no}, invasion ${INVADE:-no}, baseline $BASE, AI host $AIHOST, AI peer log ${AIPEER:-no}, afterTick ${AFTERTICK:-step}"

# 4. Runs: all copies at once, each at its own speed.
BATTLE=
if [ $SHARED = 1 ]; then
  log "copy 1 generates the battle"
  APPDATA="$(cygpath -w "$INST/1")" GEN_ONLY=1 bash "$HERE/drive.sh" "$MODE" > "$OUT/generate.drive.log" 2>&1
  B="$INST/1/songsofsyx/saves/saves/__battle.save"
  [ -f "$B" ] || { log "no battle save at $B"; [ $KEEP = 1 ] || stop_copies; exit 1; }
  log "battle save: $(du -k "$B" | cut -f1) KB"
  cp "$B" "$OUT/battle.save"  # SOS-23: for later runs of the same battle (-S)
  for k in $(seq 1 "$N"); do cp "$B" "$INST/$k/songsofsyx/saves/saves/sosmp-battle.save"; done
  BATTLE=sosmp-battle
elif [ -n "$BSAVE" ]; then
  [ -f "$BSAVE" ] || { log "no battle save at $BSAVE"; [ $KEEP = 1 ] || stop_copies; exit 1; }
  log "every copy loads the battle save $BSAVE"
  for k in $(seq 1 "$N"); do cp "$BSAVE" "$INST/$k/songsofsyx/saves/saves/sosmp-battle.save"; done
  BATTLE=sosmp-battle
fi
run_copy() {  # copy run [AI mode]
  local k=$1 r=$2 speed tag
  speed=$(echo $SPEEDS | cut -d' ' -f"$k"); speed=${speed:-3}
  tag="$PREFIX-c$k-r$r"
  AIMODE=${3:-} AILOG=$AILOG BASELINE=$BASE CITY=$CITY INVADE=$INVADE BATTLE=$BATTLE RELOAD=$RELOAD APPDATA="$(cygpath -w "$INST/$k")" bash "$HERE/drive.sh" "$MODE" "$tag" "$SECS" "$speed" "$DUMPS" "$TRACE" > "$OUT/$tag.drive.log" 2>&1
}
if [ -n "$AIPEER" ]; then
  [ -f "$AIPEER" ] || { log "no order log at $AIPEER"; [ $KEEP = 1 ] || stop_copies; exit 1; }
  for k in $(seq 1 "$N"); do cp "$AIPEER" "$INST/$k/songsofsyx/sosmp-spike/$AILOG"; done
fi
for r in $(seq 1 "$RUNS"); do
  if [ $AIHOST = 1 ]; then  # SOS-23: the host runs first and writes the order log; the peers replay it
    run_copy 1 "$r" 1
    H="$INST/1/songsofsyx/sosmp-spike/$AILOG"
    [ -f "$H" ] || { log "copy 1 wrote no order log"; [ $KEEP = 1 ] || stop_copies; exit 1; }
    log "host done; order log $(du -k "$H" | cut -f1) KB"
    cp "$H" "$OUT/$PREFIX-r$r-ailog.bin"
    for k in $(seq 2 "$N"); do cp "$H" "$INST/$k/songsofsyx/sosmp-spike/$AILOG"; run_copy "$k" "$r" 2 & done
  else
    for k in $(seq 1 "$N"); do run_copy "$k" "$r" "${AIPEER:+2}" & done
  fi
  wait
  log "run $r done"
done

# 5. Collect and compare.
for k in $(seq 1 "$N"); do
  cp "$INST/$k/songsofsyx/sosmp-spike/det/$PREFIX-"* "$OUT/" 2>/dev/null
  cp "$INST/$k/songsofsyx/saves/saves/"sosmp-step*-"$PREFIX"-* "$OUT/" 2>/dev/null
  cat "$INST/$k/songsofsyx/sosmp-spike/results.jsonl" >> "$OUT/results.jsonl"
done
[ $KEEP = 1 ] || stop_copies
SOSMP_DET="$(cygpath -w "$OUT")" python "$HERE/summary.py" "$(cygpath -w "$OUT")" | tee "$OUT/summary.txt"
