package dev.dankyeeter.btdashboard.monitor.link

import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily

/**
 * An Android broadcast reduced to plain data. The receiver does the (untestable)
 * Intent unpacking, [BroadcastEventMapper] does the (testable) interpretation.
 */
data class RawBroadcast(
    val action: String,
    val deviceAddress: String?,
    val deviceName: String?,
    val ints: Map<String, Int> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
)

/** Action strings, including the hidden ones we listen for opportunistically. */
object BtActions {
    const val ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED"
    const val ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED"

    /** `BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED` — public constant value. */
    const val A2DP_PLAYING_STATE_CHANGED =
        "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED"

    /** `BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED` — @SystemApi, value stable since O. */
    const val A2DP_CODEC_CONFIG_CHANGED =
        "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"

    /** `BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED` — @SystemApi. */
    const val A2DP_ACTIVE_DEVICE_CHANGED =
        "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"

    const val EXTRA_STATE = "android.bluetooth.profile.extra.STATE"

    /** A2DP playing states (`BluetoothA2dp.STATE_PLAYING` / `STATE_NOT_PLAYING`). */
    const val STATE_PLAYING = 10
    const val STATE_NOT_PLAYING = 11

    val all: List<String> = listOf(
        ACL_CONNECTED,
        ACL_DISCONNECTED,
        A2DP_PLAYING_STATE_CHANGED,
        A2DP_CODEC_CONFIG_CHANGED,
        A2DP_ACTIVE_DEVICE_CHANGED,
    )
}

/**
 * Maps a raw broadcast to a [MonitorEvent]. Unknown actions and broadcasts
 * without a device return null instead of throwing — the receiver is registered
 * for hidden actions whose payload shape is not guaranteed across OEM builds.
 */
object BroadcastEventMapper {

    fun map(raw: RawBroadcast, timestampMs: Long): MonitorEvent? {
        val name = raw.deviceName ?: raw.deviceAddress
        fun event(type: MonitorEventType, detail: String, codec: CodecFamily? = null) =
            MonitorEvent(timestampMs, raw.deviceAddress, name, type, detail, codec)

        return when (raw.action) {
            BtActions.ACL_CONNECTED ->
                raw.deviceAddress?.let { event(MonitorEventType.ACL_CONNECTED, "$name connected") }

            BtActions.ACL_DISCONNECTED ->
                raw.deviceAddress?.let {
                    event(MonitorEventType.ACL_DISCONNECTED, "$name disconnected")
                }

            BtActions.A2DP_PLAYING_STATE_CHANGED -> when (raw.ints[BtActions.EXTRA_STATE]) {
                BtActions.STATE_PLAYING ->
                    event(MonitorEventType.PLAYING_STARTED, "Playback started on $name")
                BtActions.STATE_NOT_PLAYING ->
                    event(MonitorEventType.PLAYING_STOPPED, "Playback stopped on $name")
                else -> null
            }

            BtActions.A2DP_CODEC_CONFIG_CHANGED -> {
                val codec = raw.strings["codec"]?.let(CodecDecoding::codecFamilyFromName)
                    ?: CodecDecoding.codecFamily(raw.ints["codecType"])
                event(
                    MonitorEventType.CODEC_CHANGED,
                    "Codec is now ${codec.displayName} on $name",
                    codec,
                )
            }

            BtActions.A2DP_ACTIVE_DEVICE_CHANGED ->
                if (raw.deviceAddress == null) {
                    MonitorEvent(
                        timestampMs, null, null, MonitorEventType.ACTIVE_DEVICE_CHANGED,
                        "No active Bluetooth audio device",
                    )
                } else {
                    event(MonitorEventType.ACTIVE_DEVICE_CHANGED, "$name is now the active device")
                }

            else -> null
        }
    }
}
