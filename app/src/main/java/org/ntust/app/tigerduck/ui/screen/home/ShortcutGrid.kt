// The body of a Home section that holds shortcuts (Quick widgets, or a
// custom section): a two-column grid of cards that open a feature, the
// iPhone's WidgetGridView. In edit mode each card loses its tap, gains a
// remove button and can be dragged to another slot.

package org.ntust.app.tigerduck.ui.screen.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.WidgetItem
import org.ntust.app.tigerduck.ui.navigation.icon
import kotlin.math.roundToInt

private const val COLUMNS = 2
private val CardGap = 12.dp
private val CardHeight = 88.dp

/**
 * @param onOpen a card tapped outside edit mode; the caller decides between
 *   the feature's page and "Coming soon", as the More page does.
 * @param onMove one step of a drag: the card [WidgetItem.id] now lies over
 *   the given slot. Called only when the slot changed.
 * @param onAdd the empty state's button: opens the Add dialog, so a section
 *   created with nothing in it shows how to fill it instead of a bare title.
 */
@Composable
internal fun ShortcutGrid(
    widgets: List<WidgetItem>,
    isEditing: Boolean,
    onOpen: (AppFeature) -> Unit,
    onRemove: (WidgetItem) -> Unit,
    onMove: (widgetId: String, toIndex: Int) -> Unit,
    onAdd: () -> Unit,
) {
    if (widgets.isEmpty()) {
        // 4dp outside plus the button's own 12dp lines its label up with the
        // section title's 16dp gutter.
        TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.home_section_add_quick_features))
        }
        return
    }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // Every card is the same size (equal weights, fixed height), so the
    // last one measured stands for all of them. Needed to turn a drag
    // distance into a slot; until a card reports, a drag moves nothing.
    var cellSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { CardGap.toPx() }
    // The drag runs inside pointerInput, which is keyed per card and does
    // not restart when the list reorders under it, so it reads the list
    // and the callback through these rather than the captures it began with.
    val currentWidgets by rememberUpdatedState(widgets)
    val currentOnMove by rememberUpdatedState(onMove)

    // Leaving edit mode mid-drag (back gesture, Done) must not leave a
    // card hanging where the finger was.
    LaunchedEffect(isEditing) {
        if (!isEditing) {
            draggingId = null
            dragOffset = Offset.Zero
        }
    }

    fun settle(delta: Offset) {
        val id = draggingId ?: return
        dragOffset += delta
        if (cellSize == IntSize.Zero) return
        val list = currentWidgets
        val from = list.indexOfFirst { it.id == id }
        val pitchX = cellSize.width + gapPx
        val pitchY = cellSize.height + gapPx
        val to = ShortcutGridGeometry.targetIndex(
            from = from, count = list.size, columns = COLUMNS,
            dx = dragOffset.x, dy = dragOffset.y, pitchX = pitchX, pitchY = pitchY,
        )
        if (to == from) return
        currentOnMove(id, to)
        // The card now has a new resting slot; take that slot's distance
        // out of the offset so it stays under the finger instead of jumping.
        dragOffset -= Offset(
            x = (to % COLUMNS - from % COLUMNS) * pitchX,
            y = (to / COLUMNS - from / COLUMNS) * pitchY,
        )
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        widgets.chunked(COLUMNS).forEach { row ->
            val rowHoldsDragged = row.any { it.id == draggingId }
            Row(
                // A dragged card must draw over the other rows too, and
                // zIndex only orders siblings, so its row is raised as well.
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (rowHoldsDragged) 1f else 0f),
                horizontalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                row.forEach { widget ->
                    key(widget.id) {
                        val isDragging = draggingId == widget.id
                        ShortcutCard(
                            widget = widget,
                            isEditing = isEditing,
                            isDragging = isDragging,
                            onOpen = { onOpen(widget.feature) },
                            onRemove = { onRemove(widget) },
                            modifier = Modifier
                                .weight(1f)
                                .zIndex(if (isDragging) 1f else 0f)
                                .offset {
                                    if (isDragging) IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
                                    else IntOffset.Zero
                                }
                                .onSizeChanged { cellSize = it }
                                .then(
                                    if (isEditing) {
                                        // No long press: the section's own handle
                                        // starts on first touch-and-move too, and a
                                        // card has nothing else to do with a drag
                                        // while editing.
                                        Modifier.pointerInput(widget.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggingId = widget.id
                                                    dragOffset = Offset.Zero
                                                },
                                                onDragEnd = { draggingId = null; dragOffset = Offset.Zero },
                                                onDragCancel = { draggingId = null; dragOffset = Offset.Zero },
                                                onDrag = { change, delta ->
                                                    change.consume()
                                                    settle(delta)
                                                },
                                            )
                                        }
                                    } else {
                                        Modifier
                                    }
                                ),
                        )
                    }
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ShortcutCard(
    widget: WidgetItem,
    isEditing: Boolean,
    isDragging: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The same ramp as a dragged section: elevation says "lifted", no wiggle.
    val elevation by animateFloatAsState(
        targetValue = if (isDragging) 12f else 0f,
        label = "shortcut-drag-elevation",
    )
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier = modifier.height(CardHeight)) {
        Card(
            onClick = onOpen,
            // Disabled rather than a no-op click, so no ripple answers a tap
            // in edit mode; the colours are pinned so "disabled" does not
            // also mean dimmed, as the card is as live as ever, just editing.
            enabled = !isEditing,
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = surface,
                disabledContainerColor = surface,
                disabledContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isDragging) Modifier.shadow(elevation.dp, RoundedCornerShape(12.dp))
                    else Modifier
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    imageVector = widget.feature.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = stringResource(widget.feature.displayNameRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isEditing) {
            // Top-start, as on the iPhone: the section's own remove button
            // overlays the top-end corner of the first row, and the two must
            // not sit under one another.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-6).dp, y = (-6).dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(enabled = !isDragging) { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(16.dp),
                )
            }
            // Decorative: the whole card drags, this only says so.
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp),
            )
        }
    }
}
