package dev.dankyeeter.btdashboard.transfer

import dev.dankyeeter.btdashboard.audio.eq.EqBandLayout
import kotlinx.serialization.json.Json

/**
 * Encodes and decodes [BackupDocument] JSON, and validates what came back.
 *
 * Pure Kotlin — no Android types — so the round trip is fully unit tested.
 *
 * Import philosophy: a backup written by *this* app on another phone must load
 * exactly; anything else is treated as hostile input. Structural problems fail
 * the whole import with a readable reason, while individual unusable records
 * (a run with no id, a curve with the wrong number of bands) are dropped with a
 * warning. Silently importing a half-valid hearing profile would be worse than
 * refusing it, because the numbers end up driving an EQ the user then listens
 * to for weeks.
 */
object BackupCodec {

    private val json = Json {
        prettyPrint = true
        // Forward compatibility: a file written by a newer build may carry
        // fields we do not know yet. We still refuse newer *schema versions*;
        // this only keeps additive same-version changes readable.
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(document: BackupDocument): String =
        json.encodeToString(BackupDocument.serializer(), document)

    /**
     * Parses and validates a backup file.
     *
     * @return [BackupParseResult.Success] with the sanitised document plus any
     *   non-fatal warnings, or [BackupParseResult.Failure] with a message meant
     *   to be shown to the user verbatim.
     */
    fun decode(raw: String): BackupParseResult {
        if (raw.isBlank()) return BackupParseResult.Failure("The file is empty.")

        val parsed = try {
            json.decodeFromString(BackupDocument.serializer(), raw)
        } catch (e: Exception) {
            return BackupParseResult.Failure(
                "This is not a readable backup file (${e.javaClass.simpleName}).",
            )
        }

        if (parsed.format != BackupSchema.FORMAT_ID) {
            return BackupParseResult.Failure(
                "This JSON file was not written by this app.",
            )
        }
        if (parsed.schemaVersion <= 0) {
            return BackupParseResult.Failure("The backup has no usable schema version.")
        }
        if (parsed.schemaVersion > BackupSchema.CURRENT_VERSION) {
            return BackupParseResult.Failure(
                "This backup uses schema version ${parsed.schemaVersion}, but this " +
                    "build only understands up to ${BackupSchema.CURRENT_VERSION}. " +
                    "Update the app on this phone first.",
            )
        }

        val warnings = mutableListOf<String>()

        val runs = parsed.hearingRuns.filter { run ->
            when {
                run.id.isBlank() -> {
                    warnings += "Skipped a hearing run without an id."
                    false
                }
                run.left.isEmpty() && run.right.isEmpty() -> {
                    warnings += "Skipped hearing run ${run.id}: no thresholds in it."
                    false
                }
                else -> true
            }
        }

        val profiles = parsed.profiles.filter { profile ->
            when {
                profile.id.isBlank() -> {
                    warnings += "Skipped a profile without an id."
                    false
                }
                !profile.eq.hasUsableBands() -> {
                    warnings += "Skipped profile \"${profile.name}\": its EQ curve is malformed."
                    false
                }
                else -> true
            }
        }

        val eq = parsed.eq?.takeIf { candidate ->
            candidate.hasUsableBands().also {
                if (!it) warnings += "The stored EQ curve was malformed and was ignored."
            }
        }

        // An active profile id pointing at a profile that did not survive
        // validation would leave the app claiming an active profile it has not
        // got. Drop it rather than import a dangling reference.
        val activeProfileId = parsed.activeProfileId?.takeIf { id ->
            profiles.any { it.id == id }.also {
                if (!it) warnings += "The active profile is not part of this backup; ignoring it."
            }
        }

        if (runs.isEmpty() && profiles.isEmpty() && eq == null) {
            return BackupParseResult.Failure("The backup contains nothing importable.")
        }

        return BackupParseResult.Success(
            document = parsed.copy(
                hearingRuns = runs,
                profiles = profiles,
                eq = eq,
                activeProfileId = activeProfileId,
            ),
            warnings = warnings,
        )
    }

    /**
     * Band lists must be complete for *some* layout; a partial curve cannot be
     * repaired safely.
     *
     * This used to demand exactly ten bands — written when ten was the only
     * layout the app had, with a comment saying a new layout would be "a
     * schema migration, not a silent mismatch". That migration has happened:
     * the file format carries a layout id now, and the Personal Reference has
     * never been ten bands. Keeping the old gate meant a valid 20-band curve
     * was thrown away here, one call before the mapper that knows how to read
     * it. The gate now asks the same question the mapper answers: is this a
     * band count any layout defines?
     */
    private fun BackupEq.hasUsableBands(): Boolean =
        EqBandLayout.entries.any { it.bandCount == leftGainsDb.size } &&
            EqBandLayout.entries.any { it.bandCount == rightGainsDb.size }
}

sealed interface BackupParseResult {
    data class Success(
        val document: BackupDocument,
        val warnings: List<String> = emptyList(),
    ) : BackupParseResult

    data class Failure(val message: String) : BackupParseResult
}
