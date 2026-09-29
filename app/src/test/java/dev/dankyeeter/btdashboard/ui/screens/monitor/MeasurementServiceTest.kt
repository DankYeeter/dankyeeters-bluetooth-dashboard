package dev.dankyeeter.btdashboard.ui.screens.monitor

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.dankyeeter.btdashboard.MainActivity
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.monitor.link.live.LinkLiveSnapshot
import dev.dankyeeter.btdashboard.monitor.optimize.Condition
import dev.dankyeeter.btdashboard.monitor.optimize.ConditionValue
import dev.dankyeeter.btdashboard.monitor.optimize.Measure
import dev.dankyeeter.btdashboard.monitor.sampling.SamplingPolicy
import dev.dankyeeter.btdashboard.privileged.adb.PairingCodeNotification
import dev.dankyeeter.btdashboard.system.boot.BootReceiver
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.service.EqForegroundService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** AD-039's measurement service against the T-055s conditions V13-V21 and B1-B3. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeasurementServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val pinScope = TestScope()
    private var pins = 0

    @Before
    fun noCaptureLeftOver() = MonitorGraph.engine.stopDeepCapture()

    /** Both handles are process-wide; no test may leave a measurement behind for the next. */
    @After
    fun release() {
        ActiveComparison.set(null)
        MonitorGraph.engine.stopDeepCapture()
    }

    // ---- what runs (pure) ----------------------------------------------------

    @Test
    fun `an arm is a comparison, from its pin on`() {
        assertEquals(RunningMeasurement("A", false, SINCE_MS), runningMeasurement(held(ComparisonPhase.PIN_AND_CHECK), 0, NOW_MS))
        assertEquals(RunningMeasurement("A", false, SINCE_MS), runningMeasurement(held(ComparisonPhase.ARM_A), 0, NOW_MS))
        assertEquals(RunningMeasurement("B", false, SINCE_MS), runningMeasurement(held(ComparisonPhase.ARM_B), 0, NOW_MS))
    }

    /** T-055s B1: between the arms nothing counts, so nothing holds the foreground. */
    @Test
    fun `instruct holds nothing`() {
        assertNull(runningMeasurement(held(ComparisonPhase.INSTRUCT), 0, NOW_MS))
    }

    @Test
    fun `a deep capture runs until its end and not after`() {
        assertEquals(RunningMeasurement(null, true, NOW_MS + 1), runningMeasurement(null, NOW_MS + 1, NOW_MS))
        assertNull(runningMeasurement(null, NOW_MS, NOW_MS))
        assertEquals(RunningMeasurement("A", true, SINCE_MS), runningMeasurement(held(ComparisonPhase.ARM_A), NOW_MS + 1, NOW_MS))
    }

    /** V19 and the UI_SPEC table; the elapsed time is the system's chronometer, so the text names the length. */
    @Test
    fun `the notification says what runs and nothing about the device`() {
        val armA = RunningMeasurement("A", false, SINCE_MS)
        val armB = RunningMeasurement("B", false, SINCE_MS)
        val deep = RunningMeasurement(null, true, NOW_MS)
        val both = RunningMeasurement("A", true, SINCE_MS)

        assertEquals("BT Dashboard — comparing", armA.title())
        assertEquals("Arm A: counting 15 min. Ends on its own, or tap Stop.", armA.text())
        assertEquals("Arm B: counting 15 min. Ends on its own, or tap Stop.", armB.text())
        assertEquals("BT Dashboard — watching live", deep.title())
        assertEquals("Up to 5 min. Ends on its own, or tap Stop.", deep.text())
        assertEquals(
            "Arm A: counting 15 min, and watching live. Ends on its own, or tap Stop to end both.",
            both.text(),
        )
        listOf(armA, armB, deep, both).flatMap { listOf(it.title(), it.text()) }
            .forEach { assertFalse("an address in \"$it\"", MAC.containsMatchIn(it)) }
    }

    /** V21: the engine counts on the wall clock; a clock set back an hour must not stretch the capture. */
    @Test
    fun `a wall clock set back an hour does not stretch a deep capture`() = runTest {
        val window = SamplingPolicy.DEEP_CAPTURE_WINDOW_MS
        val held = MutableStateFlow<ActiveComparison.Held?>(null)
        val deepUntil = MutableStateFlow(NOW_MS + window)
        var stoppedAt: Long? = null
        val wallClock = { NOW_MS + currentTime - if (currentTime >= JUMP_AT_MS) HOUR_MS else 0L }
        backgroundScope.launch {
            measurements(held, deepUntil, wallClock, { currentTime }, {
                stoppedAt = currentTime
                deepUntil.value = 0L
            }).collect {}
        }
        advanceTimeBy(JUMP_AT_MS + 1)
        // Anything that makes it look again, now on the set-back clock.
        held.value = ActiveComparison.Held(ComparisonPhase.ARM_A, NOW_MS, {})
        advanceTimeBy(HOUR_MS + window)

        assertEquals(window, stoppedAt)
    }

    // ---- the service (Robolectric) --------------------------------------------

    /** V15, V16, B3: Stop ends the comparison without a result and the capture, and writes nothing. */
    @Test
    fun `Stop in the notification ends the comparison and the capture and writes nothing`() {
        val control = comparisonInArmA()
        MonitorGraph.engine.startDeepCapture()
        val service = startedService()
        assertTrue(MeasurementService.foreground.value)

        val stop = Intent(context, MeasurementService::class.java)
            .setAction(MeasurementService.ACTION_STOP)
            .putExtra("dev.dankyeeter.btdashboard.OPEN_ROUTE", "wizard")
        val result = service.onStartCommand(stop, 0, 2)
        idle()

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(ComparisonPhase.CHOOSE, control.ui.value.phase)
        assertEquals(ComparisonEnd.Cancelled, control.ui.value.ended)
        assertEquals(Measure.USB_CABLE_OFF, control.ui.value.measure)
        assertEquals(0L, MonitorGraph.engine.status.value.deepCaptureUntilMs)
        assertEquals("the comparison's own pin, and nothing after it", 1, pins)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    /** V15: nothing running — a stop, a plain start, an unknown action — ends it without going foreground. */
    @Test
    fun `with nothing running the service ends without going foreground`() {
        val intents = listOf(
            null,
            Intent(context, MeasurementService::class.java).setAction(MeasurementService.ACTION_STOP),
            Intent(context, MeasurementService::class.java).setAction("something.else"),
        )
        intents.forEach { intent ->
            val service = Robolectric.buildService(MeasurementService::class.java).create().get()

            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
            assertTrue("stopped for $intent", shadowOf(service).isStoppedBySelf)
            assertNull("foreground for $intent", shadowOf(service).lastForegroundNotification)
        }
    }

    @Test
    fun `the service ends once the comparison stops holding it`() {
        val control = comparisonInArmA()
        val service = startedService()

        control.onReset()
        idle()

        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    /** V14, V19, B2: the posted notification, its two intents and its channel. */
    @Test
    fun `the notification is public, silent after the first time, and its intents are immutable and bare`() {
        comparisonInArmA()
        val service = startedService()
        val notification = requireNotNull(shadowOf(service).lastForegroundNotification)

        assertEquals("BT Dashboard — comparing", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Arm A: counting 15 min. Ends on its own, or tap Stop.",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals(SINCE_MS, notification.`when`)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)

        val open = shadowOf(notification.contentIntent)
        assertTrue(open.isActivity && open.isImmutable)
        assertEquals(ComponentName(context, MainActivity::class.java), open.savedIntent.component)
        assertNull(open.savedIntent.extras)

        val action = notification.actions.single()
        assertEquals("Stop", action.title)
        val stop = shadowOf(action.actionIntent)
        assertTrue(stop.isService && stop.isImmutable)
        assertEquals(ComponentName(context, MeasurementService::class.java), stop.savedIntent.component)
        assertEquals(MeasurementService.ACTION_STOP, stop.savedIntent.action)
        assertNull(stop.savedIntent.extras)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(MeasurementService.CHANNEL_ID).importance)
    }

    /** V18: a refused foreground is an answer, not a crash. */
    @Test
    fun `a refused foreground leaves foreground false and ends the service`() {
        listOf(SecurityException("refused"), IllegalStateException("refused")).forEach { refusal ->
            RefusingMeasurementService.refusal = refusal
            comparisonInArmA()
            val service = Robolectric.buildService(RefusingMeasurementService::class.java).create().get()

            service.onStartCommand(null, 0, 1)

            assertFalse(MeasurementService.foreground.value)
            assertTrue(shadowOf(service).isStoppedBySelf)
            ActiveComparison.set(null)
        }
    }

    @Test
    fun `foreground is false again once the service is gone`() {
        comparisonInArmA()
        val controller = Robolectric.buildService(MeasurementService::class.java).create()
        controller.get().onStartCommand(null, 0, 1)
        assertTrue(MeasurementService.foreground.value)

        controller.destroy()

        assertFalse(MeasurementService.foreground.value)
    }

    /** V20: its own channel and id, clear of the EQ service, the boot notice and the pairing prompt. */
    @Test
    fun `the channel and the notification id are its own`() {
        val pairingId = PairingCodeNotification::class.java.getDeclaredField("NOTIFICATION_ID")
            .apply { isAccessible = true }.getInt(null)
        val ids = listOf(MeasurementService.NOTIFICATION_ID, EqForegroundService.NOTIFICATION_ID, BootReceiver.NOTIFICATION_ID, pairingId)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(MeasurementService.CHANNEL_ID != EqForegroundService.CHANNEL_ID)
    }

    // ---- the source (V13, V16, V17) -------------------------------------------

    /** V13: the declaration, and no wake lock anywhere. */
    @Test
    fun `the manifest declares the service closed, typed and tied to the task`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val services = document.getElementsByTagName("service")
        val service = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ANDROID, "name") == ".ui.screens.monitor.MeasurementService" }

        assertEquals("false", service.getAttributeNS(ANDROID, "exported"))
        assertEquals("connectedDevice", service.getAttributeNS(ANDROID, "foregroundServiceType"))
        assertEquals("true", service.getAttributeNS(ANDROID, "stopWithTask"))
        assertFalse(service.hasAttributeNS(ANDROID, "process"))
        assertEquals(0, service.getElementsByTagName("intent-filter").length)

        val permissions = document.getElementsByTagName("uses-permission")
        val declared = (0 until permissions.length).map { (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(ANDROID, "name") }
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"),
            declared,
        )

        mainDirs().map { File(it, "AndroidManifest.xml") }.filter { it.isFile }
            .forEach { assertFalse("WAKE_LOCK in $it", "WAKE_LOCK" in it.readText()) }
    }

    /** V16: the service reaches no writer at all — no settings, no ledger, no profile, no helper. */
    @Test
    fun `the service source names no writer`() {
        val text = File(root, "app/src/main/java/dev/dankyeeter/btdashboard/ui/screens/monitor/MeasurementService.kt").readText()
        listOf("SystemGraph", "SettingsRestore", "LdacTuning", "Ledger", "DeviceProfile", "Privileged")
            .forEach { assertFalse("$it in MeasurementService.kt", it in text) }
    }

    /** V17 with the director's "Start Arm B": started from a tap handler and from nothing else. */
    @Test
    fun `the service is started only from the four tap handlers`() {
        val callers = mainDirs().flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
            .flatMap { file ->
                val lines = file.readLines()
                lines.indices.filter { "MeasurementService.start(" in lines[it] }.map { index ->
                    val function = lines.subList(0, index + 1).asReversed()
                        .firstNotNullOf { FUNCTION.find(it)?.groupValues?.get(1) }
                    "${file.name}:$function"
                }
            }
        assertEquals(
            setOf(
                "MonitorViewModel.kt:onCompare",
                "MonitorViewModel.kt:onCheck",
                "MonitorViewModel.kt:startDeepCapture",
                "BluetoothDashboardViewModel.kt:startWatchLive",
            ),
            callers.toSet(),
        )
        assertEquals("one call per handler", 4, callers.size)
    }

    // ---- helpers ---------------------------------------------------------------

    private fun held(phase: ComparisonPhase) = ActiveComparison.Held(phase, SINCE_MS, {})

    /** A real controller with a counting pin, placed in arm A the way a tap on "Compare" does. */
    private fun comparisonInArmA(): ComparisonController {
        val control = ComparisonController(
            scope = pinScope,
            pin = {
                pins++
                CodecApplyOutcome.Applied("LDAC 990")
            },
            readBook = { _, _ -> mapOf(Condition.USB_POWER to ConditionValue.Read("usb")) },
            clock = { SINCE_MS },
        )
        control.onReading(LinkLiveSnapshot(timestampMs = NOW_MS), STEP_MS)
        control.onMeasure(Measure.USB_CABLE_OFF)
        control.onCompare()
        pinScope.runCurrent()
        check(control.ui.value.phase == ComparisonPhase.ARM_A)
        return control
    }

    private fun startedService(): MeasurementService {
        val service = Robolectric.buildService(MeasurementService::class.java).create().get()
        service.onStartCommand(null, 0, 1)
        idle()
        assertNotNull("promoted", shadowOf(service).lastForegroundNotification)
        return service
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    }

    private fun mainDirs(): List<File> =
        root.listFiles().orEmpty().map { File(it, "src/main") }.filter { it.isDirectory }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val NOW_MS = 1_800_000_000_000L
        const val SINCE_MS = NOW_MS - 60_000L
        const val STEP_MS = 1_000L
        const val JUMP_AT_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        val MAC = Regex("""(?:[0-9A-Fa-fX]{2}:){5}[0-9A-Fa-f]{2}""")
        val FUNCTION = Regex("""\bfun\s+(\w+)\s*\(""")
    }
}

/** Android refusing the foreground service, the way a newer release or a missing permission does. */
class RefusingMeasurementService : MeasurementService() {
    override fun promote(notification: Notification) = throw refusal

    companion object {
        var refusal: RuntimeException = SecurityException("refused")
    }
}
