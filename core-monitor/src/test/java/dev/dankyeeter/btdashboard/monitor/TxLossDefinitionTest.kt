package dev.dankyeeter.btdashboard.monitor

import dev.dankyeeter.btdashboard.monitor.link.live.A2dpTxDelta
import dev.dankyeeter.btdashboard.monitor.link.live.TxLossChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second direction of the one loss definition (QA-010).
 *
 * `A2dpTxDelta.lossByChannel` is the single answer to "what counts as loss", and
 * the compiler already holds one half of it together: the panel's `when` over
 * [TxLossChannel] is exhaustive, so a channel added to the enum does not compile
 * until the screen has a word for it.
 *
 * The other half was unguarded. The map is written out by hand rather than
 * derived from [TxLossChannel.entries], so a channel that was added to the enum
 * and given its word, but forgotten in the map, would compile, would test green
 * everywhere else — and would never show a single lost packet. The realistic
 * sequence does not produce it, because channel and entry are written in the
 * same file in the same edit; "one definition, one place" was nonetheless "one
 * enforced place plus one unenforced one" until this file.
 */
class TxLossDefinitionTest {

    /**
     * One window with a different number on every channel.
     *
     * Distinct values on purpose: a map that wired both keys to the same field
     * would pass any assertion made with equal counts.
     */
    private val busyWindow = A2dpTxDelta(
        windowMs = 2_000L,
        dropped = 525,
        dropouts = 21,
        underflows = 3,
    )

    /** The same window with every counter still. */
    private val quietWindow = A2dpTxDelta(windowMs = 2_000L)

    @Test
    fun `every channel of the enum has an entry in the map`() {
        assertEquals(
            "a channel that is in the enum but not in the map never shows loss",
            TxLossChannel.entries.toSet(),
            busyWindow.lossByChannel.keys,
        )
    }

    /**
     * And in the window where nothing moved, too.
     *
     * The map lists every channel with the value it has, zero included, so that a
     * caller can tell "this channel counted nothing" from "this channel is not
     * asked about". A map that dropped its zeroes would satisfy the test above on
     * a busy window and lose a whole channel on a quiet one.
     */
    @Test
    fun `a window in which nothing moved still names every channel`() {
        assertEquals(TxLossChannel.entries.toSet(), quietWindow.lossByChannel.keys)
        assertTrue(
            "a quiet window dropped a channel instead of reporting its zero",
            quietWindow.lossByChannel.values.all { it == 0L },
        )
    }

    @Test
    fun `each entry carries the counter it names`() {
        assertEquals(525L, busyWindow.lossByChannel[TxLossChannel.DROPPED_PACKETS])
        assertEquals(21L, busyWindow.lossByChannel[TxLossChannel.STACK_DROPOUTS])
    }

    /**
     * The sum is the map, and nothing beside it.
     *
     * 525 + 21, with three encoder underflows in the same window that are not in
     * the total — the counter stayed at zero through the arm where stack dropouts
     * ran throughout and climbed through 39 minutes of flawless playback, so it
     * is a measurement and not a loss channel (`UI_SPEC.md`, AK-T009-24).
     */
    @Test
    fun `the total counts the map and no other counter`() {
        assertEquals(546L, busyWindow.lossCount)
        assertEquals(0L, quietWindow.lossCount)
        assertEquals(
            "the total moved with a counter that is not a loss channel",
            546L,
            busyWindow.copy(underflows = 4_000).lossCount,
        )
    }
}
