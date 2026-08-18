package dev.dankyeeter.btdashboard

import android.app.Application
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.hearing.HearingGraph
import dev.dankyeeter.btdashboard.system.SystemGraph

class BtDashboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SystemGraph.init(this)
        HearingGraph.init(this)
        SystemGraph.shizuku.register()
    }
}
