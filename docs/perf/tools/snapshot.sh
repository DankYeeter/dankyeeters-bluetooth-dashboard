#!/system/bin/sh
# T-001 measurement harness - cumulative counter snapshot.  (device side)
#
# Usage: snapshot.sh <run-id> <t0|t1>
#
# Everything this reads is a CUMULATIVE counter, so a run is measured by taking
# the snapshot once at the start and once at the end and subtracting. That is
# what resolves the observer problem in T-001: the only Bluetooth-stack contact
# in the whole harness is the single `dumpsys bluetooth_manager` below, it runs
# exactly twice per run, and it runs identically in every condition including
# the force-stopped reference. Its perturbation is a constant offset that
# cancels when conditions are compared with each other.
#
# Raw dumps are stored rather than parsed here: parsing on the host means the
# same capture can be re-read later without re-running a condition, and keeps
# the on-device footprint to two execs.

RUN="$1"; MARK="$2"
OUT=/data/local/tmp/btperf/$RUN
mkdir -p "$OUT"
M="$OUT/$MARK.meta"

{
  echo "run=$RUN"
  echo "mark=$MARK"
  echo "wall_ns=$(date +%s%N)"
  echo "uptime_s=$(cut -d' ' -f1 /proc/uptime)"
} > "$M"

# ---------------------------------------------------------------- tier 1: procfs
# Touches no Android service at all, so it is free of the observer caveat and
# could be sampled at any rate. utime/stime are fields 12/13 after "pid (comm) ".
# CLK_TCK is 100 here, so one unit is 10 ms.
cpu_of() {
  _pid="$1"; _label="$2"
  if [ -z "$_pid" ] || [ ! -e "/proc/$_pid/stat" ]; then
    echo "${_label}_pid=none"; return
  fi
  echo "${_label}_pid=$_pid"
  awk -v L="$_label" '{
    s=$0; sub(/^[^)]*\) /,"",s); split(s,f," ");
    printf "%s_utime_tck=%s\n%s_stime_tck=%s\n", L,f[12],L,f[13]
  }' "/proc/$_pid/stat"
  awk -v L="$_label" '/^voluntary_ctxt_switches/{printf "%s_vol_ctxt=%s\n",L,$2}
                      /^nonvoluntary_ctxt_switches/{printf "%s_nonvol_ctxt=%s\n",L,$2}' \
      "/proc/$_pid/status" 2>/dev/null
  # Per-thread schedstat: cpu_ns, run_delay_ns, timeslices.
  # run_delay is time spent runnable-but-not-running, i.e. CPU starvation.
  # A thread blocked on a mutex instead shows flat cpu_ns with flat run_delay.
  # Having both lets the two causes be told apart rather than assumed.
  for t in /proc/$_pid/task/*; do
    [ -e "$t/schedstat" ] || continue
    echo "${_label}_thread=${t##*/} $(cat "$t/comm" 2>/dev/null | tr ' ' '_') $(cat "$t/schedstat")"
  done
}

{
  cpu_of "$(pidof com.google.android.bluetooth)" bt
  cpu_of "$(pidof dev.dankyeeter.btdashboard)" app
  cpu_of "$(pidof audioserver)" audioserver
  cpu_of "$(pidof android.hardware.bluetooth-service.bcmbtlinux)" bthal
  cpu_of "$(pidof btdash_privileged)" helper
} >> "$M"

# ------------------------------------------- tier 2: AudioFlinger (no BT locks)
# Reaches audioserver, not the Bluetooth stack, so it is an INDEPENDENT witness
# for stalls that a bluetooth_manager dump causes.
t=$(date +%s%N)
dumpsys media.audio_flinger > "$OUT/$MARK.audio_flinger" 2>/dev/null
echo "dump_audio_flinger_ns=$(( $(date +%s%N) - t ))" >> "$M"

# ---------------------------- tier 3: the Bluetooth stack - TWICE PER RUN ONLY
t=$(date +%s%N)
dumpsys bluetooth_manager > "$OUT/$MARK.bluetooth_manager" 2>/dev/null
echo "dump_bluetooth_manager_ns=$(( $(date +%s%N) - t ))" >> "$M"

echo "ok $RUN $MARK"
