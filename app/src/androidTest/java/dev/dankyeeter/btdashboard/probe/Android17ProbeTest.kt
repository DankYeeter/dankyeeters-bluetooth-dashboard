package dev.dankyeeter.btdashboard.probe

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysBluetoothParser
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpLinkDumpParser
import dev.dankyeeter.btdashboard.privileged.PrivilegedBootstrap
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.privileged.PrivilegedProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Device-side probes for the open Android 17 questions in ANDROID17_READINESS.md.
 *
 * Driven by ci/emulator-probe.sh one method at a time, with shell steps in
 * between. Every probe reports through logcat tag [TAG] and never fails on a
 * finding: a refused reflection or a missing helper is a result to read, not a
 * broken test. Only a crash in the probe itself turns the run red.
 */
@RunWith(JUnit4::class)
class Android17ProbeTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Mints the session token and prints the exact command the app shows the user. */
    @Test
    fun printHelperCommand() {
        val command = PrivilegedBootstrap(context).deviceShellCommand()
        report("HELPER_CMD", command)
        // The pending token is written with apply(); give it time to reach disk
        // before the instrumentation tears the process down.
        SystemClock.sleep(1_500)
    }

    /** Waits for the helper to hand over its binder, then exercises every read-only call. */
    @Test
    fun helperRoundTrip() {
        val deadline = SystemClock.elapsedRealtime() + 75_000
        while (PrivilegedConnection.service.value == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(250)
        }
        val service = PrivilegedConnection.service.value
        if (service == null) {
            report("HELPER_BIND", "FAILED no binder within 75 s")
            return
        }
        report("HELPER_BIND", "OK")
        val token = PrivilegedBootstrap(context).activeToken()
        report("HELPER_TOKEN", if (token == null) "MISSING" else "present")
        report("HELPER_VERSION", attempt { service.version().toString() })
        report("HELPER_EXEC_ID", attempt { service.exec(token, listOf("id")) })
        report("HELPER_CODEC_STATUS", attempt { service.codecStatus(token, NO_DEVICE) })
        report("HELPER_OPTIONAL_CODECS", attempt { service.optionalCodecs(token, NO_DEVICE) })

        // exec answers in the wire format ("OK exit stdout64 stderr64"); the
        // parsers read the decoded stdout, as the app does.
        val reply = attempt { service.exec(token, listOf("dumpsys", "bluetooth_manager")) }
        val dump = PrivilegedProtocol.decodeResult(reply)?.second ?: reply
        report("HELPER_DUMPSYS_BT_LENGTH", dump.length.toString())
        report("PARSER_DUMPSYS", attempt { DumpsysBluetoothParser.parse(dump).toString() })
        report("PARSER_A2DP_LINK", attempt { A2dpLinkDumpParser.parse(dump).toString() })

        // Larger than the 64 KB binder reply limit, so the helper has to spill.
        val big = attempt { service.exec(token, listOf("dumpsys", "audio")) }
        report("HELPER_SPILL_REPLY_LENGTH", big.length.toString())
        report("HELPER_SPILL_REPLY_HEAD", big.take(200))
    }

    /** The reflection the app itself does, in the app's own (untrusted_app) domain. */
    @Test
    fun inAppReflection() {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = manager?.adapter
        report("BT_ADAPTER", if (adapter == null) "none" else "enabled=${adapter.isEnabled}")
        if (adapter == null) return

        val latch = CountDownLatch(1)
        var a2dp: BluetoothA2dp? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                latch.countDown()
            }
            override fun onServiceDisconnected(profile: Int) = Unit
        }
        val requested = attempt { adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP).toString() }
        report("A2DP_PROXY_REQUEST", requested)
        latch.await(10, TimeUnit.SECONDS)
        val proxy = a2dp
        report("A2DP_PROXY", if (proxy == null) "not connected" else "connected")
        if (proxy != null) {
            report("REFLECT_isA2dpPlaying", method(proxy, "isA2dpPlaying", BluetoothDevice::class.java))
            report("REFLECT_getActiveDevice", method(proxy, "getActiveDevice"))
            report("REFLECT_getCodecStatus", method(proxy, "getCodecStatus", BluetoothDevice::class.java))
            report(
                "INVOKE_getActiveDevice",
                attempt { proxy.javaClass.getMethod("getActiveDevice").invoke(proxy)?.toString() },
            )
            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        }

        val bqr = attempt {
            val callbackClass = Class.forName(
                "android.bluetooth.BluetoothAdapter\$BluetoothQualityReportReadyCallback",
            )
            adapter.javaClass.getMethod(
                "registerBluetoothQualityReportReadyCallback",
                Executor::class.java,
                callbackClass,
            ).toString()
        }
        report("REFLECT_registerBqrCallback", bqr)
    }

    private fun method(target: Any, name: String, vararg types: Class<*>): String =
        attempt { target.javaClass.getMethod(name, *types).toString() }

    private fun attempt(block: () -> String?): String = try {
        block() ?: "null"
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        "THREW ${cause.javaClass.name}: ${cause.message}"
    }

    private fun report(key: String, value: String) {
        // One logical line per key: the script greps for "PROBE <key>=".
        Log.i(TAG, "PROBE $key=${value.replace('\n', '⏎')}")
    }

    private companion object {
        const val TAG = "A17PROBE"
        const val NO_DEVICE = "00:00:00:00:00:00"
    }
}
