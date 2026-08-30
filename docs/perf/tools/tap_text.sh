#!/system/bin/sh
# Taps the centre of the first UI node whose text or content-desc equals $1.
# Test scaffolding for driving conditions C/D/E from adb; touches no app code.
#
# Usage: tap_text.sh <exact-label>
#
# Node splitting is done with awk RS=">" rather than `tr '>' '>\n'`: tr
# substitutes one character for one character and cannot insert a newline, so
# the tr form silently left the whole document on one line and the greedy
# `.*bounds=` then matched the LAST node in the file instead of the wanted one.
# That is how a tap meant for "Monitoring" landed on the Settings tab.
NEEDLE="$1"
XML=/data/local/tmp/btperf/ui.xml
uiautomator dump "$XML" >/dev/null 2>&1 || { echo "dump failed"; exit 1; }
NODE=$(awk -v RS='>' -v n="\"$NEEDLE\"" 'index($0,n){print; exit}' "$XML")
[ -n "$NODE" ] || { echo "not found: $NEEDLE"; exit 2; }
set -- $(echo "$NODE" | sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/')
[ $# -eq 4 ] || { echo "no bounds for: $NEEDLE"; exit 3; }
X=$(( ($1 + $3) / 2 )); Y=$(( ($2 + $4) / 2 ))
input tap "$X" "$Y"
echo "tapped '$NEEDLE' at $X,$Y (bounds $1,$2 $3,$4)"
