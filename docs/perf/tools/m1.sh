#!/usr/bin/env bash
# T-001 / M-1 - the INPUT side, per channel, never summed.
#
# Everything below comes out of `dumpsys media.audio_flinger`, which reaches
# audioserver and takes no Bluetooth-stack lock, plus the tx counters from
# `A2DP State:`. Rates are normalised against the MEASURED elapsed time of the
# run (wall_ns delta), not against the nominal 180 s.
#
# The AudioFlinger block is sliced from the A2DP thread's header to the next
# "Output thread" header. It deliberately does NOT stop at a blank line: the
# thread block contains blank lines inside its signal-power history, and a
# blank-line delimiter silently truncated the block before the underrun
# counters, which is why they read "n/a" in the first version of this harness.
set -u
RUN="$1"; NAME=$(basename "$RUN")

slice() { # <file>  -> the A2DP output thread's block
  local f="$1"
  local bt hdr end
  bt=$(grep -n "Output devices: 0x80 (AUDIO_DEVICE_OUT_BLUETOOTH_A2DP)" "$f" | head -1 | cut -d: -f1)
  [ -n "$bt" ] || return 1
  hdr=$(awk -v b="$bt" 'NR<=b && /^Output thread /{l=NR} END{print l}' "$f")
  end=$(awk -v h="$hdr" 'NR>h && /^Output thread /{print NR; exit}' "$f")
  [ -n "$end" ] || end=$(wc -l < "$f")
  sed -n "${hdr},$((end-1))p" "$f"
}

vals() { # <mark> -> key=value
  local m="$1"
  slice "$RUN/$m.audio_flinger" | awk '
    /Normal mixer raw underrun counters:/ {
      for(i=1;i<=NF;i++){split($i,a,"="); if(a[1]=="partial")print "mixer_ur_partial="a[2]; if(a[1]=="empty")print "mixer_ur_empty="a[2]} }
    /underruns=/ { u=$0; sub(/^.*underruns=/,"",u); sub(/[^0-9].*$/,"",u); print "fastmixer_ur="u }
    /Timestamp stats:/ { for(i=1;i<=NF;i++){ if($i~/^disc=/){v=$i;sub(/disc=/,"",v);print "ts_disc="v}
                                             if($i~/^err=/){v=$i;sub(/err=/,"",v);print "ts_err="v} } }
    /Delayed writes:/ { print "delayed_writes="$3 }
    /Total writes:/   { print "total_writes="$3 }
    # The track table: the active media track is the row with an explicit
    # client pid. "Underruns" is the 5th column from the right, ahead of
    # Flushed, BitPerfect, InternalMute and Latency(+unit).
    /Tracks of which/ {intr=1; next}
    intr && /^ *Type +Id +Active/ {next}
    intr && NF>20 { print "track_underruns[" $1 "]=" $(NF-5) "  flushed=" $(NF-4) }
    /Effect Chains/ {intr=0}
  '
  awk '
    /^A2DP State:/{inb=1; next}
    inb && /^[A-Za-z]/ && !/^ /{inb=0}
    inb && /Counts \(flushed\/dropped\/dropouts\)/{v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); n=split(v,x,"/");
        print "tx_flushed="x[1]; print "tx_dropped="x[2]; print "tx_dropouts="x[3]}
    inb && /Counts \(underflow\)/{v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); print "tx_underflow="v}
  ' "$RUN/$m.bluetooth_manager"
  grep -m1 '^wall_ns=' "$RUN/$m.meta"
}

v0=$(vals t0); v1=$(vals t1)
g(){ echo "$2" | grep -m1 "^$1=" | cut -d= -f2- | awk '{print $1}'; }
MIN=$(awk -v a="$(g wall_ns "$v0")" -v b="$(g wall_ns "$v1")" 'BEGIN{printf "%.5f",(b-a)/6e10}')
printf "%-22s %10s %10s %12s\n" "$NAME (min=$MIN)" t0 t1 "per_min"
for k in track_underruns mixer_ur_partial mixer_ur_empty fastmixer_ur ts_disc ts_err delayed_writes tx_dropped tx_dropouts tx_underflow; do
  if [ "$k" = track_underruns ]; then
    echo "$v0" | grep '^track_underruns\[' | while read -r line; do
      id=$(echo "$line" | sed 's/track_underruns\[\([0-9]*\)\].*/\1/')
      a=$(echo "$line" | sed 's/.*\]=\([0-9]*\).*/\1/')
      b=$(echo "$v1" | grep -m1 "^track_underruns\[$id\]" | sed 's/.*\]=\([0-9]*\).*/\1/')
      [ -n "$b" ] && awk -v i="$id" -v a="$a" -v b="$b" -v m="$MIN" \
        'BEGIN{printf "%-22s %10s %10s %12.2f\n","track_ur[id "i"]",a,b,(b-a)/m}'
    done
  else
    a=$(g "$k" "$v0"); b=$(g "$k" "$v1")
    [ -n "$a" ] && [ -n "$b" ] && awk -v k="$k" -v a="$a" -v b="$b" -v m="$MIN" \
      'BEGIN{printf "%-22s %10s %10s %12.2f\n",k,a,b,(b-a)/m}'
  fi
done
