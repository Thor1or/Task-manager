package com.thor1or.dayloop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.thor1or.dayloop.data.GroupEntity
import com.thor1or.dayloop.data.PlanBlock
import com.thor1or.dayloop.logic.NlParser
import com.thor1or.dayloop.logic.PlanEngine
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** Height of one minute on the timeline. */
private const val DP_PER_MIN = 1.3f

private fun snap(minute: Float, step: Int) = (minute / step).roundToInt() * step

/**
 * The day as time blocks, like a calendar's day view. The blocks sit where the schedule puts
 * them. Waiting activities can be changed by hand:
 *  - hold and drag a block to pin it to another start time;
 *  - drag its bottom edge to change its length;
 *  - tap empty space to add an activity at that time; tap a block to edit it.
 */
@Composable
fun PlanTimeline(
    blocks: List<PlanBlock>,
    slots: Map<Long, PlanEngine.Slot>,
    groups: List<GroupEntity>,
    dayStart: Int,
    dayEnd: Int,
    nowMin: Int?,
    modifier: Modifier = Modifier,
    onTap: (PlanBlock) -> Unit,
    onMove: (PlanBlock, Int) -> Unit,
    /** New length in minutes; the flag asks to lock it, because the schedule was squeezing it. */
    onResize: (PlanBlock, Int, Boolean) -> Unit,
    onCreate: (Int) -> Unit,
) {
    // Whole hours that cover the configured day and anything that falls outside it.
    val first = (minOf(dayStart, slots.values.minOfOrNull { it.start } ?: dayStart, nowMin ?: dayStart) / 60) * 60
    val last = ((maxOf(dayEnd, slots.values.maxOfOrNull { it.end } ?: dayEnd, nowMin ?: dayEnd) + 59) / 60) * 60
    val pxPerMin = with(LocalDensity.current) { DP_PER_MIN.dp.toPx() }
    val scroll = rememberScrollState()
    LaunchedEffect(first) {
        // Open at the current time, or at the first activity.
        val focus = nowMin ?: slots.values.minOfOrNull { it.start } ?: dayStart
        scroll.scrollTo(((focus - first - 45) * pxPerMin).roundToInt().coerceAtLeast(0))
    }
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Box(modifier.fillMaxWidth().verticalScroll(scroll)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(((last - first) * DP_PER_MIN).dp + 16.dp)
                .pointerInput(first) {
                    detectTapGestures { at -> onCreate(snap(first + (at.y - 8.dp.toPx()) / pxPerMin, 15).coerceIn(0, 1439)) }
                }
                .padding(top = 8.dp)
        ) {
            for (hour in first / 60..last / 60) {
                val y = ((hour * 60 - first) * DP_PER_MIN).dp
                Text(
                    "%02d:00".format(hour % 24),
                    Modifier.offset(x = 8.dp, y = y - 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.offset(y = y).padding(start = 52.dp), color = line)
            }
            blocks.forEach { b ->
                val slot = slots[b.id] ?: return@forEach
                key(b.id) {
                    TimelineBlock(b, slot, first, pxPerMin, groupColor(groups, b.groupId), onTap, onMove, onResize)
                }
            }
            if (nowMin != null) {
                Box(
                    Modifier
                        .offset(y = ((nowMin - first) * DP_PER_MIN).dp)
                        .padding(start = 46.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .zIndex(3f)
                        .background(MaterialTheme.colorScheme.error)
                )
            }
        }
    }
}

@Composable
private fun TimelineBlock(
    block: PlanBlock,
    slot: PlanEngine.Slot,
    first: Int,
    pxPerMin: Float,
    groupColor: Color?,
    onTap: (PlanBlock) -> Unit,
    onMove: (PlanBlock, Int) -> Unit,
    onResize: (PlanBlock, Int, Boolean) -> Unit,
) {
    val pending = slot.state == PlanEngine.State.PENDING
    val done = slot.state == PlanEngine.State.DONE
    // While a finger is down the block follows it; the schedule catches up when it lifts.
    var moved by remember { mutableFloatStateOf(0f) }
    var grown by remember { mutableFloatStateOf(0f) }
    var released by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(slot.start, slot.length, released) {
        if (released != 0f) delay(350)
        moved = 0f
        grown = 0f
    }
    val start = if (moved == 0f) slot.start else snap(slot.start + moved / pxPerMin, 5)
    val length = if (grown == 0f) slot.length else max(5, snap(slot.length + grown / pxPerMin, 5))

    val scheme = MaterialTheme.colorScheme
    val accent = groupColor ?: scheme.primary
    val fill = when {
        done -> scheme.surfaceVariant
        else -> accent.copy(alpha = if (slot.state == PlanEngine.State.RUNNING) 0.42f else 0.24f).compositeOver(scheme.surface)
    }
    val heightDp = max(22f, length * DP_PER_MIN)

    Box(
        Modifier
            .zIndex(if (moved != 0f || grown != 0f) 2f else 1f)
            .offset { IntOffset(0, ((start - first) * pxPerMin).roundToInt()) }
            .padding(start = 56.dp, end = 10.dp)
            .fillMaxWidth()
            .height(heightDp.dp)
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(fill)
            .then(
                if (slot.state == PlanEngine.State.RUNNING) Modifier.border(1.5.dp, accent, RoundedCornerShape(6.dp)) else Modifier
            )
            .clickable { onTap(block) }
            .then(
                if (!pending) Modifier else Modifier.pointerInput(block.id, slot.start) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, drag -> change.consume(); moved += drag.y },
                        onDragEnd = {
                            val to = snap(slot.start + moved / pxPerMin, 5).coerceIn(0, 1435)
                            if (to != slot.start) onMove(block, to)
                            released += 1f
                        },
                        onDragCancel = { moved = 0f },
                    )
                }
            )
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(if (done) scheme.outline else accent))
        Column(Modifier.padding(start = 10.dp, end = 6.dp, top = if (heightDp >= 30f) 3.dp else 0.dp)) {
            val times = "${NlParser.formatClock(start)}–${NlParser.formatClock(start + length)}"
            val marks = listOfNotNull(
                "fixed".takeIf { block.fixedStart != null && pending },
                "locked".takeIf { block.rigid && pending },
                "squeezed".takeIf { slot.compressed },
            ).joinToString(" · ")
            Text(
                if (heightDp >= 38f) block.title else "${block.title}  $times",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = if (heightDp >= 30f) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) scheme.onSurfaceVariant else scheme.onSurface,
            )
            if (heightDp >= 38f) {
                Text(
                    times + if (marks.isEmpty()) "" else " · $marks",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (pending) {
            // Bottom edge: drag to change the length.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (heightDp >= 40f) 16.dp else 8.dp)
                    .pointerInput(block.id, slot.length) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, drag -> change.consume(); grown += drag },
                            onDragEnd = {
                                val to = max(5, snap(slot.length + grown / pxPerMin, 5))
                                if (to != slot.length) onResize(block, to, slot.compressed)
                                released += 1f
                            },
                            onDragCancel = { grown = 0f },
                        )
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .padding(bottom = 2.dp)
                        .width(28.dp)
                        .height(3.dp)
                        .background(scheme.onSurface.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                )
            }
        }
    }
}
