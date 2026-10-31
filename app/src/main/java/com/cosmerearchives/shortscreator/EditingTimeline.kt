package com.cosmerearchives.shortscreator

import android.graphics.Paint
import android.provider.OpenableColumns
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val context = LocalContext.current
    val nameKeys = clips.map { it.uri to (if (it.kind == "text") it.caption else "") }
    val names = remember(nameKeys) { clips.map { clip ->
        if (clip.kind == "text") clip.caption.ifBlank { "Text overlay" }
        else try {
            val uri = Uri.parse(clip.uri)
            val display = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            display?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Untitled media"
        } catch (_: Exception) { Uri.parse(clip.uri).lastPathSegment?.substringAfterLast('/') ?: "Untitled media" }
    } }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    val pxPerMs = with(density) { (PX_PER_SECOND_DP * zoom).dp.toPx() } / 1000f
    val leftPx = with(density) { START_DP.dp.toPx() }
    val durations = remember(clips) { clips.map { it.timelineDurationMs().coerceAtLeast(1L) } }
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
    val widthDp = START_DP + total / 1000f * PX_PER_SECOND_DP * zoom + 25
    var scrollPx by remember { mutableFloatStateOf(0f) }
    val visibleScroll = rememberUpdatedState(scrollPx)
    val liveClips = rememberUpdatedState(clips)
    val livePositions = rememberUpdatedState(positions)
    val liveDurations = rememberUpdatedState(durations)
    val liveSelect = rememberUpdatedState(onSelect)
    val liveScrub = rememberUpdatedState(onScrub)
    val liveMove = rememberUpdatedState(onMove)
    val liveTrim = rememberUpdatedState(onTrim)
    val liveReorder = rememberUpdatedState(onReorder)
    val laneIndices = remember(clips) { (0..2).associateWith { lane -> clips.indices.filter { clips[it].track == lane } } }
    // The trim strip already extracts and caches peaks. Decode asynchronously, once per media URI.
    val audioSources = clips.mapNotNull { clip ->
        when {
            clip.kind == "audio" || clip.kind == "video" -> clip.uri.takeIf { it.isNotBlank() }
            clip.kind == "text" -> clip.ttsUri.takeIf { it.isNotBlank() }
            else -> null
        }
    }.distinct()
    val waveforms = remember { mutableStateMapOf<String, Pair<Long, List<Float>>>() }
    LaunchedEffect(audioSources) {
        audioSources.forEach { uri ->
            if (uri !in waveforms) {
                // A missing audio stream is stored as an empty list so silent videos aren't decoded again.
                waveforms[uri] = withContext(Dispatchers.IO) {
                    val retriever = android.media.MediaMetadataRetriever()
                    val durationMs = try {
                        retriever.setDataSource(context, Uri.parse(uri))
                        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
                    } catch (_: Exception) { 1L } finally { retriever.release() }
                    durationMs to cachedAudioPeaks(context, Uri.parse(uri))
                }
            }
        }
    }
    val rulerPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE } }
    val clipPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE } }
    val lanes = remember { listOf(1 to "Overlay", 0 to "Main", 2 to "Audio") }
    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Timeline", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(0.25f) },
                enabled = zoom > 0.25f) { Icon(Icons.Default.ZoomOut, "Zoom out timeline") }
            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            FilledTonalIconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(8f) },
                enabled = zoom < 8f) { Icon(Icons.Default.ZoomIn, "Zoom in timeline") }
        }
        Text("Playhead: %.1fs".format(playheadMs / 1000.0), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val viewportPx = with(density) { maxWidth.toPx() }
            val maxScrollPx = (with(density) { widthDp.dp.toPx() } - viewportPx).coerceAtLeast(0f)
            val scrollState = rememberScrollableState { distance ->
                val old = scrollPx
                scrollPx = (scrollPx - distance).coerceIn(0f, maxScrollPx)
                old - scrollPx
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clipToBounds()) {
            Canvas(Modifier.fillMaxWidth().height(32.dp).clipToBounds().scrollable(scrollState, Orientation.Horizontal).pointerInput(total, zoom) {
                detectTapGestures { tap -> onScrub(((tap.x + visibleScroll.value - leftPx) / pxPerMs).toLong().coerceIn(0, total)) }
            }) {
                drawRect(timelineDark)
                val paint = rulerPaint.apply { textSize = 11.sp.toPx() }
                val scroll = visibleScroll.value
                val first = (((scroll - leftPx) / (pxPerMs * 1000f)).toInt() - 1).coerceAtLeast(0)
                val last = (((scroll + size.width - leftPx) / (pxPerMs * 1000f)).toInt() + 1).coerceAtMost((total / 1000L).toInt())
                for (second in first..last) {
                    val x = leftPx + second * pxPerMs * 1000f - scroll
                    if (second % 5 == 0) {
                        drawLine(Color(0xFFE6DEEE), Offset(x, 12.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                        drawContext.canvas.nativeCanvas.drawText("${second}s", x + 2.dp.toPx(), 11.dp.toPx(), paint)
                    } else {
                        drawLine(Color(0xFF625A6C), Offset(x, 21.dp.toPx()), Offset(x, size.height), 1f)
                    }
                }
                val playX = leftPx + playheadMs * pxPerMs - visibleScroll.value
                drawLine(Color.White, Offset(playX, 0f), Offset(playX, size.height), 2.dp.toPx())
            }
            lanes.forEach { (lane, label) ->
                Canvas(Modifier.fillMaxWidth().height(61.dp).clipToBounds().pointerInput(lane, zoom) {
                    awaitEachGesture {
                        val clips = liveClips.value
                        val positions = livePositions.value
                        val durations = liveDurations.value
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val time = ((down.position.x + visibleScroll.value - leftPx) / pxPerMs).toLong().coerceAtLeast(0L)
                        val moving = (laneIndices[lane] ?: emptyList()).lastOrNull { i ->
                            time in positions[i]..(positions[i] + durations[i])
                        }
                        val start = moving?.let { positions[it] } ?: 0L
                        val initialClip = moving?.let { clips[it] }
                        val edgeMs = (14.dp.toPx() / pxPerMs).toLong()
                        val offset = time - start
                        val action = if (moving == null) 0 else if (
                            initialClip?.kind == "text" || initialClip?.kind == "image"
                        ) {
                            // Visual overlays are moved from the body and resized from the right edge.
                            // At low zoom, reserve a narrow handle so the short clip can still move.
                            val rightHandle = minOf(edgeMs, durations[moving] / 3L).coerceAtLeast(1L)
                            if (durations[moving] - offset <= rightHandle) 2 else 3
                        } else when {
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
                                    if (delta == 0L) return
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
                                    val destination = (laneIndices[0] ?: emptyList())
                                        .minByOrNull { kotlin.math.abs(positions[it] + durations[it] / 2 - destinationTime) }
                                    if (destination != null && destination != moving) liveReorder.value(moving, destination)
                                }
                            }
                        }
                    }
                }) {
                    drawRect(timelineDark)
                    val paint = clipPaint.apply { textSize = 12.sp.toPx() }
                    drawContext.canvas.nativeCanvas.drawText(label, 7.dp.toPx(), 32.dp.toPx(), paint)
                    drawLine(Color(0xFF524B5D), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
                    val scroll = visibleScroll.value
                    (laneIndices[lane] ?: emptyList()).forEach { i ->
                        if (leftPx + (positions[i] + durations[i]) * pxPerMs < scroll ||
                            leftPx + positions[i] * pxPerMs > scroll + size.width) return@forEach
                        val x = leftPx + positions[i] * pxPerMs - scroll
                        val w = max(12.dp.toPx(), durations[i] * pxPerMs - 2.dp.toPx())
                        val tint = if (clips[i].kind == "audio") audioColor else if (lane == 0) videoColor else overlayColor
                        drawRect(tint, Offset(x, 8.dp.toPx()), androidx.compose.ui.geometry.Size(w, 44.dp.toPx()))
                        val clip = clips[i]
                        val audioUri = when (clip.kind) {
                            "video", "audio" -> clip.uri
                            "text" -> clip.ttsUri
                            else -> ""
                        }
                        val waveform = waveforms[audioUri]
                        val peaks = waveform?.second.orEmpty()
                        if (peaks.isNotEmpty() && clip.endMs > clip.startMs) {
                            // Use the source's full duration for accurate trim offsets. The waveform
                            // is based on the unmodified source, not the on-screen clip length.
                            val sourceDuration = waveform?.first?.coerceAtLeast(1L) ?: 1L
                            val sourceStart = if (clip.kind == "text") 0L else clip.startMs.coerceAtLeast(0L)
                             val sourceEnd = if (clip.kind == "text") sourceDuration else clip.endMs.coerceAtMost(sourceDuration)
                             // Generated speech does not stretch when the visual text clip is extended.
                             // Keep waveform bars at their true timeline positions, leaving silence empty.
                             val audibleMs = if (clip.kind == "text") minOf(durations[i], sourceDuration) else durations[i]
                             val waveformWidth = if (clip.kind == "text") {
                                 (w * audibleMs.toFloat() / durations[i].coerceAtLeast(1L)).coerceIn(0f, w)
                             } else w
                             val bars = ((waveformWidth / 3.dp.toPx()).toInt()).coerceIn(1, 240)
                             val barWidth = waveformWidth / bars
                             clipRect(x, 8.dp.toPx(), x + waveformWidth, 52.dp.toPx()) {
                                 for (bar in 0 until bars) {
                                     val fraction = (bar + 0.5f) / bars
                                     val time = sourceStart + ((sourceEnd - sourceStart) * fraction).toLong()
                                     val index = ((time.toDouble() / sourceDuration) * peaks.size)
                                        .toInt().coerceIn(0, peaks.lastIndex)
                                    val amplitude = peaks[index].coerceIn(0f, 1f)
                                    val halfHeight = (amplitude * 16.dp.toPx()).coerceAtLeast(1.dp.toPx())
                                    val barX = x + (bar + 0.5f) * barWidth
                                    drawLine(Color.White.copy(alpha = if (i == selectedIndex) 0.68f else 0.43f),
                                        Offset(barX, 30.dp.toPx() - halfHeight),
                                        Offset(barX, 30.dp.toPx() + halfHeight),
                                        strokeWidth = (barWidth * 0.6f).coerceAtLeast(1f))
                                }
                            }
                        }
                        if (i == selectedIndex) {
                            drawRect(Color.White.copy(alpha = 0.8f), Offset(x, 8.dp.toPx()),
                                androidx.compose.ui.geometry.Size(4.dp.toPx(), 44.dp.toPx()))
                            drawRect(Color.White.copy(alpha = 0.8f), Offset(x + w - 4.dp.toPx(), 8.dp.toPx()),
                                androidx.compose.ui.geometry.Size(4.dp.toPx(), 44.dp.toPx()))
                            drawRect(Color.White, Offset(x, 8.dp.toPx()), androidx.compose.ui.geometry.Size(w, 44.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        }
                        val clipText = paint.let { android.text.TextUtils.ellipsize(names[i], android.text.TextPaint(it), (w - 10.dp.toPx()).coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString() }
                        drawContext.canvas.nativeCanvas.drawText(clipText, x + 5.dp.toPx(), 34.dp.toPx(), paint)
                    }
                    val playX = leftPx + playheadMs * pxPerMs - visibleScroll.value
                    drawLine(Color.White, Offset(playX, 0f), Offset(playX, size.height), 2.dp.toPx())
                }
            }
        }
    }
}
}
