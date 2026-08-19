package dev.dankyeeter.btdashboard.monitor.effects

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real [InstalledAppSource]: exactly two `PackageManager` round trips, one
 * pass over the result, on the IO dispatcher.
 *
 * Everything here is written for cost. `queryIntentActivities` returns only the
 * handful of apps that declare an effect panel, so it is looked up once into a
 * set instead of being asked per package. `getInstalledPackages` is called with
 * `GET_PERMISSIONS` and nothing else — that is the only extra field read, and
 * every additional flag is more parcel to marshal across the binder. App labels
 * are resolved *after* filtering: `loadLabel` opens the app's resource table,
 * which on a few hundred packages is by far the most expensive thing that could
 * happen here, and only a few dozen survive the filter.
 */
class PackageManagerAppSource(private val context: Context) : InstalledAppSource {

    override suspend fun read(): InstalledAppReadResult = withContext(Dispatchers.IO) {
        val pm = context.packageManager
            ?: return@withContext InstalledAppReadResult.Unavailable("no package manager")

        runCatching {
            val panelPackages = effectPanelPackages(pm)

            @Suppress("DEPRECATION") // The PackageInfoFlags overload is API 33+; minSdk here is 31.
            val installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            val apps = installed.mapNotNull { info ->
                val packageName = info.packageName ?: return@mapNotNull null
                val declares = packageName in panelPackages
                val requests = info.requestedPermissions
                    ?.any { it == Manifest.permission.MODIFY_AUDIO_SETTINGS } == true
                val vendor = VendorEqApps.byPackage(packageName) != null

                // Filter before loading a single label.
                if (!declares && !requests && !vendor) return@mapNotNull null

                val appInfo: ApplicationInfo? = info.applicationInfo
                InstalledApp(
                    packageName = packageName,
                    label = appInfo?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() }
                        .orEmpty()
                        .ifBlank { packageName },
                    declaresEffectPanel = declares,
                    requestsAudioSettings = requests,
                    isUntouchedSystemApp = appInfo != null &&
                        appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                        appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                )
            }
            InstalledAppReadResult.Available(apps, installed.size)
        }.getOrElse {
            // A PackageManager that throws (dead system process, a ROM that
            // caps the query, a revoked QUERY_ALL_PACKAGES) must read as
            // "cannot check", never as an empty all-clear.
            InstalledAppReadResult.Unavailable(
                it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName,
            )
        }
    }

    /**
     * Apps that answer `ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`. Declaring
     * that intent filter is an app stating it owns an equaliser UI, which is
     * why this tier is the only one that is not a guess.
     */
    private fun effectPanelPackages(pm: PackageManager): Set<String> = runCatching {
        @Suppress("DEPRECATION") // ResolveInfoFlags overload is API 33+.
        pm.queryIntentActivities(Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}

/**
 * The real [PlayingAppsSource], via `AudioManager.getActivePlaybackConfigurations()`.
 *
 * One caveat, stated rather than hidden: `AudioPlaybackConfiguration.getClientUid()`
 * is a system API. A normal app gets the configuration objects but not, on the
 * public surface, the uid inside them — so the uid is read reflectively, in the
 * same spirit as `ShizukuShellRunner`, and when the field is not reachable the
 * whole overlay reports "unavailable" instead of pretending nothing is playing.
 */
class AudioManagerPlayingAppsSource(private val context: Context) : PlayingAppsSource {

    override suspend fun playingPackages(): PlayingAppsResult = withContext(Dispatchers.IO) {
        // Dead code at minSdk 31, kept because the API contract starts at 26
        // and "we cannot check" is the only honest answer below it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return@withContext PlayingAppsResult.Unavailable(
                "this Android version does not report which app is playing",
            )
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return@withContext PlayingAppsResult.Unavailable("no audio manager")

        val configs = runCatching { am.activePlaybackConfigurations }.getOrNull()
            ?: return@withContext PlayingAppsResult.Unavailable(
                "the audio framework did not return the player list",
            )
        if (configs.isEmpty()) return@withContext PlayingAppsResult.Available(emptySet())

        val uids = configs.mapNotNull { config ->
            runCatching {
                config.javaClass.getMethod("getClientUid").invoke(config) as? Int
            }.getOrNull()
        }
        if (uids.isEmpty()) {
            return@withContext PlayingAppsResult.Unavailable(
                "this Android version does not name the app behind a playing stream",
            )
        }

        val pm = context.packageManager
            ?: return@withContext PlayingAppsResult.Unavailable("no package manager")
        PlayingAppsResult.Available(
            PlayingAppMapper.packagesFor(uids) { uid ->
                pm.getPackagesForUid(uid)?.toList().orEmpty()
            },
        )
    }
}

/**
 * Event-driven "is something playing" updates while a screen is visible.
 *
 * `registerAudioPlaybackCallback` fires when playback actually starts or stops,
 * so nothing polls. It is registered on the screen becoming visible and
 * unregistered on it leaving — there is no background listener, and the
 * callback only ever triggers the cheap overlay, never the package pass.
 */
class AudioPlaybackWatcher(private val context: Context) {

    private var callback: AudioManager.AudioPlaybackCallback? = null

    fun start(onChanged: () -> Unit) {
        if (callback != null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
                onChanged()
            }
        }
        runCatching { am.registerAudioPlaybackCallback(cb, Handler(Looper.getMainLooper())) }
            .onSuccess { callback = cb }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.unregisterAudioPlaybackCallback(cb) }
    }
}
