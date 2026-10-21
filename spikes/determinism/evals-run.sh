#!/bin/bash
# SOS-23 spike: the timing evals for the AI-on-the-host decision, one game copy at a time (copies share the CPU).
#   bash evals-run.sh <city save in the user's saves> <field battle save> [prefix=EV]
# Six runs of 10 minutes at speed 1: baseline (untouched game), host (general on its own thread, order log) and peer
# (general off, replaying that host's log), for the city attack (1,000 men) and the field battle (from the given save,
# so all three play the same battle). Then evals.py against each baseline.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
CITY=$1 BSAVE=$2 P=${3:-EV}
M="$HERE/build/matrix"
one() { bash "$HERE/matrix.sh" -n 1 -s 600 -v 1 "$@" | tail -3; }
one -C "$CITY" -I 1000 -B -p "${P}cityB"
one -C "$CITY" -I 1000 -A -p "${P}cityH"
one -C "$CITY" -I 1000 -P "$M/${P}cityH/${P}cityH-r1-ailog.bin" -p "${P}cityP"
one -S "$BSAVE" -B -p "${P}fieldB"
one -S "$BSAVE" -A -p "${P}fieldH"
one -S "$BSAVE" -P "$M/${P}fieldH/${P}fieldH-r1-ailog.bin" -p "${P}fieldP"
echo "=== city"; python "$HERE/evals.py" -b "$M/${P}cityB" "$M/${P}cityH" "$M/${P}cityP"
echo "=== field battle"; python "$HERE/evals.py" -b "$M/${P}fieldB" "$M/${P}fieldH" "$M/${P}fieldP"
