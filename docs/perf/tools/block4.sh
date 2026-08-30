#!/system/bin/sh
# T-001 block 4: does the sampling push LDAC's ABR bitrate down?
#
# This is the one quantity a listener would actually HEAR, and it is also the
# one where the observer problem cannot be designed away: the live bitrate is
# only printed by `dumpsys bluetooth_manager`, the very call under test. So it
# is attacked from the other side - sample the SAME quantity at two very
# different rates and compare the distributions. If reading it heavily degrades
# it, the heavy arm must show a lower bitrate than the light one.
#
#   L = one dump every 10 s  ->  ~18 readings, ~3 % duty cycle
#   H = one dump every 0.5 s -> ~360 readings, ~35 % duty cycle
B=/data/local/tmp/btperf
DUR=180
am force-stop dev.dankyeeter.btdashboard
sleep 5

arm() {
  name=$1; cad=$2
  out=$B/bitrate_$name.txt
  : > "$out"
  end=$(( $(date +%s) + DUR ))
  while [ "$(date +%s)" -lt "$end" ]; do
    dumpsys bluetooth_manager 2>/dev/null \
      | grep -E "LDAC transmission bitrate|LDAC quality mode|LDAC adaptive bit rate" \
      | sed 's/.*: *//' | tr '\n' ' ' >> "$out"
    echo "" >> "$out"
    sleep "$cad"
  done
  echo "arm $name done: $(grep -c . "$out") readings"
}

echo "=== L: light sampling, every 10 s ==="
arm L 10
sleep 10
echo "=== H: heavy sampling, every 0.5 s ==="
arm H 0.5
echo "BLOCK4 COMPLETE"
