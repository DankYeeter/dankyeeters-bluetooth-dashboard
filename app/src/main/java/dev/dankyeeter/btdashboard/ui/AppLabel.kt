package dev.dankyeeter.btdashboard.ui

import android.content.Context

/**
 * The app's own name for its uid, falling back to the package name and then
 * to the number.
 *
 * A uid is what the audio framework provides and a name is what the user
 * recognises, so it is resolved for display — and only for display. Without
 * QUERY_ALL_PACKAGES (AD-050) most apps are invisible to this one, so the number
 * is the normal case, not an error: inventing "Unknown app", or dropping the
 * entry, would hide that there really is a process playing (AK-3).
 *
 * A uid can hold several packages; the first one that has a label is close
 * enough, because the alternative is a list nobody asked for.
 */
fun appLabel(context: Context, uid: Int): String {
    val pm = context.packageManager
    val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull().orEmpty()
    val label = packages.firstNotNullOfOrNull { name ->
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString() }.getOrNull()
    }
    return label?.takeIf { it.isNotBlank() } ?: packages.firstOrNull() ?: "uid $uid"
}
