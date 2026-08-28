package dev.dankyeeter.btdashboard.monitor.link

/**
 * The one short line the event **list** shows for an event.
 *
 * ## Why this is not `event.detail`
 *
 * `detail` is written for the detail layer: a finished sentence, with the
 * counters and the mode tokens in it, produced by whichever machinery noticed
 * the change. Rendering it in the list gave forty rows of prose in which
 * "Measured bitrate dropped from 990 to 660 kbps (quality mode ABR)" and
 * "Playback stopped on Encore while the device stayed connected" looked exactly
 * alike, and a listener scanning for the moment their music broke had to read
 * every one of them.
 *
 * So the list line is **derived from the typed fields instead of the sentence**.
 * That is the rule that makes the two layers hold: nothing in the list is a
 * substring of anything a parser produced, no counter name, mode token or class
 * name can leak into it, and the line is short enough to be read at a glance —
 * [MAX_CHARS] is a hard bound, not a guideline, and [of] enforces it.
 *
 * ## The one thing a single event cannot say
 *
 * "LDAC 660 → 990 kbps" needs the rate *before* the step, and a stored event
 * carries only the rate it settled at. Rather than widen the row on disk for it,
 * [lines] walks the log in order and remembers the last rate it reported — so
 * the arrow is reconstructed from the log itself and reads the same whether the
 * events were just measured or loaded from last week.
 */
object MonitorEventSummary {

    /**
     * The longest a list line may be.
     *
     * Sized for the narrowest phone this app targets: a row is a timestamp
     * column plus this, and beyond about here the line wraps and the list stops
     * being one-line-per-event, which is the whole point of it. Enforced rather
     * than documented — see [clip].
     */
    const val MAX_CHARS = 48

    /**
     * How much of a device name a line may spend.
     *
     * Long enough for "Motion Boom Plus", short enough that a headphone whose
     * manufacturer put the whole model number in the Bluetooth name cannot push
     * the verb off the end of the row.
     */
    const val MAX_NAME_CHARS = 18

    /** One event ready for the list: the short line, and the event behind it. */
    data class Line(val event: MonitorEvent, val summary: String)

    /**
     * Summarises a log in the order it happened.
     *
     * Takes the whole list rather than one event because the bitrate arrow is a
     * property of the *sequence* — see the class note. Everything else is
     * decided per event, so passing events out of order costs nothing but that
     * one arrow.
     *
     * @param events oldest first, as the repository returns them.
     */
    fun lines(events: List<MonitorEvent>): List<Line> {
        var lastRateKbps: Int? = null
        return events.map { event ->
            val line = Line(event, of(event, lastRateKbps))
            // Only a rate that was really established updates the memory. An
            // adaptive mode change carries no figure on purpose, and letting it
            // clear the last one would turn the next step into a bare number.
            if (event.type == MonitorEventType.BITRATE_MODE_CHANGED) {
                lastRateKbps = event.bitrateKbps ?: lastRateKbps
            }
            line
        }
    }

    /**
     * The line for one event.
     *
     * @param previousBitrateKbps the last rate this log reported, for the arrow.
     */
    fun of(event: MonitorEvent, previousBitrateKbps: Int? = null): String =
        clip(text(event, previousBitrateKbps))

    private fun text(event: MonitorEvent, previousKbps: Int?): String {
        val name = event.shortName()
        return when (event.type) {
            // The name is worth its characters here and nowhere else: with two
            // headphones paired, *which* one connected is the whole content of
            // the line. "Connected" alone would be true of either.
            MonitorEventType.ACL_CONNECTED -> name?.let { "$it connected" } ?: "Connected"

            // "Connection lost" rather than "disconnected" when there is no name
            // to hang it on: the listener's experience of an unnamed drop is
            // that it stopped, not that a device performed an action.
            MonitorEventType.ACL_DISCONNECTED ->
                name?.let { "$it disconnected" } ?: "Connection lost"

            MonitorEventType.PLAYING_STARTED -> "Playback started"
            MonitorEventType.PLAYING_STOPPED -> "Playback stopped"

            // The codec is the answer; the verb around it is not. "Codec: LDAC"
            // reads as an instrument, "The negotiated codec changed to LDAC"
            // reads as a report about itself.
            MonitorEventType.CODEC_CHANGED ->
                event.codec?.let { "Codec: ${it.displayName}" } ?: "Codec changed"

            MonitorEventType.ACTIVE_DEVICE_CHANGED ->
                name?.let { "$it is now active" } ?: "No active audio device"

            MonitorEventType.TAKEOVER -> "Stream taken by another device"
            MonitorEventType.INTERRUPTION -> "Playback interrupted"
            MonitorEventType.QUALITY_REPORT -> "Link anomaly noticed"
            MonitorEventType.DROPOUT -> "Audio dropout"
            MonitorEventType.ENCODER_STARVATION -> "Encoder starving"
            MonitorEventType.BITRATE_MODE_CHANGED -> bitrateLine(event, previousKbps)
            MonitorEventType.MONITOR_NOTE -> "Monitor note"
        }
    }

    /**
     * "LDAC 660 → 990 kbps", with only the parts that were established.
     *
     * The arrow appears only when there is a real earlier reading to point away
     * from, and never when the two are equal — an arrow between one number and
     * itself is a claim that something moved.
     *
     * A mode change with no figure at all is the adaptive case, which is
     * deliberately rate-less: the line says what changed rather than inventing
     * a rate the stack never reported.
     */
    private fun bitrateLine(event: MonitorEvent, previousKbps: Int?): String {
        val codec = event.codec?.displayName
        val to = event.bitrateKbps
            ?: return listOfNotNull(codec, "quality changed").joinToString(" ")
        val step = if (previousKbps != null && previousKbps != to) "$previousKbps → $to" else "$to"
        return listOfNotNull(codec, "$step kbps").joinToString(" ")
    }

    /**
     * The device name, trimmed to fit a line.
     *
     * Blank names are treated as absent: the broadcast mapper falls back to the
     * address when the platform gives it nothing, and an empty string reaching
     * a line would print " connected".
     */
    private fun MonitorEvent.shortName(): String? {
        val name = deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (name.length <= MAX_NAME_CHARS) name else name.take(MAX_NAME_CHARS - 1) + "…"
    }

    /**
     * The bound, applied.
     *
     * Every branch above is written to fit, so this only ever fires on a device
     * name long enough to survive [MAX_NAME_CHARS] and still overflow. It is
     * here anyway, because "the wording is short" is a property somebody has to
     * be able to rely on, and a rule enforced only by review is not one.
     */
    private fun clip(line: String): String =
        if (line.length <= MAX_CHARS) line else line.take(MAX_CHARS - 1).trimEnd() + "…"
}
