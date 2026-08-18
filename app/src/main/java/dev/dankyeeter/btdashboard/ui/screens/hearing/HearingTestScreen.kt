package dev.dankyeeter.btdashboard.ui.screens.hearing

import androidx.compose.runtime.Composable
import dev.dankyeeter.btdashboard.ui.screens.StageStub

@Composable
fun HearingTestScreen() {
    StageStub(
        title = "Hearing Test",
        body = "Modified Hughson-Westlake pure-tone test at 250–8000 Hz, one ear at a " +
            "time, with an ambient-noise pre-check and a multi-run median audiogram.\n\n" +
            "This is audiometry-inspired consumer calibration without clinical " +
            "validity — not a substitute for professional hearing diagnostics.",
        owner = "Stage B (:core-hearing + :core-audio tone generator)",
    )
}
