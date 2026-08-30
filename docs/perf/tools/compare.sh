#!/usr/bin/env bash
# T-001 - one row per run, for the report table. Consumes parse.sh output.
set -u
BASE="$1"; TOOLS=$(cd "$(dirname "$0")" && pwd)
F="%-5s %11s %8s %8s %8s %9s %9s %9s %8s %8s %8s %7s\n"
# shellcheck disable=SC2059
printf "$F" run stimulus cpu_bt% cpu_app% uflow/m ufbyte/m enqOvd/m deqOvd/m tsDisc/m delayW/m latMax passes
for d in "$BASE"/*/; do
  n=$(basename "$d"); [ -f "$d/t1.bluetooth_manager" ] || continue
  o=$("$TOOLS/parse.sh" "$d" 2>/dev/null)
  g() { echo "$o" | grep -m1 "^$1"; }
  stim=$(g 'condition:' | sed 's/.*stimulus=\([a-z]*\).*/\1/')
  cad=$(g 'condition:'  | sed 's/.*cadence_ms=\([0-9]*\).*/\1/')
  [ "$stim" = "none" ] || stim="$stim@$cad"
  cpubt=$(g  'cpu_bt_ms_per_min'  | sed 's/.*(\(.*\)% of one core)/\1/')
  cpuapp=$(g 'cpu_app_ms_per_min' | sed 's/.*(\(.*\)% of one core)/\1/')
  case "$cpuapp" in *absent*) cpuapp="--";; esac
  num() { echo "$o" | grep -m1 "^$1" | awk -v f="$2" '{print $f}' | sed 's|/min||'; }
  printf "$F" "$n" "$stim" "$cpubt" "$cpuapp" \
    "$(num 'underflow count:' 3)" "$(num 'underflow bytes:' 3)" \
    "$(num 'enqueue dev' 4)" "$(num 'dequeue dev overdue' 4)" \
    "$(num 'timestamp discont' 3)" "$(num 'DELAYED WRITES:' 3)" \
    "$(g 'loop latency std ms:' | sed 's/.*max: [^ ]* -> //')" \
    "$(g 'stimulus_passes=' | sed 's/stimulus_passes=\([0-9]*\).*/\1/')"
done
