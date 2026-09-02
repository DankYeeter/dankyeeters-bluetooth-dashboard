# T-011 / M-5 - parse series.log into one CSV row per sample.
# Fields: idx,t_ns,dropped,dropouts,underflow,flushed,max_dropped,quality,bitrate,queue,abr_idx,abr_adj
BEGIN { FS=":"; OFS="," ; print "idx,t_ns,dropped,dropouts,underflow,flushed,max_dropped,quality,bitrate,queue,abr_idx,abr_adj" }
/^===SAMPLE/ {
  if (idx != "") print idx,t_ns,dropped,dropouts,underflow,flushed,maxdrop,quality,bitrate,queue,abridx,abradj
  idx=$0; sub(/^===SAMPLE /,"",idx); tpart=idx; sub(/ t_ns=.*/,"",idx); sub(/.*t_ns=/,"",tpart); sub(/===$/,"",tpart); t_ns=tpart
  dropped="";dropouts="";underflow="";flushed="";maxdrop="";quality="";bitrate="";queue="";abridx="";abradj=""
  next
}
/Counts \(flushed\/dropped\/dropouts\)/ {
  v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); split(v,x,"/"); flushed=x[1]; dropped=x[2]; dropouts=x[3]; next
}
/Counts \(underflow\)/ { v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); underflow=v; next }
/Counts \(max dropped\)/ { v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); maxdrop=v; next }
/LDAC quality mode/ { v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); quality=v; next }
/LDAC transmission bitrate/ { v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); bitrate=v; next }
/LDAC saved transmit queue length/ { v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); queue=v; next }
/LDAC adaptive bit rate encode quality mode index/ { v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); abridx=v; next }
/LDAC adaptive bit rate adjustments/ { v=$0; sub(/^[^:]*:[ ]*/,"",v); gsub(/ /,"",v); abradj=v; next }
END { if (idx != "") print idx,t_ns,dropped,dropouts,underflow,flushed,maxdrop,quality,bitrate,queue,abridx,abradj }
