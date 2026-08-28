package dev.dankyeeter.btdashboard.ui.screens.preference

import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The label fallback chain, which decides both what a song-run is called and —
 * through [dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun.matchKey]
 * — whether re-running it replaces the old answer or adds a second one.
 */
class PreferenceTrackLabelTest {

    private val time = "14:05"

    @Test
    fun `a track title wins outright`() {
        val label = PreferenceTrackLabel.resolve(
            trackTitle = "Blue Monday",
            artist = "New Order",
            appNames = listOf("Spotify"),
            timeLabel = time,
        )
        assertEquals("Blue Monday — New Order", label.text)
        assertEquals(PreferenceLabelSource.TRACK, label.source)
    }

    @Test
    fun `a title with no artist is still a title`() {
        val label = PreferenceTrackLabel.resolve(trackTitle = "Untitled", timeLabel = time)
        assertEquals("Untitled", label.text)
        assertEquals(PreferenceLabelSource.TRACK, label.source)
    }

    @Test
    fun `a blank title falls through to the app`() {
        val label = PreferenceTrackLabel.resolve(
            trackTitle = "   ",
            appNames = listOf("Tidal"),
            timeLabel = time,
        )
        assertEquals("Tidal · 14:05", label.text)
        assertEquals(PreferenceLabelSource.APP, label.source)
    }

    /**
     * The app name carries the time with it deliberately. Without it every run
     * made in one player would share a match key, and the pool's replacement
     * rule would keep overwriting one entry instead of collecting songs.
     */
    @Test
    fun `the app name is never the whole label`() {
        val first = PreferenceTrackLabel.resolve(appNames = listOf("Spotify"), timeLabel = "14:05")
        val second = PreferenceTrackLabel.resolve(appNames = listOf("Spotify"), timeLabel = "14:22")
        assertEquals(PreferenceLabelSource.APP, first.source)
        org.junit.Assert.assertNotEquals(first.text, second.text)
    }

    @Test
    fun `only one app is named`() {
        val label = PreferenceTrackLabel.resolve(
            appNames = listOf("Spotify", "Maps"),
            timeLabel = time,
        )
        assertEquals("Spotify · 14:05", label.text)
    }

    @Test
    fun `blank app names are skipped rather than printed`() {
        val label = PreferenceTrackLabel.resolve(appNames = listOf("", "  ", "Poweramp"), timeLabel = time)
        assertEquals("Poweramp · 14:05", label.text)
    }

    @Test
    fun `nothing readable leaves the time alone`() {
        val label = PreferenceTrackLabel.resolve(timeLabel = time)
        assertEquals(time, label.text)
        assertEquals(PreferenceLabelSource.NONE, label.source)
    }

    @Test
    fun `what the user types wins over everything`() {
        val fallback = PreferenceTrackLabel.resolve(appNames = listOf("Spotify"), timeLabel = time)
        val label = PreferenceTrackLabel.manual("  Bohemian Rhapsody  ", fallback)
        assertEquals("Bohemian Rhapsody", label.text)
        assertEquals(PreferenceLabelSource.MANUAL, label.source)
    }

    @Test
    fun `clearing the field goes back to the fallback rather than to nothing`() {
        val fallback = PreferenceTrackLabel.resolve(appNames = listOf("Spotify"), timeLabel = time)
        assertEquals(fallback, PreferenceTrackLabel.manual("", fallback))
        assertEquals(fallback, PreferenceTrackLabel.manual("   ", fallback))
    }
}
