package dev.dankyeeter.btdashboard.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Material icons the app shows, carried as path data instead of pulling in
 * the whole `material-icons-extended` artifact for ten glyphs.
 *
 * Path data copied unchanged from `material-icons-extended` 1.7.6 (Google
 * Material Icons, Apache License 2.0 - see THIRD_PARTY_LICENSES.md).
 */
object AppIcons {
    val Bluetooth: ImageVector = materialIcon(
        "Filled.Bluetooth",
        "M17.71,7.71L12,2h-1v7.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L11,14.41L11,22h1l5.71,-5.71l-4.3,-4.29l4.3,-4.29ZM13,5.83l1.88,1.88L13,9.59L13,5.83ZM14.88,16.29L13,18.17v-3.76l1.88,1.88Z",
    )

    val Equalizer: ImageVector = materialIcon(
        "Filled.Equalizer",
        "M10,20h4L14,4h-4v16ZM4,20h4v-8L4,12v8ZM16,9v11h4L20,9h-4Z",
    )

    val ExpandLess: ImageVector = materialIcon(
        "Filled.ExpandLess",
        "M12,8l-6,6l1.41,1.41L12,10.83l4.59,4.58L18,14Z",
    )

    val ExpandMore: ImageVector = materialIcon(
        "Filled.ExpandMore",
        "M16.59,8.59L12,13.17L7.41,8.59L6,10l6,6l6,-6Z",
    )

    val GraphicEq: ImageVector = materialIcon(
        "Filled.GraphicEq",
        "M7,18h2L9,6L7,6v12ZM11,22h2L13,2h-2v20ZM3,14h2v-4L3,10v4ZM15,18h2L17,6h-2v12ZM19,10v4h2v-4h-2Z",
    )

    val Headphones: ImageVector = materialIcon(
        "Filled.Headphones",
        "M12,3c-4.97,0,-9,4.03,-9,9v7c0,1.1,0.9,2,2,2h4v-8H5v-1c0,-3.87,3.13,-7,7,-7s7,3.13,7,7v1h-4v8h4c1.1,0,2,-0.9,2,-2v-7C21,7.03,16.97,3,12,3Z",
    )

    val Hearing: ImageVector = materialIcon(
        "Filled.Hearing",
        "M17,20c-0.29,0,-0.56,-0.06,-0.76,-0.15c-0.71,-0.37,-1.21,-0.88,-1.71,-2.38c-0.51,-1.56,-1.47,-2.29,-2.39,-3c-0.79,-0.61,-1.61,-1.24,-2.32,-2.53C9.29,10.98,9,9.93,9,9c0,-2.8,2.2,-5,5,-5s5,2.2,5,5h2c0,-3.93,-3.07,-7,-7,-7S7,5.07,7,9c0,1.26,0.38,2.65,1.07,3.9c0.91,1.65,1.98,2.48,2.85,3.15c0.81,0.62,1.39,1.07,1.71,2.05c0.6,1.82,1.37,2.84,2.73,3.55c0.51,0.23,1.07,0.35,1.64,0.35c2.21,0,4,-1.79,4,-4h-2c0,1.1,-0.9,2,-2,2ZM7.64,2.64L6.22,1.22C4.23,3.21,3,5.96,3,9s1.23,5.79,3.22,7.78l1.41,-1.41C6.01,13.74,5,11.49,5,9s1.01,-4.74,2.64,-6.36ZM11.5,9c0,1.38,1.12,2.5,2.5,2.5s2.5,-1.12,2.5,-2.5s-1.12,-2.5,-2.5,-2.5s-2.5,1.12,-2.5,2.5Z",
    )

    val Insights: ImageVector = materialIcon(
        "Filled.Insights",
        "M21,8c-1.45,0,-2.26,1.44,-1.93,2.51l-3.55,3.56c-0.3,-0.09,-0.74,-0.09,-1.04,0l-2.55,-2.55C12.27,10.45,11.46,9,10,9c-1.45,0,-2.27,1.44,-1.93,2.52l-4.56,4.55C2.44,15.74,1,16.55,1,18c0,1.1,0.9,2,2,2c1.45,0,2.26,-1.44,1.93,-2.51l4.55,-4.56c0.3,0.09,0.74,0.09,1.04,0l2.55,2.55C12.73,16.55,13.54,18,15,18c1.45,0,2.27,-1.44,1.93,-2.52l3.56,-3.55C21.56,12.26,23,11.45,23,10C23,8.9,22.1,8,21,8Z",
        "M15,9l0.94,-2.07l2.06,-0.93l-2.06,-0.93l-0.94,-2.07l-0.92,2.07l-2.08,0.93l2.08,0.93Z",
        "M3.5,11l0.5,-2l2,-0.5l-2,-0.5l-0.5,-2l-0.5,2l-2,0.5l2,0.5Z",
    )

    val Settings: ImageVector = materialIcon(
        "Filled.Settings",
        "M19.14,12.94c0.04,-0.3,0.06,-0.61,0.06,-0.94c0,-0.32,-0.02,-0.64,-0.07,-0.94l2.03,-1.58c0.18,-0.14,0.23,-0.41,0.12,-0.61l-1.92,-3.32c-0.12,-0.22,-0.37,-0.29,-0.59,-0.22l-2.39,0.96c-0.5,-0.38,-1.03,-0.7,-1.62,-0.94L14.4,2.81c-0.04,-0.24,-0.24,-0.41,-0.48,-0.41h-3.84c-0.24,0,-0.43,0.17,-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22,-0.08,-0.47,0,-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,11.69,4.8,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14,-0.23,0.41,-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39,-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44,-0.17,0.47,-0.41l0.36,-2.54c0.59,-0.24,1.13,-0.56,1.62,-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59,-0.22l1.92,-3.32c0.12,-0.22,0.07,-0.47,-0.12,-0.61L19.14,12.94ZM12,15.6c-1.98,0,-3.6,-1.62,-3.6,-3.6s1.62,-3.6,3.6,-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6Z",
    )

    val HelpOutline: ImageVector = materialIcon(
        "AutoMirrored.Outlined.HelpOutline",
        "M11,18h2v-2h-2v2ZM12,2C6.48,2,2,6.48,2,12s4.48,10,10,10s10,-4.48,10,-10S17.52,2,12,2ZM12,20c-4.41,0,-8,-3.59,-8,-8s3.59,-8,8,-8s8,3.59,8,8s-3.59,8,-8,8ZM12,6c-2.21,0,-4,1.79,-4,4h2c0,-1.1,0.9,-2,2,-2s2,0.9,2,2c0,2,-3,1.75,-3,5h2c0,-2.25,3,-2.5,3,-5c0,-2.21,-1.79,-4,-4,-4Z",
        autoMirror = true,
    )
}

/** Same frame as the library's icons: 24 dp on a 24-unit viewport, black fill for tinting. */
private fun materialIcon(name: String, vararg paths: String, autoMirror: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply {
        paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
    }.build()
