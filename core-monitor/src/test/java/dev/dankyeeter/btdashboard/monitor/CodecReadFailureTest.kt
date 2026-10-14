package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.codec.CodecReadResult
import dev.dankyeeter.btdashboard.monitor.codec.codecReadFailure
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reason a failed codec read carries is shown as-is on the Bluetooth tab.
 * It used to read "InvocationTargetException": the reflective call wraps what
 * the system threw, and the wrapper has no message, so its class name was the
 * text. These pin the unwrapping and the fixed wording.
 */
class CodecReadFailureTest {

    @Test
    fun `a wrapped SecurityException is read as the missing permission it is`() {
        val thrown = InvocationTargetException(SecurityException("Need BLUETOOTH_PRIVILEGED permission"))

        assertEquals(
            CodecReadResult.Unsupported("privileged permission required"),
            codecReadFailure(thrown),
        )
    }

    @Test
    fun `any other failure shows a fixed sentence, not the exception`() {
        val thrown = InvocationTargetException(IllegalStateException("uid 10234 not bound"))

        assertEquals(
            CodecReadResult.Failed("the codec read failed, so it cannot be checked"),
            codecReadFailure(thrown),
        )
    }

    @Test
    fun `a missing hidden method is still told apart`() {
        assertEquals(
            CodecReadResult.Unsupported("API not present on this build"),
            codecReadFailure(NoSuchMethodException("getCodecStatus")),
        )
    }
}
