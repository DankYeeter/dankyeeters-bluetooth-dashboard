package dev.dankyeeter.btdashboard.ui.common

import dev.dankyeeter.btdashboard.system.attach.AttachmentStatus
import dev.dankyeeter.btdashboard.ui.theme.PillTone

// ---- live state, rendered ---------------------------------------------------
//
// One rendering of the attachment state, for every screen that shows it.
//
// There used to be a copy per screen, and they had already drifted into
// contradicting each other: one named Tidal and an Android version as if the
// EQ's reach were a fact about that app, the other said the EQ screen names the
// apps. Whichever screen the user happened to open decided what they believed.
// The state is one thing, so its words are one thing.
//
// Deliberately free of app and version names: which players announce their
// audio session changes with every build, and a sentence naming one is a
// sentence that goes stale without anyone noticing.
//
// [describe] and [describeWithApps] are two renderings of the *same* state and
// live here as siblings for that reason. The second one used to sit privately
// in EqScreen, and it had already drifted: it learned that global attachment
// also covers players that keep their playback to themselves, and the shared
// sentence never did. Whichever screen the user opened decided what they
// believed — again. Kept side by side, a change to one is read next to the
// other, which is the only thing that keeps them saying the same thing.

/** Short label for the status pill — a state, not a sentence. */
fun AttachmentStatus.pill(): String = when (this) {
    is AttachmentStatus.ActiveGlobal -> "Global"
    is AttachmentStatus.ActiveSessions -> "Session only"
    is AttachmentStatus.Unavailable -> "Unavailable"
    AttachmentStatus.Inactive -> "Off"
}

/**
 * Session mode warns rather than reassures: it is a working EQ, but one that
 * silently misses any player keeping its playback to itself.
 */
fun AttachmentStatus.tone(): PillTone = when (this) {
    is AttachmentStatus.ActiveGlobal -> PillTone.ACCENT
    is AttachmentStatus.ActiveSessions, is AttachmentStatus.Unavailable -> PillTone.WARN
    AttachmentStatus.Inactive -> PillTone.NEUTRAL
}

/** One line saying what the state means for what the user is hearing. */
fun AttachmentStatus.describe(): String = when (this) {
    is AttachmentStatus.ActiveGlobal ->
        "Attached to the output mix — every app is equalised."
    // No count of sessions: a number is nothing the user can check against what
    // they are hearing.
    is AttachmentStatus.ActiveSessions ->
        "Following whatever is playing, app by app."
    is AttachmentStatus.Unavailable -> reason
    // Inactive is both "the user switched the EQ off" and "nothing has applied
    // it yet this session" — it is the controller's initial value. Naming only
    // the first would be a guess dressed up as a fact.
    AttachmentStatus.Inactive -> "Not attached."
}

/**
 * The same line for the screen that can name the apps being equalised.
 *
 * Names and never numbers. Session ids used to be in this sentence, and a count
 * of them is not something anyone can check against what they are hearing. A
 * name is: either the app playing is in the list, or the EQ is not reaching it.
 */
fun AttachmentStatus.describeWithApps(playingApps: List<String>): String = when (this) {
    is AttachmentStatus.ActiveGlobal ->
        "Attached to the output mix — every app is equalised, including those that " +
            "keep their playback to themselves."

    is AttachmentStatus.ActiveSessions -> when {
        playingApps.isEmpty() ->
            "Following whatever is playing. Nothing is playing at the moment."

        else -> "Currently equalising " + playingApps.joinToString(
            separator = ", ",
            limit = 3,
            truncated = "others",
        ) + "."
    }

    is AttachmentStatus.Unavailable -> reason
    AttachmentStatus.Inactive -> "Not attached."
}
