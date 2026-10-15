package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy
import org.ntust.app.tigerduck.ui.theme.VisualStylePolicy.ClassTableGridStyle
import org.ntust.app.tigerduck.widget.LayerCourse
import org.ntust.app.tigerduck.widget.ScheduleCell
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.buildScheduleCells
import org.ntust.app.tigerduck.widget.renderConflictLayer
import org.ntust.app.tigerduck.widget.widgetCourseColor

/**
 * The Week widget: the class table's grid, drawn in the interface style as the
 * class table is — blocks, or under TAT a pastel tile per period, the period
 * without its time, bare empty slots and banded rows.
 */
@Composable
fun WeekGridContent(
    state: WidgetState,
    colors: WidgetColors,
    style: VisualStylePolicy,
    tapAction: Action,
) {
    val context = LocalContext.current
    val dayNames = listOf(
        context.getString(R.string.weekday_mon_short),
        context.getString(R.string.weekday_tue_short),
        context.getString(R.string.weekday_wed_short),
        context.getString(R.string.weekday_thu_short),
        context.getString(R.string.weekday_fri_short),
        context.getString(R.string.weekday_sat_short),
        context.getString(R.string.weekday_sun_short),
    )
    val showsPeriodTimes = style.classTableShowsPeriodTimes
    // Narrower where the column holds only the period, growing with a large
    // font as the class table's does, so "10" stays on one line.
    val periodColWidth = if (showsPeriodTimes) {
        32.dp
    } else {
        maxOf(20.dp, (16 * context.resources.configuration.fontScale).dp)
    }
    val outerPadding = 6.dp
    val headerHeight = 22.dp
    val headerBottomPad = 4.dp

    val widgetSize = LocalSize.current
    val widgetHeight = widgetSize.height
    val widgetWidth = widgetSize.width
    val usableHeight = (widgetHeight - outerPadding * 2 - headerHeight - headerBottomPad)
        .coerceAtLeast(40.dp)
    val rowCount = state.activePeriodIds.size.coerceAtLeast(1)
    val cellHeight: Dp = (usableHeight.value / rowCount).dp.coerceAtLeast(20.dp)
    val dayColWidth: Dp = run {
        val numDays = state.activeWeekdays.size.coerceAtLeast(1)
        ((widgetWidth - outerPadding * 2 - periodColWidth).value / numDays).dp
            .coerceAtLeast(20.dp)
    }

    val showsGrid = state.isLoggedIn && state.courses.isNotEmpty()

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.background))
            .clickable(tapAction),
    ) {
        // Behind the grid and outside its padding, so the bands run from edge
        // to edge, as on the class table.
        if (showsGrid) {
            WeekRowBands(
                style = style,
                onSurface = colors.onSurface,
                rowCount = state.activePeriodIds.size,
                top = outerPadding + headerHeight,
                cellHeight = cellHeight,
            )
        }
        Column(modifier = GlanceModifier.fillMaxSize().padding(outerPadding)) {
            if (!showsGrid) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (!state.isLoggedIn) {
                            context.getString(R.string.widget_sign_in)
                        } else {
                            context.getString(R.string.widget_no_courses)
                        },
                        style = TextStyle(
                            color = ColorProvider(colors.onSurfaceVariant),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
                return@Column
            }

            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(headerHeight)
                    .padding(bottom = headerBottomPad),
            ) {
                Box(modifier = GlanceModifier.width(periodColWidth)) {}
                state.activeWeekdays.forEach { day ->
                    Box(
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = dayNames[day - 1],
                            style = TextStyle(
                                color = ColorProvider(colors.highlight),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                }
            }

            Row(modifier = GlanceModifier.fillMaxWidth()) {
                GlanceStack(
                    items = state.activePeriodIds,
                    modifier = GlanceModifier.width(periodColWidth),
                ) { periodId ->
                    PeriodLabelCell(periodId, colors, cellHeight, showsPeriodTimes)
                }
                state.activeWeekdays.forEach { weekday ->
                    val cells = buildScheduleCells(state.activePeriodIds, state.courses, weekday)
                    GlanceStack(
                        items = cells,
                        modifier = GlanceModifier.defaultWeight().padding(horizontal = 1.dp),
                    ) { cell ->
                        ScheduleCellBox(
                            cell = cell,
                            colors = colors,
                            style = style,
                            courseColors = state.courseColors,
                            ongoingCourseNos = state.ongoingCourseNos,
                            blockHeight = cellHeight * cell.length,
                            blockWidth = dayColWidth,
                            courseNameScale = state.courseNameScale,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The bands behind the grid's [rowCount] period rows, where the style has
 * them: each one row high and the widget's whole width, from [top] down.
 */
@Composable
private fun WeekRowBands(
    style: VisualStylePolicy,
    onSurface: Color,
    rowCount: Int,
    top: Dp,
    cellHeight: Dp,
) {
    val bands = List(rowCount) { style.classTableRowBand(it, onSurface) }
    if (bands.all { it == null }) return
    // The offset is padding rather than a spacer: a spacer would be one more
    // child, and Glance draws only a container's first ten.
    GlanceStack(
        items = bands,
        modifier = GlanceModifier.fillMaxWidth().padding(top = top),
    ) { band ->
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(cellHeight)
                .background(ColorProvider(band ?: Color.Transparent)),
        ) {}
    }
}

/** The most children a Glance Row, Column or Box draws; it drops the rest. */
private const val GLANCE_MAX_CHILDREN = 10

/**
 * A column of one child per item, which, past [GLANCE_MAX_CHILDREN] items,
 * nests them in runs of that many so none is dropped: a grid of more than ten
 * periods would otherwise lose its last rows, and a course run of more than
 * ten its last period tiles.
 */
@Composable
private fun <T> GlanceStack(
    items: List<T>,
    modifier: GlanceModifier,
    content: @Composable (T) -> Unit,
) {
    Column(modifier = modifier) {
        if (items.size <= GLANCE_MAX_CHILDREN) {
            items.forEach { content(it) }
        } else {
            items.chunked(GLANCE_MAX_CHILDREN).forEach { run ->
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    run.forEach { content(it) }
                }
            }
        }
    }
}

@Composable
private fun PeriodLabelCell(
    periodId: String,
    colors: WidgetColors,
    cellHeight: Dp,
    showsTime: Boolean,
) {
    val startTime = AppConstants.PeriodTimes.mapping[periodId]?.first ?: ""
    Column(
        modifier = GlanceModifier.fillMaxWidth().height(cellHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = periodId,
            style = TextStyle(
                color = ColorProvider(colors.onSurface),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        if (showsTime) {
            Text(
                text = startTime,
                style = TextStyle(
                    color = ColorProvider(colors.onSurfaceVariant),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun ScheduleCellBox(
    cell: ScheduleCell,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    ongoingCourseNos: List<String>,
    blockHeight: Dp,
    blockWidth: Dp,
    courseNameScale: Float,
) {
    when (cell) {
        is ScheduleCell.Empty -> EmptyCell(blockHeight, colors, style)
        is ScheduleCell.Solo -> SoloCell(
            course = cell.course,
            length = cell.length,
            ongoing = cell.course.courseNo in ongoingCourseNos,
            colors = colors,
            style = style,
            courseColors = courseColors,
            blockHeight = blockHeight,
            courseNameScale = courseNameScale,
        )

        is ScheduleCell.Conflict -> ConflictCell(
            cell = cell,
            colors = colors,
            style = style,
            courseColors = courseColors,
            ongoingCourseNos = ongoingCourseNos,
            blockHeight = blockHeight,
            blockWidth = blockWidth,
            courseNameScale = courseNameScale,
        )

        is ScheduleCell.MultiConflict -> MultiConflictCell(
            cell = cell,
            colors = colors,
            style = style,
            courseColors = courseColors,
            ongoingCourseNos = ongoingCourseNos,
            blockHeight = blockHeight,
            courseNameScale = courseNameScale,
        )
    }
}

@Composable
private fun EmptyCell(blockHeight: Dp, colors: WidgetColors, style: VisualStylePolicy) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(blockHeight)
            .padding(vertical = 1.dp),
    ) {
        // Left bare under a style that leaves empty slots bare, as on the class table.
        if (style.classTableGridStyle == ClassTableGridStyle.Blocks) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(colors.emptyCell))
                    .cornerRadius(4.dp),
            ) {}
        }
    }
}

@Composable
private fun SoloCell(
    course: Course,
    length: Int,
    ongoing: Boolean,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    blockHeight: Dp,
    courseNameScale: Float,
) {
    val cellBg = weekCourseFill(course, ongoing, colors, style, courseColors)
    val textColor = weekCourseTextColor(ongoing, colors, style)
    when (style.classTableGridStyle) {
        ClassTableGridStyle.Blocks -> CourseTile(
            name = course.displayName,
            fill = cellBg,
            textColor = textColor,
            height = blockHeight,
            cornerRadius = 6.dp,
            maxLines = if (length >= 2) 3 else 2,
            courseNameScale = courseNameScale,
        )

        // One tile per period, each naming the course, as on the class table.
        ClassTableGridStyle.PeriodTiles -> {
            val tileHeight = blockHeight / length
            val fontScale = LocalContext.current.resources.configuration.fontScale
            GlanceStack(
                items = List(length) { it },
                modifier = GlanceModifier.fillMaxWidth().height(blockHeight),
            ) {
                CourseTile(
                    name = course.displayName,
                    fill = cellBg,
                    textColor = textColor,
                    height = tileHeight,
                    // A period tile's corners, tighter than a block's.
                    cornerRadius = 4.dp,
                    maxLines = tileNameLines(tileHeight, courseNameScale, fontScale),
                    courseNameScale = courseNameScale,
                )
            }
        }
    }
}

/** A course's name on its own fill, [height] high, with the 1dp above and below that every cell keeps. */
@Composable
private fun CourseTile(
    name: String,
    fill: Color,
    textColor: Color,
    height: Dp,
    cornerRadius: Dp,
    maxLines: Int,
    courseNameScale: Float,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 1.dp),
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(fill))
                .cornerRadius(cornerRadius)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name,
                style = TextStyle(
                    color = ColorProvider(textColor),
                    fontSize = 10.sp * courseNameScale,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = maxLines,
            )
        }
    }
}

@Composable
private fun ConflictCell(
    cell: ScheduleCell.Conflict,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    ongoingCourseNos: List<String>,
    blockHeight: Dp,
    blockWidth: Dp,
    courseNameScale: Float,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val widthPx = ((blockWidth - 2.dp).value * density).toInt().coerceAtLeast(1)
    val heightPx = (blockHeight.value * density).toInt().coerceAtLeast(1)

    val ongoingA = cell.courseA.courseNo in ongoingCourseNos
    val ongoingB = cell.courseB.courseNo in ongoingCourseNos
    // If both conflicting courses are currently ongoing, painting both halves
    // with the single highlight color merges them into a solid rectangle and
    // hides the L-shape. Keep them in their own course colors in that case —
    // the "進行中" status is surfaced by the NextClass widget anyway.
    val bothOngoing = ongoingA && ongoingB
    val highlightA = ongoingA && !bothOngoing
    val highlightB = ongoingB && !bothOngoing

    // renderConflictLayer allocates ARGB_8888 bitmaps and runs Canvas paths —
    // without `remember` every Glance recomposition (class-boundary ticks,
    // sync completes, theme flips) would re-allocate and re-draw both layers
    // and inflate the RemoteViews payload toward the Binder 1 MB limit.
    val fillA = weekCourseFill(cell.courseA, highlightA, colors, style, courseColors)
    val fillB = weekCourseFill(cell.courseB, highlightB, colors, style, courseColors)
    val bitmapA = remember(cell, widthPx, heightPx, density, fillA) {
        renderConflictLayer(
            clusterWidthPx = widthPx,
            clusterHeightPx = heightPx,
            densityFactor = density,
            cell = cell,
            course = LayerCourse.A,
            fillColor = fillA,
        )
    }
    val bitmapB = remember(cell, widthPx, heightPx, density, fillB) {
        renderConflictLayer(
            clusterWidthPx = widthPx,
            clusterHeightPx = heightPx,
            densityFactor = density,
            cell = cell,
            course = LayerCourse.B,
            fillColor = fillB,
        )
    }

    val rowHeight = blockHeight / cell.combinedSpan.coerceAtLeast(1)
    val barWidth = (blockWidth.value * (1f - 0.28f)).dp
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(blockHeight)
            .padding(vertical = 1.dp),
    ) {
        Box(modifier = GlanceModifier.fillMaxSize()) {
            Image(
                provider = ImageProvider(bitmapA),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Image(
                provider = ImageProvider(bitmapB),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            // Course A label — pinned inside A's own Box at top-right (Γ's top bar).
            ConflictLabel(
                courseName = cell.courseA.courseName,
                textColor = weekCourseTextColor(highlightA, colors, style),
                alignment = Alignment.TopEnd,
                boxTopPadding = rowHeight * cell.offsetA,
                boxBottomPadding = rowHeight * (cell.combinedSpan - cell.offsetA - cell.spanA),
                barWidth = barWidth,
                courseNameScale = courseNameScale,
            )
            // Course B label — pinned inside B's own Box at bottom-left (mirror-L's bottom bar).
            ConflictLabel(
                courseName = cell.courseB.courseName,
                textColor = weekCourseTextColor(highlightB, colors, style),
                alignment = Alignment.BottomStart,
                boxTopPadding = rowHeight * cell.offsetB,
                boxBottomPadding = rowHeight * (cell.combinedSpan - cell.offsetB - cell.spanB),
                barWidth = barWidth,
                courseNameScale = courseNameScale,
            )
        }
    }
}

/**
 * Renders a 3+ course cluster as side-by-side lane columns. Each lane stacks
 * its members vertically with empty gaps preserved as transparent spacers, so
 * the in-app class-table's MultiConflictStart layout is mirrored on widgets
 * without needing a 3-tile bitmap path.
 */
@Composable
private fun MultiConflictCell(
    cell: ScheduleCell.MultiConflict,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
    ongoingCourseNos: List<String>,
    blockHeight: Dp,
    courseNameScale: Float,
) {
    val rowHeight = blockHeight / cell.combinedSpan.coerceAtLeast(1)

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(blockHeight)
            .padding(vertical = 1.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            for (laneIdx in 0 until cell.laneCount) {
                val laneMembers = cell.members
                    .filter { it.lane == laneIdx }
                    .sortedBy { it.offset }
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .padding(horizontal = 0.5.dp),
                ) {
                    var cursor = 0
                    laneMembers.forEach { member ->
                        if (member.offset > cursor) {
                            Box(modifier = GlanceModifier.height(rowHeight * (member.offset - cursor))) {}
                        }
                        val ongoing = member.course.courseNo in ongoingCourseNos
                        val tileFill = weekCourseFill(member.course, ongoing, colors, style, courseColors)
                        val tileTextColor = weekCourseTextColor(ongoing, colors, style)
                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(rowHeight * member.span)
                                .padding(vertical = 0.5.dp),
                        ) {
                            Box(
                                modifier = GlanceModifier
                                    .fillMaxSize()
                                    .background(ColorProvider(tileFill))
                                    .cornerRadius(4.dp)
                                    .padding(1.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = member.course.displayName,
                                    style = TextStyle(
                                        color = ColorProvider(tileTextColor),
                                        fontSize = 9.sp * courseNameScale,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                    ),
                                    maxLines = if (member.span >= 2) 3 else 2,
                                )
                            }
                        }
                        cursor = member.offset + member.span
                    }
                    if (cursor < cell.combinedSpan) {
                        Box(modifier = GlanceModifier.height(rowHeight * (cell.combinedSpan - cursor))) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictLabel(
    courseName: String,
    textColor: Color,
    alignment: Alignment,
    boxTopPadding: Dp,
    boxBottomPadding: Dp,
    barWidth: Dp,
    courseNameScale: Float,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(top = boxTopPadding, bottom = boxBottomPadding),
        contentAlignment = alignment,
    ) {
        Box(
            modifier = GlanceModifier
                .width(barWidth)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = courseName,
                style = TextStyle(
                    color = ColorProvider(textColor),
                    fontSize = 9.sp * courseNameScale,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 2,
            )
        }
    }
}

/**
 * A course's fill on the Week widget: the highlight while the class is on,
 * else the class table's pastel under TAT, else the widget's own tint, a
 * little stronger in light mode than the class table's.
 */
internal fun weekCourseFill(
    course: Course,
    highlighted: Boolean,
    colors: WidgetColors,
    style: VisualStylePolicy,
    courseColors: Map<String, Color>,
): Color {
    if (highlighted) return colors.highlight
    val themed = widgetCourseColor(course, courseColors, colors.isDark)
    return when (style.classTableGridStyle) {
        ClassTableGridStyle.Blocks -> if (colors.isDark) themed else themed.copy(alpha = 0.55f)
        ClassTableGridStyle.PeriodTiles -> style.classTableCourseFill(
            courseColor = widgetCourseColor(course, courseColors, isDark = false),
            themeColor = themed,
            isDark = colors.isDark,
        )
    }
}

/** A course's name on [weekCourseFill]. */
internal fun weekCourseTextColor(
    highlighted: Boolean,
    colors: WidgetColors,
    style: VisualStylePolicy,
): Color = if (highlighted) Color.White else style.classTableCourseTextColor(colors.isDark)

/**
 * How many lines of a course name fit a period tile [height] high, from one up
 * to two: a second line that does not fit is cut through the middle, on every
 * tile of the run. The name is 10sp, scaled by [courseNameScale] and the
 * system [fontScale], inside the tile's 1dp above and below and 2dp padding;
 * a line of Chinese, which most course names are, takes about 1.45 times
 * its size.
 */
internal fun tileNameLines(height: Dp, courseNameScale: Float, fontScale: Float): Int {
    val textHeight = height.value - 2 * 1f - 2 * 2f
    val lineHeight = 10f * courseNameScale * fontScale * 1.45f
    return (textHeight / lineHeight).toInt().coerceIn(1, 2)
}
