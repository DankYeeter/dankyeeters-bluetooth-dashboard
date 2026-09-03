#!/system/bin/sh
# T-032 Phase 2 - long unattended cadence loop (device side), pinned-990 read-back.
#
# Same block-capture convention as m5_run.sh (A2DP State: + A2DP LDAC State:
# blocks per sample, 1 Hz, appended to one file) - covers dropped/dropouts/
# underflow/flushed/max-dropped, Frames per packet (total/max/ave), LDAC
# saved transmit queue length, quality mode/bitrate, and the Enqueue/Dequeue
# deviation counts, because all of those already live inside the two blocks
# m5_run.sh's awk selects.
#
# Adds a BQR watcher. IMPORTANT, found during dry-run (2026-09-03): the
# "BT Quality Report Events:" section does NOT behave like an append-only log.
# A populated queue (25 events, dated 09-02) was observed to read back as
# "Event queue is empty." after a handful of unrelated `dumpsys
# bluetooth_manager` calls in between - consistent with dumpsys draining the
# queue on every read, not with a bounded ring buffer that merely evicts the
# oldest entry. Not confirmed against source (that is a researcher-level
# claim, not asserted here as fact - see the T-032 report). Consequence for
# this script: it must be the ONLY reader of `dumpsys bluetooth_manager`
# while it runs, or its counts are meaningless; and because a drained queue
# always reads back empty on the next call, "snapshot when count changed"
# and "snapshot whenever count>0" are the same thing here - there is no
# stable nonzero state to diff against. The block is parsed by matching
# either "Event queue is empty." or an event line (optional leading "*",
# then "MM-DD HH:MM:SS ...") and stopping at the first non-matching line -
# NOT by blank-line boundary, because the empty-queue form has no trailing
# blank line before the next dumpsys section on this device/build.
OUT=/data/local/tmp/btperf/t032
LOG="$OUT/series.log"
STOP="$OUT/stop"
mkdir -p "$OUT"
rm -f "$STOP"
: > "$LOG"
chmod 600 "$LOG" 2>/dev/null
LAST_BQR=0
i=0
while [ ! -e "$STOP" ]; do
  i=$((i+1))
  t=$(date +%s%N)
  DUMP=$(dumpsys bluetooth_manager 2>/dev/null)
  echo "===SAMPLE $i t_ns=$t===" >> "$LOG"
  echo "$DUMP" | awk '
    /^A2DP State:/{a=1}
    a{print; if(/^$/ && a==1){a=0}}
    /^A2DP LDAC State:/{b=1}
    b{print; if(/^$/ && b==1){b=0}}
  ' >> "$LOG"
  BQR_COUNT=$(echo "$DUMP" | awk '
    /^BT Quality Report Events:/{f=1; next}
    f && /^Event queue is empty\.$/ {exit}
    f && /^\*?[ \t]*[0-9][0-9]-[0-9][0-9] /{c++; next}
    f {exit}
    END{print c+0}
  ')
  echo "bqr_event_count=$BQR_COUNT" >> "$LOG"
  if [ "$BQR_COUNT" -gt 0 ]; then
    echo "$DUMP" | awk '
      /^BT Quality Report Events:/{f=1; print; next}
      f && /^Event queue is empty\.$/ {print; exit}
      f && /^\*?[ \t]*[0-9][0-9]-[0-9][0-9] /{print; next}
      f {exit}
    ' > "$OUT/bqr_snapshot_$i.txt"
    chmod 600 "$OUT/bqr_snapshot_$i.txt" 2>/dev/null
    echo "bqr_snapshot_file=bqr_snapshot_$i.txt" >> "$LOG"
  fi
  LAST_BQR="$BQR_COUNT"
  sleep 1
done
echo "$i" > "$OUT/count"
chmod 600 "$OUT/count" "$LOG" 2>/dev/null
echo DONE > "$OUT/donemark"
chmod 600 "$OUT/donemark" 2>/dev/null
