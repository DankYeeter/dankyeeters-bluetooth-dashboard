package dev.dankyeeter.btdashboard

import android.app.Application
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.privileged.PrivilegedCodec
import dev.dankyeeter.btdashboard.privileged.PrivilegedCodecController
import dev.dankyeeter.btdashboard.privileged.PrivilegedShellRunner
import dev.dankyeeter.btdashboard.system.SystemGraph
import dev.dankyeeter.btdashboard.system.service.EqForegroundService

class BtDashboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SystemGraph.init(this)
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
        MonitorGraph.installShellRunner(PrivilegedShellRunner(this))
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
