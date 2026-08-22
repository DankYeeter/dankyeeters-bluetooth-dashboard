package dev.dankyeeter.btdashboard.monitor.codec

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.dankyeeter.btdashboard.monitor.link.BroadcastConnectionTicks
import dev.dankyeeter.btdashboard.monitor.link.ConnectionTicks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Real [CodecStatusSource] on top of `BluetoothA2dp`.
 *
 * `BluetoothA2dp.getCodecStatus()` is a hidden `@SystemApi`: it is not in the
 * public SDK, it is not on the light greylist on every build, and several OEMs
 * ship a stub that returns null or throws. **All** of that is contained here —
 * one reflection surface, everything above sees plain data classes and a
 * three-state result.
 */
class A2dpCodecStatusSource(
    private val context: Context,
    private val ticks: ConnectionTicks = BroadcastConnectionTicks(context),
) : CodecStatusSource {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile
    private var a2dp: BluetoothProfile? = null

    /**
     * Whether [a2dp] is bound. A signal, not bookkeeping: the proxy binds
     * asynchronously, so whoever reads the device list at construction time
     * gets an empty answer and would stay wrong until something else happened
     * to ask again. That was the dashboard, and the refresh button was the
     * something else.
     */
    private val proxyBound = MutableStateFlow(false)

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.A2DP) return
            a2dp = proxy
            proxyBound.value = true
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.A2DP) return
            a2dp = null
            proxyBound.value = false
        }
    }

    /** Binds the A2DP proxy. Safe to call repeatedly. */
    fun connect() {
        runCatching { adapter?.getProfileProxy(context, serviceListener, BluetoothProfile.A2DP) }
            .onFailure { Log.w(TAG, "A2DP proxy bind failed", it) }
    }

    fun close() {
        val proxy = a2dp ?: return
        runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
        a2dp = null
    }

    override val isProfileAvailable: Boolean
        get() = adapter != null && a2dp != null && hasConnectPermission()

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by hasConnectPermission()
    override suspend fun connectedDevices(): List<BtAudioDevice> {
        if (!hasConnectPermission()) return emptyList()
        val proxy = a2dp ?: return emptyList()
        val active = activeDeviceAddress()
        return runCatching {
            proxy.connectedDevices.map { device ->
                BtAudioDevice(
                    address = device.address,
                    name = deviceName(device),
                    isActive = device.address == active,
                    isPlaying = isPlaying(proxy, device),
                )
            }
        }.getOrElse {
            Log.w(TAG, "connectedDevices failed", it)
            emptyList()
        }
    }

    /**
     * The live device list - the dashboard's only source, and the reason it
     * needs no refresh button.
     *
     * Two triggers, no timer:
     *  - [proxyBound], which as a `StateFlow` also emits its current value on
     *    collection and so doubles as the initial read; and
     *  - [ticks], the Bluetooth broadcasts.
     *
     * The breadth of the broadcast set is what makes guessing a settling delay
     * unnecessary. ACL_CONNECTED arrives before A2DP is negotiated, so that
     * read finds the device with an unknown codec - and then
     * CONNECTION_STATE_CHANGED and CODEC_CONFIG_CHANGED arrive and it is read
     * again, now complete.
     *
     * [distinctUntilChanged] is what keeps that from churning: several
     * broadcasts describe one connect, and only the reads that actually
     * changed something reach the UI.
     */
    override fun connectedDevicesFlow(): Flow<List<BtAudioDevice>> =
        merge(proxyBound.map { }, ticks.ticks())
            .map { connectedDevices() }
            .distinctUntilChanged()

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull() ?: device.address

    /** `BluetoothA2dp.isA2dpPlaying(device)` is hidden — reflection, defensively. */
    private fun isPlaying(proxy: BluetoothProfile, device: BluetoothDevice): Boolean =
        runCatching {
            val m = proxy.javaClass.getMethod("isA2dpPlaying", BluetoothDevice::class.java)
            m.isAccessible = true
            m.invoke(proxy, device) as? Boolean ?: false
        }.getOrDefault(false)

    /** `BluetoothA2dp.getActiveDevice()` is hidden as well. */
    @Suppress("DEPRECATION")
    private fun activeDeviceAddress(): String? = runCatching {
        val proxy = a2dp ?: return null
        val m = proxy.javaClass.getMethod("getActiveDevice")
        m.isAccessible = true
        (m.invoke(proxy) as? BluetoothDevice)?.address
    }.getOrNull()

    override suspend fun codecStatus(address: String): CodecReadResult {
        if (!hasConnectPermission()) {
            return CodecReadResult.Unsupported("Bluetooth permission not granted")
        }
        val proxy = a2dp ?: return CodecReadResult.Unsupported("A2DP profile not connected")
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
            ?: return CodecReadResult.Failed("Unknown device address")

        return runCatching {
            val method = proxy.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
            method.isAccessible = true
            val status = method.invoke(proxy, device)
                ?: return CodecReadResult.Unsupported("getCodecStatus() returned null")
            val config = invokeOrNull(status, "getCodecConfig")
                ?: return CodecReadResult.Unsupported("no codec config in status")
            CodecReadResult.Available(
                readConfig(config).copy(selectableCodecs = readSelectable(status)),
            )
        }.getOrElse { t ->
            // NoSuchMethodException on OEM stubs, SecurityException without
            // BLUETOOTH_PRIVILEGED, anything else from a vendor implementation.
            Log.i(TAG, "getCodecStatus unavailable: ${t.javaClass.simpleName}")
            when (t) {
                is NoSuchMethodException -> CodecReadResult.Unsupported("API not present on this build")
                is SecurityException -> CodecReadResult.Unsupported("privileged permission required")
                else -> CodecReadResult.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun readConfig(config: Any): CodecStatus {
        val rawType = invokeOrNull(config, "getCodecType") as? Int
        val family = CodecDecoding.codecFamily(rawType)
        val sampleRate = (invokeOrNull(config, "getSampleRate") as? Int)
            ?.let(CodecDecoding::sampleRate)
        val bits = (invokeOrNull(config, "getBitsPerSample") as? Int)
            ?.let(CodecDecoding::bitsPerSample)
        val channels = (invokeOrNull(config, "getChannelMode") as? Int)
            ?.let(CodecDecoding::channelMode) ?: ChannelMode.UNKNOWN
        val specific1 = (invokeOrNull(config, "getCodecSpecific1") as? Long)
        val bitrate = if (family == CodecFamily.LDAC && specific1 != null) {
            CodecDecoding.ldacBitrateKbps(specific1)
        } else {
            null
        }
        return CodecStatus(
            family = family,
            rawCodecType = rawType,
            sampleRateHz = sampleRate,
            bitsPerSample = bits,
            channelMode = channels,
            bitrateKbps = bitrate,
        )
    }

    private fun readSelectable(status: Any): List<CodecFamily> = runCatching {
        @Suppress("UNCHECKED_CAST")
        val list = invokeOrNull(status, "getCodecsSelectableCapabilities") as? List<Any> ?: return emptyList()
        list.mapNotNull { CodecDecoding.codecFamily(invokeOrNull(it, "getCodecType") as? Int) }
            .filter { it != CodecFamily.UNKNOWN }
            .distinct()
    }.getOrDefault(emptyList())

    private fun invokeOrNull(target: Any, method: String): Any? = runCatching {
        target.javaClass.getMethod(method).apply { isAccessible = true }.invoke(target)
    }.getOrNull()

    private companion object {
        const val TAG = "A2dpCodecStatus"
    }
}

/** Used when Bluetooth itself is missing (emulator, disabled adapter). */
object UnavailableCodecStatusSource : CodecStatusSource {
    override suspend fun connectedDevices(): List<BtAudioDevice> = emptyList()
    override fun connectedDevicesFlow(): Flow<List<BtAudioDevice>> = flowOf(emptyList())
    override suspend fun codecStatus(address: String): CodecReadResult =
        CodecReadResult.Unsupported("Bluetooth unavailable")
    override val isProfileAvailable: Boolean get() = false
}
