#!/usr/bin/env bash
# T-001 measurement harness - host-side parser. Turns one captured run into
# per-minute deltas. Reads only the files snapshot.sh stored; touches no device.
#
# Usage: parse.sh <run-dir>
set -u
RUN="$1"; NAME=$(basename "$RUN")

extract() { # <mark>
  local m="$1" meta="$RUN/$1.meta" af="$RUN/$1.audio_flinger" bm="$RUN/$1.bluetooth_manager"
  grep -E '^[a-z_]+=' "$meta" | grep -v '_thread='
  # A2DP State: bounded to the block, so later sections that repeat these
  # labels for other codecs cannot overwrite the tx-queue's own counters.
  awk '
    /^A2DP State:/ {inb=1; next}
    inb && /^[A-Za-z]/ && !/^ / {inb=0}
    inb {
      v=$0; sub(/^[^:]*:[ ]*/,"",v); k=$0; sub(/[ ]*:.*$/,"",k); gsub(/^[ ]+/,"",k)
      gsub(/[^A-Za-z0-9]+/,"_",k); sub(/_+$/,"",k); gsub(/ /,"",v)
      print "a2dp_" tolower(k) "=" v
    }
    /^A2DP LDAC State:/ {inl=1; next}
    inl && /^[A-Za-z]/ && !/^ / {inl=0}
    inl && /LDAC transmission bitrate/ {v=$0; sub(/^[^:]*:[ ]*/,"",v); print "ldac_bitrate_kbps=" v}
    inl && /LDAC quality mode/ {v=$0; sub(/^[^:]*:[ ]*/,"",v); print "ldac_quality_mode=" v}
  ' "$bm"
  # The AudioFlinger output thread carrying the A2DP route. This is the
  # observer-free witness: reading it enters audioserver, never the BT stack.
  awk '
    /^Output thread .*name /{blk=""; isbt=0}
    {blk=blk"\n"$0}
    /Output devices: 0x80 \(AUDIO_DEVICE_OUT_BLUETOOTH_A2DP\)/{isbt=1}
    /^$/ && isbt {print blk; isbt=0; blk=""}
  ' "$af" | awk '
    /Total writes:/            {print "af_total_writes=" $3}
    /Delayed writes:/          {print "af_delayed_writes=" $3}
    /Frames written:/          {print "af_frames_written=" $3}
    /Timestamp stats:/ {
      for(i=1;i<=NF;i++){ if($i ~ /^n=/){v=$i; sub(/n=/,"",v); print "af_ts_n=" v}
                          if($i ~ /^disc=/){v=$i; sub(/disc=/,"",v); print "af_ts_disc=" v}
                          if($i ~ /^err=/){v=$i; sub(/err=/,"",v); print "af_ts_err=" v} } }
    /Threadloop write latency stats:/ {
      for(i=1;i<=NF;i++){ split($i,a,"="); if(a[1]=="std")print "af_loop_lat_std_ms=" a[2];
                          if(a[1]=="max")print "af_loop_lat_max_ms=" a[2];
                          if(a[1]=="ave")print "af_loop_lat_ave_ms=" a[2] } }
    /Normal mixer raw underrun counters:/ {
      for(i=1;i<=NF;i++){ split($i,a,"="); if(a[1]=="partial")print "af_ur_partial=" a[2];
                          if(a[1]=="empty")print "af_ur_empty=" a[2] } }
  '
}

v0=$(extract t0); v1=$(extract t1)
get() { echo "$2" | grep -m1 "^$1=" | cut -d= -f2-; }

W0=$(get wall_ns "$v0"); W1=$(get wall_ns "$v1")
SECS=$(awk -v a="$W0" -v b="$W1" 'BEGIN{printf "%.1f",(b-a)/1e9}')
MIN=$(awk -v s="$SECS" 'BEGIN{printf "%.4f", s/60}')

echo "### $NAME"
echo "condition: $(tr '\n' ' ' < "$RUN/condition")"
echo "elapsed_s=$SECS"
[ -f "$RUN/stimulus_ns.txt" ] && awk '{n++; s+=$1; if($1>m)m=$1} END{if(n)printf "stimulus_passes=%d  stimulus_ms_ave=%.1f  stimulus_ms_max=%.1f\n",n,s/n/1e6,m/1e6}' "$RUN/stimulus_ns.txt"

# CPU: CLK_TCK=100 on this device, so one tick is 10 ms.
for p in bt app audioserver bthal helper; do
  u0=$(get "${p}_utime_tck" "$v0"); s0=$(get "${p}_stime_tck" "$v0")
  u1=$(get "${p}_utime_tck" "$v1"); s1=$(get "${p}_stime_tck" "$v1")
  if [ -n "$u0" ] && [ -n "$u1" ]; then
    awk -v p="$p" -v a=$((u0+s0)) -v b=$((u1+s1)) -v m="$MIN" -v s="$SECS" \
      'BEGIN{printf "cpu_%s_ms_per_min=%.0f  (%.1f%% of one core)\n",p,(b-a)*10/m,(b-a)*10/(s*1000)*100}'
  else
    echo "cpu_${p}_ms_per_min=absent"
  fi
done

# Rates per minute for the counters that matter, plus the raw pair for audit.
rate() { # key label
  local a b; a=$(get "$1" "$v0"); b=$(get "$1" "$v1")
  [ -z "$a" ] || [ -z "$b" ] && { echo "$2: n/a"; return; }
  case "$a" in *"/"*)
      awk -v A="$a" -v B="$b" -v m="$MIN" -v L="$2" 'BEGIN{
        n=split(A,x,"/"); split(B,y,"/"); printf "%s: ",L
        for(i=1;i<=n;i++) printf "%s%.1f/min", (i>1?"  ":""), (y[i]-x[i])/m
        printf "   [%s -> %s]\n",A,B}' ;;
    *)  awk -v A="$a" -v B="$b" -v m="$MIN" -v L="$2" 'BEGIN{printf "%s: %.2f/min   [%s -> %s]\n",L,(B-A)/m,A,B}' ;;
  esac
}
echo "-- Bluetooth stack (btif_a2dp_source), read twice per run only --"
rate a2dp_counts_underflow            "underflow count"
rate a2dp_bytes_underflow             "underflow bytes"
rate a2dp_counts_flushed_dropped_dropouts "flushed/dropped/dropouts"
rate a2dp_counts_enqueue_dequeue_readbuf  "enqueue/dequeue/readbuf"
rate a2dp_enqueue_deviation_counts_overdue_premature "enqueue dev overdue/premature"
rate a2dp_dequeue_deviation_counts_overdue_premature "dequeue dev overdue/premature"
rate a2dp_dequeue_overdue_scheduling_time_in_ms_total_max_ave "dequeue overdue ms tot/max/ave"
echo "ldac: $(get ldac_bitrate_kbps "$v0") -> $(get ldac_bitrate_kbps "$v1") kbps, mode $(get ldac_quality_mode "$v1")"
echo "-- AudioFlinger A2DP output thread (observer-free: no BT locks) --"
rate af_total_writes    "total writes"
rate af_delayed_writes  "DELAYED WRITES"
rate af_ts_disc         "timestamp discontinuities"
rate af_ts_err          "timestamp errors"
rate af_ur_empty        "mixer underrun empty"
rate af_ur_partial      "mixer underrun partial"
echo "loop latency std ms: $(get af_loop_lat_std_ms "$v0") -> $(get af_loop_lat_std_ms "$v1")   max: $(get af_loop_lat_max_ms "$v0") -> $(get af_loop_lat_max_ms "$v1")"
echo "-- dump wall time (this run's own two snapshots) --"
echo "bluetooth_manager: $(awk -v a="$(get dump_bluetooth_manager_ns "$v0")" -v b="$(get dump_bluetooth_manager_ns "$v1")" 'BEGIN{printf "%.0f / %.0f ms",a/1e6,b/1e6}')"
echo "media.audio_flinger: $(awk -v a="$(get dump_audio_flinger_ns "$v0")" -v b="$(get dump_audio_flinger_ns "$v1")" 'BEGIN{printf "%.0f / %.0f ms",a/1e6,b/1e6}')"
# Busiest Bluetooth threads by CPU delta - identifies the encoder without
# assuming which one it is.
echo "-- busiest com.google.android.bluetooth threads (cpu_ms, run_delay_ms) --"
join -j1 <(grep '^bt_thread=' "$RUN/t0.meta" | sed 's/^bt_thread=//' | awk '{print $1"|"$2, $3, $4}' | sort) \
         <(grep '^bt_thread=' "$RUN/t1.meta" | sed 's/^bt_thread=//' | awk '{print $1"|"$2, $3, $4}' | sort) \
  | awk -v m="$MIN" '{c=($4-$2)/1e6; d=($5-$3)/1e6; if(c>1) printf "%-28s cpu %8.1f ms/min   run_delay %7.1f ms/min\n", $1, c/m, d/m}' \
  | sort -k3 -rn | head -6
echo
