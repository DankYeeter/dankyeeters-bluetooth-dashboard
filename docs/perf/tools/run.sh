#!/system/bin/sh
# T-001 measurement harness - one condition run.  (device side)
#
# Usage: run.sh <run-id> <duration_s> <stimulus> <cadence_ms>
#
#   stimulus = none    nothing runs; this is the floor
#              btdump  `dumpsys bluetooth_manager`     - touches the BT stack
#              afdump  `dumpsys media.audio_flinger`   - same shape, different
#                                                        subsystem, no BT locks
#              burn    matched CPU work, touches no service at all
#
# The three stimuli are the discriminator for the Director hypothesis. They cost
# comparable CPU, but only `btdump` enters the Bluetooth stack. If the audio path
# degrades under btdump and not under afdump/burn, the cost is the lock. If it
# degrades equally under burn, the cost is CPU. If nothing degrades, neither.

RUN="$1"; DUR="$2"; STIM="$3"; CAD="${4:-2000}"
BASE=/data/local/tmp/btperf
OUT=$BASE/$RUN
mkdir -p "$OUT"

# Sub-second sleep: toybox sleep takes fractions.
naps=$(awk -v c="$CAD" 'BEGIN{printf "%.3f", c/1000}')

stim_loop() {
  end=$(( $(date +%s) + DUR ))
  n=0
  while [ "$(date +%s)" -lt "$end" ]; do
    t=$(date +%s%N)
    case "$STIM" in
      btdump) dumpsys bluetooth_manager >/dev/null 2>&1 ;;
      afdump) dumpsys media.audio_flinger >/dev/null 2>&1 ;;
      # Matched CPU: sized to the measured wall time of one btdump (171 ms vs 182 ms), and
      # deliberately reading /dev/zero so that no service and no lock is entered.
      burn)   dd if=/dev/zero of=/dev/null bs=1048576 count=450 >/dev/null 2>&1 ;;
    esac
    d=$(( $(date +%s%N) - t ))
    echo "$d" >> "$OUT/stimulus_ns.txt"
    n=$((n+1))
    # Cadence measured from the start of the pass, matching how LiveLinkSource
    # and A2dpTxProbe space their own polls.
    rest=$(awk -v c="$CAD" -v d="$d" 'BEGIN{r=(c-d/1000000)/1000; if(r<0.05)r=0.05; printf "%.3f", r}')
    sleep "$rest"
  done
  echo "stimulus_passes=$n" > "$OUT/stimulus.count"
}

echo "stimulus=$STIM" > "$OUT/condition"
echo "cadence_ms=$CAD" >> "$OUT/condition"
echo "duration_s=$DUR" >> "$OUT/condition"

sh "$BASE/snapshot.sh" "$RUN" t0
if [ "$STIM" = "none" ]; then
  sleep "$DUR"
else
  stim_loop
fi
sh "$BASE/snapshot.sh" "$RUN" t1
echo "DONE $RUN"
