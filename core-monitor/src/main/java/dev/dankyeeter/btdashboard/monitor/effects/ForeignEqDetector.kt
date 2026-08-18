package dev.dankyeeter.btdashboard.monitor.effects

/** A foreign EQ that is stacking on top of ours, with app attribution. */
data class ForeignEqWarning(
    val effectName: String,
    val sessionId: Int,
    val packageName: String?,
    val pid: Int?,
    val enabled: Boolean,
) {
    /** Ready-to-show sentence, e.g. "Equalizer active from Wavelet". */
    val message: String
        get() = buildString {
            append(effectName)
            append(" active")
            packageName?.let { append(" from ").append(it) }
            if (packageName == null && pid != null) append(" from PID ").append(pid)
        }
}

/**
 * Decides which effects in a dump are foreign equalizers.
 *
 * Two `DynamicsProcessing` instances on one session fight each other, and a
 * third-party EQ (Wavelet, an OEM "sound enhancement") silently undoes our
 * compensation curve — which looks like a bug in our app. Detecting it is
 * pure list work, so it is fully unit-tested.
 */
object ForeignEqDetector {

    /** Effect names that equalize. Matched case-insensitively as substrings. */
    private val EQ_EFFECT_NAMES = listOf(
        "equalizer",
        "dynamicsprocessing",
        "dynamics processing",
        "bassboost",
        "bass boost",
        "loudnessenhancer",
        "loudness enhancer",
        "virtualizer",
    )

    /** Known third-party EQ packages, used only to word the warning better. */
    val knownEqPackages: Map<String, String> = mapOf(
        "com.pittvandewitt.wavelet" to "Wavelet",
        "james.dsp" to "JamesDSP",
        "com.jamesdsp" to "JamesDSP",
        "com.h6ah4i.android.example.openslmediaplayer" to "OpenSL EQ",
    )

    fun isEqualizerEffect(name: String): Boolean {
        val n = name.lowercase()
        return EQ_EFFECT_NAMES.any { n.contains(it) }
    }

    /**
     * @param ownPid our process id, so our own DynamicsProcessing never warns.
     * @param pidToPackage resolved process map; missing entries degrade to
     *   a PID-only warning rather than being dropped.
     */
    fun detect(
        snapshot: EffectDumpSnapshot,
        ownPid: Int,
        pidToPackage: Map<Int, String> = emptyMap(),
        onlyEnabled: Boolean = true,
    ): List<ForeignEqWarning> = snapshot.chains.flatMap { chain ->
        chain.effects.mapNotNull { effect ->
            if (!isEqualizerEffect(effect.name)) return@mapNotNull null
            if (onlyEnabled && !effect.enabled) return@mapNotNull null
            val foreignPids = effect.clientPids.filter { it != ownPid }
            // No client info at all: attribute to the system, still worth showing.
            if (effect.clientPids.isNotEmpty() && foreignPids.isEmpty()) return@mapNotNull null
            val pid = foreignPids.firstOrNull()
            val pkg = pid?.let { pidToPackage[it] }
            ForeignEqWarning(
                effectName = effect.name,
                sessionId = chain.sessionId,
                packageName = pkg?.let { knownEqPackages[it] ?: it },
                pid = pid,
                enabled = effect.enabled,
            )
        }
    }.distinctBy { it.effectName to it.sessionId to it.pid }
}

/**
 * Maps process ids to package names. On modern Android a normal app cannot
 * enumerate other processes, so the shell-backed `ps` implementation is the
 * only one that works — hence the interface.
 */
interface ProcessResolver {
    suspend fun pidToPackage(): Map<Int, String>
}

/** Parser for `ps -A -o PID,NAME` (and the wider default `ps -A` layout). */
object PsOutputParser {
    fun parse(output: String): Map<Int, String> = buildMap {
        for (line in output.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            // "PID NAME" or the default "USER PID PPID VSZ RSS WCHAN ADDR S NAME".
            val pid = parts[0].toIntOrNull() ?: parts.getOrNull(1)?.toIntOrNull() ?: continue
            val name = parts.last()
            if (name.equals("NAME", true) || name.equals("CMD", true)) continue
            if (name.startsWith("[")) continue
            put(pid, name)
        }
    }
}
