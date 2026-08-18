package dev.dankyeeter.btdashboard.system.devices

import android.content.Context
import android.media.AudioManager
import android.util.Log
import dev.dankyeeter.btdashboard.audio.eq.EqSettings
import dev.dankyeeter.btdashboard.hearing.store.CompensationProfileStore
import dev.dankyeeter.btdashboard.system.attach.EqController
import dev.dankyeeter.btdashboard.system.persist.EqSettingsStore
import kotlin.math.roundToInt

/**
 * `STREAM_MUSIC` volume as a percentage.
 *
 * Percent rather than raw steps because the step count differs per device and
 * per stream, and a profile made on one phone should still mean "about 60 %"
 * on another. Rounding is to the nearest step; a device with 15 steps simply
 * cannot hit every percentage, and pretending otherwise would be noise.
 */
class SystemMediaVolumeController(context: Context) : MediaVolumeController {

    private val audio = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun currentPercent(): Int? {
        val manager = audio ?: return null
        return runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return null
            (manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
        }.getOrNull()
    }

    override fun setPercent(percent: Int): Boolean {
        val manager = audio ?: return false
        return runCatching {
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val min = manager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            if (max <= min) return false
            val target = (min + (max - min) * percent.coerceIn(0, 100) / 100f).roundToInt()
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, target.coerceIn(min, max), 0)
            true
        }.onFailure {
            // Do Not Disturb blocks volume changes without notification-policy
            // access. That is a legitimate refusal, not an error to shout about.
            Log.i(TAG, "volume change refused", it)
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "MediaVolumeController"
    }
}

/**
 * Loads a stored compensation profile and pushes its curve onto the live EQ.
 *
 * The profile's own `enabled` flag is ignored on purpose: applying a profile is
 * an explicit "use this now", so the EQ is switched on. The stored settings
 * otherwise pass through untouched.
 */
class EqCompensationApplier(
    private val profiles: CompensationProfileStore,
    private val settingsStore: EqSettingsStore,
    private val controller: EqController,
) : CompensationApplier {

    override suspend fun apply(compensationProfileId: String): Boolean {
        val profile = profiles.current().firstOrNull { it.id == compensationProfileId } ?: return false
        val settings: EqSettings = profile.eq.copy(enabled = true).sanitized()
        settingsStore.save(settings)
        settingsStore.setActiveProfileId(profile.id)
        controller.apply(settings)
        return true
    }
}
