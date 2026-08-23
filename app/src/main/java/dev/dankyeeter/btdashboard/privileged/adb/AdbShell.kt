package dev.dankyeeter.btdashboard.privileged.adb

import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * Runs one shell command over an authenticated ADB connection.
 *
 * This is the last link in the chain: with `adbd` trusting the app's key, the
 * app can open a shell stream on its own phone and start the privileged helper
 * there - no computer, no cable.
 *
 * ## How an ADB stream works
 *
 * `OPEN` asks for a service by name and carries a **local id** the client
 * chooses. The daemon answers `OKAY` with its own id, and from then on both
 * sides address each other by the pair. Output arrives as `WRTE`, and every
 * single one has to be answered with `OKAY` - the daemon waits for that
 * acknowledgement before sending more, so a client that forgets it reads the
 * first chunk of output and then hangs forever, which looks exactly like a
 * command that never finishes.
 *
 * `CLSE` ends the stream, and courtesy requires sending one back.
 *
 * ## Why plain `shell:` and not `shell,v2:`
 *
 * v2 multiplexes stdout, stderr and the exit code into a framed sub-protocol.
 * That is worth having when the output matters. Here the command either starts
 * a background process or does not, the app checks the *result* by looking for
 * the helper, and v2 would add a second framing layer to parse for no gain.
 */
internal object AdbShell {

    /**
     * @param command passed to the device shell verbatim.
     * @return everything the command wrote, or null if the stream never opened.
     */
    fun execute(
        input: InputStream,
        output: OutputStream,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): String? {
        val localId = LOCAL_ID
        // The trailing NUL is part of the service name, not decoration: adbd
        // reads it as a C string and rejects the request without it.
        AdbMessage(
            command = AdbMessage.A_OPEN,
            arg0 = localId,
            arg1 = 0,
            payload = "shell:$command\u0000".toByteArray(Charsets.UTF_8),
        ).writeTo(output)

        var remoteId = 0
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val message = runCatching { AdbMessage.readFrom(input) }.getOrElse { return null }
            when (message.command) {
                AdbMessage.A_OKAY -> remoteId = message.arg0

                AdbMessage.A_WRTE -> {
                    collected.append(String(message.payload, Charsets.UTF_8))
                    // Acknowledge before doing anything else, or the daemon
                    // stops sending and this loop waits out its timeout.
                    AdbMessage(AdbMessage.A_OKAY, localId, message.arg0).writeTo(output)
                    remoteId = message.arg0
                }

                AdbMessage.A_CLSE -> {
                    runCatching {
                        AdbMessage(AdbMessage.A_CLSE, localId, remoteId).writeTo(output)
                    }
                    return collected.toString()
                }

                else -> Log.w(TAG, "unexpected $message while running a command")
            }
        }
        Log.w(TAG, "command timed out after ${timeoutMs}ms: $command")
        return collected.toString()
    }

    private const val TAG = "AdbShell"

    /**
     * Any non-zero value works; the daemon only has to tell our streams apart,
     * and this client opens one at a time.
     */
    private const val LOCAL_ID = 1

    /**
     * The helper command returns immediately - it backgrounds itself with
     * `nohup ... &`. This bound exists for the case where the daemon accepts
     * the stream and then says nothing, which would otherwise block the UI
     * thread's coroutine indefinitely.
     */
    private const val DEFAULT_TIMEOUT_MS = 15_000L
}
