package dev.dankyeeter.btdashboard.privileged

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.dankyeeter.btdashboard.monitor.codec.CodecController
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.NoOpCodecController
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner
import dev.dankyeeter.btdashboard.system.devices.BluetoothCodecOptions
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.CodecPreferenceController
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates and remembers the tokens, and produces the one command the user runs.
 *
 * The token is what tells this app's helper apart from anything else that might
 * call the app's exported provider. It is generated here, reaches the helper
 * only because the user pasted it into an ADB command, and never leaves the
 * device.
 *
 * ## Why there are two of them
 *
 * There used to be one, stored once and reused forever: the same token that was
 * on disk months ago still opened the door. Rotating it naively breaks the case
 * that matters most — a helper that is already connected keeps sending the
 * token it was started with, so replacing the stored value mid-session would
 * make every subsequent call fail with "bad token" and look exactly like the
 * helper having crashed.
 *
 * So the two states are kept apart:
 *
 *  - **active** — what the currently connected helper answers to. This is what
 *    [PrivilegedShellRunner] and the codec controller send on every call.
 *  - **pending** — the token embedded in the most recently generated ADB
 *    command. No helper is using it yet.
 *
 * The provider accepts a hand-over carrying *either*, and the active token is
 * replaced at exactly one moment: when a helper presenting the pending token is
 * accepted ([promote]). Never when a command is generated. That is the whole
 * ordering rule, and it is why generating a new command cannot disturb a
 * working helper.
 *
 * Only the newest pending token is ever accepted, because generating a command
 * overwrites it. An ADB command from an older session is therefore dead the
 * moment a newer one exists.
 *
 * ## Why a session and not a call
 *
 * Minting on every single call to [adbCommand] would be strictly more
 * rotation and strictly worse: the setup screen and the wizard both render the
 * command, a recomposition would silently invalidate the line the user has
 * already copied to their clipboard, and they would paste a command that the
 * app has just decided not to honour. So a token is minted once per app process
 * and reused while that process lives — a *session* — with [newAdbCommand] for
 * when the user deliberately wants a fresh one.
 */
class PrivilegedBootstrap(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("privileged", Context.MODE_PRIVATE)

    private val packageName: String = context.applicationContext.packageName

    /** Which of the two tokens a hand-over presented. */
    sealed interface TokenMatch {
        /** From the most recent ADB command. Accepting it rotates the active token. */
        data class Pending(val token: String) : TokenMatch

        /**
         * The token the app is already using for its live calls.
         *
         * Accepted because refusing it would be refusing the token this app
         * itself considers current — the case is a user re-running an older
         * copy of the command that happens to carry it, and telling them "that
         * command no longer works" while the app is still sending that very
         * token would be a lie. Nothing rotates on this path.
         */
        data class Active(val token: String) : TokenMatch
    }

    /** What live calls must carry, or null when no helper has ever been accepted. */
    fun activeToken(): String? = prefs.getString(KEY_ACTIVE, null)

    /** The token in the most recently generated command, if one is outstanding. */
    fun pendingToken(): String? = prefs.getString(KEY_PENDING, null)

    /**
     * Classifies a token offered by a hand-over. Null means "neither" — refuse.
     *
     * Pending is tested first so that re-running the newest command always
     * rotates, even in the corner case where a mint happened to produce the
     * value already in use.
     */
    fun match(offered: String?): TokenMatch? {
        val pending = pendingToken()
        if (PrivilegedProtocol.tokensMatch(offered, pending)) {
            return TokenMatch.Pending(pending!!)
        }
        val active = activeToken()
        if (PrivilegedProtocol.tokensMatch(offered, active)) {
            return TokenMatch.Active(active!!)
        }
        return null
    }

    /**
     * Makes [token] the active one and clears the pending slot.
     *
     * @return the token the outgoing helper answers to, so the caller can shut
     *   it down. Null when there was no previous helper.
     */
    fun promote(token: String): String? {
        val previous = activeToken()
        prefs.edit()
            .putString(KEY_ACTIVE, token)
            .remove(KEY_PENDING)
            .apply()
        // The command shown from here on must carry a *new* token: the one that
        // was pending has just been spent.
        mintedThisSession = null
        return previous?.takeIf { it != token }
    }

    /**
     * The command the user runs once per boot, from a computer with ADB.
     *
     * Stable for the life of this app process — see the class documentation for
     * why that matters to somebody holding a copied line in their clipboard.
     *
     * `nohup … &` matters: without it the helper is a child of the adb shell
     * session and dies the moment that session closes, which looks exactly like
     * the helper having crashed. `$(pm path …)` is evaluated **on the device**,
     * so the whole thing is single-quoted.
     */
    fun adbCommand(): String = command(sessionToken())

    /** Deliberately invalidates the previous command and returns a new one. */
    fun newAdbCommand(): String {
        mintedThisSession = null
        return adbCommand()
    }

    private fun command(token: String): String = "adb shell '" + shellCommand(token) + "'"

    /**
     * The same command without the `adb shell '...'` wrapper.
     *
     * The app can now open a shell on its own phone and run this itself. There
     * is no `adb` on that path, and the surrounding quotes would become part of
     * the command rather than protecting it.
     *
     * Deliberately next to [command] rather than in the ADB client: the two
     * must stay identical in substance, and the surest way to let them drift is
     * to keep them in different files.
     */
    fun deviceShellCommand(): String = shellCommand(sessionToken())

    private fun shellCommand(token: String): String = buildString {
        append("CLASSPATH=$(pm path ")
        append(packageName)
        append(" | grep base.apk | cut -d: -f2) nohup app_process /system/bin ")
        append("--nice-name=${PrivilegedContract.HELPER_PROCESS_NAME} ")
        append("dev.dankyeeter.btdashboard.privileged.PrivilegedServer ")
        append(token)
        append(" >/dev/null 2>&1 &")
    }

    /**
     * The pending token for this process, minted on first use.
     *
     * Held in the process as well as on disk because the on-disk value is what
     * the provider checks, while the in-process value is what decides whether
     * this session has already minted. Without the second one, every
     * `PrivilegedBootstrap(context)` — and they are constructed freely — would
     * be a new session.
     */
    private fun sessionToken(): String = synchronized(LOCK) {
        mintedThisSession?.let { return it }
        val minted = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_PENDING, minted).apply()
        mintedThisSession = minted
        minted
    }

    private companion object {
        const val KEY_ACTIVE = "token"
        const val KEY_PENDING = "pending_token"

        val LOCK = Any()

        /**
         * Process-wide on purpose. The key stays `"token"` so an install that
         * predates the split keeps its active token — and therefore its running
         * helper — instead of being told to set everything up again. That
         * helper will still be refused for being version 1, but by the version
         * check, with a message that says so.
         */
        @Volatile
        var mintedThisSession: String? = null
    }
}

/**
 * [ShellRunner] talking to this app's own privileged helper over its Binder.
 *
 * A drop-in replacement for the Shizuku-backed runner: everything downstream —
 * the dumpsys fallback, the foreign-EQ scan, the quality reports — goes through
 * the same interface and needs no change.
 *
 * The helper is not started from here and cannot be: only ADB can put a process
 * on the shell uid. This class uses the connection if [PrivilegedProvider]
 * accepted one, and reports itself unavailable otherwise so `MonitorGraph`
 * falls back to Shizuku.
 */
class PrivilegedShellRunner(
    context: Context,
    private val bootstrap: PrivilegedBootstrap = PrivilegedBootstrap(context),
) : ShellRunner {

    /**
     * Whether the helper is connected.
     *
     * A live binder rather than a cached flag: the helper dies on reboot, and
     * the death recipient in [PrivilegedConnection] is what turns this false
     * instead of leaving a stale proxy that fails on every call.
     */
    override val isAvailable: Boolean
        get() = PrivilegedConnection.isConnected

    override suspend fun run(command: List<String>): ShellResult = withContext(Dispatchers.IO) {
        // Refused here as well as in the helper. The helper's copy is the one
        // that matters for safety; this one turns a mistake into a clear
        // message instead of a round trip.
        if (!PrivilegedProtocol.isAllowed(command)) {
            return@withContext ShellResult(-1, "", "not a whitelisted command: $command")
        }

        val service = PrivilegedConnection.service.value
            ?: return@withContext ShellResult(-1, "", "privileged helper is not running")
        val token = bootstrap.activeToken()
            ?: return@withContext ShellResult(-1, "", "no token for the privileged helper")

        runCatching {
            val reply = service.exec(token, command)
                ?: return@runCatching ShellResult(-1, "", "helper returned nothing")

            PrivilegedProtocol.decodeResult(reply)?.let { (exit, out, err) ->
                ShellResult(exit, out, err)
            } ?: ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = PrivilegedProtocol.decodeError(reply) ?: "unreadable reply",
            )
        }.getOrElse {
            // A dead binder throws here rather than returning; the death
            // recipient will clear the connection, but this call still has to
            // answer something honest.
            Log.w(TAG, "privileged helper call failed", it)
            PrivilegedConnection.forget()
            ShellResult(-1, "", "privileged helper stopped responding")
        }
    }

    private companion object {
        const val TAG = "PrivilegedShell"
    }
}

/**
 * Where the rest of the app picks up codec control.
 *
 * `MonitorViewModel` is a plain `ViewModel` with no Context, and it should stay
 * that way — so the one controller the Application builds is registered here
 * and handed out on demand. Resolved per call, never cached by the caller: the
 * helper can connect or die at any moment, and a diagnostic started an hour
 * after the screen opened must ask again.
 *
 * [controller] deliberately falls back to [NoOpCodecController] rather than
 * returning a live controller that would answer "no codecs" — the diagnostic
 * words an empty list as "needs privileged access we do not have", which is the
 * truth when the helper is absent and a lie when it is present.
 */
object PrivilegedCodec {

    @Volatile
    private var installed: PrivilegedCodecController? = null

    fun install(controller: PrivilegedCodecController) {
        installed = controller
    }

    /** The real controller while the helper is answering, the honest stub otherwise. */
    fun controller(): CodecController =
        installed?.takeIf { it.isAvailable() } ?: NoOpCodecController
}

/** Where a codec answer came from, and what it actually said. */
sealed interface CodecCallResult {
    data class Observed(val observation: CodecObservation) : CodecCallResult

    /** No helper, no token, or the call itself failed. Never a silent empty. */
    data class Unavailable(val reason: String) : CodecCallResult
}

/**
 * Codec control through the privileged helper.
 *
 * ## Why one class implements two interfaces
 *
 * `CodecController` lives in `:core-monitor` (the diagnostic uses it) and
 * `CodecPreferenceController` lives in `:core-system` (the profile applier uses
 * it). `:core-system` does not depend on `:core-monitor` and should not start
 * now — the two modules are deliberately unaware of each other. `:app` sees
 * both, so the one place that can satisfy both contracts is here, and doing it
 * with one object is what keeps a single codec request from having two
 * different code paths.
 *
 * ## What it will and will not claim
 *
 * Nothing here reports success from a call that returned without throwing. The
 * helper reads the codec back and says what it saw; this class passes that
 * through unchanged. When the helper is absent the answer is "cannot check",
 * never "no codecs available" — those look identical in a list and mean
 * opposite things.
 */
class PrivilegedCodecController(
    context: Context,
    private val bootstrap: PrivilegedBootstrap = PrivilegedBootstrap(context),
) : CodecController, CodecPreferenceController {

    override fun isAvailable(): Boolean =
        PrivilegedConnection.isConnected && bootstrap.activeToken() != null

    /** What the headphone advertised, narrowed to what this app can request. */
    override suspend fun availableCodecs(address: String): List<CodecFamily> =
        when (val result = read(address)) {
            is CodecCallResult.Observed ->
                result.observation.selectableFamilies
                    .filter { it in A2dpCodecMasks.writableFamilies }

            is CodecCallResult.Unavailable -> {
                Log.i(TAG, "codec capabilities unreadable: ${result.reason}")
                emptyList()
            }
        }

    /**
     * Asks for a codec family and nothing else.
     *
     * Returns the family only when the read-back agreed. A request that was
     * accepted but not observed comes back null — the diagnostic's wording for
     * that is "could not be applied", which is the truth.
     */
    override suspend fun selectCodec(address: String, codec: CodecFamily): CodecFamily? =
        when (val result = request(address, CodecRequest(family = codec))) {
            is CodecCallResult.Observed ->
                result.observation.takeIf { it.matched == true }?.codecFamily

            is CodecCallResult.Unavailable -> null
        }

    override suspend fun apply(
        address: String,
        preference: CodecPreference,
    ): CodecApplyOutcome {
        // System Default is an action, not a codec: it skips the family
        // mapping entirely and travels as a sentinel type. There is nothing to
        // "match" afterwards — whatever the stack renegotiates is the point —
        // so the raw call is used and any observation counts as applied.
        if (preference.codec == BluetoothCodecOptions.SYSTEM_DEFAULT) {
            return when (val result = call { service, token ->
                service.setCodecPreference(
                    token, address, A2dpCodecMasks.SYSTEM_DEFAULT_SENTINEL, 0, 0, 0, 0L,
                )
            }) {
                is CodecCallResult.Unavailable -> CodecApplyOutcome.Unavailable(result.reason)
                is CodecCallResult.Observed ->
                    CodecApplyOutcome.Applied(result.observation.summary)
            }
        }

        val family = CodecFamily.entries.firstOrNull { it.name == preference.codec }
            ?: return CodecApplyOutcome.Unavailable(
                "\"${preference.codec}\" is not a codec this app knows",
            )
        val request = CodecRequest(
            family = family,
            sampleRateHz = preference.sampleRateHz,
            bitsPerSample = preference.bitsPerSample,
            channelMode = preference.channelMode,
            ldacQuality = preference.ldacQuality,
        )
        return when (val result = request(address, request)) {
            is CodecCallResult.Unavailable -> CodecApplyOutcome.Unavailable(result.reason)

            is CodecCallResult.Observed -> {
                val observed = result.observation
                if (observed.matched == true) {
                    CodecApplyOutcome.Applied(observed.summary)
                } else {
                    CodecApplyOutcome.NotObserved(observed.summary, observed.note)
                }
            }
        }
    }

    /** Reads the codec status through the helper. */
    private suspend fun read(address: String): CodecCallResult = call { service, token ->
        service.codecStatus(token, address)
    }

    /** The one mutating call this app makes. */
    private suspend fun request(address: String, requested: CodecRequest): CodecCallResult {
        A2dpCodecMasks.reject(requested)?.let { return CodecCallResult.Unavailable(it) }
        val codecType = A2dpCodecMasks.codecType(requested.family)
            ?: return CodecCallResult.Unavailable(
                "${requested.family.displayName} cannot be requested on this Android version",
            )
        return call { service, token ->
            service.setCodecPreference(
                token,
                address,
                codecType,
                requested.sampleRateHz,
                requested.bitsPerSample,
                requested.channelMode,
                requested.ldacQuality,
            )
        }
    }

    private suspend fun call(
        block: (IPrivilegedService, String) -> String?,
    ): CodecCallResult = withContext(Dispatchers.IO) {
        val service = PrivilegedConnection.service.value
            ?: return@withContext CodecCallResult.Unavailable(
                "the privileged helper is not running, so the codec cannot be checked",
            )
        val token = bootstrap.activeToken()
            ?: return@withContext CodecCallResult.Unavailable("no token for the privileged helper")

        runCatching {
            val reply = block(service, token)
                ?: return@runCatching CodecCallResult.Unavailable("the helper returned nothing")
            PrivilegedProtocol.decodeCodec(reply)?.let { CodecCallResult.Observed(it) }
                ?: CodecCallResult.Unavailable(
                    PrivilegedProtocol.decodeError(reply) ?: "unreadable reply from the helper",
                )
        }.getOrElse {
            Log.w(TAG, "privileged codec call failed", it)
            PrivilegedConnection.forget()
            CodecCallResult.Unavailable("the privileged helper stopped responding")
        }
    }

    private companion object {
        const val TAG = "PrivilegedCodec"
    }
}
