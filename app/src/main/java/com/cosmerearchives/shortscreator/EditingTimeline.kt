package com.cosmerearchives.shortscreator

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

private const val PX_PER_SECOND_DP = 32f
private const val START_DP = 56f
private val timelineDark = Color(0xFF292630)
private val videoColor = Color(0xFF75618F)
private val overlayColor = Color(0xFF9C86BC)
private val audioColor = Color(0xFF558D86)

/** Measured time lane; V1 clips are sequential, other tracks use their saved offset. */
@Composable
fun EditingTimeline(
    clips: List<Clip>,
    selectedIndex: Int,
    playheadMs: Long,
    onSelect: (Int) -> Unit,
    onScrub: (Long) -> Unit,
    onMove: (Int, Long) -> Unit,
    onTrim: (Int, Long, Long) -> Unit,
    onReorder: (Int, Int) -> Unit
) {
    val density = LocalDensity.current
    val pxPerMs = with(density) { PX_PER_SECOND_DP.dp.toPx() } / 1000f
    val leftPx = with(density) { START_DP.dp.toPx() }
    val durations = clips.map { max(1000L, it.endMs - it.startMs) }
    var running = 0L
    val positions = clips.mapIndexed { index, clip ->
        if (clip.track == 0) {
            val pos = running
            running += durations[index]
            pos
        } else clip.positionMs
    }
    val end = clips.indices.maxOfOrNull { positions[it] + durations[it] } ?: 0L
    val total = max(15000L, end + 4000L)
    val widthDp = START_DP + total / 1000f * PX_PER_SECOND_DP + 25
    val scroll = rememberScrollState()
    val liveClips = rememberUpdatedState(clips)
    val livePositions = rememberUpdatedState(positions)
    val liveDurations = rememberUpdatedState(durations)
    val liveSelect = rememberUpdatedState(onSelect)
    val liveScrub = rememberUpdatedState(onScrub)
    val liveMove = rememberUpdatedState(onMove)
    val liveTrim = rememberUpdatedState(onTrim)
    val liveReorder = rememberUpdatedState(onReorder)
    val lanes = listOf(1 to "Overlay", 0 to "Main", 2 to "Audio")
    Column {
        Text("Timeline • tap ruler to scrub", style = MaterialTheme.typography.labelMedium)
        Text("Playhead: %.1fs".format(playheadMs / 1000.0), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Canvas(Modifier.width(widthDp.dp).height(32.dp).pointerInput(total) {
                detectTapGestures { tap -> onScrub(((tap.x - leftPx) / pxPerMs).toLong().coerceIn(0, total)) }
            }) {
                drawRect(timelineDark)
                val paint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 11.sp.toPx(); isAntiAlias = true }
                for (second in 0..(total / 1000L).toInt()) {
                    val x = leftPx + second * pxPerMs * 1000f
                    if (second % 5 == 0) {
                        drawLine(Color(0xFFE6DEEE), Offset(x, 12.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                        drawContext.canvas.nativeCanvas.drawText("${second}s", x + 2.dp.toPx(), 11.dp.toPx(), paint)
                    } else {
                        drawLine(Color(0xFF625A6C), Offset(x, 21.dp.toPx()), Offset(x, size.height), 1f)
                    }
                }
                val playX = leftPx + playheadMs * pxPerMs
                drawLine(Color.White, Offset(playX, 0f), Offset(playX, size.height), 2.dp.toPx())
            }
            lanes.forEach { (lane, label) ->
                Canvas(Modifier.width(widthDp.dp).height(61.dp).pointerInput(lane) {
                    awaitEachGesture {
                        val clips = liveClips.value
                        val positions = livePositions.value
                        val durations = liveDurations.value
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val time = ((down.position.x - leftPx) / pxPerMs).toLong().coerceAtLeast(0L)
                        val moving = clips.indices.lastOrNull { i ->
                            clips[i].track == lane && time in positions[i]..(positions[i] + durations[i])
                        }
                        val start = moving?.let { positions[it] } ?: 0L
                        val initialClip = moving?.let { clips[it] }
                        val edgeMs = (14.dp.toPx() / pxPerMs).toLong()
                        val offset = time - start
                        val action = if (moving == null) 0 else when {
                            offset <= edgeMs -> 1
                            durations[moving] - offset <= edgeMs -> 2
                            else -> 3
                        }
                        var totalDx = 0f
                        var currentStart = initialClip?.startMs ?: 0L
                        var currentEnd = initialClip?.endMs ?: 0L
                        var currentPosition = start
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, beyond ->
                            change.consume()
                            totalDx += beyond
                        }
                        if (drag == null) {
                            if (moving != null) liveSelect.value(moving) else liveScrub.value(time.coerceAtMost(total))
                        } else {
                            if (moving != null) liveSelect.value(moving)
                            if (moving != null && initialClip != null) {
                                fun update(dx: Float) {
                                    val delta = (dx / pxPerMs).toLong()
                                    when (action) {
                                        1 -> {
                                            currentStart = (currentStart + delta).coerceIn(0L, (currentEnd - 100L).coerceAtLeast(0L))
                                            liveTrim.value(moving, currentStart, currentEnd)
                                        }
                                        2 -> {
                                            currentEnd = (currentEnd + delta).coerceAtLeast(currentStart + 100L)
                                            liveTrim.value(moving, currentStart, currentEnd)
                                        }
                                        3 -> if (initialClip.track != 0) {
                                            currentPosition = (currentPosition + delta).coerceAtLeast(0L)
                                            liveMove.value(moving, currentPosition)
                                        }
                                    }
                                }
                                update(totalDx)
                                horizontalDrag(drag.id) { change ->
                                    val dx = change.positionChange().x
                                    change.consume()
                                    totalDx += dx
                                    update(dx)
                                }
                                if (action == 3 && initialClip.track == 0) {
                                    val destinationTime = start + (totalDx / pxPerMs).toLong() + durations[moving] / 2
                                    val destination = clips.indices.filter { clips[it].track == 0 }
                                        .minByOrNull { kotlin.math.abs(positions[it] + durations[it] / 2 - destinationTime) }
                                    if (destination != null && destination != moving) liveReorder.value(moving, destination)
                                }
                            }
                        }
                    }
                }) {
                    drawRect(timelineDark)
                    val paint = Paint().apply { isAntiAlias = true; textSize = 12.sp.toPx(); color = android.graphics.Color.WHITE }
                    drawContext.canvas.nativeCanvas.drawText(label, 7.dp.toPx(), 32.dp.toPx(), paint)
                    drawLine(Color(0xFF524B5D), Offset(leftPx, size.height - 1f), Offset(size.width, size.height - 1f))
                    clips.indices.filter { clips[it].track == lane }.forEach { i ->
                        val x = leftPx + positions[i] * pxPerMs
                        val w = max(12.dp.toPx(), durations[i] * pxPerMs - 2.dp.toPx())
                        val tint = if (clips[i].kind == "audio") audioColor else if (lane == 0) videoColor else overlayColor
                        drawRect(tint, Offset(x, 8.dp.toPx()), androidx.compose.ui.geometry.Size(w, 44.dp.toPx()))
                        if (i == selectedIndex) {
                            drawRect(Color.White.copy(alpha = 0.8f), Offset(x, 8.dp.toPx()),
                                androidx.compose.ui.geometry.Size(4.dp.toPx(), 44.dp.toPx()))
                            drawRect(Color.White.copy(alpha = 0.8f), Offset(x + w - 4.dp.toPx(), 8.dp.toPx()),
                                androidx.compose.ui.geometry.Size(4.dp.toPx(), 44.dp.toPx()))
                            drawRect(Color.White, Offset(x, 8.dp.toPx()), androidx.compose.ui.geometry.Size(w, 44.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        }
                        drawContext.canvas.nativeCanvas.drawText("Clip ${i+1}", x + 5.dp.toPx(), 34.dp.toPx(), paint)
                    }
                    val playX = leftPx + playheadMs * pxPerMs
                    drawLine(Color.White, Offset(playX, 0f), Offset(playX, size.height), 2.dp.toPx())
                }
            }
        }
        Text("Drag clip edges to trim. Drag the center to move overlays or reorder main clips.", style = MaterialTheme.typography.bodySmall)
    }
}
