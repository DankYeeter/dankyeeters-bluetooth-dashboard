package dev.dankyeeter.btdashboard.system.attach

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who is playing right now, as uids.
 *
 * Kept apart from the attachment machinery on purpose: that machinery works in
 * session ids and should keep doing so, because ids are what an audio effect
 * attaches to. This is the other question - *whose* sound is being equalised -
 * and it exists only so the app can answer it in words.
 *
 * Uids rather than names because resolving a name needs a PackageManager, and
 * that belongs in the layer that renders text, not in the one that reads a
 * dumpsys. Nothing here is shown to anyone as a number.
 */
object PlayingApps {

    private val _uids = MutableStateFlow<Set<Int>>(emptySet())

    val uids: StateFlow<Set<Int>> = _uids.asStateFlow()

    fun report(uids: Set<Int>) {
        _uids.value = uids.filter { it > 0 }.toSet()
    }
}
