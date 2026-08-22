package dev.dankyeeter.btdashboard.system.attach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Answers one question: does an effect on the **output mix** still reach the
 * sound the user hears?
 *
 * ## Why it has to be asked
 *
 * The app attaches to session 0 because that is the one attach point covering
 * *every* player, including those that never announce an audio session. On the
 * phone speaker that works: an 18 dB cut measures as a 14 dB drop in the air.
 *
 * Over Bluetooth it does not, and nothing says so. Measured through the
 * earcups on a Pixel with the Bathys, an 18 dB cut on session 0 moved the sound
 * by 0,4 dB, then 1,7 dB, then 0,2 dB across repeated runs - scatter around
 * zero. The same cut on a track's own session moved it by 8 dB on every band,
 * twice, reproducibly.
 *
 * Meanwhile AudioFlinger reports the session-0 effect on the correct output
 * thread, ACTIVE, enabled, not suspended, with its gains reading back exactly as
 * written. The only tell is `Active tracks: 0` on that chain. An equaliser that
 * is "attached" and inaudible is worse than one that admits it failed -
 * especially when its job is to compensate hearing loss.
 *
 * ## What the rule is, and what it is not
 *
 * Spatial Audio was the first suspect and it was **wrong**: the A2DP route runs
 * through a SPATIALIZER thread, which looked like the obvious culprit, but
 * turning Spatial Audio off changed nothing - three further runs still scattered
 * around zero. It is kept as a second condition because a spatialized output is
 * a genuine bypass wherever it engages, but it is not the reason Bluetooth
 * fails here.
 *
 * The rule that matches the measurements is simply: **the output mix is not a
 * trustworthy attach point for Bluetooth output.** That is a statement about
 * this device, honestly scoped - it may well be false on another ROM. The
 * asymmetry is what makes it safe to apply anyway: guessing "unreachable" when
 * it would have worked costs reach on players that do not broadcast, while
 * guessing "reachable" when it does not costs the user *everything* and says
 * nothing about it.
 */
class OutputMixReachGate(private val context: Context) {

    /**
     * True when a session-0 effect can be expected to reach the output.
     *
     * Fails **open**: if the route cannot be determined, the answer is yes.
     * Global is the better mode whenever it works, and an unknown state should
     * not silently cost the wider reach.
     */
    fun globalAttachReachesOutput(): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        !routesToBluetooth(audio) && !spatializerEngaged(audio)
    }.getOrDefault(true)

    /**
     * Where media *would* go right now - not what is plugged in.
     *
     * `getAudioDevicesForAttributes` answers for the routing that media playback
     * would actually take, which is the only thing that matters here; a
     * connected-but-unused device must not push the app into the narrower mode.
     */
    private fun routesToBluetooth(audio: AudioManager): Boolean {
        val media = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return audio.getAudioDevicesForAttributes(media).any { it.type in BLUETOOTH_OUTPUTS }
    }

    /**
     * `isEnabled` is the user's setting, `isAvailable` is routing-dependent.
     * Both together mean the spatializer is engaged for the current output.
     */
    private fun spatializerEngaged(audio: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return false
        val spatializer = audio.spatializer
        return spatializer.isEnabled && spatializer.isAvailable
    }

    private companion object {
        val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )
    }
}
