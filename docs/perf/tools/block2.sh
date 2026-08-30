#!/system/bin/sh
# T-001 block 2: what the app itself costs, without any monitor screen open.
# This is the AK-4 test - "Hintergrund kostet nichts" - and it is driven
# entirely from adb, so it needs no hands on the device.
B=/data/local/tmp/btperf
D=180
launch() {
  am force-stop dev.dankyeeter.btdashboard
  sleep 3
  am start -n dev.dankyeeter.btdashboard/.MainActivity >/dev/null 2>&1
  # 60 s settle: long enough for start-up work and for any codec-change burst
  # window to be visible in the run rather than straddling its start.
  sleep 60
}

echo "=== B1: app foreground, Bluetooth tab, no monitor screen ==="
launch
sh $B/run.sh B1 $D none 2000
sleep 8

echo "=== B2: app backgrounded with HOME, screen still on ==="
input keyevent KEYCODE_HOME
sleep 20
sh $B/run.sh B2 $D none 2000
sleep 8

echo "=== B3: repeat of B1 for spread ==="
launch
sh $B/run.sh B3 $D none 2000
sleep 8

echo "=== A3: reference again, app force-stopped, closes the block ==="
am force-stop dev.dankyeeter.btdashboard
sleep 15
sh $B/run.sh A3 $D none 2000
echo "BLOCK2 COMPLETE"
