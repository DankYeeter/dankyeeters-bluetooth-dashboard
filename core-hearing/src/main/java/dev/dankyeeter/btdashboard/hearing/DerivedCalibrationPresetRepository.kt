package dev.dankyeeter.btdashboard.hearing

/**
 * The bundled presets, plus whatever the user has derived for their own
 * headphones.
 *
 * A decorator rather than an addition to [BundledCalibrationPresets], because
 * the two lists have nothing in common but their type. The bundled file is a
 * fixed, reviewed table of shapes read off published charts; these entries
 * appear and disappear as the user derives and discards them, and they are
 * measurements of one person's ears. Merging the mutable set into the constant
 * one would make the constant one no longer constant, and every honesty rule in
 * [BundledCalibrationPresets] is written on the assumption that it is.
 *
 * ## Why the snapshot, and not a suspend function
 *
 * [CalibrationPresetRepository.byId] is synchronous and is called from the
 * middle of the compensation math, which has no coroutine to suspend in. The
 * derivations live in a DataStore, which only has a Flow. So the collection
 * happens once, at the edge (see `HearingGraph.init`), and lands here in a
 * volatile field. A lookup that arrives before the first emission sees only the
 * bundled presets — which is the correct answer at that moment, not a stale
 * one: nothing derived is in force until it has been read.
 */
class DerivedCalibrationPresetRepository(
    private val bundled: CalibrationPresetRepository,
) : CalibrationPresetRepository {

    @Volatile
    private var derived: List<CalibrationPreset> = emptyList()

    /**
     * Replaces the derived half of the list. Idempotent, so the collector can
     * call it on every emission without checking whether anything changed.
     */
    fun setDerived(calibrations: List<DerivedCalibration>) {
        derived = calibrations.map { it.toPreset() }
    }

    /**
     * Bundled first, derived after.
     *
     * The order is what a picker shows, and "Uncalibrated generic" has to stay
     * at the top of that list where it has always been. The derived entries name
     * their device, so they are findable wherever they sit.
     */
    override fun all(): List<CalibrationPreset> = bundled.all() + derived

    /**
     * Bundled ids win.
     *
     * They cannot collide today — [DerivedCalibration.ID_PREFIX] is not a prefix
     * any bundled id uses — and the order is fixed anyway so that a future
     * bundled preset can never be shadowed by a stored record from an older
     * build. A derived id that no longer resolves comes back null, and callers
     * already treat that the way they treat any unknown preset: they fall back
     * to generic rather than crash.
     */
    override fun byId(id: String): CalibrationPreset? =
        bundled.byId(id) ?: derived.firstOrNull { it.id == id }
}
