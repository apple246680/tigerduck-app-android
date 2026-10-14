// The timetable grid proper — weekday columns, period rows, and the
// scroll plumbing. Renders cells but does not know what a cell looks
// like; that is ClassTableCourseCell / ClassTableConflictCells.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.theme.LocalVisualStylePolicy

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TimetableGrid(
    viewModel: ClassTableViewModel,
    /**
     * The collected course list, not `viewModel.courses.value`: a plain read
     * of the StateFlow registers no dependency, so a course added into rows
     * and days already on screen left every other input here unchanged and
     * the grid was skipped until the next restart.
     */
    courses: List<Course>,
    /** Print each course's room in its cell's corner; see [CourseRoomHint]. */
    showRoomHints: Boolean,
    weekdays: List<Int>,
    periods: List<org.ntust.app.tigerduck.data.model.TimetablePeriod>,
    courseNosWithAssignments: Set<String>,
    /** Courses whose cells carry the ignored mark; none in the exported image. */
    courseNosIgnoringAssignments: Set<String> = emptySet(),
    onRename: (Course) -> Unit = {},
    onDelete: (Course) -> Unit = {},
    onPickColor: (Course) -> Unit = {},
    /** Null leaves the action out of the long-press menu. */
    onToggleIgnoreAssignments: ((Course) -> Unit)? = null,
    onPickConflict: (Course, Course, Int, String) -> Unit = { _, _, _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dayLabels = listOf(
        "",
        stringResource(R.string.weekday_mon_short),
        stringResource(R.string.weekday_tue_short),
        stringResource(R.string.weekday_wed_short),
        stringResource(R.string.weekday_thu_short),
        stringResource(R.string.weekday_fri_short),
        stringResource(R.string.weekday_sat_short),
        stringResource(R.string.weekday_sun_short),
    )
    // The period column stacks three lines (start / 節 / end) totalling 36sp
    // of line height. That leaves comfortable slack in a 52dp row at the
    // default font scale, but the rows are placed at absolute
    // `cellHeight * index` offsets — so once the text outgrows the row it
    // does not push the next one down, it overlaps it. Grow the row with the
    // system font scale instead: unchanged up to ~1.3x, taller beyond, which
    // is what someone who asked for bigger text wants anyway.
    val fontScale = LocalDensity.current.fontScale
    val cellHeight = maxOf(52.dp, (40 * fontScale).dp)
    val style = LocalVisualStylePolicy.current
    val periodColWidth = style.classTablePeriodColumnWidth(fontScale)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // Outside the padding, so the bands run from edge to edge.
            .classTableRowBands(style, MaterialTheme.colorScheme.onSurface, periods.size, cellHeight)
            .padding(horizontal = 4.dp)
    ) {
        val dayColWidth = (maxWidth - periodColWidth) / weekdays.size
        val totalHeight = cellHeight * periods.size

        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row {
                Box(modifier = Modifier.width(periodColWidth))
                weekdays.forEach { day ->
                    Box(
                        modifier = Modifier.width(dayColWidth),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayLabels.getOrElse(day) { "$day" },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Grid body — use Box with absolute positioning so blocks can span rows
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
            ) {
                // Period labels (left column)
                periods.forEachIndexed { periodIndex, period ->
                    ClassTablePeriodLabel(
                        period = period,
                        cellHeight = cellHeight,
                        modifier = Modifier
                            .width(periodColWidth)
                            .absoluteOffset(x = 0.dp, y = cellHeight * periodIndex),
                    )
                }

                // Day cells
                weekdays.forEachIndexed { dayIndex, weekday ->
                    val x = periodColWidth + dayColWidth * dayIndex

                    periods.forEachIndexed { periodIndex, period ->
                        val y = cellHeight * periodIndex

                        when (val role = ClassTableCellLayout.roleAt(courses, periods, weekday, periodIndex)) {
                            is CellRole.Empty -> {
                                ClassTableEmptySlot(
                                    modifier = Modifier
                                        .width(dayColWidth)
                                        .height(cellHeight)
                                        .absoluteOffset(x = x, y = y)
                                )
                            }

                            is CellRole.SoloStart -> {
                                SoloCourseCell(
                                    course = role.course,
                                    // Solo cells only: a 衝堂 cluster splits the
                                    // cell and leaves no corner to print in.
                                    roomHint = if (showRoomHints) {
                                        CourseRoomHint.room(role.course, weekday, period.id)
                                    } else {
                                        null
                                    },
                                    spanCount = role.spanCount,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    x = x,
                                    y = y,
                                    weekday = weekday,
                                    hasAssignment = role.course.courseNo in courseNosWithAssignments,
                                    assignmentsIgnored =
                                        role.course.courseNo in courseNosIgnoringAssignments,
                                    onTap = {
                                        viewModel.selectCourse(
                                            role.course,
                                            weekday,
                                            period.id
                                        )
                                    },
                                    onLongPress = {
                                        Haptics.perform(
                                            context,
                                            HapticScenario.ClassTableLongPress,
                                        )
                                    },
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onToggleIgnoreAssignments = onToggleIgnoreAssignments,
                                    onDelete = onDelete,
                                )
                            }

                            is CellRole.ConflictStart -> {
                                ConflictCourseCell(
                                    cellRole = role,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    x = x,
                                    y = y,
                                    weekday = weekday,
                                    periodId = period.id,
                                    hasAssignmentA = role.courseA.courseNo in courseNosWithAssignments,
                                    hasAssignmentB = role.courseB.courseNo in courseNosWithAssignments,
                                    assignmentsIgnoredA =
                                        role.courseA.courseNo in courseNosIgnoringAssignments,
                                    assignmentsIgnoredB =
                                        role.courseB.courseNo in courseNosIgnoringAssignments,
                                    onPickConflict = onPickConflict,
                                    onLongPress = {
                                        Haptics.perform(
                                            context,
                                            HapticScenario.ClassTableLongPress,
                                        )
                                    },
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onToggleIgnoreAssignments = onToggleIgnoreAssignments,
                                    onDelete = onDelete,
                                )
                            }

                            is CellRole.MultiConflictStart -> {
                                MultiConflictCourseCell(
                                    cellRole = role,
                                    dayColWidth = dayColWidth,
                                    cellHeight = cellHeight,
                                    x = x,
                                    y = y,
                                    weekday = weekday,
                                    hasAssignment = { courseNo -> courseNo in courseNosWithAssignments },
                                    assignmentsIgnored = { courseNo ->
                                        courseNo in courseNosIgnoringAssignments
                                    },
                                    onSelect = { course, firstPeriodId ->
                                        viewModel.selectCourse(course, weekday, firstPeriodId)
                                    },
                                    onLongPress = {
                                        Haptics.perform(
                                            context,
                                            HapticScenario.ClassTableLongPress,
                                        )
                                    },
                                    onRename = onRename,
                                    onPickColor = onPickColor,
                                    onToggleIgnoreAssignments = onToggleIgnoreAssignments,
                                    onDelete = onDelete,
                                )
                            }

                            is CellRole.Skip -> {
                                // Rendered as part of an earlier SoloStart / ConflictStart
                            }
                        }
                    }
                }
            }
        }
    }
}
