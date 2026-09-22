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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialisation for [PreferenceProfile], on `org.json` like the rest of the
 * store package. This record is a dozen listening sessions somebody sat
 * through, and a silent encoding bug would cost all of them, so its round trip
 * is tested under Robolectric against Android's own `org.json` (AD-026).
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

    fun encode(profiles: List<PreferenceProfile>): String =
        JSONArray(profiles.map(::profileToJson)).toString()

    fun parse(raw: String?): List<PreferenceProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return array.objects().mapNotNull(::toProfile)
    }

    // ---- writing -------------------------------------------------------------

    private fun profileToJson(profile: PreferenceProfile): JSONObject = JSONObject()
        .put("deviceKey", profile.deviceKey)
        .put("deviceName", profile.deviceName ?: JSONObject.NULL)
        .put("layout", profile.layout.id)
        .put("baseLeft", JSONArray(profile.baseLeftDb.map(::finiteOrZero)))
        .put("baseRight", JSONArray(profile.baseRightDb.map(::finiteOrZero)))
        .put("manualBassDb", profile.manualBassDb?.let(::finiteOrZero) ?: JSONObject.NULL)
        .put("manualTrebleDb", profile.manualTrebleDb?.let(::finiteOrZero) ?: JSONObject.NULL)
        .put("finalCheck", profile.finalCheck.name)
        .put("createdAtMillis", profile.createdAtMillis)
        .put("updatedAtMillis", profile.updatedAtMillis)
        .put("runs", JSONArray(profile.runs.map(::runToJson)))

    private fun runToJson(run: PreferenceRun): JSONObject = JSONObject()
        .put("id", run.id)
        .put("label", run.label)
        .put("labelSource", run.labelSource.name)
        .put("createdAtMillis", run.createdAtMillis)
        .put("bassDb", finiteOrZero(run.candidate.bassDb))
        .put("trebleDb", finiteOrZero(run.candidate.trebleDb))
        .put("consistency", finiteOrZero(run.consistency))
        .put("trials", JSONArray(run.trials.map(::trialToJson)))

    private fun trialToJson(trial: PreferenceTrial): JSONObject = JSONObject()
        .put("i", trial.index)
        .put("p", trial.phase.name)
        .put("x", trial.axis.name)
        .put("ab", finiteOrZero(trial.a.bassDb))
        .put("at", finiteOrZero(trial.a.trebleDb))
        .put("bb", finiteOrZero(trial.b.bassDb))
        .put("bt", finiteOrZero(trial.b.trebleDb))
        .put("c", trial.choice.name)
        .put("r", trial.repeat)

    /**
     * JSON has no NaN and no infinity, and `org.json` throws on them. They
     * become 0.0 rather than costing the whole record.
     */
    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0

    private fun finiteOrZero(value: Float): Double = finiteOrZero(value.toDouble())

    // ---- reading -------------------------------------------------------------

    private fun toProfile(obj: JSONObject): PreferenceProfile? {
        val deviceKey = (obj.opt("deviceKey") as? String)?.takeIf { it.isNotBlank() } ?: return null
        val layout = EqBandLayout.fromId(obj.opt("layout") as? String)
        return PreferenceProfile(
            deviceKey = deviceKey,
            deviceName = obj.opt("deviceName") as? String,
            runs = obj.optJSONArray("runs")?.objects().orEmpty().mapNotNull(::toRun),
            layout = layout,
            baseLeftDb = obj.optJSONArray("baseLeft").toGains(layout),
            baseRightDb = obj.optJSONArray("baseRight").toGains(layout),
            manualBassDb = obj.number("manualBassDb")?.toFloat(),
            manualTrebleDb = obj.number("manualTrebleDb")?.toFloat(),
            finalCheck = enumOr(obj.opt("finalCheck"), FinalCheck.NOT_RUN),
            createdAtMillis = obj.number("createdAtMillis")?.toLong() ?: 0L,
            updatedAtMillis = obj.number("updatedAtMillis")?.toLong() ?: 0L,
        )
    }

    private fun toRun(obj: JSONObject): PreferenceRun? {
        val id = (obj.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return null
        return PreferenceRun(
            id = id,
            label = obj.opt("label") as? String ?: "",
            labelSource = enumOr(obj.opt("labelSource"), PreferenceLabelSource.NONE),
            createdAtMillis = obj.number("createdAtMillis")?.toLong() ?: 0L,
            candidate = PreferenceCandidate(
                bassDb = obj.number("bassDb")?.toFloat() ?: 0f,
                trebleDb = obj.number("trebleDb")?.toFloat() ?: 0f,
            ).clamped(),
            consistency = obj.number("consistency")?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.0,
            trials = obj.optJSONArray("trials")?.objects().orEmpty().mapNotNull(::toTrial),
        )
    }

    private fun toTrial(obj: JSONObject): PreferenceTrial? {
        val choice = enumOrNull<PreferenceChoice>(obj.opt("c")) ?: return null
        return PreferenceTrial(
            index = obj.number("i")?.toInt() ?: 0,
            phase = enumOr(obj.opt("p"), TrialPhase.LEAD_IN),
            axis = enumOr(obj.opt("x"), PreferenceAxis.BASS),
            a = PreferenceCandidate(
                obj.number("ab")?.toFloat() ?: 0f,
                obj.number("at")?.toFloat() ?: 0f,
            ),
            b = PreferenceCandidate(
                obj.number("bb")?.toFloat() ?: 0f,
                obj.number("bt")?.toFloat() ?: 0f,
            ),
            choice = choice,
            repeat = obj.opt("r") as? Boolean ?: false,
        )
    }

    /** A number, or null for anything else — a numeric string included. */
    private fun JSONObject.number(key: String): Number? = opt(key) as? Number

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    /**
     * A stored base curve at the resolution it was saved at.
     *
     * Same rule as `EqSettingsStore.parseGains`: a list whose length belongs to
     * a *different* layout is the same curve at another resolution and is
     * resampled; a length that matches no layout is not a curve and degrades to
     * flat, which is the only value that cannot invent a correction.
     */
    private fun JSONArray?.toGains(layout: EqBandLayout): List<Float> {
        val array = this ?: JSONArray()
        val parsed = (0 until array.length()).mapNotNull { (array.opt(it) as? Number)?.toFloat() }
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
