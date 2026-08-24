package dev.dankyeeter.btdashboard

import android.app.Application
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.privileged.PrivilegedCodec
import dev.dankyeeter.btdashboard.privileged.PrivilegedCodecController
import dev.dankyeeter.btdashboard.privileged.PrivilegedShellRunner
import dev.dankyeeter.btdashboard.privileged.PrivilegedConnection
import dev.dankyeeter.btdashboard.privileged.adb.HelperAutoStart
import dev.dankyeeter.btdashboard.privileged.PrivilegedBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import dev.dankyeeter.btdashboard.privileged.adb.WirelessDebuggingSwitch
import kotlinx.coroutines.delay
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.service.EqForegroundService

class BtDashboardApplication : Application() {

    /**
     * Lives as long as the process. Used for the two things below that have to
     * outlast whatever started them: the permission grant, and serialising
     * activation attempts.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One activation at a time, process-wide.
     *
     * Two callers arriving together used to start two helpers, and the second
     * helper's first act was to retire the first - a whole runtime booted and
     * thrown away. Serialising them means the second caller waits, finds a
     * helper already attached, and returns.
     */
    private val activation = Mutex()

    /** Long enough for a just-started helper's launching shell to let go. */
    private val HELPER_SETTLE_MS = 6_000L

    override fun onCreate() {
        super.onCreate()
        SystemGraph.init(this)

        // The seam that lets a reboot fix itself.
        //
        // `:core-system` runs the boot restore but cannot reach the ADB stack,
        // which lives here. Installed on every process start, including the one
        // BOOT_COMPLETED creates - Application.onCreate always runs before any
        // receiver in the same process, so the boot receiver never finds this
        // null.
        //
        // Returns quickly when a helper is already attached: this is called
        // from a path that also runs on ordinary launches, and re-pairing
        // something that already works would be a slow way to change nothing.
        SystemGraph.activateHelper = {
            activation.withLock {
                if (PrivilegedConnection.isConnected) {
                    true
                } else {
                    HelperAutoStart(this).attempt() is HelperAutoStart.Outcome.Started
                }
            }
        }

        // Ask for WRITE_SECURE_SETTINGS whenever a helper turns up.
        //
        // Hung on the *arrival of a helper* rather than on the code path that
        // started one, which is the mistake this replaces: the grant sat at the
        // end of `HelperAutoStart`, a helper reached the app by some other
        // route first, and the grant was skipped every single time while
        // everything around it reported success.
        //
        // There is more than one way a helper appears - an activation, a
        // surviving helper reattaching after the app restarts, a reconnect -
        // and all of them end here. Cheap when the permission is already held:
        // that check is a local permission read, not a call into the helper.
        appScope.launch {
            PrivilegedConnection.service.collect { service ->
                if (service == null) return@collect
                val app = this@BtDashboardApplication
                val granted = PrivilegedBootstrap(app).grantSecureSettings()

                // And close the door behind it.
                //
                // This used to sit at the end of the activation, which turned
                // out to be a place that is often not reached: something starts
                // a helper before the activation runs, the activation sees one
                // already attached and returns early, and everything after that
                // point was skipped. Wireless debugging then stayed open
                // indefinitely - the opposite of why any of this exists.
                //
                // Hung on the helper being *connected* instead, for the same
                // reason the grant is: every route ends here, and it does not
                // matter which one was taken. A helper is attached, so adbd has
                // done its job and the port is nothing but exposure.
                //
                // The pause lets the activation finish first. The helper is
                // detached from the shell that started it, so it survives adbd
                // going away - but the shell may still be inside its start-up
                // grace period, and cutting that short is a race worth not
                // having.
                if (granted) {
                    delay(HELPER_SETTLE_MS)
                    val closed = WirelessDebuggingSwitch(app).disable()
                    Log.i("BtDashboard", "wireless debugging closed: $closed")
                }
            }
        }
        HearingGraph.init(this)
        MonitorGraph.init(this)
        // Our own privileged helper, when it is running. Everything shell-based
        // degrades honestly when it is not, so installing it unconditionally is
        // safe and needs no probing here.
        //
        // No token is created here any more. It is minted when the setup screen
        // generates the ADB command, which is the only moment it can reach a
        // helper anyway - see PrivilegedBootstrap for why the token now rotates
        // per session instead of being written once and kept forever.
        val shellRunner = PrivilegedShellRunner(this)
        MonitorGraph.installShellRunner(shellRunner)
        // The same helper, for a second reader: the EQ needs the session ids of
        // players that never announce themselves (Tidal). Only stdout is passed
        // on - the harvester keeps integers and throws the rest away.
        SystemGraph.installPrivilegedShell { command ->
            shellRunner.run(command).takeIf { it.exitCode == 0 }?.stdout
        }
        // Codec control needs the same helper. Installed unconditionally for
        // the same reason: without a helper the controller reports "cannot
        // check", which is what every layer above it is built to display.
        //
        // One instance, registered in two places: the profile applier reaches
        // it through SystemGraph, the diagnostic through PrivilegedCodec. Two
        // instances would mean two objects deciding independently whether the
        // helper is there.
        val codecController = PrivilegedCodecController(this)
        SystemGraph.installCodecPreferenceController(codecController)
        PrivilegedCodec.install(codecController)
        // Per-device profiles: listen for ACL connects for as long as we live.
        SystemGraph.startDeviceProfileAutoApply()
        // "As long as we live" used to be the problem: this process is whatever
        // Android leaves running, so the EQ died with a task swipe. The service
        // takes ownership of the audio chain and outlives the UI; starting it
        // here is legal because Application.onCreate for a launched app runs
        // while the app is visible.
        EqForegroundService.start(this)
        // The link monitor is documented as outliving any single screen, but it
        // only ever started when the Monitor tab was opened — so connects and
        // dropouts that happened anywhere else were never recorded. Events are
        // broadcast-driven and cost nothing; the sampler still idles itself to
        // zero work when nothing is playing.
        MonitorGraph.ensureRunning()
    }
}
