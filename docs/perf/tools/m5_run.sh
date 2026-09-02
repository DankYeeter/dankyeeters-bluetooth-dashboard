#!/system/bin/sh
# T-011 / M-5 - long unattended cadence loop (device side).
# Captures only the A2DP State (Counts incl. dropped/dropouts/underflow) and
# A2DP LDAC State (quality mode, bitrate, adaptive-bitrate index/adjustments)
# blocks per sample, tagged with device wall-clock ns, appended to one file.
# Full dumpsys bluetooth_manager touches the BT stack identically every
# sample (Block-1 finding: costs CPU, not loss counters); this script only
# reduces what gets WRITTEN, not what gets read.
OUT=/data/local/tmp/btperf/m5
LOG="$OUT/series.log"
STOP="$OUT/stop"
mkdir -p "$OUT"
rm -f "$STOP"
: > "$LOG"
chmod 600 "$LOG" 2>/dev/null
i=0
while [ ! -e "$STOP" ]; do
  i=$((i+1))
  t=$(date +%s%N)
  echo "===SAMPLE $i t_ns=$t===" >> "$LOG"
  dumpsys bluetooth_manager 2>/dev/null | awk '
    /^A2DP State:/{a=1}
    a{print; if(/^$/ && a==1){a=0}}
    /^A2DP LDAC State:/{b=1}
    b{print; if(/^$/ && b==1){b=0}}
  ' >> "$LOG"
  sleep 1
done
echo "$i" > "$OUT/count"
chmod 600 "$OUT/count" "$LOG" 2>/dev/null
echo DONE > "$OUT/donemark"
chmod 600 "$OUT/donemark" 2>/dev/null
