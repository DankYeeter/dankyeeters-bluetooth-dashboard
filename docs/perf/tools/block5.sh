#!/system/bin/sh
# T-001 block 5 (condition E): the EQ chain, separated from the measuring.
#
# Block 2 showed that merely running the app inserts a `DynamicsProcessing`
# effect into the playing session - it is present in B and absent in A. This
# block isolates that: same app, same screen, effect attached vs detached,
# via the "EQ enabled" master switch (which detaches the chain entirely,
# unlike "Compare with EQ off", which only flattens the bands).
B=/data/local/tmp/btperf
D=180

# The Switch sits at the right-hand end of the row carrying the label, and
# uiautomator exposes the label, not the switch. So: find the label's bounds,
# tap the row at the far right.
tap_switch_for() {
  uiautomator dump $B/ui.xml >/dev/null 2>&1
  tr '>' '>\n' < $B/ui.xml | grep -F "\"$1\"" | head -1 > $B/.node
  [ -s $B/.node ] || { echo "label not found: $1"; return 1; }
  set -- $(sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\].*/\1 \2 \3 \4/' $B/.node)
  Y=$(( ($2 + $4) / 2 )); X=$(( 1080 - 90 ))
  input tap $X $Y; echo "tapped switch at $X,$Y"
}
eq_attached() { dumpsys media.audio_flinger 2>/dev/null | grep -q "name: DynamicsProcessing"; }

am force-stop dev.dankyeeter.btdashboard; sleep 3
am start -n dev.dankyeeter.btdashboard/.MainActivity >/dev/null 2>&1
sleep 30
sh $B/tap_text.sh "EQ" || { echo "ABORT: no EQ tab"; exit 1; }
sleep 12
uiautomator dump $B/ui.xml >/dev/null 2>&1
grep -qF '"EQ enabled"' $B/ui.xml || { echo "ABORT: not on the EQ screen"; exit 2; }

if eq_attached; then echo "start state: EQ ATTACHED"; else echo "start state: EQ detached"; fi

echo "--- turning EQ off ---"
tap_switch_for "EQ enabled" || exit 3
sleep 15
if eq_attached; then echo "ABORT: EQ still attached after toggling off"; exit 4; fi
echo "=== E_off: app running, EQ chain detached ==="
sh $B/run.sh E_off $D none 2000
sleep 8

echo "--- turning EQ back on ---"
tap_switch_for "EQ enabled" || exit 5
sleep 20
eq_attached || { echo "WARN: EQ did not re-attach; E_on is not valid"; }
echo "=== E_on: app running, EQ chain attached ==="
sh $B/run.sh E_on $D none 2000
echo "BLOCK5 COMPLETE"
