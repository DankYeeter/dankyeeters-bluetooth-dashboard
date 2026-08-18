package dev.dankyeeter.btdashboard.nowplaying

import android.app.Notification
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reads track metadata out of media notifications, Tidal first.
 *
 * Strictly read-only. We take the [MediaController] the notification carries
 * and ask it for its metadata; we never send a transport command, never write
 * to the player, and never persist what was playing. PLAN.md's "never touch
 * Tidal itself" rule is a hard boundary, not a preference.
 *
 * Metadata comes from the session when the notification exposes one (correct
 * artist/album fields) and falls back to the notification's own title/text
 * extras otherwise, which is all some players publish.
 */
class TidalNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        NowPlayingRepository.setConnected(true)
        refresh()
    }

    override fun onListenerDisconnected() {
        NowPlayingRepository.setConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    /**
     * Re-derives the current reading from all active notifications.
     *
     * Recomputing from the full list rather than tracking one notification
     * keeps the state honest when several players are alive: Tidal wins,
     * otherwise the most recently posted media notification.
     */
    private fun refresh() {
        val active = try {
            activeNotifications
        } catch (e: SecurityException) {
            // Happens when access is revoked while we are bound.
            Log.w(TAG, "notification access lost", e)
            NowPlayingRepository.setConnected(false)
            return
        } ?: return

        val candidates = active
            .filter { it.notification?.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true }
            .sortedByDescending { it.postTime }

        val chosen = candidates.firstOrNull { it.packageName == TIDAL_PACKAGE }
            ?: candidates.firstOrNull()

        NowPlayingRepository.publish(chosen?.let(::toNowPlaying))
    }

    private fun toNowPlaying(sbn: StatusBarNotification): NowPlaying? {
        val extras = sbn.notification?.extras ?: return null

        val metadata = runCatching {
            val token = extras.getParcelable(
                Notification.EXTRA_MEDIA_SESSION,
                android.media.session.MediaSession.Token::class.java,
            )
            token?.let { MediaController(this, it).metadata }
        }.getOrNull()

        val track = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (track.isNullOrBlank()) return null

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        return NowPlaying(
            track = track,
            artist = artist?.takeIf { it.isNotBlank() },
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            isTidal = sbn.packageName == TIDAL_PACKAGE,
        )
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)),
        ).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val TAG = "TidalNowPlaying"

        /** Tidal's application id on Android. */
        const val TIDAL_PACKAGE = "com.aspiro.tidal"
    }
}
