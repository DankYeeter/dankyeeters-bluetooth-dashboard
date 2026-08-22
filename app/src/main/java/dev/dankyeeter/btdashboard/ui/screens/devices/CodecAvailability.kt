package dev.dankyeeter.btdashboard.ui.screens.devices

import dev.dankyeeter.btdashboard.system.devices.BluetoothCodecOptions

/**
 * Which picker entries a device cannot do, and where they belong in the list.
 *
 * Pulled out of the Compose code because these two rules are the whole point of
 * the feature and both are easy to get subtly wrong — one of them by being
 * confidently wrong rather than merely ugly.
 */

/**
 * The codecs to grey out, given what the headphone currently offers.
 *
 * @param offered `CodecFamily` names the device advertises as selectable, or
 *   null when that could not be established — no privileged helper, device not
 *   connected, or an address this Android build redacts.
 *
 * Null yields an **empty set**: nothing is greyed out. "We could not ask" must
 * never render as "your headphone cannot do this", because the second claim is
 * one the app has no evidence for. This is the same rule the rest of the
 * project follows for degraded paths.
 */
internal fun unavailableCodecs(offered: List<String>?): Set<String> =
    offered
        // An empty answer is treated exactly like null, and not only because
        // the no-op controller returns one. There is no headphone that supports
        // no codec at all, so an empty list can only mean the question went
        // unanswered — and reading it literally would grey out every row.
        ?.takeIf { it.isNotEmpty() }
        ?.let { available -> BluetoothCodecOptions.codecs.filterNot { it in available }.toSet() }
        .orEmpty()

/**
 * One list, usable entries first, the rest appended in their original order.
 *
 * Deliberately not a filter: an entry that vanishes leaves someone who knows
 * their headphone supports aptX HD searching for a menu item that is simply
 * gone. Keeping it visible but plainly unusable is the honest shape.
 */
/** Where the codec shown in the editor comes from. */
internal enum class CodecOrigin {
    /** The profile asks for it, and will ask again on every connect. */
    STORED,

    /** Nobody stored anything; this is simply what the link negotiated. */
    NEGOTIATED,

    /**
     * A headphone is connected but its codec could not be read.
     *
     * Split out from [NONE] because collapsing the two produced a plain lie on
     * screen: with the helper down the A2DP read throws and the dumpsys
     * fallback is unavailable, so a connected Focal Bathys was labelled "Not
     * connected". Not knowing and nothing being there are different facts.
     */
    UNREADABLE,

    /** Nothing connected, so there is no codec to name. */
    NONE,
}

/**
 * What the codec field shows.
 *
 * The field never says "nothing is set" for a connected device, because an
 * A2DP link without a codec does not exist — if this app did not choose one,
 * the stack did. A stored wish wins over the live value: it is what will happen
 * on the next connect, which is what a field labelled "on connect" promises.
 */
internal fun codecToShow(preference: String?, negotiated: String?): String? =
    preference ?: negotiated

/**
 * Which of the two the field is showing.
 *
 * Kept separate from [codecToShow] because the value alone is ambiguous: "aptX"
 * as a stored wish and "aptX" as the codec that merely happens to be running
 * look identical, and confusing the two would make the app claim a setting it
 * never made.
 */
internal fun codecOrigin(
    preference: String?,
    negotiated: String?,
    deviceConnected: Boolean = false,
): CodecOrigin = when {
    preference != null -> CodecOrigin.STORED
    negotiated != null -> CodecOrigin.NEGOTIATED
    deviceConnected -> CodecOrigin.UNREADABLE
    else -> CodecOrigin.NONE
}

internal fun <T> orderByAvailability(
    options: List<Pair<T, String>>,
    unavailable: Set<T>,
): List<Pair<T, String>> =
    options.filterNot { it.first in unavailable } + options.filter { it.first in unavailable }
