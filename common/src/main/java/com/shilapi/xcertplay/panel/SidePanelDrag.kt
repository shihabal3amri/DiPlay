package com.shilapi.xcertplay.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The long-press drag state machine, one instance per panel surface. Tiles report their bounds in
 * window coordinates and their pointer positions map onto the same space, so the drop slot follows
 * the queue purely from geometry: the grid never reflows mid-drag, and a refresh tick that
 * re-places tiles only moves the slot with them. Nothing persists here — the drop calls
 * [SidePanelModel.move] once.
 */
class DragState {
    /** The widget kind currently lifted, or null when resting. */
    var liftedKind by mutableStateOf<String?>(null)
        private set
    /** Queue index the drop would land on, or -1 when no slot shows. */
    var targetIndex by mutableStateOf(-1)
        private set
    /** Accumulated finger delta since the lift, applied to the lifted tile as a visual translation. */
    var dragOffset by mutableStateOf(Offset.Zero)
        private set
    /** Window rect of the panel surface, set by the panel root for slot and auto-scroll math. */
    var panelBounds by mutableStateOf(Rect.Zero)
    /** kind -> tile bounds in window coordinates, refreshed by every tile's layout pass. */
    val bounds = mutableStateMapOf<String, Rect>()

    private var startY = Float.NaN

    /** Finger Y in window coordinates, derived from the lift position plus the accumulated delta. */
    val fingerY: Float get() = if (startY.isNaN()) -1f else startY + dragOffset.y

    fun begin(kind: String, windowPos: Offset) {
        liftedKind = kind
        targetIndex = -1
        dragOffset = Offset.Zero
        startY = windowPos.y
    }

    fun drag(delta: Offset) {
        dragOffset += delta
    }

    /** Recompute the drop slot from the queue order: the first tile whose centre lies below the finger. */
    fun updateTarget(queue: List<String>) {
        val lift = liftedKind ?: return
        val liftIndex = queue.indexOf(lift)
        if (liftIndex < 0) { targetIndex = -1; return }
        val finger = fingerY
        if (finger < 0f) { targetIndex = -1; return }
        var below = -1
        for ((index, kind) in queue.withIndex()) {
            val rect = bounds[kind] ?: continue
            if (finger < rect.center.y) { below = index; break }
        }
        val raw = if (below >= 0) below else queue.size - 1
        targetIndex = if (raw == liftIndex) -1 else raw
    }

    /** Commit the drop: move the lifted widget to the slot if one shows, then clear the lift. */
    fun drop(model: SidePanelModel) {
        val lift = liftedKind
        if (lift != null && targetIndex >= 0) {
            val from = model.widgets.indexOfFirst { it.kind == lift }
            if (from >= 0) model.move(from, targetIndex)
        }
        model.startEditing() // the editor stays open after a drop (or a plain long-press release)
        cancel()
    }

    fun cancel() {
        liftedKind = null
        targetIndex = -1
        dragOffset = Offset.Zero
        startY = Float.NaN
    }
}

/**
 * The tile gesture: long-press 350 ms lifts (scale + shadow + translation follows the finger),
 * the slot shows the landing index, and release drops once into the model. A finger that moves
 * before the threshold is a plain scroll, so the panel keeps scrolling in browse mode. Edge
 * proximity nudges [scroll] so a drag can cross a long list.
 */
internal fun Modifier.tileDrag(
    kind: String,
    model: SidePanelModel,
    drag: DragState,
    scroll: ScrollState,
    edgePx: Float,
    stepPx: Float,
): Modifier = this
    .onGloballyPositioned { drag.bounds[kind] = it.boundsInWindow() }
    .pointerInput(kind) {
        detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                val rect = drag.bounds[kind] ?: return@detectDragGesturesAfterLongPress
                if (model.widgets.size > 1) {
                    drag.begin(kind, Offset(rect.left + position.x, rect.top + position.y))
                    drag.updateTarget(model.widgets.map { it.kind })
                }
            },
            onDrag = { change, amount ->
                change.consume()
                drag.drag(amount)
                drag.updateTarget(model.widgets.map { it.kind })
                // Auto-scroll: graduated speed while the finger grazes the panel's top or bottom.
                val finger = drag.fingerY
                val panel = drag.panelBounds
                if (edgePx > 0f && panel.height > 0f && finger >= 0f) {
                    val delta = when {
                        finger < panel.top + edgePx -> -((panel.top + edgePx - finger) / edgePx).coerceIn(0f, 1f) * stepPx
                        finger > panel.bottom - edgePx -> ((finger - (panel.bottom - edgePx)) / edgePx).coerceIn(0f, 1f) * stepPx
                        else -> 0f
                    }
                    if (delta != 0f) scroll.dispatchRawDelta(delta)
                }
            },
            onDragEnd = { drag.drop(model) },
            onDragCancel = { drag.cancel() },
        )
    }
    .graphicsLayer {
        if (drag.liftedKind == kind) {
            scaleX = 1.06f
            scaleY = 1.06f
            translationX = drag.dragOffset.x
            translationY = drag.dragOffset.y
            shadowElevation = 12.dp.toPx()
        }
    }

/**
 * The dashed slot previewing where the lifted tile lands, drawn over the grid in panel coordinates.
 * It re-derives its rect from [DragState.bounds], so it tracks tiles through refresh ticks and
 * auto-scroll without owning any layout.
 */
@Composable
internal fun DropSlot(model: SidePanelModel, drag: DragState, palette: TilePalette) {
    val lift = drag.liftedKind ?: return
    val target = drag.targetIndex
    if (target < 0 || target >= model.widgets.size) return
    val rect = drag.bounds[model.widgets[target].kind] ?: return
    val panel = drag.panelBounds
    if (panel.width <= 0f || rect.width <= 0f) return
    val density = LocalDensity.current
    val left = rect.left - panel.left
    val top = rect.top - panel.top
    Canvas(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() }),
    ) {
        val shape = CornerRadius(22.dp.toPx())
        drawRoundRect(color = palette.accent.copy(alpha = 0.08f), cornerRadius = shape)
        drawRoundRect(
            color = palette.accent,
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 12.dp.toPx())),
            ),
            cornerRadius = shape,
        )
    }
}
