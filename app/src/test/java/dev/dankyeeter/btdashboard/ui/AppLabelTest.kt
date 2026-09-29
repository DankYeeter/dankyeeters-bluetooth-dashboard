package dev.dankyeeter.btdashboard.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLabelTest {

    /** Without QUERY_ALL_PACKAGES a playing app can be invisible; it must still show, as its uid (AK-3). */
    @Test
    fun `a uid without a visible package reads as the uid, not as nothing`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        assertEquals("uid 12345", appLabel(context, 12345))
    }
}
