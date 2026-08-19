package dev.dankyeeter.btdashboard

import android.app.Application
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.monitor.MonitorGraph
import dev.dankyeeter.btdashboard.system.SystemGraph

class BtDashboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SystemGraph.init(this)
        HearingGraph.init(this)
        MonitorGraph.init(this)
        SystemGraph.shizuku.register()
        // Per-device profiles: listen for ACL connects for as long as we live.
        SystemGraph.startDeviceProfileAutoApply()
        // Feeds the dashboard's now-playing codec clause from the monitor.
    }
}
