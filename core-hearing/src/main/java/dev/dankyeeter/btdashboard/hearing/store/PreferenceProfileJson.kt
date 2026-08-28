package dev.dankyeeter.btdashboard.hearing.store

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import dev.dankyeeter.btdashboard.hearing.preference.FinalCheck
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceAxis
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceCandidate
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceChoice
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceLabelSource
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceProfile
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceRun
import dev.dankyeeter.btdashboard.hearing.preference.PreferenceTrial
import dev.dankyeeter.btdashboard.hearing.preference.TrialPhase

/**
 * Serialisation for [PreferenceProfile], written on [MiniJson] rather than
 * `org.json` for the reason [DerivedCalibrationJson] gives: a codec built on
 * `android.jar`'s JSON cannot be round-trip tested on the host JVM, and this
 * record is a dozen listening sessions somebody sat through. A silent encoding
 * bug would cost all of them.
 *
 * Trial keys are single letters. A pool of ten runs is a hundred trials, and
 * this string lives in a DataStore preference that is rewritten every time a
 * song-run finishes; long keys would be several kilobytes of the word "phase".
 * They are decoded in exactly one place, immediately below.
 *
 * Every value degrades on its own: an unknown enum name falls back to the value
 * that assumes least, a base curve of the wrong length is resampled the way
 * `EqSettingsStore.parseGains` resamples one, and an entry that cannot name a
 * device is dropped rather than stored against nothing.
 */
internal object PreferenceProfileJson {

    fun encode(profiles: List<PreferenceProfile>): String = buildString {
        append('[')
        profiles.forEachIndexed { index, profile ->
            if (index > 0) append(',')
            appendProfile(profile)
        }
        append(']')
    }

    fun parse(raw: String?): List<PreferenceProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = MiniJson.parse(raw) as? List<*> ?: return emptyList()
        return array.mapNotNull { entry -> (entry as? Map<*, *>)?.let(::toProfile) }
    }

    // ---- writing -------------------------------------------------------------

    private fun StringBuilder.appendProfile(profile: PreferenceProfile) {
        append('{')
        append("\"deviceKey\":").append(MiniJson.quote(profile.deviceKey))
        append(",\"deviceName\":").append(profile.deviceName?.let(MiniJson::quote) ?: "null")
        append(",\"layout\":").append(MiniJson.quote(profile.layout.id))
        append(",\"baseLeft\":").appendFloats(profile.baseLeftDb)
        append(",\"baseRight\":").appendFloats(profile.baseRightDb)
        append(",\"manualBassDb\":").append(profile.manualBassDb?.let(MiniJson::number) ?: "null")
        append(",\"manualTrebleDb\":").append(profile.manualTrebleDb?.let(MiniJson::number) ?: "null")
        append(",\"finalCheck\":").append(MiniJson.quote(profile.finalCheck.name))
        append(",\"createdAtMillis\":").append(profile.createdAtMillis.toString())
        append(",\"updatedAtMillis\":").append(profile.updatedAtMillis.toString())
        append(",\"runs\":[")
        profile.runs.forEachIndexed { index, run ->
            if (index > 0) append(',')
            appendRun(run)
        }
        append("]}")
    }

    private fun StringBuilder.appendRun(run: PreferenceRun) {
        append('{')
        append("\"id\":").append(MiniJson.quote(run.id))
        append(",\"label\":").append(MiniJson.quote(run.label))
        append(",\"labelSource\":").append(MiniJson.quote(run.labelSource.name))
        append(",\"createdAtMillis\":").append(run.createdAtMillis.toString())
        append(",\"bassDb\":").append(MiniJson.number(run.candidate.bassDb))
        append(",\"trebleDb\":").append(MiniJson.number(run.candidate.trebleDb))
        append(",\"consistency\":").append(MiniJson.number(run.consistency))
        append(",\"trials\":[")
        run.trials.forEachIndexed { index, trial ->
            if (index > 0) append(',')
            appendTrial(trial)
        }
        append("]}")
    }

    private fun StringBuilder.appendTrial(trial: PreferenceTrial) {
        append('{')
        append("\"i\":").append(trial.index.toString())
        append(",\"p\":").append(MiniJson.quote(trial.phase.name))
        append(",\"x\":").append(MiniJson.quote(trial.axis.name))
        append(",\"ab\":").append(MiniJson.number(trial.a.bassDb))
        append(",\"at\":").append(MiniJson.number(trial.a.trebleDb))
        append(",\"bb\":").append(MiniJson.number(trial.b.bassDb))
        append(",\"bt\":").append(MiniJson.number(trial.b.trebleDb))
        append(",\"c\":").append(MiniJson.quote(trial.choice.name))
        append(",\"r\":").append(if (trial.repeat) "true" else "false")
        append('}')
    }

    private fun StringBuilder.appendFloats(values: List<Float>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(MiniJson.number(value))
        }
        append(']')
    }

    // ---- reading -------------------------------------------------------------

    private fun toProfile(obj: Map<*, *>): PreferenceProfile? {
        val deviceKey = (obj["deviceKey"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val layout = EqBandLayout.fromId(obj["layout"] as? String)
        return PreferenceProfile(
            deviceKey = deviceKey,
            deviceName = obj["deviceName"] as? String,
            runs = (obj["runs"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.let(::toRun) },
            layout = layout,
            baseLeftDb = (obj["baseLeft"] as? List<*>).toGains(layout),
            baseRightDb = (obj["baseRight"] as? List<*>).toGains(layout),
            manualBassDb = (obj["manualBassDb"] as? Double)?.toFloat(),
            manualTrebleDb = (obj["manualTrebleDb"] as? Double)?.toFloat(),
            finalCheck = enumOr(obj["finalCheck"], FinalCheck.NOT_RUN),
            createdAtMillis = (obj["createdAtMillis"] as? Double)?.toLong() ?: 0L,
            updatedAtMillis = (obj["updatedAtMillis"] as? Double)?.toLong() ?: 0L,
        )
    }

    private fun toRun(obj: Map<*, *>): PreferenceRun? {
        val id = (obj["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return PreferenceRun(
            id = id,
            label = obj["label"] as? String ?: "",
            labelSource = enumOr(obj["labelSource"], PreferenceLabelSource.NONE),
            createdAtMillis = (obj["createdAtMillis"] as? Double)?.toLong() ?: 0L,
            candidate = PreferenceCandidate(
                bassDb = (obj["bassDb"] as? Double)?.toFloat() ?: 0f,
                trebleDb = (obj["trebleDb"] as? Double)?.toFloat() ?: 0f,
            ).clamped(),
            consistency = (obj["consistency"] as? Double)?.coerceIn(0.0, 1.0) ?: 0.0,
            trials = (obj["trials"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.let(::toTrial) },
        )
    }

    private fun toTrial(obj: Map<*, *>): PreferenceTrial? {
        val choice = enumOrNull<PreferenceChoice>(obj["c"]) ?: return null
        return PreferenceTrial(
            index = (obj["i"] as? Double)?.toInt() ?: 0,
            phase = enumOr(obj["p"], TrialPhase.LEAD_IN),
            axis = enumOr(obj["x"], PreferenceAxis.BASS),
            a = PreferenceCandidate(
                (obj["ab"] as? Double)?.toFloat() ?: 0f,
                (obj["at"] as? Double)?.toFloat() ?: 0f,
            ),
            b = PreferenceCandidate(
                (obj["bb"] as? Double)?.toFloat() ?: 0f,
                (obj["bt"] as? Double)?.toFloat() ?: 0f,
            ),
            choice = choice,
            repeat = obj["r"] as? Boolean ?: false,
        )
    }

    /**
     * A stored base curve at the resolution it was saved at.
     *
     * Same rule as `EqSettingsStore.parseGains`: a list whose length belongs to
     * a *different* layout is the same curve at another resolution and is
     * resampled; a length that matches no layout is not a curve and degrades to
     * flat, which is the only value that cannot invent a correction.
     */
    private fun List<*>?.toGains(layout: EqBandLayout): List<Float> {
        val parsed = this.orEmpty().mapNotNull { (it as? Double)?.toFloat() }
        if (parsed.size == layout.bandCount) return parsed
        val source = EqBandLayout.entries.firstOrNull { it.bandCount == parsed.size }
            ?: return List(layout.bandCount) { 0f }
        return EqBandLayout.resample(parsed, source, layout)
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: Any?, fallback: T): T =
        enumOrNull<T>(raw) ?: fallback

    private inline fun <reified T : Enum<T>> enumOrNull(raw: Any?): T? {
        val name = raw as? String ?: return null
        return runCatching { enumValueOf<T>(name) }.getOrNull()
    }
}
