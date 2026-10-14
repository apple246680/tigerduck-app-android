package org.ntust.app.tigerduck.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * What a [VisualPreset] means for each surface that draws differently under
 * it. Views read these values instead of branching on the preset, so a new
 * style is a new case here rather than an `if` in every view.
 *
 * Only the class table differs today; every other screen looks the same
 * under both presets.
 */
@Immutable
data class VisualStylePolicy(val preset: VisualPreset) {

    enum class ClassTableGridStyle {
        /**
         * A course's run of periods is one block, the period column shows each
         * period's start and end, and an empty slot is a faint tile.
         */
        Blocks,

        /**
         * Each period of a run is a tile of its own, the period column shows
         * only the period, and rows alternate background bands while an empty
         * slot stays bare.
         */
        PeriodTiles,
    }

    val classTableGridStyle: ClassTableGridStyle
        get() = when (preset) {
            VisualPreset.TigerDuck -> ClassTableGridStyle.Blocks
            VisualPreset.Tat -> ClassTableGridStyle.PeriodTiles
        }

    /** Whether the period column shows each period's start and end beside it. */
    val classTableShowsPeriodTimes: Boolean
        get() = classTableGridStyle == ClassTableGridStyle.Blocks

    /**
     * A course's fill on the class table, conflict clusters included.
     *
     * [courseColor] is the course's own colour, the light palette hex that is
     * stored and synced; [themeColor] is that colour as the current theme draws
     * it, its dark variant in dark mode.
     */
    fun classTableCourseFill(courseColor: Color, themeColor: Color, isDark: Boolean): Color =
        when (classTableGridStyle) {
            ClassTableGridStyle.Blocks -> if (isDark) themeColor else courseColor.copy(alpha = 0.5f)
            ClassTableGridStyle.PeriodTiles -> courseColor.pastel()
        }

    /** A course's name on [classTableCourseFill]. */
    fun classTableCourseTextColor(isDark: Boolean): Color = when (classTableGridStyle) {
        ClassTableGridStyle.Blocks -> if (isDark) Color.White else Color(0xFF1C1C1E)
        ClassTableGridStyle.PeriodTiles -> TextOnPastel
    }

    /**
     * The room hint and the corner marks on [classTableCourseFill]. On a pastel
     * it stays at 80%: at 70% it fell under 4.5:1 on the pastel of a dark
     * picked colour.
     */
    fun classTableCourseSecondaryTextColor(isDark: Boolean): Color = when (classTableGridStyle) {
        ClassTableGridStyle.Blocks -> classTableCourseTextColor(isDark).copy(alpha = 0.7f)
        ClassTableGridStyle.PeriodTiles -> TextOnPastel.copy(alpha = 0.8f)
    }

    /** A slot no course takes, given the theme's `surfaceVariant`. */
    fun classTableEmptySlotColor(surfaceVariant: Color): Color = when (classTableGridStyle) {
        ClassTableGridStyle.Blocks -> surfaceVariant.copy(alpha = 0.3f)
        ClassTableGridStyle.PeriodTiles -> Color.Transparent
    }

    /**
     * The band behind the period row at [rowIndex], from the top, given the
     * theme's `onSurface`; null where rows have no bands.
     */
    fun classTableRowBand(rowIndex: Int, onSurface: Color): Color? = when (classTableGridStyle) {
        ClassTableGridStyle.Blocks -> null
        ClassTableGridStyle.PeriodTiles -> onSurface.copy(alpha = if (rowIndex % 2 == 0) 0.08f else 0.04f)
    }

    companion object {
        /** Text on a [pastel] fill, which stays light in either theme. */
        val TextOnPastel = Color(0xFF1A1A1A)
    }
}

/**
 * This colour mixed with white into an opaque pastel. Each sRGB channel moves
 * 65% of the way to white, which keeps the hue, so a course still shows the
 * colour it was given, and lifts every channel to at least 65%, where
 * [VisualStylePolicy.TextOnPastel] reads at better than 7:1.
 */
fun Color.pastel(): Color = Color(
    red = red + (1f - red) * PASTEL_WHITE_SHARE,
    green = green + (1f - green) * PASTEL_WHITE_SHARE,
    blue = blue + (1f - blue) * PASTEL_WHITE_SHARE,
    alpha = 1f,
)

private const val PASTEL_WHITE_SHARE = 0.65f

/**
 * The interface style in effect, provided once at the root of the app from
 * the Settings preference.
 */
val LocalVisualStylePolicy = staticCompositionLocalOf { VisualStylePolicy(VisualPreset.Default) }
