// Draws the class table's pieces under each interface style and reads the
// pixels back: TigerDuck keeps one block per course run, TAT gives each
// period a pastel tile of its own, drops the period times and bands the rows.

package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.TimetablePeriod
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.pastel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
// Real drawing, so the captured image holds actual pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClassTableVisualStyleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calculus = Course(courseNo = "CS1001", courseName = "Calculus")
    private val physics = Course(courseNo = "PH2002", courseName = "Physics")
    private val statistics =
        Course(courseNo = "MA3003", courseName = "Introduction to Probability and Statistics")

    // At density 1 a dp is a pixel, so the cell geometry reads straight off the image.
    private val width = 60
    private val cellHeight = 52

    @After
    fun resetTheme() {
        TigerDuckTheme.setDarkMode(false)
    }

    private fun draw(preset: VisualPreset, widthPx: Int, heightPx: Int, content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalVisualStylePolicy provides VisualStylePolicy(preset),
                LocalDensity provides Density(1f, 1f),
            ) {
                Box(Modifier.size(widthPx.dp, heightPx.dp).background(Color.White)) { content() }
            }
        }
    }

    private fun pixels(): PixelMap = composeRule.onRoot().captureToImage().toPixelMap()

    private fun soloCell(
        preset: VisualPreset,
        spanCount: Int,
        course: Course = calculus,
        hasAssignment: Boolean = false,
    ) = draw(preset, width, cellHeight * spanCount) { soloCellContent(course, spanCount, hasAssignment) }

    @Composable
    private fun soloCellContent(
        course: Course,
        spanCount: Int,
        hasAssignment: Boolean = false,
        cellWidth: Int = width,
    ) = SoloCourseCell(
        course = course,
        roomHint = null,
        spanCount = spanCount,
        dayColWidth = cellWidth.dp,
        cellHeight = cellHeight.dp,
        x = 0.dp,
        y = 0.dp,
        weekday = 1,
        hasAssignment = hasAssignment,
        assignmentsIgnored = false,
        onTap = {},
        onLongPress = {},
        onRename = {},
        onPickColor = {},
        onToggleIgnoreAssignments = null,
        onDelete = {},
    )

    private fun assertNear(message: String, expected: Color, actual: Color) {
        val near = abs(expected.red - actual.red) <= 2 / 255f &&
            abs(expected.green - actual.green) <= 2 / 255f &&
            abs(expected.blue - actual.blue) <= 2 / 255f
        assertTrue("$message: expected $expected, was $actual", near)
    }

    /** [colour] at [alpha] over white, as a translucent block shows on the test's white page. */
    private fun overWhite(colour: Color, alpha: Float) = Color(
        red = colour.red * alpha + (1 - alpha),
        green = colour.green * alpha + (1 - alpha),
        blue = colour.blue * alpha + (1 - alpha),
    )

    private val calculusColour get() = TigerDuckTheme.courseColorVibrant(calculus.courseNo)
    private val physicsColour get() = TigerDuckTheme.courseColorVibrant(physics.courseNo)

    // ---- a course's run ----

    @Test
    fun `TigerDuck draws a two-period run as one block across the row boundary`() {
        soloCell(VisualPreset.TigerDuck, spanCount = 2)
        val image = pixels()
        val block = overWhite(calculusColour, 0.5f)
        assertNear("first period", block, image[8, 8])
        // Beside the name, which is centred on the boundary.
        assertNear("row boundary", block, image[3, cellHeight])
        composeRule.onAllNodesWithText(calculus.courseName, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `TAT draws a pastel tile for each period with a gap between them`() {
        soloCell(VisualPreset.Tat, spanCount = 2)
        val image = pixels()
        val tile = calculusColour.pastel()
        assertNear("first tile", tile, image[8, 8])
        assertNear("second tile", tile, image[8, cellHeight + 8])
        assertNear("gap at the row boundary", Color.White, image[3, cellHeight])
        // Each tile names the course; the run stays one tap target and one TalkBack element.
        composeRule.onAllNodesWithText(calculus.courseName, useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun `TAT tiles keep the light pastel in dark mode`() {
        TigerDuckTheme.setDarkMode(true)
        soloCell(VisualPreset.Tat, spanCount = 1)
        assertNear("tile", calculusColour.pastel(), pixels()[8, 8])
    }

    // ---- conflicts ----

    private fun conflictCell(preset: VisualPreset) {
        val role = CellRole.ConflictStart(
            courseA = calculus, spanA = 2, offsetA = 0,
            courseB = physics, spanB = 2, offsetB = 0,
            combinedSpan = 2,
        )
        draw(preset, width, cellHeight * 2) {
            ConflictCourseCell(
                cellRole = role,
                dayColWidth = width.dp,
                cellHeight = cellHeight.dp,
                x = 0.dp,
                y = 0.dp,
                weekday = 1,
                periodId = "1",
                hasAssignmentA = false,
                hasAssignmentB = false,
                assignmentsIgnoredA = false,
                assignmentsIgnoredB = false,
                onPickConflict = { _, _, _, _ -> },
                onLongPress = {},
                onRename = {},
                onPickColor = {},
                onToggleIgnoreAssignments = null,
                onDelete = {},
            )
        }
    }

    @Test
    fun `a two-course conflict keeps its translucent course colours under TigerDuck`() {
        conflictCell(VisualPreset.TigerDuck)
        val image = pixels()
        // The first course is a Γ along the top and right edges, the second an
        // L along the left and bottom ones.
        assertNear("first course", overWhite(calculusColour, 0.5f), image[width - 5, 6])
        assertNear("second course", overWhite(physicsColour, 0.5f), image[4, cellHeight * 2 - 6])
    }

    @Test
    fun `a two-course conflict is recoloured to pastels under TAT`() {
        conflictCell(VisualPreset.Tat)
        val image = pixels()
        assertNear("first course", calculusColour.pastel(), image[width - 5, 6])
        assertNear("second course", physicsColour.pastel(), image[4, cellHeight * 2 - 6])
    }

    @Test
    fun `a conflict of three or more is recoloured to pastels under TAT`() {
        val role = CellRole.MultiConflictStart(
            members = listOf(
                CellRole.MultiConflictStart.Member(calculus, span = 2, offset = 0, lane = 0, firstPeriodId = "1"),
                CellRole.MultiConflictStart.Member(physics, span = 2, offset = 0, lane = 1, firstPeriodId = "1"),
            ),
            combinedSpan = 2,
            laneCount = 2,
        )
        draw(VisualPreset.Tat, width, cellHeight * 2) {
            MultiConflictCourseCell(
                cellRole = role,
                dayColWidth = width.dp,
                cellHeight = cellHeight.dp,
                x = 0.dp,
                y = 0.dp,
                weekday = 1,
                hasAssignment = { false },
                assignmentsIgnored = { false },
                onSelect = { _, _ -> },
                onLongPress = {},
                onRename = {},
                onPickColor = {},
                onToggleIgnoreAssignments = null,
                onDelete = {},
            )
        }
        val image = pixels()
        assertNear("first lane", calculusColour.pastel(), image[width / 4, 6])
        assertNear("second lane", physicsColour.pastel(), image[width * 3 / 4, 6])
    }

    // ---- period column and rows ----

    private val firstPeriod = TimetablePeriod(id = "1", startTime = "08:10", endTime = "09:00")

    @Test
    fun `the period column shows the times under TigerDuck`() {
        draw(VisualPreset.TigerDuck, 40, cellHeight) { ClassTablePeriodLabel(firstPeriod, cellHeight.dp) }
        composeRule.onNodeWithText("1").assertExists()
        composeRule.onNodeWithText("08:10").assertExists()
        composeRule.onNodeWithText("09:00").assertExists()
    }

    @Test
    fun `the period column shows only the period under TAT`() {
        draw(VisualPreset.Tat, 40, cellHeight) { ClassTablePeriodLabel(firstPeriod, cellHeight.dp) }
        composeRule.onNodeWithText("1").assertExists()
        composeRule.onNodeWithText("08:10").assertDoesNotExist()
        composeRule.onNodeWithText("09:00").assertDoesNotExist()
    }

    // ---- the corner mark ----

    @Test
    fun `a one-period TAT tile keeps a long name clear of the corner mark`() {
        soloCell(VisualPreset.Tat, spanCount = 1, course = statistics, hasAssignment = true)
        val name = composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
        // The tile ends 1dp inside the cell; the mark is 12dp at a 3dp inset.
        val tileBottom = cellHeight - 1f
        val clearance = tileBottom - name.boundsInRoot.bottom
        assertTrue("the name ends $clearance dp above the tile's bottom", clearance >= 13f)
    }

    // ---- empty slots ----

    private fun emptySlot(preset: VisualPreset): Color {
        var surfaceVariant = Color.Unspecified
        draw(preset, width, cellHeight) {
            surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
            ClassTableEmptySlot(Modifier.size(width.dp, cellHeight.dp))
        }
        return surfaceVariant
    }

    @Test
    fun `TigerDuck draws an empty slot as a faint tile`() {
        val surfaceVariant = emptySlot(VisualPreset.TigerDuck)
        assertNear("empty slot", overWhite(surfaceVariant, 0.3f), pixels()[width / 2, cellHeight / 2])
    }

    @Test
    fun `TAT leaves an empty slot bare`() {
        emptySlot(VisualPreset.Tat)
        assertNear("empty slot", Color.White, pixels()[width / 2, cellHeight / 2])
    }

    // ---- period column ----

    @Test
    fun `the period column narrows to the period alone under TAT`() {
        val tigerDuck = VisualStylePolicy(VisualPreset.TigerDuck)
        val tat = VisualStylePolicy(VisualPreset.Tat)
        assertEquals(36.dp, tigerDuck.classTablePeriodColumnWidth(fontScale = 1f))
        assertEquals(20.dp, tat.classTablePeriodColumnWidth(fontScale = 1f))
        // Both grow with a large font, from where the scaled width passes the floor.
        assertEquals(42.dp, tigerDuck.classTablePeriodColumnWidth(fontScale = 1.5f))
        assertEquals(24.dp, tat.classTablePeriodColumnWidth(fontScale = 1.5f))
    }

    // ---- row bands ----

    private val headerHeight = 20
    private val inset = 4

    /**
     * The grid's frame: the bands on the outside, the padding inside them, then
     * a header and a two-period run, as TimetableGrid lays them out.
     */
    private fun bandedGrid(preset: VisualPreset) = draw(preset, width, headerHeight + cellHeight * 2) {
        Box(
            Modifier
                .fillMaxSize()
                .classTableRowBands(
                    LocalVisualStylePolicy.current,
                    MaterialTheme.colorScheme.onSurface,
                    rowCount = 2,
                    cellHeight = cellHeight.dp,
                )
                .padding(horizontal = inset.dp)
        ) {
            Column {
                Spacer(Modifier.height(headerHeight.dp))
                Box { soloCellContent(calculus, spanCount = 2, cellWidth = width - 2 * inset) }
            }
        }
    }

    @Test
    fun `TAT bands the period rows from edge to edge, under the cells`() {
        bandedGrid(VisualPreset.Tat)
        val image = pixels()
        val firstRow = headerHeight + 8
        val secondRow = headerHeight + cellHeight + 8
        assertNear("header", Color.White, image[1, 5])
        // Inside the grid's side padding: the bands reach the edge.
        val first = image[1, firstRow]
        val second = image[1, secondRow]
        assertTrue("first band $first is darker than the second $second", first.red < second.red)
        assertTrue("second band $second is not bare", second.red < 1f)
        // The cells sit on top, and the band shows only in the gap between tiles.
        assertNear("first tile", calculusColour.pastel(), image[inset + 8, firstRow])
        assertNear("second tile", calculusColour.pastel(), image[inset + 8, secondRow])
        assertNear("gap between the tiles", second, image[inset + 3, headerHeight + cellHeight])
    }

    @Test
    fun `TigerDuck leaves the rows plain`() {
        bandedGrid(VisualPreset.TigerDuck)
        val image = pixels()
        assertNear("first row", Color.White, image[1, headerHeight + 8])
        assertNear("second row", Color.White, image[1, headerHeight + cellHeight + 8])
    }
}
