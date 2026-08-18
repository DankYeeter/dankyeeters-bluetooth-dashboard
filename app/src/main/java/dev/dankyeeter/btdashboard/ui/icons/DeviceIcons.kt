package dev.dankyeeter.btdashboard.ui.icons

import androidx.annotation.DrawableRes
import dev.dankyeeter.btdashboard.R

/**
 * Maps a calibration preset id to its line-art device icon.
 *
 * The drawables are our own minimalist illustrations — PLAN.md rules out
 * manufacturer press images, whose editorial licences do not cover software
 * redistribution. Each one is a single stroke colour so the theme tints it
 * (gold on the Edgy black background) instead of shipping per-theme copies.
 *
 * Unknown ids fall back to the generic silhouette; an icon is never a reason
 * for a screen to fail.
 */
object DeviceIcons {

    @DrawableRes
    fun forPresetId(presetId: String?): Int = when (presetId) {
        "focal_bathys" -> R.drawable.ic_device_bathys
        "noble_encore" -> R.drawable.ic_device_encore
        "sennheiser_momentum4" -> R.drawable.ic_device_momentum4
        "airpods_pro_3" -> R.drawable.ic_device_airpods_pro_3
        "airpods_pro_2" -> R.drawable.ic_device_airpods_pro_2
        "airpods_4_anc" -> R.drawable.ic_device_airpods_4_anc
        "airpods_4" -> R.drawable.ic_device_airpods_4
        "airpods_3" -> R.drawable.ic_device_airpods_3
        "airpods_2" -> R.drawable.ic_device_airpods_2
        else -> R.drawable.ic_device_generic
    }
}
