package dev.dankyeeter.btdashboard.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What is currently playing, as far as the media notification tells us.
 *
 * Read-only by design: we listen to the notification the player posts anyway.
 * We never touch Tidal itself — no transport commands, no settings, nothing
 * written back. PLAN.md is explicit about that boundary.
 */
data class NowPlaying(
    val track: String,
    val artist: String?,
    val album: String?,
    /** Package name of the player, e.g. `com.aspiro.tidal`. */
    val packageName: String,
    /** Human-readable app name when we could resolve it, else the package. */
    val appLabel: String,
    val isTidal: Boolean,
) {
    /** "Artist — Track", or just the track when no artist was published. */
    fun describe(): String = if (artist.isNullOrBlank()) track else "$artist — $track"
}

/**
 * The codec half of the "You're hearing X in LDAC @ 990 kbps" line.
 *
 * The link monitor owns the real codec data. This interface is the seam
 * between that work and this card, so neither side has to wait for the other:
 * the Dashboard renders the codec clause only when something has registered a
 * source, and reads "codec unknown" until then.
 */
interface NowPlayingCodecSource {
    val codec: StateFlow<CodecSummary?>
}

/**
 * Minimal codec description this screen needs. Deliberately small — the
 * monitor's own richer model stays in its module.
 *
 * @param name codec as reported by the platform, e.g. "LDAC", "aptX Adaptive"
 * @param bitrateKbps negotiated bitrate when known; null for codecs (and OEM
 *   implementations) that do not report one
 */
data class CodecSummary(
    val name: String,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
) {
    /** "LDAC @ 990 kbps" or just "LDAC". */
    fun describe(): String =
        if (bitrateKbps != null) "$name @ $bitrateKbps kbps" else name
}

/**
 * Process-wide registration point for the codec source.
 *
 * Loose wiring on purpose: :app must not depend on the monitor module for a
 * dashboard card to render. Whoever owns codec data calls [register] once
 * during startup; if nobody does, the card simply omits the codec clause.
 */
object NowPlayingCodecRegistry : NowPlayingCodecSource {

    private val empty = MutableStateFlow<CodecSummary?>(null)
    private val _source = MutableStateFlow<NowPlayingCodecSource?>(null)

    /** The registered source, or null while none has been installed. */
    val source: StateFlow<NowPlayingCodecSource?> = _source.asStateFlow()

    fun register(source: NowPlayingCodecSource) {
        _source.value = source
    }

    override val codec: StateFlow<CodecSummary?>
        get() = _source.value?.codec ?: empty.asStateFlow()
}
