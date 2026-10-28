package com.cosmerearchives.shortscreator

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
                Canvas(Modifier.width(widthDp.dp).height(61.dp).pointerInput(clips, positions, durations) {
                    detectTapGestures { tap ->
                        val time = ((tap.x - leftPx) / pxPerMs).toLong().coerceAtLeast(0L)
                        val chosen = clips.indices.lastOrNull { i -> clips[i].track == lane && time in positions[i]..(positions[i] + durations[i]) }
                        if (chosen != null) onSelect(chosen) else onScrub(time.coerceAtMost(total))
                    }
                }.pointerInput(clips, positions, durations, selectedIndex) {
                    var moving = -1
                    var action = 0 // 1=start trim, 2=end trim, 3=move
                    var deltaTime = 0L
                    var startPosition = 0L
                    detectDragGestures(
                        onDragStart = { pos ->
                            val time = ((pos.x - leftPx) / pxPerMs).toLong().coerceAtLeast(0L)
                            moving = clips.indices.lastOrNull { i ->
                                clips[i].track == lane && time in positions[i]..(positions[i] + durations[i])
                            } ?: -1
                            if (moving >= 0) {
                                onSelect(moving)
                                val offset = time - positions[moving]
                                val edgeMs = (12.dp.toPx() / pxPerMs).toLong()
                                action = when {
                                    offset <= edgeMs -> 1
                                    durations[moving] - offset <= edgeMs -> 2
                                    else -> 3
                                }
                                startPosition = positions[moving]
                                deltaTime = 0L
                            }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (moving >= 0) {
                                deltaTime += (amount.x / pxPerMs).toLong()
                                val clip = clips[moving]
                                when (action) {
                                    1 -> {
                                        val newStart = (clip.startMs + (amount.x / pxPerMs).toLong())
                                            .coerceIn(0L, (clip.endMs - 100L).coerceAtLeast(0L))
                                        onTrim(moving, newStart, clip.endMs)
                                    }
                                    2 -> {
                                        val newEnd = (clip.endMs + (amount.x / pxPerMs).toLong())
                                            .coerceAtLeast(clip.startMs + 100L)
                                        onTrim(moving, clip.startMs, newEnd)
                                    }
                                    3 -> if (clip.track != 0) {
                                        val newPosition = (startPosition + deltaTime).coerceAtLeast(0L)
                                        onMove(moving, newPosition)
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (moving >= 0 && action == 3 && clips[moving].track == 0) {
                                val mainOrder = clips.indices.filter { clips[it].track == 0 }
                                val targetTime = positions[moving] + deltaTime + durations[moving] / 2L
                                val destination = mainOrder.minByOrNull {
                                    kotlin.math.abs((positions[it] + durations[it] / 2L) - targetTime)
                                }
                                if (destination != null && destination != moving) onReorder(moving, destination)
                            }
                            moving = -1
                        },
                        onDragCancel = { moving = -1 }
                    )
 {
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
