#!/usr/bin/env bash
# Device-side probes against a running emulator. See .github/workflows/synthetic-tests.yml.
# Usage: emulator-probe.sh <app.apk> <androidTest.apk> <out-dir>
#
# Every probe records what it saw; none of them decides pass/fail on a finding.
# The run fails only if the install breaks; a failed instrumentation is
# reported in the summary.
set -uo pipefail
# Git Bash on Windows rewrites /data/... arguments into host paths; no-op elsewhere.
export MSYS_NO_PATHCONV=1

APP_APK=$1
TEST_APK=$2
OUT=$3
ADB=${ADB:-$ANDROID_HOME/platform-tools/adb}
PKG=dev.dankyeeter.btdashboard
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
PROBE=$PKG.probe.Android17ProbeTest
mkdir -p "$OUT"
SUMMARY=$OUT/summary.md

sh_() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }
note() { echo "$*" | tee -a "$SUMMARY"; }
section() { printf '\n### %s\n\n' "$*" | tee -a "$SUMMARY"; }

instrument() {
    "$ADB" logcat -c
    "$ADB" shell am instrument -w -e class "$PROBE#$1" "$RUNNER" | tr -d '\r' > "$OUT/instrument-$1.txt"
    "$ADB" logcat -d -s A17PROBE:I | tr -d '\r' | grep -o 'PROBE .*' > "$OUT/probe-$1.txt" || true
    cat "$OUT/probe-$1.txt" | sed 's/^PROBE /- /' | cut -c1-400 | tee -a "$SUMMARY"
    grep -q 'OK (1 test)' "$OUT/instrument-$1.txt" || { note "**instrumentation $1 failed**"; cat "$OUT/instrument-$1.txt"; }
}

: > "$SUMMARY"
section "Device"
note "- release=$(sh_ getprop ro.build.version.release) sdk=$(sh_ getprop ro.build.version.sdk) fingerprint=$(sh_ getprop ro.build.fingerprint)"
note "- page size: $(sh_ getconf PAGE_SIZE)"

section "Install"
for apk in "$APP_APK" "$TEST_APK"; do
    out=$("$ADB" install -r -g "$apk" 2>&1); echo "$out" | tee -a "$SUMMARY"
    grep -q '^Success' <<< "$out" || { note "**install of $apk failed**"; exit 1; }
done
sh_ cmd bluetooth_manager enable > /dev/null; sleep 3
note "- bluetooth: $(sh_ settings get global bluetooth_on)"

section "2.1 Launch / crash check"
"$ADB" logcat -c
sh_ am start -W -n $PKG/.MainActivity | tee "$OUT/start.txt" | grep -E 'Status|TotalTime' | sed 's/^/- /' | tee -a "$SUMMARY"
sleep 8
"$ADB" logcat -d | tr -d '\r' > "$OUT/logcat-launch.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat-launch.txt"; then
    note "- **FATAL EXCEPTION on launch**"; grep -A15 "FATAL EXCEPTION" "$OUT/logcat-launch.txt" | head -40 | sed 's/^/    /' | tee -a "$SUMMARY"
else
    note "- no FATAL EXCEPTION within 8 s"
fi
note "- 16 KB alignment dialog: $(grep -c -i 'page size\|16 KB\|16KB' "$OUT/logcat-launch.txt") matching log lines"
section "2.5 Hidden-API access log (app process, launch)"
grep -E "hiddenapi|Accessing hidden" "$OUT/logcat-launch.txt" | sed 's/^.*Accessing/Accessing/' | sort -u | head -30 | sed 's/^/- /' | tee -a "$SUMMARY"

section "2.2 Foreground service connectedDevice, no device connected"
sh_ dumpsys activity services $PKG > "$OUT/services-after-launch.txt"
note "- after launch: $(grep -cE 'EqForegroundService' "$OUT/services-after-launch.txt") service records, isForeground: $(grep -oE 'isForeground=[a-z]+' "$OUT/services-after-launch.txt" | head -1) types: $(grep -oE 'foregroundServiceType=0x[0-9a-f]+' "$OUT/services-after-launch.txt" | head -1)"
grep -E "EqForegroundService|ForegroundService|MissingForegroundServiceType|SecurityException" "$OUT/logcat-launch.txt" | head -10 | sed 's/^/    /' | tee -a "$SUMMARY"
# Same start, but with BLUETOOTH_CONNECT revoked: the A17 risk is a type check
# that wants a real precondition rather than a held permission.
sh_ am force-stop $PKG
sh_ pm revoke $PKG android.permission.BLUETOOTH_CONNECT
"$ADB" logcat -c
sh_ am start -W -n $PKG/.MainActivity > /dev/null; sleep 6
"$ADB" logcat -d | tr -d '\r' > "$OUT/logcat-launch-noconnect.txt"
sh_ dumpsys activity services $PKG > "$OUT/services-noconnect.txt"
note "- without BLUETOOTH_CONNECT: isForeground $(grep -oE 'isForeground=[a-z]+' "$OUT/services-noconnect.txt" | head -1); fatal: $(grep -c 'FATAL EXCEPTION' "$OUT/logcat-launch-noconnect.txt")"
grep -E "could not start the EQ service|ForegroundService|SecurityException" "$OUT/logcat-launch-noconnect.txt" | head -6 | sed 's/^/    /' | tee -a "$SUMMARY"
sh_ pm grant $PKG android.permission.BLUETOOTH_CONNECT

section "2.4 In-app reflection"
instrument inAppReflection

section "2.3 Helper start via app_process as shell (uid 2000)"
instrument printHelperCommand
CMD=$(grep -o 'HELPER_CMD=.*' "$OUT/probe-printHelperCommand.txt" | sed 's/^HELPER_CMD=//')
if [ -z "$CMD" ]; then
    note "- no helper command printed"
else
    sh_ am start -n $PKG/.MainActivity > /dev/null; sleep 2
    "$ADB" shell "$CMD" > "$OUT/helper-start.txt" 2>&1
    sleep 3
    note "- helper process: $(sh_ ps -A -o USER,PID,NAME | grep btdash_privileged || echo none)"
    note "- helper log: $(sh_ cat /data/local/tmp/btdash_helper.log | head -20 | tr '\n' '|' | cut -c1-600)"
    instrument helperRoundTrip
    section "U-0 / U-6 descriptor hand-over (SR-001, SR-009, SR-020)"
    note "- SELinux denials during the round trip: $("$ADB" logcat -d | tr -d '\r' | grep -c 'avc: denied.*shell')"
    "$ADB" logcat -d | tr -d '\r' | grep 'avc: denied.*shell' | head -10 | sed 's/^/    /' | tee -a "$SUMMARY"
    HPID=$(sh_ ps -A -o PID,NAME | awk '/btdash_privileged/ {print $1; exit}')
    fds() { [ -n "$HPID" ] && sh_ ls /proc/"$HPID"/fd | wc -l || echo "?"; }
    FD_BEFORE=$(fds)
    instrument helperRefusals
    note "- helper open descriptors: before $FD_BEFORE, after refusals $(fds)"
    note "- staged reply files after live session: $(sh_ ls /data/local/tmp | grep -c '^btdash_exec_')"
    note "- helper log mode: $(sh_ stat -c %a /data/local/tmp/btdash_helper.log)"

    section "U-4 Stop helper and clean up"
    instrument helperCleanup
    note "- helper afterwards: $(sh_ ps -A -o USER,PID,NAME | grep btdash_privileged || echo none)"
    note "- btdash files afterwards: $(sh_ ls /data/local/tmp | grep '^btdash' | tr '\n' ' ')"
    # Start it again so 2.8 measures what happens to a live helper on uninstall.
    instrument printHelperCommand
    CMD=$(grep -o 'HELPER_CMD=.*' "$OUT/probe-printHelperCommand.txt" | sed 's/^HELPER_CMD=//')
    sh_ am start -n $PKG/.MainActivity > /dev/null; sleep 2
    "$ADB" shell "$CMD" >> "$OUT/helper-start.txt" 2>&1
    sleep 5
    note "- helper restarted: $(sh_ ps -A -o USER,PID,NAME | grep btdash_privileged || echo none)"
fi

section "2.8 SR-001 / SR-009 / SR-016: files and helper around uninstall"
sh_ ls -la /data/local/tmp/ | tee "$OUT/tmp-before-uninstall.txt" | grep -E 'btdash' | sed 's/^/- /' | tee -a "$SUMMARY"
"$ADB" uninstall $PKG.test > /dev/null
"$ADB" uninstall $PKG | sed 's/^/- uninstall: /' | tee -a "$SUMMARY"
sleep 5
note "- helper after uninstall: $(sh_ ps -A -o USER,PID,NAME | grep btdash_privileged || echo none)"
sh_ ls -la /data/local/tmp/ | tee "$OUT/tmp-after-uninstall.txt" | grep -E 'btdash' | sed 's/^/- left behind: /' | tee -a "$SUMMARY"
sleep 60
note "- helper 65 s after uninstall: $(sh_ ps -A -o USER,PID,NAME | grep btdash_privileged || echo none)"
sh_ ls -la /data/local/tmp/ | grep -E 'btdash' | sed 's/^/- still left 65 s after uninstall: /' | tee -a "$SUMMARY"

section "2.9 AudioEffectSessionReceiver (exported) with forged broadcast"
"$ADB" install -r -g "$APP_APK" > /dev/null
sh_ pm enable $PKG/dev.dankyeeter.btdashboard.system.attach.AudioEffectSessionReceiver | sed 's/^/- /' | tee -a "$SUMMARY"
"$ADB" logcat -c
sh_ am broadcast -a android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION \
    --ei android.media.extra.AUDIO_SESSION 424242 --es android.media.extra.PACKAGE_NAME forged.sender \
    -n $PKG/dev.dankyeeter.btdashboard.system.attach.AudioEffectSessionReceiver | sed 's/^/- /' | tee -a "$SUMMARY"
sleep 3
"$ADB" logcat -d | tr -d '\r' > "$OUT/logcat-forged.txt"
grep -E "424242|forged.sender|AudioEffectSession|FATAL" "$OUT/logcat-forged.txt" | head -10 | sed 's/^/    /' | tee -a "$SUMMARY"

section "2.6 dumpsys bluetooth_manager (A17 format sample)"
sh_ dumpsys bluetooth_manager > "$OUT/dumpsys-bluetooth_manager.txt"
note "- $(wc -l < "$OUT/dumpsys-bluetooth_manager.txt") lines captured (artifact)"
grep -nE "A2DP|a2dp|Codec|BQR|Quality" "$OUT/dumpsys-bluetooth_manager.txt" | head -15 | sed 's/^/    /' | tee -a "$SUMMARY"

section "2.10 Boot restore (BOOT_COMPLETED as root)"
if "$ADB" root > /dev/null 2>&1; then
    sleep 3
    sh_ am force-stop $PKG
    "$ADB" logcat -c
    sh_ am broadcast -a android.intent.action.BOOT_COMPLETED -p $PKG > /dev/null
    sleep 8
    "$ADB" logcat -d | tr -d '\r' > "$OUT/logcat-boot.txt"
    sh_ dumpsys activity services $PKG > "$OUT/services-boot.txt"
    note "- after BOOT_COMPLETED: isForeground $(grep -oE 'isForeground=[a-z]+' "$OUT/services-boot.txt" | head -1); fatal: $(grep -c 'FATAL EXCEPTION' "$OUT/logcat-boot.txt")"
    grep -E "could not start the EQ service|ForegroundService|BootReceiver" "$OUT/logcat-boot.txt" | head -6 | sed 's/^/    /' | tee -a "$SUMMARY"
    "$ADB" unroot > /dev/null 2>&1 || true
else
    note "- adb root not available on this image"
fi
exit 0
