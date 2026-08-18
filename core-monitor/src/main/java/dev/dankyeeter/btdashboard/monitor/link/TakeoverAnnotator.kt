package dev.dankyeeter.btdashboard.monitor.link

/**
 * Turns the flat broadcast stream into the annotations the timeline needs.
 *
 * The interesting case Daniel actually hits: music stops on the Encore because
 * the office speaker connected and the system moved the stream. Android does
 * not tell us that in one event — it arrives as
 * `PLAYING_STOPPED(Encore)` plus `ACTIVE_DEVICE_CHANGED(SoundCore)`, in either
 * order, milliseconds apart. This class correlates the two within
 * [correlationWindowMs] and emits a [MonitorEventType.TAKEOVER]; a stop with no
 * matching active-device change becomes an [MonitorEventType.INTERRUPTION].
 *
 * Pure and sequential: feed events in timestamp order, get extra events back.
 */
class TakeoverAnnotator(private val correlationWindowMs: Long = 3_000L) {

    private var activeAddress: String? = null
    private var activeName: String? = null
    private val playing = linkedMapOf<String, String?>() // address -> name

    private var lastStop: Stop? = null

    private data class Stop(val address: String, val name: String?, val timestampMs: Long)

    /** @return derived events to append after [event]. Never throws. */
    fun onEvent(event: MonitorEvent): List<MonitorEvent> {
        val out = mutableListOf<MonitorEvent>()
        when (event.type) {
            MonitorEventType.PLAYING_STARTED -> {
                event.deviceAddress?.let { playing[it] = event.deviceName }
                lastStop = null
            }

            MonitorEventType.PLAYING_STOPPED -> {
                val address = event.deviceAddress ?: return emptyList()
                playing.remove(address)
                val takeoverBy = activeAddress?.takeIf { it != address }
                if (takeoverBy != null) {
                    out += takeoverEvent(event, activeName ?: takeoverBy)
                    lastStop = null
                } else {
                    lastStop = Stop(address, event.deviceName, event.timestampMs)
                }
            }

            MonitorEventType.ACTIVE_DEVICE_CHANGED -> {
                val previous = activeAddress
                activeAddress = event.deviceAddress
                activeName = event.deviceName
                val stop = lastStop
                if (stop != null &&
                    event.deviceAddress != null &&
                    event.deviceAddress != stop.address &&
                    event.timestampMs - stop.timestampMs <= correlationWindowMs
                ) {
                    out += MonitorEvent(
                        timestampMs = stop.timestampMs,
                        deviceAddress = stop.address,
                        deviceName = stop.name,
                        type = MonitorEventType.TAKEOVER,
                        detail = "Playback paused — ${event.deviceName ?: event.deviceAddress} " +
                            "took the stream from ${stop.name ?: stop.address}",
                    )
                    lastStop = null
                } else if (previous != null && event.deviceAddress != null &&
                    previous != event.deviceAddress && playing.containsKey(previous)
                ) {
                    out += takeoverEvent(event, event.deviceName ?: event.deviceAddress)
                }
            }

            MonitorEventType.ACL_DISCONNECTED -> {
                event.deviceAddress?.let { playing.remove(it) }
                if (event.deviceAddress == activeAddress) {
                    activeAddress = null
                    activeName = null
                }
                lastStop = null
            }

            else -> Unit
        }
        return out
    }

    /**
     * Call when the correlation window has passed with no active-device change:
     * a stop that stands alone is a genuine interruption worth annotating.
     */
    fun flushPending(nowMs: Long): List<MonitorEvent> {
        val stop = lastStop ?: return emptyList()
        if (nowMs - stop.timestampMs < correlationWindowMs) return emptyList()
        lastStop = null
        return listOf(
            MonitorEvent(
                timestampMs = stop.timestampMs,
                deviceAddress = stop.address,
                deviceName = stop.name,
                type = MonitorEventType.INTERRUPTION,
                detail = "Playback stopped on ${stop.name ?: stop.address} " +
                    "while the device stayed connected",
            ),
        )
    }

    private fun takeoverEvent(source: MonitorEvent, taker: String) = MonitorEvent(
        timestampMs = source.timestampMs,
        deviceAddress = source.deviceAddress,
        deviceName = source.deviceName,
        type = MonitorEventType.TAKEOVER,
        detail = "Playback paused — $taker took the stream",
    )

    val currentActiveAddress: String? get() = activeAddress
    fun isPlaying(address: String): Boolean = playing.containsKey(address)
    val anyPlaying: Boolean get() = playing.isNotEmpty()
}
