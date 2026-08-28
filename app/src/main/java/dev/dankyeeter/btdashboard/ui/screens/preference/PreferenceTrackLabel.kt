package dev.dankyeeter.btdashboard.ui.screens.preference

import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource

/** What a song-run is called, and how much that name can be trusted. */
data class TrackLabel(
    val text: String,
    val source: PreferenceLabelSource,
)

/**
 * Works out what to call the song a run was made over.
 *
 * ## The fallback chain, and where it currently stops
 *
 * 1. **The track itself** — title, or "title — artist" when both are there.
 * 2. **The app that was playing**, plus the time, because one app plays many
 *    songs and a label of "Spotify" alone would make every run look like the
 *    same one to [dev.dankyeeter.btdashboard.hearing.preference.PreferencePool]'s
 *    replacement rule.
 * 3. **The time alone**, when even that is unreadable.
 *
 * Step 1 is wired but currently never fires, and that is a deliberate stop
 * rather than an oversight. Reading the playing track's metadata on Android goes
 * through `MediaSessionManager.getActiveSessions`, which is gated behind
 * notification-listener access — a permission that grants an app the contents of
 * every notification on the phone. Asking for that so a listening test can print
 * a song title is not a trade this app makes, so the parameter stays and nothing
 * fills it in. If a permission-free route ever exists, it lands in one call site.
 *
 * The user can always type a label on the run's result screen; that answer wins
 * over everything above ([PreferenceLabelSource.MANUAL]).
 *
 * Pure, so the chain is testable without a media session or a package manager.
 */
object PreferenceTrackLabel {

    fun resolve(
        trackTitle: String? = null,
        artist: String? = null,
        appNames: List<String> = emptyList(),
        timeLabel: String,
    ): TrackLabel {
        val title = trackTitle?.trim().orEmpty()
        if (title.isNotEmpty()) {
            val by = artist?.trim().orEmpty()
            return TrackLabel(
                text = if (by.isEmpty()) title else "$title — $by",
                source = PreferenceLabelSource.TRACK,
            )
        }
        // One name, not a list: with two players running the second is almost
        // always something that beeped, and a label reading "Spotify, Maps" is
        // worse than one that names the likely one and lets the user correct it.
        val app = appNames.map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (app != null) {
            return TrackLabel(text = "$app · $timeLabel", source = PreferenceLabelSource.APP)
        }
        return TrackLabel(text = timeLabel, source = PreferenceLabelSource.NONE)
    }

    /** A label the user typed, or the fallback when they cleared the field. */
    fun manual(typed: String, fallback: TrackLabel): TrackLabel {
        val text = typed.trim()
        if (text.isEmpty()) return fallback
        return TrackLabel(text = text, source = PreferenceLabelSource.MANUAL)
    }
}
