package dev.dankyeeter.btdashboard.privileged

import android.util.Log
import dev.dankyeeter.btdashboard.system.devices.SystemPropertyReader

/**
 * Reads system properties through the hidden `android.os.SystemProperties`.
 *
 * ## Why reflection, and why that is fine here
 *
 * The class is `@hide`, so there is no public API — but it is on the unsupported
 * *greylist* rather than blocked, and reading is all this does. Every property
 * it is asked for is one `getprop` prints for any process on the device; nothing
 * here reaches a value an ordinary shell could not.
 *
 * ## Why the app reads these at all when it cannot write them
 *
 * Because the read is the honest half of a read-only row. Saying "A2DP hardware
 * offload cannot be changed here" is a fact about this app; saying "it is
 * currently on, and cannot be changed here" is a fact about the phone, and it is
 * the one the user came for. Writing is a different mechanism entirely and is
 * refused by the kernel for the shell — see `BluetoothReadOnlySettings`.
 *
 * ## What null means
 *
 * Unset, which for every property this app looks at is the *normal* state: none
 * of the `persist.bluetooth.*` keys exist on a stock Pixel until something
 * writes one, and the stack falls back to its compiled-in default. So null is
 * reported as null and worded as "not set" one layer up, never as "off" — those
 * are different, and for the offload switch they are opposites.
 */
object AndroidSystemProperties : SystemPropertyReader {

    private val getter: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
        }.onFailure { Log.i(TAG, "SystemProperties is not reachable on this build", it) }
            .getOrNull()
    }

    override fun read(key: String): String? = runCatching {
        // The platform returns "" for an unset property, not null. Collapsing
        // the two here keeps the distinction the caller cares about — set to
        // something versus never set — instead of passing an empty string up
        // that would render as a blank value.
        (getter?.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.onFailure { Log.i(TAG, "reading $key failed", it) }.getOrNull()

    private const val TAG = "SystemProperties"
}
