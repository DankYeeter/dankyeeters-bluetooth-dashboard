package dev.dankyeeter.btdashboard.privileged

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEADLINE_MS = 200L

/** AD-053: the helper's exec ends after its deadline even when stdout stays open. */
class ExecDeadlineTest {

    private val pipeClosed = CountDownLatch(1)

    @After
    fun closePipe() {
        pipeClosed.countDown()
    }

    @Test(timeout = 5_000)
    fun `a child that does not exit is killed and yields no output`() {
        val child = FakeProcess(exits = false, stdout = OpenStdout(pipeClosed))

        assertNull(awaitOutput(child, DEADLINE_MS))
        assertTrue("the child was left running", child.destroyed)
    }

    @Test(timeout = 5_000)
    fun `a child that exited while a grandchild keeps stdout open yields no output`() {
        val child = FakeProcess(exits = true, stdout = OpenStdout(pipeClosed))

        assertNull(awaitOutput(child, DEADLINE_MS))
    }

    @Test(timeout = 5_000)
    fun `a child that exits and closes its output yields both streams`() {
        val child = FakeProcess(exits = true, stdout = "state: ON".byteInputStream(), stderr = "warn".byteInputStream())

        assertEquals("state: ON" to "warn", awaitOutput(child, DEADLINE_MS))
    }
}

/** stdout of a process whose pipe some descendant still holds: no byte, no end, until [closed]. */
private class OpenStdout(private val closed: CountDownLatch) : InputStream() {
    override fun read(): Int {
        closed.await()
        return -1
    }
}

private class FakeProcess(
    private val exits: Boolean,
    private val stdout: InputStream,
    private val stderr: InputStream = ByteArrayInputStream(ByteArray(0)),
) : Process() {

    @Volatile
    var destroyed = false

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        if (!exits) Thread.sleep(unit.toMillis(timeout))
        return exits
    }

    override fun waitFor(): Int = error("an unbounded wait is what AD-053 removes")

    override fun exitValue(): Int = 0

    override fun destroy() {
        destroyed = true
    }

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
}
