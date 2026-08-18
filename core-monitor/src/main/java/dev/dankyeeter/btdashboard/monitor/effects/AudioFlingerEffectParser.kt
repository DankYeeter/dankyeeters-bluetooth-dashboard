package dev.dankyeeter.btdashboard.monitor.effects

/** One effect instance inside a chain. */
data class AudioEffect(
    val id: Int?,
    val name: String,
    val uuid: String? = null,
    val implementor: String? = null,
    val enabled: Boolean = false,
    val clientPids: List<Int> = emptyList(),
)

/** All effects attached to one audio session. */
data class EffectChain(
    val sessionId: Int,
    val effects: List<AudioEffect> = emptyList(),
)

data class EffectDumpSnapshot(
    val chains: List<EffectChain> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Parser for `dumpsys media.audio_flinger` effect sections.
 *
 * Same rules as the Bluetooth dump parser: tolerant line scanner, unknown lines
 * ignored, never throws. AudioFlinger's dump format has changed shape several
 * times (tab-indented tables, then key/value blocks), so both the
 * `Session Status State` table and plain `Session: 145` lines are accepted.
 */
object AudioFlingerEffectParser {

    private val SESSION_HEADER = Regex("""[Ee]ffects?\s+session\s+(\d+)""")
    private val CHAIN_HEADER = Regex("""[Ee]ffect chain session\s+(\d+)""")
    private val EFFECT_ID = Regex("""Effect ID\s+(\d+)""")
    private val NAME = Regex("""-?\s*name:\s*(.+?)\s*$""")
    private val UUID = Regex("""-?\s*UUID:\s*([0-9A-Fa-f\-]{8,})""")
    private val IMPLEMENTOR = Regex("""-?\s*implementor:\s*(.+?)\s*$""")
    private val SESSION_KV = Regex("""Session:\s*(\d+)""")
    private val PID_ROW = Regex("""^\s*(\d{2,7})\s+\d+\s+([yn])\s*$""")
    private val STATUS_ROW = Regex("""^\s*(\d+)\s+\d+\s+\d+\s+([yn])\s+([yn])\s+([yn])\s*$""")

    fun parse(dump: String): EffectDumpSnapshot = try {
        parseInternal(dump)
    } catch (t: Throwable) {
        EffectDumpSnapshot(warnings = listOf("audio_flinger parse failed: ${t.javaClass.simpleName}"))
    }

    private fun parseInternal(dump: String): EffectDumpSnapshot {
        if (dump.isBlank()) return EffectDumpSnapshot(warnings = listOf("empty dump"))

        val chains = LinkedHashMap<Int, MutableList<AudioEffect>>()
        val warnings = mutableListOf<String>()
        var session: Int? = null
        var current: AudioEffect? = null
        var inClientTable = false

        fun flush() {
            val effect = current ?: return
            val s = session
            if (s != null && effect.name.isNotBlank()) {
                chains.getOrPut(s) { mutableListOf() }.add(effect)
            }
            current = null
        }

        for (rawLine in dump.lineSequence()) {
            val line = rawLine.replace('\t', ' ').trimEnd()
            if (line.isBlank()) continue

            (SESSION_HEADER.find(line) ?: CHAIN_HEADER.find(line))?.let { m ->
                flush()
                session = m.groupValues[1].toIntOrNull()
                inClientTable = false
            }

            EFFECT_ID.find(line)?.let { m ->
                flush()
                current = AudioEffect(id = m.groupValues[1].toIntOrNull(), name = "")
                inClientTable = false
            }

            SESSION_KV.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { session = it }

            NAME.find(line)?.groupValues?.getOrNull(1)?.let { n ->
                current = (current ?: AudioEffect(null, "")).copy(name = n)
            }
            UUID.find(line)?.groupValues?.getOrNull(1)?.let { u ->
                current = (current ?: AudioEffect(null, "")).copy(uuid = u)
            }
            IMPLEMENTOR.find(line)?.groupValues?.getOrNull(1)?.let { i ->
                current = (current ?: AudioEffect(null, "")).copy(implementor = i)
            }

            STATUS_ROW.find(line)?.let { m ->
                // "Session Status State Registered Enabled Suspended" value row.
                m.groupValues[1].toIntOrNull()?.let { session = it }
                current = (current ?: AudioEffect(null, ""))
                    .copy(enabled = m.groupValues[3] == "y")
            }

            if (line.contains("Clients", true) || line.contains("Pid   Priority", true) ||
                Regex("""^\s*Pid\b""").containsMatchIn(line)
            ) {
                inClientTable = true
                continue
            }
            if (inClientTable) {
                val pid = PID_ROW.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""^\s*(\d{2,7})\s""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (pid != null) {
                    current = (current ?: AudioEffect(null, ""))
                        .let { it.copy(clientPids = it.clientPids + pid) }
                } else {
                    inClientTable = false
                }
            }
        }
        flush()

        if (chains.isEmpty()) warnings += "no effect chains found in dump"
        return EffectDumpSnapshot(
            chains = chains.map { (s, e) -> EffectChain(s, e) },
            warnings = warnings,
        )
    }
}
