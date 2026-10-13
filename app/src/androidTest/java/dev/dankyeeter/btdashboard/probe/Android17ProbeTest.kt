package dev.dankyeeter.btdashboard.probe

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.dankyeeter.btdashboard.monitor.dumpsys.DumpsysBluetoothParser
import dev.dankyeeter.btdashboard.monitor.link.live.A2dpLinkDumpParser
import dev.dankyeeter.btdashboard.privileged.HelperCleanup
import dev.dankyeeter.btdashboard.privileged.IPrivilegedService
import dev.dankyeeter.btdashboard.privileged.PrivilegedBootstrap
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.privileged.PrivilegedShellRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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

    /**
     * Waits for the helper to hand over its binder, then exercises every read-only call.
     *
     * The exec reply travels through a descriptor (AD-010). The app's own form
     * (3': unlinked app-private file) goes through [PrivilegedShellRunner]; the
     * two fallback forms the design names (memfd, pipe) are tried against the
     * raw service so a SELinux denial on one shows next to the others (U-0).
     */
    @Test
    fun helperRoundTrip() {
        val service = awaitService() ?: return
        val token = PrivilegedBootstrap(context).activeToken()
        report("HELPER_TOKEN", if (token == null) "MISSING" else "present")
        report("HELPER_VERSION", attempt { service.version().toString() })
        report("HELPER_CODEC_STATUS", attempt { service.codecStatus(token, NO_DEVICE) })
        report("HELPER_OPTIONAL_CODECS", attempt { service.optionalCodecs(token, NO_DEVICE) })

        val runner = PrivilegedShellRunner(context)
        report("HELPER_EXEC_ID", attempt { runBlocking { runner.run(listOf("id")) }.toString() })

        val bt = runBlocking { runner.run(listOf("dumpsys", "bluetooth_manager")) }
        report("HELPER_EXEC_FD_FILE", "exit=${bt.exitCode} stderr=${bt.stderr.take(200)}")
        report("HELPER_DUMPSYS_BT_LENGTH", bt.stdout.length.toString())
        report("PARSER_DUMPSYS", attempt { DumpsysBluetoothParser.parse(bt.stdout).toString() })
        report("PARSER_A2DP_LINK", attempt { A2dpLinkDumpParser.parse(bt.stdout).toString() })

        // Larger than the 64 KB binder reply limit; with a descriptor that no
        // longer matters, which is the point.
        val audio = runBlocking { runner.run(listOf("dumpsys", "audio")) }
        report("HELPER_EXEC_LARGE", "exit=${audio.exitCode} length=${audio.stdout.length} stderr=${audio.stderr.take(200)}")

        val command = listOf("dumpsys", "bluetooth_manager")
        report("SPIKE_MEMFD", attempt { memfdExec(service, token, command) })
        report("SPIKE_PIPE", attempt { pipeExec(service, token, command) })
        report("HELPER_EXEC_NULL_FD", attempt { service.exec(token, command, null) })
    }

    /**
     * Calls the helper many times with requests it must refuse.
     *
     * The script counts the helper's open descriptors before and after: every
     * refusal has to close the descriptor it was handed (SR-020).
     */
    @Test
    fun helperRefusals() {
        val service = awaitService() ?: return
        var refused = 0
        repeat(REFUSAL_CALLS) {
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                val reply = service.exec("wrong-token", listOf("id"), pipe[1])
                if (reply?.startsWith("ERR") == true) refused++
            } finally {
                pipe.forEach { it.close() }
            }
        }
        report("HELPER_REFUSED", "$refused of $REFUSAL_CALLS")
        val token = PrivilegedBootstrap(context).activeToken()
        report("HELPER_REFUSED_NOT_WHITELISTED", attempt { withPipe { service.exec(token, listOf("id", "-u"), it) } })
    }

    /** The settings action "Stop helper and clean up", as the user would trigger it (U-4). */
    @Test
    fun helperCleanup() {
        awaitService() ?: return
        val report = runBlocking { HelperCleanup(context).run() }
        report("HELPER_CLEANUP", "stopped=${report.stopped} files=${report.files}")
        report("HELPER_CLEANUP_TEXT", report.describe())
    }

    private fun awaitService(): IPrivilegedService? {
        val deadline = SystemClock.elapsedRealtime() + 75_000
        while (PrivilegedConnection.service.value == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(250)
        }
        val service = PrivilegedConnection.service.value
        report("HELPER_BIND", if (service == null) "FAILED no binder within 75 s" else "OK")
        return service
    }

    private fun memfdExec(service: IPrivilegedService, token: String?, command: List<String>): String {
        val memfd = Os.memfd_create("btdash_probe", 0)
        try {
            val reply = ParcelFileDescriptor.dup(memfd).use { service.exec(token, command, it) }
            val size = Os.fstat(memfd).st_size
            return "reply=${reply.take(120)} size=$size"
        } finally {
            Os.close(memfd)
        }
    }

    private fun pipeExec(service: IPrivilegedService, token: String?, command: List<String>): String {
        val (read, write) = ParcelFileDescriptor.createPipe()
        var received = 0L
        val reader = Thread {
            ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    received += n
                }
            }
        }.apply { start() }
        val reply = write.use { service.exec(token, command, it) }
        reader.join(20_000)
        return "reply=${reply.take(120)} received=$received"
    }

    private fun withPipe(block: (ParcelFileDescriptor) -> String?): String? {
        val pipe = ParcelFileDescriptor.createPipe()
        return try { block(pipe[1]) } finally { pipe.forEach { it.close() } }
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
        const val REFUSAL_CALLS = 200
    }
}
