#!/system/bin/sh
# T-001 block 1: the hypothesis discriminator. App force-stopped throughout, so
# nothing in here measures the app - it measures what a dumpsys of each kind
# does to a live LDAC stream, which is the question underneath the hypothesis.
B=/data/local/tmp/btperf
D=180
am force-stop dev.dankyeeter.btdashboard
sleep 10
for arm in "A1 none 2000" "S1 btdump 2000" "S2 afdump 2000" "S3 burn 2000" "S4 btdump 500" "A2 none 2000" "S1b btdump 2000" "S4b btdump 500"; do
  set -- $arm
  echo "=== starting $1 ($2 @ $3 ms) at $(date) ==="
  sh $B/run.sh "$1" $D "$2" "$3"
  sleep 8
done
echo "BLOCK1 COMPLETE"
