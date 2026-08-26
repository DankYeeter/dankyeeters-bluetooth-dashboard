package dev.dankyeeter.btdashboard.monitor.link.live

import dev.dankyeeter.btdashboard.monitor.codec.CodecFamily
import dev.dankyeeter.btdashboard.monitor.shell.ShellRunner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Polls the three dumps that together describe the live audio path and turns
 * each pass into a [LinkLiveSnapshot] plus the [LinkEvent]s that separate it
 * from the previous one.
 *
 * ## Where the data comes from
 *
 * | dump | what it contributes |
 * |------|---------------------|
 * | `dumpsys bluetooth_manager` | negotiated codec, LDAC quality index, whether the codec is offloaded, and `btif_a2dp_source`'s tx-queue counters |
 * | `dumpsys media.audio_flinger` | the output thread the Bluetooth route sits on, its underrun counters, and per-track underruns |
 * | `dumpsys audio` | which apps are playing and at what sample rate and channel count |
 *
 * All three are already on the privileged helper's whitelist. **Nothing was
 * added to it for this**, and the reason is worth recording: the obvious fourth
 * source is `logcat`, and on the device this was built against it contributes
 * nothing. A full unfiltered `logcat` covering two hours of LDAC playback
 * contained no LDAC, ABR, or bitrate line of any kind — the stack does not log
 * quality-mode transitions at default log levels. Widening what a shell-uid
 * process is allowed to run, in exchange for a stream that is empty, is a bad
 * trade in both directions.
 *
 * ## Why it polls rather than streams
 *
 * [ShellRunner] is a request/response seam: `run(command)` returns once the
 * command has exited. There is no streaming variant, so a `logcat -T 1`
 * follower is not expressible through it, and adding one would mean a second
 * privileged transport for a source that has nothing in it (see above).
 *
 * ## Cost
 *
 * One pass is three `dumpsys` execs inside the helper, roughly six thousand
 * lines Base64'd back over a Binder call. Measured on the Pixel 11 Pro this was
 * built against: 233 ms for `bluetooth_manager`, 155 ms for
 * `media.audio_flinger`, 162 ms for `audio` — about half a second of work per
 * pass, so at [DEFAULT_INTERVAL_MS] the phone spends roughly a quarter of its
 * time producing this screen.
 *
 * That is far too expensive to run in the background, which is why [updates] is
 * a **cold** flow: it polls only while something is collecting it, and stops
 * the moment the screen goes away. The interval is a parameter rather than a
 * constant precisely because of the figures above — a user actively chasing a
 * dropout can afford one second, and a panel left open cannot.
 */
class LiveLinkSource(
    private val shell: ShellRunner,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val isAvailable: Boolean get() = shell.isAvailable

    /**
     * One complete reading.
     *
     * [previous] is what the deltas are measured against. Passing null — the
     * first poll of a session — yields a snapshot with counters but no deltas,
     * which is the honest result: a cumulative total says nothing about now.
     */
    suspend fun readOnce(previous: LinkLiveSnapshot? = null): LinkLiveSnapshot {
        val now = clock()
        if (!shell.isAvailable) {
            return LinkLiveSnapshot(
                timestampMs = now,
                warnings = listOf("no shell identity — the helper is not running"),
            )
        }

        val warnings = mutableListOf<String>()
        val btDump = read(listOf("dumpsys", "bluetooth_manager"), warnings)
        val flingerDump = read(listOf("dumpsys", "media.audio_flinger"), warnings)
        val audioDump = read(listOf("dumpsys", "audio"), warnings)

        val link = A2dpLinkDumpParser.parse(btDump)
        val flinger = AudioFlingerTrackParser.parse(flingerDump)
        val players = PlayingStreamParser.playingStreams(audioDump)

        warnings += link.warnings
        warnings += flinger.warnings

        val mixerThread = flinger.bluetoothThread
        val previousMixer = previous?.mixer?.takeIf { it.threadName == mixerThread?.output?.threadName }
        val mixer = mixerThread?.output?.let { output ->
            output.copy(
                fastMixerUnderrunDelta = increase(previousMixer?.fastMixerUnderruns, output.fastMixerUnderruns),
                normalMixerEmptyDelta = increase(
                    previousMixer?.normalMixerEmptyUnderruns,
                    output.normalMixerEmptyUnderruns,
                ),
            )
        }
        if (mixerThread == null && link.device?.isConnected == true) {
            warnings += "no AudioFlinger output thread is routed to Bluetooth right now"
        }

        val inputs = joinInputs(players, mixerThread, previous)
        val ldac = link.codec
            ?.takeIf { it.family == CodecFamily.LDAC }
            ?.let { LdacState.from(it.codecSpecific1, it.sampleRateHz) }

        // The tx counters are only meaningful for a codec the host encodes. On
        // an offloaded codec btif_a2dp_source is bypassed entirely and its
        // counters sit wherever the last host-encoded session left them, which
        // would read as a frozen, perfectly healthy link.
        val txUsable = link.codec?.isEncodedOnHost != false
        if (!txUsable) {
            warnings += "${link.codec?.family?.displayName} is encoded by the controller — " +
                "the Bluetooth stack's tx-queue counters do not apply to this link"
        }
        val tx = link.tx?.takeIf { txUsable }

        return LinkLiveSnapshot(
            timestampMs = now,
            device = link.device,
            codec = link.codec,
            ldac = ldac,
            tx = tx,
            txDelta = txDelta(previous, tx, now),
            inputs = inputs,
            mixer = mixer,
            warnings = warnings,
        )
    }

    /**
     * A cold flow of readings. Polling starts on collection and stops with it.
     *
     * The interval is measured from the *start* of each pass, so a slow dump
     * eats into the wait rather than adding to it and the sample spacing stays
     * roughly even — which matters, because every delta in the snapshot is
     * divided by that spacing.
     */
    fun updates(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<LinkLiveUpdate> = flow {
        var previous: LinkLiveSnapshot? = null
        while (currentCoroutineContext().isActive) {
            val startedAt = clock()
            val snapshot = readOnce(previous)
            val events = previous?.let { eventsBetween(it, snapshot) }.orEmpty()
            emit(LinkLiveUpdate(snapshot, events))
            previous = snapshot
            val spent = clock() - startedAt
            delay((intervalMs - spent).coerceAtLeast(MIN_INTERVAL_MS))
        }
    }

    private suspend fun read(command: List<String>, warnings: MutableList<String>): String {
        val result = shell.run(command)
        // A non-zero exit with partial output still parses: dumpsys routinely
        // times out on one section while the rest is intact.
        if (!result.isSuccess && result.stdout.isBlank()) {
            warnings += "${command.joinToString(" ")} failed: " +
                result.stderr.ifBlank { "exit ${result.exitCode}" }
        }
        return result.stdout
    }

    // ---- joining and differencing -------------------------------------------

    /**
     * Matches each playing app to its mixer track by pid.
     *
     * pid rather than uid because two processes of the same app can each hold a
     * track, and pid rather than session id because an app that never announced
     * a session still has one in AudioFlinger. Note the two dumps print the
     * pair in opposite orders — `u/pid:<uid>/<pid>` against
     * `Client(pid/uid)` — which is a good way to silently join the wrong column.
     */
    private fun joinInputs(
        players: List<PlayingStream>,
        thread: MixerThreadDump?,
        previous: LinkLiveSnapshot?,
    ): List<InputStreamSnapshot> {
        val tracksByPid = thread?.tracks.orEmpty().associateBy { it.pid }
        val previousByPid = previous?.inputs.orEmpty().associateBy { it.pid }
        return players.map { player ->
            val track = tracksByPid[player.pid]
            val before = previousByPid[player.pid]
            InputStreamSnapshot(
                uid = player.uid,
                pid = player.pid,
                sessionId = player.sessionId,
                sampleRateHz = player.sampleRateHz,
                channelCount = player.channelCount,
                pcmFormat = track?.format,
                trackSampleRateHz = track?.sampleRateHz,
                isSpatialized = player.isSpatialized,
                usage = player.usage,
                contentType = player.contentType,
                underrunCount = track?.underruns,
                underrunDelta = increase(before?.underrunCount, track?.underruns),
                flushedCount = track?.flushed,
                flushedDelta = increase(before?.flushedCount, track?.flushed),
            )
        }
    }

    /**
     * The rise in a cumulative counter, or null when there is nothing to
     * compare against.
     *
     * A *fall* also returns null rather than zero. Counters only go down when
     * the thing counting them restarted — a new mixer track, a Bluetooth stack
     * restart — and in that case the honest answer is "this window cannot be
     * measured", not "nothing happened in it".
     */
    private fun increase(before: Long?, now: Long?): Long? {
        if (before == null || now == null) return null
        return (now - before).takeIf { it >= 0 }
    }

    private fun txDelta(
        previous: LinkLiveSnapshot?,
        current: A2dpTxStats?,
        nowMs: Long,
    ): A2dpTxDelta? {
        val before = previous?.tx ?: return null
        val now = current ?: return null
        val window = nowMs - previous.timestampMs
        if (window <= 0) return null
        // A single counter going backwards means the whole block was reset, so
        // no field of this window is comparable.
        val enqueued = increase(before.enqueueCount, now.enqueueCount) ?: return null
        return A2dpTxDelta(
            windowMs = window,
            enqueued = enqueued,
            dropped = increase(before.droppedCount, now.droppedCount) ?: 0,
            dropouts = increase(before.dropoutCount, now.dropoutCount) ?: 0,
            flushed = increase(before.flushedCount, now.flushedCount) ?: 0,
            underflows = increase(before.underflowCount, now.underflowCount) ?: 0,
            underflowBytes = increase(before.underflowBytes, now.underflowBytes) ?: 0,
            framesEncoded = increase(before.framesPerPacketTotal, now.framesPerPacketTotal) ?: 0,
        )
    }

    /**
     * What changed between two readings.
     *
     * Only differences produce events. Nothing here fires on a steady state,
     * so an idle link writes nothing to the timeline no matter how long it is
     * watched.
     */
    fun eventsBetween(previous: LinkLiveSnapshot, current: LinkLiveSnapshot): List<LinkEvent> {
        val events = mutableListOf<LinkEvent>()
        val at = current.timestampMs

        val wasConnected = previous.device?.isConnected == true
        val isConnected = current.device?.isConnected == true
        if (wasConnected != isConnected) {
            events += LinkEvent.ConnectionChanged(
                timestampMs = at,
                isConnected = isConnected,
                detail = if (isConnected) "Device connected" else "Device disconnected",
            )
        }

        val wasPlaying = previous.device?.isPlaying == true
        val isPlaying = current.device?.isPlaying == true
        if (wasPlaying != isPlaying) {
            events += LinkEvent.PlaybackChanged(
                timestampMs = at,
                isPlaying = isPlaying,
                detail = if (isPlaying) "A2DP stream started" else "A2DP stream stopped",
            )
        }

        val fromCodec = previous.codec?.family
        val toCodec = current.codec?.family
        if (toCodec != null && fromCodec != null && toCodec != fromCodec) {
            events += LinkEvent.CodecChanged(
                timestampMs = at,
                from = fromCodec,
                to = toCodec,
                detail = "Codec changed from ${fromCodec.displayName} to ${toCodec.displayName}",
            )
        }

        val fromMode = previous.ldac?.mode
        val toLdac = current.ldac
        if (toLdac != null && fromMode != null && toLdac.mode != fromMode) {
            events += LinkEvent.LdacModeChanged(
                timestampMs = at,
                from = fromMode,
                to = toLdac.mode,
                nominalKbps = toLdac.nominalKbps,
                detail = buildString {
                    append("LDAC quality mode changed from ${fromMode.label} to ${toLdac.mode.label}")
                    toLdac.nominalKbps?.let { append(" (nominal $it kbps)") }
                },
            )
        }

        lossEvent(current)?.let(events::add)
        return events
    }

    private fun lossEvent(current: LinkLiveSnapshot): LinkEvent.LossDetected? {
        if (!current.hasLossThisWindow) return null
        val tx = current.txDelta
        val inputUnderruns = current.inputUnderrunDelta
        val mixerUnderruns = (current.mixer?.fastMixerUnderrunDelta ?: 0L) +
            (current.mixer?.normalMixerEmptyDelta ?: 0L)
        val parts = buildList {
            if (inputUnderruns > 0) add("$inputUnderruns app underrun(s)")
            if (mixerUnderruns > 0) add("$mixerUnderruns mixer underrun(s)")
            tx?.dropped?.takeIf { it > 0 }?.let { add("$it dropped packet(s)") }
            tx?.dropouts?.takeIf { it > 0 }?.let { add("$it stack dropout(s)") }
            tx?.underflows?.takeIf { it > 0 }?.let { add("$it encoder underflow(s)") }
        }
        return LinkEvent.LossDetected(
            timestampMs = current.timestampMs,
            windowMs = tx?.windowMs ?: 0L,
            inputUnderruns = inputUnderruns,
            mixerUnderruns = mixerUnderruns,
            txDropped = tx?.dropped ?: 0L,
            txDropouts = tx?.dropouts ?: 0L,
            txUnderflows = tx?.underflows ?: 0L,
            detail = "Audio loss: " + parts.joinToString(", "),
        )
    }

    companion object {
        /**
         * Fast enough that a dropout lands in a window the user can still
         * connect to what they heard, slow enough that three `dumpsys` execs
         * per pass do not become the most expensive thing on the phone.
         */
        const val DEFAULT_INTERVAL_MS = 2_000L

        /** A floor, so a slow device cannot turn the loop into a busy spin. */
        const val MIN_INTERVAL_MS = 250L
    }
}
