#!/system/bin/sh
# T-001 block 3: the live views. Conditions C (Monitor screen, 2 s poll) and
# D (C plus the "Watch closely" close-up, 500 ms probe). Driven by uiautomator
# taps so it needs no hands; every navigation step is verified before a run
# starts, and the block aborts rather than measuring the wrong screen.
B=/data/local/tmp/btperf
D=180

on_screen() { uiautomator dump $B/ui.xml >/dev/null 2>&1; grep -qF "\"$1\"" $B/ui.xml; }

am force-stop dev.dankyeeter.btdashboard
sleep 3
am start -n dev.dankyeeter.btdashboard/.MainActivity >/dev/null 2>&1
sleep 25

echo "--- navigating to Monitoring ---"
sh $B/tap_text.sh "Monitoring" || { echo "ABORT: no Monitoring tab"; exit 1; }
sleep 12
if on_screen "Watch closely"; then echo "OK: monitor screen up, close-up is OFF"
elif on_screen "Watching";  then echo "NOTE: close-up already ON, turning it off"; sh $B/tap_text.sh "Watching"; sleep 8
else echo "ABORT: monitor screen not recognised"; exit 2; fi
sleep 20

echo "=== C1: live panel open, close-up off ==="
sh $B/run.sh C1 $D none 2000
sleep 8

echo "--- enabling close-up ---"
sh $B/tap_text.sh "Watch closely" || { echo "ABORT: no close-up chip"; exit 3; }
sleep 10
on_screen "Watching" || { echo "ABORT: close-up did not turn on"; exit 4; }
echo "=== D1: live panel + close-up (500 ms probe) ==="
sh $B/run.sh D1 $D none 2000
sleep 8

echo "--- disabling close-up, repeat of C ---"
sh $B/tap_text.sh "Watching"; sleep 10
echo "=== C2: repeat ==="
sh $B/run.sh C2 $D none 2000
sleep 8

echo "--- enabling close-up, repeat of D ---"
sh $B/tap_text.sh "Watch closely"; sleep 10
echo "=== D2: repeat ==="
sh $B/run.sh D2 $D none 2000
echo "BLOCK3 COMPLETE"
