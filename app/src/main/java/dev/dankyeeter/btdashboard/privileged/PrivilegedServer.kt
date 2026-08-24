package dev.dankyeeter.btdashboard.privileged

import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import dev.dankyeeter.btdashboard.privileged.PrivilegedProtocol.PrivilegedOperation
import java.util.concurrent.TimeUnit

/**
 * The app's own privileged helper — what replaces the dependency on Shizuku.
 *
 * ## What this is
 *
 * A plain `main()` that Android's `app_process` starts **from this app's own
 * APK**, once, over ADB. ADB's shell runs as uid 2000, so anything it starts
 * inherits that. The helper then offers the operations named in
 * [PrivilegedOperation] to the app, which is an ordinary unprivileged app the
 * rest of the time.
 *
 * Same mechanism as Shizuku. The differences: the code lives in this APK, so
 * there is one app to install instead of two, and the helper serves a closed
 * set of named operations instead of a general shell.
 *
 * ## What it is not
 *
 * Root. This is shell uid — exactly what `adb shell` already has, with the same
 * limits: SELinux, OEM restrictions, and no reach beyond what the shell user
 * has anyway. Nothing here escalates past what the user could type themselves.
 *
 * ## Why a Binder and not a socket
 *
 * The socket version was written first and **measured to be impossible** on a
 * Pixel 8 Pro running Android 16. The helper started, ran as uid 2000 in
 * `u:r:shell:s0`, and bound its socket; the app still could not reach it:
 *
 * ```
 * avc: denied { connectto } for path=@dankyeeter_btdashboard_privileged
 *   scontext=u:r:untrusted_app:s0:c184,... tcontext=u:r:shell:s0
 *   tclass=unix_stream_socket permissive=0
 * ```
 *
 * `untrusted_app` may not connect to a unix stream socket owned by the `shell`
 * domain, and no permission an app can request changes that. A Binder handed
 * over through a ContentProvider call travels via `system_server` instead of
 * being a direct app-to-shell connection, and that path *is* permitted — which
 * is why Shizuku has always done it this way.
 *
 * ## The one thing that cannot be worked around
 *
 * It dies on reboot, and restarting it needs ADB again. There is no way around
 * that without root; Shizuku has the same limitation. The app notices the
 * helper is gone (the binder dies) and says so rather than degrading silently.
 *
 * ## Why there is no inactivity shutdown — decided, not overlooked
 *
 * An idle timeout was considered and **rejected by the user**. A helper that
 * stops itself after a quiet hour cannot be restarted from the phone: it needs
 * a computer, a cable and the ADB command again. The app would then be sitting
 * there degraded until the user next happens to have their laptop out, which is
 * the one thing this app must never do to him.
 *
 * The cost of not having one is an idle process holding a Binder and a Looper —
 * no wakelocks, no timers, no polling. It wakes only when the app calls it.
 * That is a smaller price than an unpredictable re-setup, so please do not add
 * one back.
 *
 * Started by [PrivilegedBootstrap.adbCommand], which supplies the token.
 */
object PrivilegedServer {

    /**
     * Bumped whenever the interface or the operation set changes, so the app
     * can refuse a helper left running from an older APK instead of getting
     * confusing answers from it.
     *
     * 2: caller-uid verification, and the codec operations.
     */
    const val VERSION: Int = 3

    /**
     * How often the helper looks for the app while disconnected.
     *
     * Only runs between the app dying and coming back, and only reads `/proc`,
     * so a slow cadence costs nothing and a fast one buys nothing — the user is
     * not watching the screen during those seconds either.
     */
    private const val RECONNECT_POLL_MS = 3_000L

    /**
     * Backoff ceiling; see the reconnect thread for why it exists at all.
     *
     * Lowered from 60 s. The ceiling only decides how long the *app* waits for
     * its helper after restarting, and that wait is not free: without the
     * helper the EQ cannot reach players that never announce their session, so
     * a minute of silence for Tidal was the price of a wake-up the shell
     * process would otherwise skip. One /proc scan every 15 s while the app is
     * not running is a rounding error next to that; the scan does no binder
     * call, sends no broadcast and starts nothing.
     */
    private const val RECONNECT_POLL_MAX_MS = 15_000L

    private const val TAG = "btdash_privileged"

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.firstOrNull()
        if (token.isNullOrBlank()) {
            System.err.println("privileged helper: no token supplied, refusing to start")
            return
        }

        // A Looper is what keeps this bare main() alive; the Binder itself is
        // served on its own threads.
        Looper.prepareMainLooper()

        val context = systemContext()
        if (context == null) {
            System.err.println("privileged helper: no system context, cannot hand over the binder")
            return
        }

        val appUid = resolveAppUid(context)
        if (appUid == null) {
            // Fails closed on purpose. Starting anyway would mean serving
            // privileged operations behind the token alone, while the class
            // documentation claims two independent factors — a helper that
            // cannot identify its app has no business pretending otherwise.
            System.err.println(
                "privileged helper: cannot resolve the uid of the app that owns " +
                    "${PrivilegedContract.AUTHORITY}, refusing to start",
            )
            return
        }

        // Retire any earlier helper before serving. The app-side retire() only
        // reaches a helper the *current* app process knows about; after an
        // `adb install -r` the app is a fresh process with no such reference,
        // so a helper from before the reinstall would linger as uid 2000
        // forever, answering to a token nobody holds. A new helper runs as the
        // same shell uid as the old one, so it can — and does — reap its
        // siblings here. The result is the invariant the design wants: exactly
        // one btdash_privileged process, the newest.
        reapOtherHelpers()

        val service = PrivilegedService(token, appUid, HelperBluetooth(context))
        val appToken = handOver(context, service, token)
        if (appToken == null) {
            System.err.println("privileged helper: the app did not accept the binder")
            return
        }

        println(
            "privileged helper: serving as uid ${Process.myUid()} for app uid $appUid, " +
                "version $VERSION",
        )
        watchApp(context, service, token, appUid, appToken)
        Looper.loop()
    }

    /**
     * Re-offers the binder whenever the app process comes back.
     *
     * The hand-over used to be a one-shot at helper start, and that quietly
     * halved the app: Android reclaims a backgrounded app whenever it likes,
     * and the restarted process had no route back to a helper that was still
     * running perfectly well. The user saw "App helper: not running" and had to
     * re-run the ADB command for nothing.
     *
     * Two rules keep this from becoming a nuisance:
     *
     *  - **Death-driven, not timed.** While the app is alive this costs
     *     nothing; the death recipient on the app's own token is the trigger.
     *  - **Never wake the app.** `getContentProviderExternal` would *start* the
     *     app process, so the retry waits until the app is running of its own
     *     accord ([appIsRunning]) before offering anything. A helper that
     *     resurrected a backgrounded app every few seconds would be worse than
     *     the bug it fixes.
     */
    private fun watchApp(
        context: Context,
        service: PrivilegedService,
        token: String,
        appUid: Int,
        appToken: IBinder,
    ) {
        val onDeath = IBinder.DeathRecipient {
            println("privileged helper: the app process is gone, waiting for it to return")
            Thread {
                // Exponential backoff, because this can run for hours: the app
                // usually returns within seconds of dying (Android restarting
                // it, or the user reopening), and if it has not come back after
                // a few minutes it probably will not for a while. A flat 3 s
                // poll would keep waking this shell process all day for
                // nothing; at the ceiling it wakes once a minute, and each wake
                // is one /proc scan — no binder, no broadcast, no app start.
                var wait = RECONNECT_POLL_MS
                while (true) {
                    Thread.sleep(wait)
                    if (!appIsRunning(appUid)) {
                        wait = (wait * 2).coerceAtMost(RECONNECT_POLL_MAX_MS)
                        continue
                    }
                    wait = RECONNECT_POLL_MS
                    val fresh = handOver(context, service, token)
                    if (fresh != null) {
                        println("privileged helper: re-attached to the restarted app")
                        watchApp(context, service, token, appUid, fresh)
                        return@Thread
                    }
                    // A refusal is final: the app rotated its token, which is
                    // what a reinstall does. This helper can never authenticate
                    // to it again, so it is the orphan — two `btdash_privileged`
                    // processes after `adb install -r` was exactly this, and the
                    // old one used to linger on the shell uid until reboot
                    // because nothing could tell it to go.
                    //
                    // Exiting is safe precisely because the refusal proves
                    // somebody else owns the app now: a *live* helper is never
                    // refused, it is answered with Active and keeps running.
                    println("privileged helper: the app no longer knows this token, exiting")
                    Process.killProcess(Process.myPid())
                    return@Thread
                }
            }.apply { isDaemon = true }.start()
        }
        runCatching { appToken.linkToDeath(onDeath, 0) }.onFailure {
            System.err.println(
                "privileged helper: cannot watch the app for death (${it.message}); " +
                    "it will need a manual restart if the app is killed",
            )
        }
    }

    /**
     * Whether the app is running *right now*, without touching it.
     *
     * Reads `/proc` directly rather than asking ActivityManager: this runs on a
     * timer while disconnected, and a uid match on the process table is both
     * cheaper and free of the risk that a query starts something.
     */
    private fun appIsRunning(appUid: Int): Boolean = runCatching {
        java.io.File("/proc").listFiles { f: java.io.File -> f.name.toIntOrNull() != null }
            ?.any { dir ->
                runCatching {
                    java.nio.file.Files.getAttribute(
                        dir.toPath(),
                        "unix:uid",
                    ) as? Int == appUid
                }.getOrDefault(false)
            } == true
    }.getOrDefault(false)

    /**
     * Kills every other `btdash_privileged` process.
     *
     * Observed on the Pixel 8 Pro: after `adb install -r` the app came back
     * with a new token, the old helper kept running with the old one, and
     * `ps -A | grep btdash_privileged` showed **two** processes where the
     * design promises one. Nothing could clear it — the app no longer holds a
     * binder to the old helper, and the old helper has no reason to exit.
     *
     * Done from the new helper because it is the only party that *can*: same
     * uid, so `kill` is permitted, and it runs at exactly the moment a
     * replacement exists. Deliberately by process name rather than by asking
     * the app what it remembers — the whole point is to catch helpers the app
     * has forgotten.
     *
     * Own pid excluded, obviously. Failures are printed and ignored: a lingering
     * process is untidy, not dangerous, and must never stop a working helper
     * from starting.
     */
    private fun reapOtherHelpers() {
        val self = Process.myPid()
        runCatching {
            val listing = ProcessBuilder("ps", "-A", "-o", "PID,NAME")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader()
                .use { it.readText() }

            listing.lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 2 && parts[1] == PrivilegedContract.HELPER_PROCESS_NAME) {
                        parts[0].toIntOrNull()
                    } else {
                        null
                    }
                }
                .filter { it != self }
                .forEach { pid ->
                    println("privileged helper: retiring stale helper pid $pid")
                    Process.killProcess(pid)
                }
        }.onFailure {
            System.err.println("privileged helper: could not look for stale helpers: ${it.message}")
        }
    }

    /**
     * The uid the helper will accept calls from.
     *
     * Resolved from the **provider authority**, not from a hardcoded package
     * name. The helper hands its Binder to whoever owns
     * [PrivilegedContract.AUTHORITY]; the uid it must trust is therefore, by
     * definition, that same owner's uid. Deriving both from one fact means they
     * cannot drift apart — a renamed package changes the authority, and the
     * check follows it. [PrivilegedContract.APP_PACKAGE] is only a fallback for
     * builds where `resolveContentProvider` comes back empty.
     *
     * Resolved once, at startup, rather than on every call:
     *
     *  - it is a PackageManager round trip, and `exec` is on the monitor's hot
     *    path; and
     *  - a reinstall gives the app a *new* uid, and a helper that quietly
     *    followed it along would be exactly the replay this design is trying to
     *    prevent. After a reinstall the app also has a new token, so the right
     *    outcome is that the old helper serves nobody and the user starts a new
     *    one — which is what pinning the uid at startup produces.
     *
     * Full uids are compared, not app ids. That means an instance of this app
     * in a second Android user or a work profile is *not* served, which matches
     * [handOver] — it fetches the provider for user 0 explicitly, so user 0 is
     * the only instance this helper ever talks to.
     */
    @Suppress("DEPRECATION") // the flags overloads need API 33; minSdk here is 31
    private fun resolveAppUid(context: Context): Int? {
        val packageManager = context.packageManager ?: return null

        runCatching {
            packageManager.resolveContentProvider(PrivilegedContract.AUTHORITY, 0)
                ?.applicationInfo
                ?.uid
        }.getOrNull()?.let { return it }

        return runCatching {
            packageManager.getPackageUid(PrivilegedContract.APP_PACKAGE, 0)
        }.onFailure {
            System.err.println("privileged helper: resolving the app uid failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * A `Context` inside a process that is not an app.
     *
     * `ActivityThread.systemMain()` is hidden API and this is the one place
     * that needs it: a bare `main()` has no Context, and without one there is
     * no way to reach ActivityManager at all.
     *
     * The context it returns belongs to the package `android` even though this
     * process runs as uid 2000. That mismatch is *not* fixed here — see
     * [handOver], which works around it by naming the package explicitly rather
     * than by trying to obtain a differently-attributed context. Recreating the
     * context for [SHELL_PACKAGE] was tried first and does not help: the
     * reported package comes from the context's attribution, not from its
     * package info.
     */
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }.onFailure {
        System.err.println("privileged helper: no usable context: ${it.message}")
    }.getOrNull()

    /** The package that owns uid 2000, i.e. the identity ADB itself runs as. */
    private const val SHELL_PACKAGE = "com.android.shell"

    /**
     * Hands the binder to the app through the app's own ContentProvider.
     *
     * ## Why this does not use `ContentResolver.call`
     *
     * It did first, and the system refused it:
     *
     * ```
     * Given calling package android does not match caller's uid 2000
     * ```
     *
     * A `ContentResolver` reports the package of the context it came from. The
     * only context obtainable in a bare `main()` comes from
     * `ActivityThread.systemMain()` and belongs to the package `android`, while
     * this process runs as uid 2000. The system checks the two against each
     * other and rejects the mismatch. `createPackageContext("com.android.shell")`
     * does not fix it either — the reported package comes from the context's
     * attribution, not from the package info.
     *
     * So the provider is addressed directly, the way Shizuku does it: fetch the
     * `IContentProvider` from ActivityManager and call it with an
     * [android.content.AttributionSource] that names [SHELL_PACKAGE] explicitly.
     * That package really does own uid 2000, so the check passes.
     *
     * Everything here is hidden API and its signatures have moved between
     * releases, so the `call` method is found by shape rather than by exact
     * signature, and every failure degrades to a printed reason.
     */
    private fun handOver(context: Context, service: PrivilegedService, token: String): IBinder? =
        runCatching {
            val extras = Bundle().apply {
                putBinder(PrivilegedContract.EXTRA_BINDER, service)
                putString(PrivilegedContract.EXTRA_TOKEN, token)
                putInt(PrivilegedContract.EXTRA_VERSION, VERSION)
            }

            val activityManager = Class.forName("android.app.ActivityManager")
                .getMethod("getService")
                .invoke(null)
                ?: error("no ActivityManager")

            // getContentProviderExternal(name, userId, token, tag) — pulls the
            // provider up without going through our own (mis-attributed) resolver.
            val holderToken = Binder()
            val holder = activityManager.javaClass.getMethod(
                "getContentProviderExternal",
                String::class.java,
                Int::class.javaPrimitiveType,
                IBinder::class.java,
                String::class.java,
            ).invoke(activityManager, PrivilegedContract.AUTHORITY, 0, holderToken, TAG)
                ?: error("the app's provider could not be started")

            val provider = holder.javaClass.getField("provider").get(holder)
                ?: error("provider holder was empty")

            val attribution = AttributionSource.Builder(Process.myUid())
                .setPackageName(SHELL_PACKAGE)
                .build()

            val call = provider.javaClass.methods.firstOrNull {
                it.name == "call" &&
                    it.parameterTypes.size == 5 &&
                    it.parameterTypes[0] == AttributionSource::class.java
            } ?: error("IContentProvider.call has an unexpected shape on this build")

            val reply = call.invoke(
                provider,
                attribution,
                PrivilegedContract.AUTHORITY,
                PrivilegedContract.METHOD_SEND_BINDER,
                null,
                extras,
            ) as? Bundle

            // Best effort: the external reference keeps the provider process
            // pinned until it is dropped.
            runCatching {
                activityManager.javaClass
                    .getMethod("removeContentProviderExternalAsUser", IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(activityManager, holderToken, 0)
            }

            if (reply?.getBoolean(PrivilegedContract.EXTRA_ACCEPTED) != true) return@runCatching null
            // An app old enough not to send one still works; it just cannot be
            // watched, so this helper stays a one-shot for that install.
            reply.getBinder(PrivilegedContract.EXTRA_APP_TOKEN)
        }.onFailure {
            System.err.println("privileged helper: hand-over failed: ${it.message}")
        }.getOrNull()
}

/**
 * The Binder the app talks to.
 *
 * ## Two factors, checked on every call
 *
 * 1. **The token.** The app generated it; it reached this process only because
 *    the user pasted it into an ADB command.
 * 2. **The calling uid.** Supplied by the kernel through Binder, so no process
 *    can claim someone else's.
 *
 * Either alone is weak in a different way. A leaked token — a screenshot of the
 * setup screen, a shell history file — would otherwise be enough on its own,
 * and that is precisely the case the second factor exists for: the caller must
 * *also* be running as this app's uid, which on a non-rooted device means being
 * this app.
 *
 * The token check was here from the start; the uid check was not, and its
 * absence meant a leaked token was a complete bypass. Both are now checked in
 * one place, [refuse], so a new operation cannot be added with only one of
 * them — see [PrivilegedOperation] for the full set of entry points.
 */
private class PrivilegedService(
    private val expectedToken: String,
    /** Resolved once at startup; see [PrivilegedServer.resolveAppUid]. */
    private val appUid: Int,
    private val bluetooth: HelperBluetooth,
) : IPrivilegedService.Stub() {

    /**
     * Deliberately unguarded. It returns a build number this app compiled into
     * itself, tells a caller nothing it could not read out of the APK, and
     * changes nothing. Guarding it would make a version mismatch look like an
     * authentication failure, which is the harder of the two to diagnose.
     */
    override fun version(): Int = PrivilegedServer.VERSION

    override fun exec(token: String?, command: MutableList<String>?): String {
        refuse(token, PrivilegedOperation.EXEC)?.let { return it }
        val argv = command?.toList().orEmpty()
        if (!PrivilegedProtocol.isAllowed(argv)) {
            // Named in the reply on purpose: a refusal the app cannot see a
            // reason for is indistinguishable from a broken helper.
            return PrivilegedProtocol.encodeError("not on the whitelist: ${argv.firstOrNull()}")
        }
        // Logged so it is provable which shell identity actually served a
        // command — with Shizuku installed as a fallback, a successful result
        // alone does not say which one produced it.
        println("privileged helper: exec " + argv.joinToString(" "))
        return execute(argv)
    }

    override fun codecStatus(token: String?, address: String?): String {
        refuse(token, PrivilegedOperation.CODEC_STATUS)?.let { return it }
        val target = address?.takeIf { it.isNotBlank() }
            ?: return PrivilegedProtocol.encodeError("no device address")
        println("privileged helper: codecStatus ${tail(target)}")
        return bluetooth.codecStatus(target).encoded()
    }

    override fun setCodecPreference(
        token: String?,
        address: String?,
        codecType: Int,
        sampleRateHz: Int,
        bitsPerSample: Int,
        channelMode: Int,
        ldacQuality: Long,
    ): String {
        refuse(token, PrivilegedOperation.SET_CODEC_PREFERENCE)?.let { return it }
        val target = address?.takeIf { it.isNotBlank() }
            ?: return PrivilegedProtocol.encodeError("no device address")
        // The only operation in this helper that changes the phone, so it is
        // the one worth being able to point at in a log afterwards.
        println(
            "privileged helper: setCodecPreference ${tail(target)} " +
                "type=$codecType rate=$sampleRateHz bits=$bitsPerSample " +
                "channels=$channelMode ldac=$ldacQuality",
        )
        return bluetooth.setCodecPreference(
            address = target,
            rawCodecType = codecType,
            sampleRateHz = sampleRateHz,
            bitsPerSample = bitsPerSample,
            channelMode = channelMode,
            ldacQuality = ldacQuality,
        ).encoded()
    }

    /**
     * Hands the app the one permission that lets it work without this helper.
     *
     * `WRITE_SECURE_SETTINGS` is `signature|privileged|development`, and the
     * *development* flag is what makes this possible at all: development
     * permissions can be granted from a shell, which is what this process is.
     *
     * ## Why it is worth doing
     *
     * Wireless debugging is the door this helper came through, and it stays
     * open for as long as the app cannot close it. With this permission the app
     * can turn it on for the few seconds an activation needs and turn it off
     * again - so the pairing and connect ports exist only while they are being
     * used, instead of all day.
     *
     * ## What is fixed and why
     *
     * Both the package and the permission are constants here. Neither is a
     * parameter, because a caller able to choose them would have a way to grant
     * any development permission to any installed package, using a helper whose
     * whole design is a short list of things it will do.
     */
    override fun grantSecureSettings(token: String?): String {
        refuse(token, PrivilegedOperation.GRANT_SECURE_SETTINGS)?.let { return it }
        val command = listOf("pm", "grant", PrivilegedContract.APP_PACKAGE, SECURE_SETTINGS)
        println("privileged helper: " + command.joinToString(" "))
        return execute(command)
    }

    override fun shutdown(token: String?) {
        if (refuse(token, PrivilegedOperation.SHUTDOWN) != null) return
        Process.killProcess(Process.myPid())
    }

    /**
     * Null when the call may proceed, otherwise the encoded refusal.
     *
     * The uid is read here rather than passed in because
     * [Binder.getCallingUid] is only meaningful inside the transaction — it
     * returns the *own* uid when there is no transaction in flight, so caching
     * it or reading it on another thread would silently turn the check into a
     * tautology.
     *
     * The two factors are reported apart. Telling a leaked-token caller only
     * "refused" would leave the app unable to say whether the user needs to
     * re-run the ADB command or whether something else on the phone is probing
     * the helper, and those need different reactions.
     */
    private fun refuse(token: String?, operation: PrivilegedOperation): String? {
        val callingUid = Binder.getCallingUid()
        if (callingUid != appUid) {
            System.err.println(
                "privileged helper: ${operation.aidlName} from uid $callingUid, " +
                    "expected the app's uid $appUid",
            )
            return PrivilegedProtocol.encodeError(
                "caller uid $callingUid is not this app — refused",
            )
        }
        if (!PrivilegedProtocol.tokensMatch(token, expectedToken)) {
            System.err.println("privileged helper: ${operation.aidlName} with a bad token")
            return PrivilegedProtocol.encodeError("bad token")
        }
        return null
    }

    private fun Result<CodecObservation>.encoded(): String = fold(
        onSuccess = { PrivilegedProtocol.encodeCodec(it) },
        onFailure = { PrivilegedProtocol.encodeError(it.message ?: it.javaClass.simpleName) },
    )

    /**
     * The last two octets only. Enough to tell apart the two or three devices
     * one phone has connected at once, and the project does not write full
     * addresses down anywhere — not even into a log that goes to /dev/null.
     */
    private fun tail(address: String): String = address.takeLast(5)

    /** The only permission [grantSecureSettings] will ever hand out. */
    private val SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    private fun execute(command: List<String>): String = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        // stderr is drained on its own thread, in parallel with stdout. Reading
        // them in sequence can deadlock outright: a child that fills the 64 KB
        // stderr pipe blocks writing, stdout never reaches EOF, readText()
        // hangs — and the timeout below is never reached, because it sits
        // *after* the reads. One wedged binder thread in the helper, forever.
        val stderrReader = java.util.concurrent.CompletableFuture.supplyAsync {
            runCatching { process.errorStream.bufferedReader().use { it.readText() } }
                .getOrDefault("")
        }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = runCatching {
            stderrReader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault("")
        // A dumpsys that never returns must not wedge the helper for good.
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return PrivilegedProtocol.encodeError("timed out after $TIMEOUT_SECONDS s")
        }
        PrivilegedProtocol.encodeResult(process.exitValue(), stdout, stderr)
    }.getOrElse {
        PrivilegedProtocol.encodeError(it.message ?: it.javaClass.simpleName)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
    }
}
