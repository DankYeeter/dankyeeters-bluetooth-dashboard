package dev.dankyeeter.btdashboard.privileged

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.os.IBinder
import android.os.Looper
import android.os.Process
import dev.dankyeeter.btdashboard.monitor.codec.ChannelMode
import dev.dankyeeter.btdashboard.monitor.codec.CodecDecoding
import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A2DP codec access from inside the helper process.
 *
 * ## Why this exists at all
 *
 * `BluetoothA2dp.setCodecConfigPreference` is guarded by BLUETOOTH_PRIVILEGED,
 * which no ordinary app can hold. `com.android.shell` **does** hold it —
 * verified on the device, `granted=true` — and the helper runs as that uid, so
 * inside this process the call is reachable. There is no shell command that
 * would do it instead: `cmd bluetooth_manager` offers only enable, disable,
 * enableBle, disableBle, factoryReset and wait-for-state.
 *
 * ## Getting an adapter on Android 16
 *
 * The earlier design built a `BluetoothAdapter` through the hidden
 * `createAdapter(AttributionSource)`, naming `com.android.shell` explicitly to
 * dodge the attribution check. **That method no longer exists on Android 16** —
 * reflection on-device shows the class exposes only `getDefaultAdapter()` and a
 * cache invalidator as static factories. So the adapter now comes from the
 * `BluetoothManager` system service, which carries the context's own
 * attribution. That context comes from `ActivityThread.systemMain()` and is
 * attributed to the package `android`; whether the privileged codec calls
 * accept that attribution is the open question, and a refusal names itself
 * precisely rather than coming back as a silent null.
 *
 * Every failure below reports which step failed and why, and the caller turns
 * that into a sentence the user reads. Nothing here ever answers "fine" without
 * having read a value back.
 *
 * ## Why the reading code is not shared with `A2dpCodecStatusSource`
 *
 * That class is an app-side component: it owns a Context, a permission check, a
 * broadcast-driven flow and a service listener tied to the app's lifecycle.
 * Reusing it would mean dragging all of that into a bare `main()` that has no
 * app identity — for the sake of about forty lines of field extraction. The
 * bitmask arithmetic that would actually be worth sharing *is* shared:
 * [CodecDecoding] is the same object on both sides.
 */
internal class HelperBluetooth(private val context: Context) {

    @Volatile
    private var proxy: BluetoothProfile? = null

    @Volatile
    private var adapter: BluetoothAdapter? = null

    @Volatile
    private var frameworkPrimed = false

    /**
     * Reads the negotiated codec, plus what the headphone says it can do.
     *
     * The selectable list is the part the app cannot get any other way: dumpsys
     * prints the current config, not the remote device's capabilities.
     */
    fun codecStatus(address: String): Result<CodecObservation> {
        val device = device(address).getOrElse { return Result.failure(it) }
        val a2dp = profile().getOrElse { return Result.failure(it) }
        val status = readStatus(a2dp, device).getOrElse { return Result.failure(it) }
        return Result.success(status.copy(note = "read through the privileged A2DP API"))
    }

    /**
     * Requests a codec, then reads back what the stack actually did.
     *
     * The read-back is the whole point, and it follows
     * `GlobalSettingsController.write`: a call that returned without throwing
     * says the request was *accepted*, not that it was *honoured*. Only the
     * read-back is evidence, and even that cannot distinguish "refused" from
     * "still renegotiating" — so a mismatch is reported as a mismatch with the
     * elapsed time attached, never as a failure and never as a success.
     */
    fun setCodecPreference(
        address: String,
        rawCodecType: Int,
        sampleRateHz: Int,
        bitsPerSample: Int,
        channelMode: Int,
        ldacQuality: Long,
    ): Result<CodecObservation> {
        // "System Default" arrives as a sentinel type rather than a new binder
        // method, so an old helper paired with a new app fails loudly on the
        // reject below instead of silently doing the wrong thing.
        if (rawCodecType == A2dpCodecMasks.SYSTEM_DEFAULT_SENTINEL) {
            return handBackToSystem(address)
        }

        A2dpCodecMasks.rejectRaw(rawCodecType, sampleRateHz, bitsPerSample, channelMode, ldacQuality)
            ?.let { return Result.failure(IllegalArgumentException(it)) }

        val device = device(address).getOrElse { return Result.failure(it) }
        val a2dp = profile().getOrElse { return Result.failure(it) }
        val config = buildConfig(
            rawCodecType, sampleRateHz, bitsPerSample, channelMode, ldacQuality,
            CODEC_PRIORITY_HIGHEST,
        ).getOrElse { return Result.failure(it) }

        runCatching {
            val method = a2dp.javaClass.getMethod(
                "setCodecConfigPreference",
                BluetoothDevice::class.java,
                config.javaClass,
            )
            method.isAccessible = true
            method.invoke(a2dp, device, config)
        }.onFailure {
            return Result.failure(
                IllegalStateException("setCodecConfigPreference failed: ${reason(it)}"),
            )
        }

        val requested = CodecDecoding.codecFamily(rawCodecType)
        return Result.success(
            observeUntilSettled(a2dp, device, requested, sampleRateHz, bitsPerSample, channelMode),
        )
    }

    /**
     * Hands the codec decision back to the stack.
     *
     * `setCodecConfigPreference` has no "forget my preference" form, so the
     * closest honest equivalent is: take the codec the link is on right now and
     * re-assert it at `CODEC_PRIORITY_DEFAULT` (0). That overwrites the pinned
     * `CODEC_PRIORITY_HIGHEST` entry a previous write left behind — the pin
     * that otherwise survives every reconnect until the headphone is power
     * cycled — and from then on the stack ranks codecs by its own defaults
     * again.
     *
     * No settle-polling afterwards: there is no requested codec to wait for.
     * Whatever the stack renegotiates *is* the correct outcome, so the result
     * reports a single read-back with no expectation attached.
     */
    private fun handBackToSystem(address: String): Result<CodecObservation> {
        val device = device(address).getOrElse { return Result.failure(it) }
        val a2dp = profile().getOrElse { return Result.failure(it) }

        val current = readStatus(a2dp, device).getOrElse {
            return Result.failure(
                IllegalStateException(
                    "cannot hand back to the system: the current codec is unreadable (${reason(it)})",
                ),
            )
        }
        val currentType = CodecFamily.entries.firstOrNull { it.name == current.family }
            ?.let { A2dpCodecMasks.codecType(it) }
            ?: return Result.failure(
                IllegalStateException(
                    "cannot hand back to the system: the link reads as " +
                        "'${current.family}', which has no writable codec type",
                ),
            )

        val config = buildConfig(
            currentType,
            sampleRateHz = 0, bitsPerSample = 0, channelMode = 0, ldacQuality = 0L,
            priority = CODEC_PRIORITY_DEFAULT,
        ).getOrElse { return Result.failure(it) }

        runCatching {
            val method = a2dp.javaClass.getMethod(
                "setCodecConfigPreference",
                BluetoothDevice::class.java,
                config.javaClass,
            )
            method.isAccessible = true
            method.invoke(a2dp, device, config)
        }.onFailure {
            return Result.failure(
                IllegalStateException("setCodecConfigPreference failed: ${reason(it)}"),
            )
        }

        val after = readStatus(a2dp, device).getOrNull() ?: current
        return Result.success(
            after.copy(
                matched = null,
                note = "handed back to the system — the stack now ranks codecs by its own defaults",
            ),
        )
    }

    /**
     * Polls the read-back until it agrees with the request or the budget runs
     * out.
     *
     * A fixed sleep would be a guess. Polling is not much better, but it at
     * least stops as soon as there is an answer, and the elapsed time goes into
     * the note so a slow "yes" is distinguishable from a fast "no" in the log.
     */
    private fun observeUntilSettled(
        a2dp: BluetoothProfile,
        device: BluetoothDevice,
        requestedFamily: CodecFamily,
        sampleRateHz: Int,
        bitsPerSample: Int,
        channelMode: Int,
    ): CodecObservation {
        val startedAt = System.currentTimeMillis()
        var last: CodecObservation? = null
        var failure: String? = null

        while (System.currentTimeMillis() - startedAt < SETTLE_BUDGET_MS) {
            readStatus(a2dp, device)
                .onSuccess { observed ->
                    last = observed
                    if (agrees(observed, requestedFamily, sampleRateHz, bitsPerSample, channelMode)) {
                        return observed.copy(
                            matched = true,
                            note = "read back after ${System.currentTimeMillis() - startedAt} ms",
                        )
                    }
                }
                .onFailure { failure = reason(it) }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        val elapsed = System.currentTimeMillis() - startedAt
        val observed = last ?: return CodecObservation(
            family = "",
            matched = false,
            note = "the request was accepted but the codec could not be read back" +
                (failure?.let { " ($it)" } ?: "") +
                " — whether it took effect is unknown",
        )
        return observed.copy(
            matched = false,
            // Both readings of this are live and neither can be ruled out from
            // here, so both are stated.
            note = "after $elapsed ms the codec still reads ${observed.summary} — the stack " +
                "either refused the request or has not finished renegotiating",
        )
    }

    private fun agrees(
        observed: CodecObservation,
        family: CodecFamily,
        sampleRateHz: Int,
        bitsPerSample: Int,
        channelMode: Int,
    ): Boolean {
        if (observed.codecFamily != family) return false
        // Zero meant "no preference", so an unrequested field can hold anything.
        if (sampleRateHz != 0 && observed.sampleRateHz != sampleRateHz) return false
        if (bitsPerSample != 0 && observed.bitsPerSample != bitsPerSample) return false
        if (channelMode != ChannelModes.UNSPECIFIED && observed.channelMode != channelMode) return false
        return true
    }

    // ---- the reflection surface ---------------------------------------------

    private fun readStatus(a2dp: BluetoothProfile, device: BluetoothDevice): Result<CodecObservation> =
        runCatching {
            val method = a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
            method.isAccessible = true
            val status = method.invoke(a2dp, device)
                ?: error("getCodecStatus() returned null")
            val config = call(status, "getCodecConfig") ?: error("no codec config in the status")

            val rawType = call(config, "getCodecType") as? Int
            val family = CodecDecoding.codecFamily(rawType)
            CodecObservation(
                family = if (family == CodecFamily.UNKNOWN) "" else family.name,
                sampleRateHz = (call(config, "getSampleRate") as? Int)
                    ?.let(CodecDecoding::sampleRate) ?: 0,
                bitsPerSample = (call(config, "getBitsPerSample") as? Int)
                    ?.let(CodecDecoding::bitsPerSample) ?: 0,
                channelMode = ChannelModes.fromChannelMode(
                    (call(config, "getChannelMode") as? Int)
                        ?.let(CodecDecoding::channelMode)
                        ?: ChannelMode.UNKNOWN,
                ),
                ldacQuality = if (family == CodecFamily.LDAC) {
                    (call(config, "getCodecSpecific1") as? Long) ?: 0L
                } else {
                    0L
                },
                selectable = selectable(status),
            )
        }.recoverCatching { throw IllegalStateException("getCodecStatus failed: ${reason(it)}", it) }

    private fun selectable(status: Any): List<String> = runCatching {
        @Suppress("UNCHECKED_CAST")
        val list = call(status, "getCodecsSelectableCapabilities") as? List<Any> ?: return emptyList()
        list.mapNotNull { CodecDecoding.codecFamily(call(it, "getCodecType") as? Int) }
            .filter { it != CodecFamily.UNKNOWN }
            .distinct()
            .map { it.name }
    }.getOrDefault(emptyList())

    /**
     * Builds a `BluetoothCodecConfig`.
     *
     * Two shapes, because the class changed: Android 13 introduced a Builder
     * and the old nine-argument constructor is not present on every build.
     * The Builder is tried first and the constructor is the fallback, rather
     * than branching on `SDK_INT` — the version a class *was* changed in is not
     * the same fact as whether this particular build has it.
     */
    private fun buildConfig(
        rawCodecType: Int,
        sampleRateHz: Int,
        bitsPerSample: Int,
        channelMode: Int,
        ldacQuality: Long,
        priority: Int,
    ): Result<Any> {
        val sampleRate = A2dpCodecMasks.sampleRateMask(sampleRateHz) ?: A2dpCodecMasks.NONE
        val bits = A2dpCodecMasks.bitsMask(bitsPerSample) ?: A2dpCodecMasks.NONE
        val channels = A2dpCodecMasks.channelMask(channelMode) ?: A2dpCodecMasks.NONE

        val configClass = runCatching { Class.forName("android.bluetooth.BluetoothCodecConfig") }
            .getOrElse {
                return Result.failure(
                    IllegalStateException("BluetoothCodecConfig is not present on this build"),
                )
            }

        viaBuilder(rawCodecType, sampleRate, bits, channels, ldacQuality, priority)
            ?.let { return Result.success(it) }

        viaConstructor(configClass, rawCodecType, sampleRate, bits, channels, ldacQuality, priority)
            ?.let { return Result.success(it) }

        return Result.failure(
            IllegalStateException(
                "BluetoothCodecConfig has neither a usable Builder nor the known constructor " +
                    "on this build — the codec cannot be expressed, so nothing was attempted",
            ),
        )
    }

    private fun viaBuilder(
        codecType: Int,
        sampleRate: Int,
        bits: Int,
        channels: Int,
        ldacQuality: Long,
        priority: Int,
    ): Any? = runCatching {
        val builderClass = Class.forName("android.bluetooth.BluetoothCodecConfig\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        fun set(name: String, type: Class<*>, value: Any) {
            builderClass.getMethod(name, type).invoke(builder, value)
        }
        set("setCodecType", Int::class.javaPrimitiveType!!, codecType)
        set("setCodecPriority", Int::class.javaPrimitiveType!!, priority)
        set("setSampleRate", Int::class.javaPrimitiveType!!, sampleRate)
        set("setBitsPerSample", Int::class.javaPrimitiveType!!, bits)
        set("setChannelMode", Int::class.javaPrimitiveType!!, channels)
        set("setCodecSpecific1", Long::class.javaPrimitiveType!!, ldacQuality)
        builderClass.getMethod("build").invoke(builder)
    }.getOrNull()

    private fun viaConstructor(
        configClass: Class<*>,
        codecType: Int,
        sampleRate: Int,
        bits: Int,
        channels: Int,
        ldacQuality: Long,
        priority: Int,
    ): Any? = runCatching {
        val int = Int::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        configClass.getConstructor(int, int, int, int, int, long, long, long, long)
            .newInstance(
                codecType, priority, sampleRate, bits, channels,
                ldacQuality, 0L, 0L, 0L,
            )
    }.getOrNull()

    // ---- getting hold of the profile ----------------------------------------

    private fun device(address: String): Result<BluetoothDevice> {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return Result.failure(IllegalArgumentException("'$address' is not a Bluetooth address"))
        }
        val a = adapterOrBuild().getOrElse { return Result.failure(it) }
        return runCatching { a.getRemoteDevice(address) }
            .recoverCatching { throw IllegalStateException("unknown device $address", it) }
    }

    /**
     * Fills in the static the Bluetooth framework expects an app process to
     * have been given at startup.
     *
     * `BluetoothAdapter` reaches its binders through
     * `BluetoothFrameworkInitializer.getBluetoothServiceManager()`. In a real
     * app that static is set by `ActivityThread` long before any app code runs;
     * in a bare `main()` nobody sets it, and the first privileged call dies on
     *
     * ```
     * Attempt to invoke virtual method
     * BluetoothServiceManager.getBluetoothManagerServiceRegisterer()
     * on a null object reference
     * ```
     *
     * which reads like a missing device, not a missing initialiser. Setting it
     * twice throws by design, so a failure here is expected on the second call
     * and is reported rather than treated as fatal.
     */
    private fun primeFrameworkInitializer() {
        if (frameworkPrimed) return
        runCatching {
            val managerClass = Class.forName("android.os.BluetoothServiceManager")
            val instance = managerClass.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
                .getDeclaredMethod("setBluetoothServiceManager", managerClass)
                .apply { isAccessible = true }
                .invoke(null, instance)
            System.err.println("privileged helper: BluetoothFrameworkInitializer primed")
        }.onFailure {
            System.err.println(
                "privileged helper: framework initialiser not primed " +
                    "(${it.cause?.message ?: it.message ?: it.javaClass.simpleName})",
            )
        }
        frameworkPrimed = true
    }

    /**
     * The adapter, from the `BluetoothManager` service with `getDefaultAdapter`
     * as a fallback.
     *
     * `createAdapter(AttributionSource)` — the old first choice — is gone on
     * Android 16, so there is no way left to name `com.android.shell` as the
     * attribution. Both remaining routes attribute to `android`; whether the
     * privileged codec calls accept that is unknown, and a refusal reports
     * itself precisely rather than as a null. Each route that fails is printed,
     * because the whole path ran for weeks with every failure invisible.
     */
    private fun adapterOrBuild(): Result<BluetoothAdapter> {
        adapter?.let { return Result.success(it) }

        primeFrameworkInitializer()

        // Three routes, best attribution first. Every failure is *printed*
        // rather than swallowed: this path ran for weeks with each failure
        // invisible behind `runCatching{}.getOrNull()`, and the single sentence
        // the user got — "no BluetoothAdapter is reachable" — named neither the
        // route nor the reason.
        val failures = mutableListOf<String>()

        // 1. The constructor that takes an AttributionSource. This is the only
        //    route that can name com.android.shell, which is the package that
        //    actually owns this uid and holds BLUETOOTH_PRIVILEGED. It needs an
        //    IBluetoothManager, fetched from ServiceManager the way the
        //    framework does it internally.
        val attributed = runCatching {
            // "bluetooth_manager", not Context.BLUETOOTH_SERVICE ("bluetooth"):
            // the service registered with ServiceManager is the manager, and the
            // two names differ.
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, BLUETOOTH_MANAGER_SERVICE) as? IBinder
                ?: error("ServiceManager has no $BLUETOOTH_MANAGER_SERVICE")
            val managerInterface = Class.forName("android.bluetooth.IBluetoothManager")
            val manager = Class.forName("android.bluetooth.IBluetoothManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
                ?: error("IBluetoothManager.asInterface returned null")
            val source = AttributionSource.Builder(Process.myUid())
                .setPackageName(SHELL_PACKAGE)
                .build()
            BluetoothAdapter::class.java
                .getDeclaredConstructor(managerInterface, Context::class.java, AttributionSource::class.java)
                .apply { isAccessible = true }
                .newInstance(manager, context, source)
        }.onFailure {
            failures += "attributed ctor: ${it.cause?.message ?: it.message ?: it.javaClass.simpleName}"
        }.getOrNull()

        // 2. `createAdapter(Context)` — present on Android 16, confirmed by
        //    reflection on-device. Attributed to whatever the context is, which
        //    here means the package `android`.
        val created = attributed ?: runCatching {
            // getDeclaredMethod, not getMethod: the factory is hidden API and
            // therefore not public, so the public lookup misses it entirely.
            BluetoothAdapter::class.java
                .getDeclaredMethod("createAdapter", Context::class.java)
                .apply { isAccessible = true }
                .invoke(null, context) as? BluetoothAdapter
                ?: error("createAdapter(Context) returned null")
        }.onFailure {
            failures += "createAdapter(Context): ${it.cause?.message ?: it.message ?: it.javaClass.simpleName}"
        }.getOrNull()

        // 3. The ordinary route. Returns null in a bare process on this build,
        //    kept because it costs nothing and would work on other releases.
        val resolved = created ?: runCatching {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter() ?: error("getDefaultAdapter() returned null")
        }.onFailure {
            failures += "getDefaultAdapter: ${it.cause?.message ?: it.message ?: it.javaClass.simpleName}"
        }.getOrNull()

        if (resolved == null) {
            System.err.println("privileged helper: adapter unreachable: $failures")
            return Result.failure(
                IllegalStateException(
                    "no BluetoothAdapter is reachable from the helper process " +
                        "(${failures.joinToString("; ")})",
                ),
            )
        }
        if (attributed == null) {
            System.err.println(
                "privileged helper: adapter has no shell attribution ($failures); " +
                    "privileged calls may be refused",
            )
        } else {
            System.err.println("privileged helper: adapter attributed to $SHELL_PACKAGE")
        }
        adapter = resolved
        return Result.success(resolved)
    }

    /**
     * The A2DP proxy, bound on first use.
     *
     * Bound lazily rather than at startup so a helper that is only ever asked
     * for dumpsys never binds a Bluetooth service at all.
     *
     * The wait is safe because Binder transactions arrive on Binder threads
     * while the helper's main Looper is free to deliver `onServiceConnected`.
     * Called from the main thread this would deadlock, so that is refused
     * outright rather than left to time out and look like a missing profile.
     */
    private fun profile(): Result<BluetoothProfile> {
        proxy?.let { return Result.success(it) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Result.failure(
                IllegalStateException("the A2DP proxy cannot be bound from the helper's main thread"),
            )
        }
        val a = adapterOrBuild().getOrElse { return Result.failure(it) }

        val latch = CountDownLatch(1)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, bound: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                proxy = bound
                latch.countDown()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.A2DP) proxy = null
            }
        }

        val requested = runCatching { a.getProfileProxy(context, listener, BluetoothProfile.A2DP) }
            .getOrElse {
                return Result.failure(IllegalStateException("getProfileProxy threw: ${reason(it)}"))
            }
        if (!requested) {
            return Result.failure(IllegalStateException("the A2DP profile proxy was refused"))
        }
        if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return Result.failure(
                IllegalStateException("the A2DP profile did not bind within $BIND_TIMEOUT_SECONDS s"),
            )
        }
        return proxy?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("the A2DP profile bound but handed back nothing"))
    }

    private fun call(target: Any, method: String): Any? = runCatching {
        target.javaClass.getMethod(method).apply { isAccessible = true }.invoke(target)
    }.getOrNull()

    /** Reflection buries the real cause one level down; this digs it back out. */
    private fun reason(t: Throwable): String {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
    }

    private companion object {
        /** The package that owns uid 2000 and holds BLUETOOTH_PRIVILEGED. */
        const val SHELL_PACKAGE = "com.android.shell"

        /**
         * The ServiceManager name of the Bluetooth *manager*.
         *
         * Deliberately not [Context.BLUETOOTH_SERVICE], which is `"bluetooth"`
         * and is the name of a different binder. Using that one costs a
         * confusing "ServiceManager has no bluetooth".
         */
        const val BLUETOOTH_MANAGER_SERVICE = "bluetooth_manager"

        /** AOSP `BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST`. */
        const val CODEC_PRIORITY_HIGHEST = 1_000_000

        /**
         * AOSP `BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT`. Writing at this
         * priority un-pins whatever an earlier HIGHEST write nailed down and
         * lets the stack rank codecs by its own defaults again.
         */
        const val CODEC_PRIORITY_DEFAULT = 0
        const val BIND_TIMEOUT_SECONDS = 8L
        const val SETTLE_BUDGET_MS = 2_500L
        const val POLL_INTERVAL_MS = 250L
    }
}
