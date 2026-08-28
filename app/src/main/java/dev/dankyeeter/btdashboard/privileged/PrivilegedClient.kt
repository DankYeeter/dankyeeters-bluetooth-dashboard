package dev.dankyeeter.btdashboard.privileged

import android.content.Context
import android.content.SharedPreferences
import dev.dankyeeter.btdashboard.privileged.adb.WirelessDebuggingSwitch
import android.util.Log
import dev.dankyeeter.btdashboard.monitor.codec.CodecController
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.codec.NoOpCodecController
import dev.dankyeeter.btdashboard.monitor.shell.ShellResult
import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner
import dev.dankyeeter.btdashboard.system.devices.BluetoothCodecOptions
import dev.dankyeeter.btdashboard.system.devices.BluetoothRestartController
import dev.dankyeeter.btdashboard.system.devices.BluetoothRestartOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecApplyOutcome
import dev.dankyeeter.btdashboard.system.devices.CodecPreference
import dev.dankyeeter.btdashboard.system.devices.CodecPreferenceController
import dev.dankyeeter.btdashboard.system.devices.HdAudioController
import dev.dankyeeter.btdashboard.system.devices.HdAudioOutcome
import dev.dankyeeter.btdashboard.system.devices.HdAudioPreference
import dev.dankyeeter.btdashboard.system.devices.HdAudioState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val appContext: Context = context.applicationContext

    /**
     * Asks the helper for `WRITE_SECURE_SETTINGS`, unless the app already has it.
     *
     * This is what turns a helper that must be summoned into an app that can
     * look after itself: with the permission the app switches wireless
     * debugging on for the seconds an activation takes and off again
     * afterwards, and can do it after a reboot without anyone tapping anything.
     *
     * Called on every connect and cheap when it has already happened - the
     * check below is a local permission read, not a call into the helper.
     *
     * @return true if the app holds the permission when this returns.
     */
    suspend fun grantSecureSettings(): Boolean = withContext(Dispatchers.IO) {
        if (holdsSecureSettings()) {
            Log.i("PrivilegedBootstrap", "secure settings: already granted")
            return@withContext true
        }

        // Both of these used to return silently, which is why a grant that
        // never happened looked exactly like one that did: no helper call, no
        // log line, no permission, and nothing to say which of the two reasons
        // it was.
        val service = PrivilegedConnection.service.value ?: run {
            Log.w("PrivilegedBootstrap", "secure settings: no helper attached")
            return@withContext false
        }
        val token = activeToken() ?: run {
            Log.w("PrivilegedBootstrap", "secure settings: helper attached but no active token")
            return@withContext false
        }

        val granted = runCatching { service.grantSecureSettings(token) }
            .onFailure { Log.w("PrivilegedBootstrap", "the helper could not grant the permission", it) }
            .getOrNull()
            ?.let { PrivilegedProtocol.decodeError(it) == null }
            ?: false

        // Asked again rather than trusting the reply: `pm grant` reports
        // success for a permission it did not actually change on some builds,
        // and the only answer that matters is whether the app can write the
        // setting now.
        val holds = holdsSecureSettings()
        Log.i("PrivilegedBootstrap", "secure settings grant: reported=$granted effective=$holds")
        holds
    }

    /**
     * The permission read, made unable to throw.
     *
     * [grantSecureSettings] is called from the collector in
     * `BtDashboardApplication` that watches for a helper arriving, and a
     * throwing suspend function inside a `collect` ends that collector for the
     * life of the process — the app would then never react to another helper
     * connecting, and the one visible symptom would be wireless debugging
     * quietly staying open. A permission check that cannot answer is a "no",
     * not a reason to stop watching.
     */
    private fun holdsSecureSettings(): Boolean =
        runCatching { WirelessDebuggingSwitch(appContext).canEnable() }
            .onFailure { Log.w("PrivilegedBootstrap", "secure settings check failed", it) }
            .getOrDefault(false)

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

    /**
     * How long the launching shell waits before letting go.
     *
     * Long enough for the runtime to come up and hand its binder to the app,
     * short enough that a user watching a spinner does not notice. It is not a
     * correctness guarantee - `setsid` is - but it removes the race that made
     * the failure look like a rejected token.
     */
    private val helperStartGraceSeconds = 3

    private fun shellCommand(token: String): String = buildString {
        append("CLASSPATH=$(pm path ")
        append(packageName)
        // `setsid` puts the helper in its own session, out of the shell's
        // process group. When the app closes the adb stream, the daemon tears
        // that group down - and `nohup` only blocks SIGHUP, not the kill that
        // follows. Measured: the helper was dying 36 ms after being started,
        // before it had produced a single line of output.
        append(" | grep base.apk | cut -d: -f2) nohup setsid app_process /system/bin ")
        append("--nice-name=${PrivilegedContract.HELPER_PROCESS_NAME} ")
        append("dev.dankyeeter.btdashboard.privileged.PrivilegedServer ")
        append(token)
        // Kept rather than discarded to /dev/null.
        //
        // The helper is started through a shell that is closed immediately
        // afterwards, so anything it prints - including the reason it gave up -
        // has nowhere to go. That cost a full debugging round once already: the
        // start looked successful from the app's side while the helper was
        // exiting on the far end and saying why to nobody.
        //
        // Truncating rather than appending: only the most recent start can
        // explain the state the helper is in now, and an ever-growing file in
        // a world-readable directory is not worth the history.
        append(" >")
        append(PrivilegedContract.HELPER_LOG_PATH)
        // The trailing wait keeps the shell - and with it the daemon's service
        // - alive while the runtime starts. Leaving on the same breath as the
        // launch is what killed it: `app_process` needs to boot a VM, which
        // takes far longer than the shell takes to exit.
        append(" 2>&1 & sleep ")
        append(helperStartGraceSeconds)
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
 * accepted one, and reports itself unavailable otherwise - which is the end of
 * the line now that it is the only shell identity in the project. Everything
 * downstream degrades to "cannot check" rather than to a second provider.
 */
class PrivilegedShellRunner internal constructor(
    private val bootstrap: PrivilegedBootstrap,
    /** Shared with every other privileged caller; see [PrivilegedCallGuard]. */
    private val guard: PrivilegedCallGuard,
    /** Reads back the replies the helper was too large to send; see [ExecSpill]. */
    private val spill: ExecSpill,
) : ShellRunner {

    constructor(
        context: Context,
        bootstrap: PrivilegedBootstrap = PrivilegedBootstrap(context),
    ) : this(bootstrap, PrivilegedCallGuard.SHARED, ExecSpill())

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

        EXEC_LOCK.withLock {
            runCatching {
                val reply = service.exec(token, command)
                // The transaction came back. Whatever the reply *says*, the
                // transport worked, so any run of transient failures is over.
                guard.succeeded()
                if (reply == null) {
                    ShellResult(-1, "", "helper returned nothing")
                } else {
                    decode(reply)
                }
            }.getOrElse { error ->
                // NOT automatically a dead helper — that assumption is what put
                // the activation screen in front of a user whose helper was
                // running fine. [PrivilegedCallGuard] decides, and only
                // DeadObjectException (or a helper that fails a liveness ping
                // after a run of failures) clears the connection.
                val verdict = guard.failed(SHELL_CALL, error) { service.version() }
                ShellResult(-1, "", verdict.reason)
            }
        }
    }

    /**
     * Turns a reply into a result, whichever shape it arrived in.
     *
     * [PrivilegedProtocol.decodeResult] is tried first because the inline reply
     * is the common one — an error, an empty dump, a `ps` listing — and because
     * the two shapes are told apart by their keyword, so the order is a matter
     * of cost rather than of correctness.
     */
    private fun decode(reply: String): ShellResult {
        PrivilegedProtocol.decodeResult(reply)?.let { (exit, out, err) ->
            return ShellResult(exit, out, err)
        }
        PrivilegedProtocol.decodeFileResult(reply)?.let { handoff ->
            return spill.collect(handoff).fold(
                onSuccess = { ShellResult(handoff.exitCode, it, handoff.stderr) },
                onFailure = {
                    Log.w(TAG, "a staged reply could not be collected", it)
                    ShellResult(-1, "", it.message ?: "the staged reply could not be read")
                },
            )
        }
        return ShellResult(
            exitCode = -1,
            stdout = "",
            stderr = PrivilegedProtocol.decodeError(reply) ?: "unreadable reply",
        )
    }

    private companion object {
        const val TAG = "PrivilegedShell"

        const val SHELL_CALL = "a privileged shell command"

        /**
         * One exec at a time, process-wide.
         *
         * WHY: Binder gives a *pair of processes* a single 1 MB asynchronous
         * buffer, shared by every transaction in flight between them. A dumpsys
         * reply measured 115-222 KB on the owner's device and travels as a Java
         * String, so UTF-16 doubles it — two or three overlapping replies
         * exhaust the buffer and the transaction fails with
         * FAILED_TRANSACTION (-2147483646) while both processes are healthy.
         * The monitor's "Watch live" and "Watch closely" readers poll
         * independently, so overlapping is the normal case, not the corner one.
         *
         * A kotlinx [Mutex] rather than `synchronized`: every caller is already
         * a coroutine, and blocking a Binder thread — or an IO dispatcher
         * thread — to wait for another Binder call is how a pool runs out of
         * threads. This suspends instead.
         *
         * On the companion so that it is one lock for the process even though
         * runners are cheap to construct. Staging large replies in a file
         * (see [ExecSpill]) is the other half of the same fix and neither half
         * replaces the other: serialising still puts a 444 KB reply through a
         * buffer shared with the codec calls, and staging still lets a burst of
         * small replies pile up.
         *
         * ## This lock is load-bearing for [ExecSpill]
         *
         * The helper stages every large reply into **one reused filename**, and
         * that is only safe while at most one exec is in flight: the reply is
         * written, handed over, read and released inside a single turn of this
         * lock, so a write can never land under a reader. Removing or narrowing
         * the lock means giving the helper unique names again.
         */
        val EXEC_LOCK = Mutex()
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

    /** Shared with every other privileged caller; see [PrivilegedCallGuard]. */
    private val guard: PrivilegedCallGuard = PrivilegedCallGuard.SHARED

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
            guard.succeeded()
            if (reply == null) {
                CodecCallResult.Unavailable("the helper returned nothing")
            } else {
                PrivilegedProtocol.decodeCodec(reply)?.let { CodecCallResult.Observed(it) }
                    ?: CodecCallResult.Unavailable(
                        PrivilegedProtocol.decodeError(reply) ?: "unreadable reply from the helper",
                    )
            }
        }.getOrElse { error ->
            // The line that used to be here called forget() on any throw, and
            // the `getCodecStatus unavailable: InvocationTargetException` in the
            // owner's log was one such throw from a live helper — which then
            // put the activation gate over a working app. See
            // [PrivilegedCallGuard].
            val verdict = guard.failed("a privileged codec call", error) { service.version() }
            CodecCallResult.Unavailable(verdict.reason)
        }
    }

    private companion object {
        const val TAG = "PrivilegedCodec"
    }
}

/**
 * HD audio (optional codecs) through the privileged helper.
 *
 * Its own class rather than three more methods on [PrivilegedCodecController],
 * because it answers a different question: that one is "which codec is this
 * link running", this one is "is the link allowed to run anything but SBC".
 * They are read and written independently, and the profile applier treats them
 * as separate steps with separate outcomes — one class implementing both would
 * be the only thing suggesting otherwise.
 *
 * Everything about the honesty contract is inherited from that class: no
 * success is reported from a call that merely returned, and an absent helper
 * answers "cannot check" rather than "not supported".
 */
class PrivilegedHdAudioController(
    context: Context,
    private val bootstrap: PrivilegedBootstrap = PrivilegedBootstrap(context),
) : HdAudioController {

    /** Shared with every other privileged caller; see [PrivilegedCallGuard]. */
    private val guard: PrivilegedCallGuard = PrivilegedCallGuard.SHARED

    override fun isAvailable(): Boolean =
        PrivilegedConnection.isConnected && bootstrap.activeToken() != null

    override suspend fun read(address: String): HdAudioState =
        when (val result = call { service, token -> service.optionalCodecs(token, address) }) {
            is HdAudioCallResult.Observed -> HdAudioState.Known(
                // Unknown support is reported as supported rather than as not.
                // A bonded device that has never connected reads "unknown", and
                // greying the control out there would tell a user their LDAC
                // headphones are SBC-only. The read after the first connect
                // corrects it either way, and offering a control that turns out
                // to be moot is a much smaller wrong than withholding one.
                supported = result.observation.supported != false,
                enabled = result.observation.enabled,
            )

            is HdAudioCallResult.Unavailable -> HdAudioState.Unreadable(result.reason)
        }

    override suspend fun apply(address: String, preference: HdAudioPreference): HdAudioOutcome {
        val wire = OptionalCodecs.fromTriState(preference.asEnabled())
        return when (
            val result = call { service, token ->
                service.setOptionalCodecs(token, address, wire)
            }
        ) {
            is HdAudioCallResult.Unavailable -> HdAudioOutcome.Unavailable(result.reason)

            is HdAudioCallResult.Observed -> {
                val observed = result.observation
                if (observed.enabled == preference.asEnabled()) {
                    HdAudioOutcome.Applied(observed.enabled)
                } else {
                    HdAudioOutcome.NotObserved(observed.note)
                }
            }
        }
    }

    /**
     * The same idiom as `PrivilegedCodecController.call`, with the HD-audio
     * decoder. Not shared with it: the two differ only in which decode function
     * they use, and threading that through as a parameter would make the one
     * place that decides "is this reply readable" generic — which is exactly
     * where a wrong answer would be least visible.
     */
    private suspend fun call(
        block: (IPrivilegedService, String) -> String?,
    ): HdAudioCallResult = withContext(Dispatchers.IO) {
        val service = PrivilegedConnection.service.value
            ?: return@withContext HdAudioCallResult.Unavailable(
                "the privileged helper is not running, so HD audio cannot be checked",
            )
        val token = bootstrap.activeToken()
            ?: return@withContext HdAudioCallResult.Unavailable(
                "no token for the privileged helper",
            )

        runCatching {
            val reply = block(service, token)
            guard.succeeded()
            if (reply == null) {
                HdAudioCallResult.Unavailable("the helper returned nothing")
            } else {
                PrivilegedProtocol.decodeHdAudio(reply)?.let { HdAudioCallResult.Observed(it) }
                    ?: HdAudioCallResult.Unavailable(
                        PrivilegedProtocol.decodeError(reply) ?: "unreadable reply from the helper",
                    )
            }
        }.getOrElse { error ->
            val verdict = guard.failed("a privileged HD-audio call", error) { service.version() }
            HdAudioCallResult.Unavailable(verdict.reason)
        }
    }

    // No log tag of its own any more: the one line that used it has moved into
    // PrivilegedCallGuard, which logs every privileged failure in one place
    // together with how it classified it — which is the part worth reading.
}

/** Where an HD-audio answer came from, and what it actually said. */
private sealed interface HdAudioCallResult {
    data class Observed(val observation: HdAudioObservation) : HdAudioCallResult
    data class Unavailable(val reason: String) : HdAudioCallResult
}

/**
 * Turns Bluetooth off and on again through the privileged helper.
 *
 * The app has been telling users to do this by hand ever since the developer
 * options existed — the stack reads those keys at startup, so a stored AVRCP
 * version does nothing until the radio is cycled. The instruction was correct
 * and was still a dead end: the app knew what had to happen and made the user
 * go and do it.
 */
class PrivilegedBluetoothRestartController(
    context: Context,
    private val bootstrap: PrivilegedBootstrap = PrivilegedBootstrap(context),
) : BluetoothRestartController {

    /** Shared with every other privileged caller; see [PrivilegedCallGuard]. */
    private val guard: PrivilegedCallGuard = PrivilegedCallGuard.SHARED

    override fun isAvailable(): Boolean =
        PrivilegedConnection.isConnected && bootstrap.activeToken() != null

    override suspend fun restart(): BluetoothRestartOutcome = withContext(Dispatchers.IO) {
        val service = PrivilegedConnection.service.value
            ?: return@withContext BluetoothRestartOutcome.Unavailable(
                "the privileged helper is not running, so Bluetooth cannot be restarted from here",
            )
        val token = bootstrap.activeToken()
            ?: return@withContext BluetoothRestartOutcome.Unavailable(
                "no token for the privileged helper",
            )

        runCatching {
            val reply = service.restartBluetooth(token)
            guard.succeeded()
            when {
                reply == null -> BluetoothRestartOutcome.Failed("the helper returned nothing")

                PrivilegedProtocol.decodeResult(reply)?.first == 0 ->
                    BluetoothRestartOutcome.Restarted

                else -> BluetoothRestartOutcome.Failed(
                    PrivilegedProtocol.decodeError(reply) ?: "unreadable reply from the helper",
                )
            }
        }.getOrElse { error ->
            val verdict = guard.failed("the Bluetooth restart", error) { service.version() }
            // Failed, not Unavailable: the call was made, so the radio may be
            // off right now. Reporting "nothing was attempted" would send the
            // user looking for a problem somewhere else entirely.
            BluetoothRestartOutcome.Failed(
                "${verdict.reason} — the call was already under way, so check " +
                    "whether Bluetooth is back on",
            )
        }
    }

    // Same as the HD-audio controller: the failure logging lives in
    // PrivilegedCallGuard now.
}
