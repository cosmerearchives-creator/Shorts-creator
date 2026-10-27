package com.cosmerearchives.shortscreator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteOrder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.core.view.WindowCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

private val ivory = Color(0xFFF5F4EF)
private val lavender = Color(0xFFEFE9F5)
private val purple = Color(0xFF685B86)
private val ink = Color(0xFF353238)
private val dark = Color(0xFF292630)

data class Clip(val uri: String, val kind: String, val track: Int, val startMs: Long = 0L, val endMs: Long = 0L, val positionMs: Long = 0L, val pipScale: Float = 0.35f, val pipX: Float = 0.55f, val pipY: Float = 0.55f, val caption: String = "", val textSize: Float = 62f, val textColor: Int = -1, val textX: Float = 0f, val textY: Float = -0.65f, val imageX: Float = 0f, val imageY: Float = 0f, val imageScale: Float = 1f)
data class PendingImport(val uri: String, val kind: String, val track: Int, val durationMs: Long)
data class Project(val name: String, val ratio: String, val clips: List<Clip> = emptyList())

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("projects", MODE_PRIVATE) }
    private val writeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var queuedWrite: Runnable? = null
    private var latestSnapshot: List<Project>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.navigationBarColor = android.graphics.Color.rgb(245, 244, 239)
        setContent {
            var projects by remember { mutableStateOf(loadProjects()) }
            var selected by remember { mutableIntStateOf(-1) }
            var newDialog by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var selectedTrack by remember { mutableIntStateOf(0) }
            var updateStatus by remember { mutableStateOf("") }
            var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
            var downloading by remember { mutableStateOf(false) }
            var exportStatus by remember { mutableStateOf("") }
            var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
            LaunchedEffect(Unit) {
                thread { try {
                    val found = AppUpdater.latest(this@MainActivity)
                    runOnUiThread { availableUpdate = found; updateStatus = if (found != null) "Update available: ${found.version}" else "Up to date" }
                } catch (_: Exception) { /* quiet startup failure; manual check shows errors */ } }
            }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null && selected in projects.indices) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) { }
                    val mime = contentResolver.getType(uri).orEmpty()
                    val kind = if (mime.startsWith("audio/")) "audio" else if (mime.startsWith("image/")) "image" else "video"
                    val duration = if (kind == "image") 5000L else getDuration(uri)
                    pendingImport = PendingImport(uri.toString(), kind, if (kind == "audio") 2 else selectedTrack, duration)

                }
            }
            MaterialTheme(colorScheme = lightColorScheme(primary = purple, background = ivory, surface = Color.White, onSurface = ink)) {
                Surface(color = ivory, modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected >= 0 || showSettings) {
                                IconButton(onClick = { selected = -1; showSettings = false }) { Icon(Icons.Default.ArrowBack, "Back") }
                            }
                            Text("Shorts Creator", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { showSettings = !showSettings; selected = -1 }) { Icon(Icons.Default.Settings, "Settings") }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (showSettings) {
                            Text("Settings", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                updateStatus = "Checking GitHub Releases…"
                                thread {
                                    try {
                                        val found = AppUpdater.latest(this@MainActivity)
                                        runOnUiThread { availableUpdate = found; updateStatus = if (found != null) "Update available: ${found.version}" else "Up to date" }
                                    } catch (e: Exception) { runOnUiThread { updateStatus = "Update check failed: ${e.message}" } }
                                }
                            }) { Text("Check for updates") }
                            Text(updateStatus)
                            availableUpdate?.let { update ->
                                Button(enabled = !downloading, onClick = {
                                    downloading = true
                                    updateStatus = "Downloading update…"
                                    thread { try {
                                        AppUpdater.downloadAndInstall(this@MainActivity, update) { percentage -> runOnUiThread { updateStatus = "Downloading: $percentage%" } }
                                        runOnUiThread { updateStatus = "Approve installation in Android"; downloading = false }
                                    } catch (e: Exception) { runOnUiThread { updateStatus = "Update failed: ${e.message}"; downloading = false } } }
                                }) { Text(if (downloading) "Downloading…" else "Download and install ${update.version}") }
                            }
                            OutlinedButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cosmerearchives-creator/Shorts-creator/releases/latest"))) }) { Text("Open latest release") }
                            Text("Updates require Android installation approval. Install only official signed releases.", color = ink)
                        } else if (selected !in projects.indices) {
                            if (availableUpdate != null) {
                                Card(colors = CardDefaults.cardColors(containerColor = lavender)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text(updateStatus)
                                        TextButton(onClick = { showSettings = true; selected = -1 }) { Text("Install update") }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                            Text("Your projects", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { newDialog = true }) { Icon(Icons.Default.Add, null); Text(" New project") }
                            Spacer(Modifier.height(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                projects.indices.forEach { index ->
                                    Card(onClick = { selected = index }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                            Text(projects[index].name, fontWeight = FontWeight.Bold)
                                            Text("${projects[index].ratio} · ${projects[index].clips.size} media items")
                                        }
                                    }
                                }
                            }
                        } else {
                            val project = projects[selected]
                            Text(project.name + " · " + project.ratio, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            var activeClip by remember(selected) { mutableIntStateOf(0) }
                            var playheadMs by remember(selected) { mutableLongStateOf(0L) }
                            val currentClip = project.clips.getOrNull(activeClip)
                            var previewStart = 0L
                            val previewIndex = project.clips.indices.firstOrNull { i ->
                                if (project.clips[i].track != 0) false else {
                                    val span = (project.clips[i].endMs - project.clips[i].startMs).coerceAtLeast(0L)
                                    val inside = playheadMs >= previewStart && playheadMs < previewStart + span
                                    if (!inside) previewStart += span
                                    inside
                                }
                            }
                            val previewClip = previewIndex?.let { project.clips[it] }
                            var previewSize by remember { mutableStateOf(IntSize.Zero) }
                            val frameContext = LocalContext.current
                            Box(Modifier.fillMaxWidth().height(240.dp).background(dark, RoundedCornerShape(16.dp))
                                .onSizeChanged { previewSize = it }) {
                                if (previewClip != null && previewClip.kind == "video") {
                                    val seekMs = (playheadMs - previewStart).coerceAtLeast(0L)
                                    key(previewClip.uri, previewClip.startMs, previewClip.endMs) {
                                        VideoPreview(previewClip.uri, previewClip.startMs, previewClip.endMs, seekMs,
                                            Modifier.fillMaxSize())
                                    }
                                } else {
                                    Text("Import a main video to preview", color = Color.White,
                                        modifier = Modifier.align(Alignment.Center))
                                }
                                project.clips.forEachIndexed { clipIndex, overlay ->
                                    if (overlay.track == 1 &&
                                        playheadMs >= overlay.positionMs &&
                                        playheadMs < overlay.positionMs + (overlay.endMs - overlay.startMs).coerceAtLeast(0L)) {
                                        when (overlay.kind) {
                                            "text" -> {
                                                val x = ((overlay.textX + 1f) / 2f * previewSize.width).toInt()
                                                val y = ((1f - overlay.textY) / 2f * previewSize.height).toInt()
                                                Text(overlay.caption, color = Color(overlay.textColor),
                                                    fontSize = (overlay.textSize * 0.42f).sp,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.align(Alignment.TopStart)
                                                        .offset { androidx.compose.ui.unit.IntOffset(x - previewSize.width / 4, y - 24) }
                                                        .width(180.dp)
                                                        .pointerInput(clipIndex, previewSize) {
                                                            detectDragGestures { change, drag ->
                                                                change.consume()
                                                                if (previewSize.width > 0 && previewSize.height > 0) {
                                                                    val cs = projects[selected].clips.toMutableList()
                                                                    val item = cs.getOrNull(clipIndex)
                                                                    if (item != null && item.kind == "text") {
                                                                        cs[clipIndex] = item.copy(
                                                                            textX = (item.textX + 2f * drag.x / previewSize.width).coerceIn(-0.85f, 0.85f),
                                                                            textY = (item.textY - 2f * drag.y / previewSize.height).coerceIn(-0.85f, 0.85f))
                                                                        val updated = projects.toMutableList()
                                                                        updated[selected] = projects[selected].copy(clips = cs)
                                                                        projects = updated
                                                                        saveProjects(updated)
                                                                    }
                                                                }
                                                            }
                                                        })
                                            }
                                            "image" -> {
                                                var bitmap by remember(overlay.uri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                                                LaunchedEffect(overlay.uri) {
                                                    bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        previewImageCache.get(frameContext, Uri.parse(overlay.uri))
                                                    }
                                                }
                                                val previewBitmap = bitmap
                                                if (previewBitmap != null) {
                                                    val dx = (overlay.imageX * previewSize.width / 2f).toInt()
                                                    val dy = (-overlay.imageY * previewSize.height / 2f).toInt()
                                                    Image(previewBitmap, "Image overlay",
                                                        modifier = Modifier.align(Alignment.Center)
                                                            .offset { androidx.compose.ui.unit.IntOffset(dx, dy) }
                                                            .fillMaxWidth((0.7f * overlay.imageScale).coerceIn(0.1f, 1f)).height((150f * overlay.imageScale).dp)
                                                            .pointerInput(clipIndex, previewSize) {
                                                                detectTransformGestures { _, pan, zoom, _ ->
                                                                    if (previewSize.width > 0 && previewSize.height > 0) {
                                                                        val cs = projects[selected].clips.toMutableList()
                                                                        val item = cs.getOrNull(clipIndex)
                                                                        if (item != null && item.kind == "image") {
                                                                            cs[clipIndex] = item.copy(
                                                                                imageX = (item.imageX + 2f * pan.x / previewSize.width).coerceIn(-0.9f, 0.9f),
                                                                                imageY = (item.imageY - 2f * pan.y / previewSize.height).coerceIn(-0.9f, 0.9f),
                                                                                imageScale = (item.imageScale * zoom).coerceIn(0.2f, 2f))
                                                                            val updated = projects.toMutableList()
                                                                            updated[selected] = projects[selected].copy(clips = cs)
                                                                            projects = updated
                                                                            saveProjects(updated)
                                                                        }
                                                                    }
                                                                }
                                                            })
                                                }
                                            }
                                            "video" -> {
                                                val pipTime = (playheadMs - overlay.positionMs).coerceAtLeast(0L)
                                                val pipWidth = (previewSize.width * overlay.pipScale).toInt().coerceAtLeast(1)
                                                val px = ((overlay.pipX + 1f) * previewSize.width / 2f - pipWidth / 2f).toInt()
                                                val py = ((1f - overlay.pipY) * previewSize.height / 2f - 40).toInt()
                                                key(overlay.uri, overlay.startMs, overlay.endMs) {
                                                    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(px, py) }
                                                        .width(with(androidx.compose.ui.platform.LocalDensity.current) { pipWidth.toDp() })
                                                        .height(85.dp)
                                                        .pointerInput(clipIndex, previewSize) {
                                                            detectDragGestures { change, drag ->
                                                                change.consume()
                                                                if (previewSize.width > 0 && previewSize.height > 0) {
                                                                    val cs = projects[selected].clips.toMutableList()
                                                                    val item = cs.getOrNull(clipIndex)
                                                                    if (item != null && item.kind == "video") {
                                                                        cs[clipIndex] = item.copy(
                                                                            pipX = (item.pipX + 2f * drag.x / previewSize.width).coerceIn(-0.9f, 0.9f),
                                                                            pipY = (item.pipY - 2f * drag.y / previewSize.height).coerceIn(-0.9f, 0.9f))
                                                                        val updated = projects.toMutableList()
                                                                        updated[selected] = projects[selected].copy(clips = cs)
                                                                        projects = updated
                                                                        saveProjects(updated)
                                                                    }
                                                                }
                                                            }
                                                        }) {
                                                        VideoPreview(overlay.uri, overlay.startMs, overlay.endMs, pipTime, Modifier.fillMaxSize())
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Text("Preview shows overlays at the playhead. Drag captions, images and PiP video to reposition; select PiP to resize with its slider.",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { selectedTrack = 0; launcher.launch(arrayOf("video/*", "audio/*")) }) { Text("Import") }
                                OutlinedButton(onClick = { selectedTrack = 1; launcher.launch(arrayOf("video/*", "image/*", "audio/*")) }) { Text("Add overlay") }
                                OutlinedButton(onClick = {
                                    val updated = projects.toMutableList()
                                    val cs = project.clips + Clip("", "text", 1, 0L, 5000L, playheadMs, caption = "New title")
                                    updated[selected] = project.copy(clips = cs)
                                    projects = updated
                                    activeClip = cs.lastIndex
                                    saveProjects(updated)
                                }) { Text("Add text") }
                            }
                            if (currentClip != null && currentClip.kind == "video" && currentClip.endMs > currentClip.startMs) {
                                val duration = remember(currentClip.uri) { getDuration(Uri.parse(currentClip.uri)) }
                                if (duration > 0) {
                                    Text("Trim selected clip", fontWeight = FontWeight.SemiBold)
                                    Text("Start: ${currentClip.startMs / 1000.0}s · End: ${currentClip.endMs / 1000.0}s", style = MaterialTheme.typography.bodySmall)
                                    Text("In point", style = MaterialTheme.typography.labelSmall)
                                    Slider(
                                        value = currentClip.startMs.toFloat(),
                                        onValueChange = { value ->
                                            val updated = projects.toMutableList()
                                            val cs = project.clips.toMutableList()
                                            cs[activeClip] = currentClip.copy(startMs = value.toLong().coerceAtMost(currentClip.endMs - 100L))
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                        },
                                        onValueChangeFinished = { saveProjects(projects) },
                                        valueRange = 0f..(currentClip.endMs - 100L).coerceAtLeast(1L).toFloat()
                                    )
                                    Text("Out point", style = MaterialTheme.typography.labelSmall)
                                    Slider(
                                        value = currentClip.endMs.toFloat(),
                                        onValueChange = { value ->
                                            val updated = projects.toMutableList()
                                            val cs = project.clips.toMutableList()
                                            cs[activeClip] = currentClip.copy(endMs = value.toLong().coerceAtLeast(currentClip.startMs + 100L))
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                        },
                                        onValueChangeFinished = { saveProjects(projects) },
                                        valueRange = (currentClip.startMs + 100L).toFloat()..duration.toFloat()
                                    )
                                    Button(onClick = {
                                        exportStatus = "Exporting selected clip…"
                                        try {
                                            Exporter.export(this@MainActivity, currentClip.uri, currentClip.startMs, currentClip.endMs, project.ratio) {
                                                exportStatus = it
                                            }
                                        } catch (e: Exception) { exportStatus = "Export failed: ${e.message}" }
                                    }) { Text("Export selected clip") }
                                    if (exportStatus.isNotBlank()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            val mainClips = project.clips.filter { it.track == 0 }
                            val overlayClips = project.clips.filter { it.track == 1 }
                            val unsupportedOverlays = overlayClips.any { it.kind != "image" }
                            Button(
                                enabled = mainClips.isNotEmpty() && mainClips.all { it.kind == "video" && it.endMs > it.startMs },
                                onClick = {
                                    exportStatus = "Exporting main timeline…"
                                    try {
                                        Exporter.exportTimeline(this@MainActivity, project.clips, project.ratio) { status ->
                                            runOnUiThread { exportStatus = status }
                                        }
                                    } catch (e: Exception) { exportStatus = "Timeline export failed: ${e.message}" }
                                }
                            ) { Text("Export complete timeline") }
                            if (overlayClips.isNotEmpty()) Text(
                                "Note: V0.10 exports video overlays as small picture-in-picture windows in the upper right. Select a video overlay to adjust its size and position.",
                                style = MaterialTheme.typography.bodySmall, color = Color(0xFF8D4738)
                            )
                            if (exportStatus.isNotBlank()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
                            EditingTimeline(
                                clips = project.clips,
                                selectedIndex = activeClip,
                                playheadMs = playheadMs,
                                onSelect = { i -> activeClip = i; playheadMs = 0L },
                                onScrub = { time -> playheadMs = time },
                                onMove = { index, position ->
                                    val cs = projects[selected].clips.toMutableList()
                                    if (index in cs.indices && cs[index].track != 0) {
                                        cs[index] = cs[index].copy(positionMs = position)
                                        val updated = projects.toMutableList()
                                        updated[selected] = projects[selected].copy(clips = cs)
                                        projects = updated
                                        saveProjects(updated)
                                    }
                                }
                            )
                            if (currentClip != null && (currentClip.kind == "video" || currentClip.kind == "audio") && currentClip.endMs > currentClip.startMs) {
                                val sourceDuration = remember(currentClip.uri) { getDuration(Uri.parse(currentClip.uri)) }
                                if (sourceDuration > 100L) {
                                    Text("Timeline clip trim · ${(currentClip.endMs - currentClip.startMs) / 1000.0}s selected",
                                        fontWeight = FontWeight.SemiBold)
                                    TimelineMediaStrip(currentClip.uri, currentClip.kind, sourceDuration,
                                        currentClip.startMs, currentClip.endMs) { start, end ->
                                        val cs = project.clips.toMutableList()
                                        cs[activeClip] = currentClip.copy(startMs = start, endMs = end)
                                        val updated = projects.toMutableList()
                                        updated[selected] = project.copy(clips = cs)
                                        projects = updated
                                        saveProjects(updated)
                                    }
                                    RangeSlider(
                                        value = currentClip.startMs.toFloat()..currentClip.endMs.toFloat(),
                                        onValueChange = { range ->
                                            val cs = project.clips.toMutableList()
                                            val start = range.start.toLong().coerceIn(0L, sourceDuration - 100L)
                                            val end = range.endInclusive.toLong().coerceIn(start + 100L, sourceDuration)
                                            cs[activeClip] = currentClip.copy(startMs = start, endMs = end)
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                        },
                                        onValueChangeFinished = { saveProjects(projects) },
                                        valueRange = 0f..sourceDuration.toFloat(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = {
                                            val cs = project.clips.toMutableList()
                                            cs[activeClip] = currentClip.copy(startMs = (currentClip.startMs - 33L).coerceAtLeast(0L))
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                            saveProjects(updated)
                                        }) { Text("In −") }
                                        TextButton(onClick = {
                                            val cs = project.clips.toMutableList()
                                            cs[activeClip] = currentClip.copy(startMs = (currentClip.startMs + 33L).coerceAtMost(currentClip.endMs - 100L))
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                            saveProjects(updated)
                                        }) { Text("In +") }
                                        TextButton(onClick = {
                                            val cs = project.clips.toMutableList()
                                            cs[activeClip] = currentClip.copy(endMs = (currentClip.endMs - 33L).coerceAtLeast(currentClip.startMs + 100L))
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                            saveProjects(updated)
                                        }) { Text("Out −") }
                                        TextButton(onClick = {
                                            val cs = project.clips.toMutableList()
                                            cs[activeClip] = currentClip.copy(endMs = (currentClip.endMs + 33L).coerceAtMost(sourceDuration))
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                            saveProjects(updated)
                                        }) { Text("Out +") }
                                    }
                                }
                            }
                            if (currentClip?.kind == "text" && currentClip.track == 1) {
                                OutlinedTextField(value = currentClip.caption, label = { Text("Caption text") },
                                    onValueChange = { value ->
                                        val cs = project.clips.toMutableList()
                                        cs[activeClip] = currentClip.copy(caption = value.take(160))
                                        val updated = projects.toMutableList()
                                        updated[selected] = project.copy(clips = cs)
                                        projects = updated
                                        saveProjects(updated)
                                    }, modifier = Modifier.fillMaxWidth())
                                Text("Font size")
                                Slider(value = currentClip.textSize.coerceIn(24f, 110f), valueRange = 24f..110f,
                                    onValueChange = { v ->
                                        val cs = project.clips.toMutableList()
                                        cs[activeClip] = currentClip.copy(textSize = v)
                                        val updated = projects.toMutableList()
                                        updated[selected] = project.copy(clips = cs)
                                        projects = updated
                                    }, onValueChangeFinished = { saveProjects(projects) })
                                Text("Text color")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("White" to -1, "Black" to -16777216, "Yellow" to -256, "Purple" to -10066330).forEach { (label, value) ->
                                        TextButton(onClick = {
                                            val cs = project.clips.toMutableList()
                                            cs[activeClip] = currentClip.copy(textColor = value)
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                            saveProjects(updated)
                                        }) { Text(label) }
                                    }
                                }
                                listOf("Text horizontal" to 0, "Text vertical" to 1).forEach { (label, axis) ->
                                    Text(label)
                                    Slider(value = if (axis == 0) currentClip.textX else currentClip.textY,
                                        valueRange = -0.85f..0.85f,
                                        onValueChange = { v ->
                                            val cs = project.clips.toMutableList()
                                            cs[activeClip] = if (axis == 0) currentClip.copy(textX = v) else currentClip.copy(textY = v)
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                        }, onValueChangeFinished = { saveProjects(projects) })
                                }
                                Text("Caption duration: ${currentClip.endMs / 1000.0}s")
                                Slider(value = currentClip.endMs.coerceIn(500L, 30000L).toFloat(),
                                    valueRange = 500f..30000f,
                                    onValueChange = { value ->
                                        val cs = project.clips.toMutableList()
                                        cs[activeClip] = currentClip.copy(endMs = value.toLong())
                                        val updated = projects.toMutableList()
                                        updated[selected] = project.copy(clips = cs)
                                        projects = updated
                                    }, onValueChangeFinished = { saveProjects(projects) })
                            }
                            if (currentClip?.kind == "video" && currentClip.track == 1) {
                                Text("PiP size: ${(currentClip.pipScale * 100).toInt()}%")
                                listOf("Size", "Horizontal", "Vertical").forEachIndexed { i, title ->
                                    val value = when(i) { 0 -> currentClip.pipScale; 1 -> currentClip.pipX; else -> currentClip.pipY }
                                    Text(title)
                                    Slider(value = value, valueRange = if (i == 0) 0.15f..0.75f else -0.9f..0.9f,
                                        onValueChange = { v ->
                                            val cs = project.clips.toMutableList()
                                            cs[activeClip] = when(i) {
                                                0 -> currentClip.copy(pipScale = v)
                                                1 -> currentClip.copy(pipX = v)
                                                else -> currentClip.copy(pipY = v)
                                            }
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                        }, onValueChangeFinished = { saveProjects(projects) })
                                }
                            }
                            if (currentClip?.kind == "image" && currentClip.track == 1) {
                                Text("Image duration: ${currentClip.endMs / 1000.0}s")
                                Slider(
                                    value = currentClip.endMs.coerceIn(500L, 30000L).toFloat(),
                                    onValueChange = { value ->
                                        val cs = project.clips.toMutableList()
                                        cs[activeClip] = currentClip.copy(endMs = value.toLong())
                                        val updated = projects.toMutableList()
                                        updated[selected] = project.copy(clips = cs)
                                        projects = updated
                                    },
                                    onValueChangeFinished = { saveProjects(projects) },
                                    valueRange = 500f..30000f
                                )
                            }
                            if (currentClip != null && currentClip.track != 0) {
                                Text("Overlay position: ${currentClip.positionMs / 1000.0}s", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = currentClip.positionMs.toFloat().coerceIn(0f, 60000f),
                                    onValueChange = { value ->
                                        val updated = projects.toMutableList()
                                        val cs = project.clips.toMutableList()
                                        cs[activeClip] = currentClip.copy(positionMs = value.toLong())
                                        updated[selected] = project.copy(clips = cs)
                                        projects = updated
                                    },
                                    onValueChangeFinished = { saveProjects(projects) },
                                    valueRange = 0f..60000f
                                )
                            }
                            if (currentClip != null) {
                                val durations = project.clips.map { (it.endMs - it.startMs).coerceAtLeast(0L) }
                                val clipStart = if (currentClip.track == 0) project.clips.indices
                                    .filter { it < activeClip && project.clips[it].track == 0 }.sumOf { durations[it] }
                                    else currentClip.positionMs
                                val splitOffset = playheadMs - clipStart
                                val canSplit = currentClip.kind == "video" && splitOffset >= 100L &&
                                    splitOffset <= durations[activeClip] - 100L
                                Button(enabled = canSplit, onClick = {
                                    val cut = currentClip.startMs + splitOffset
                                    val cs = project.clips.toMutableList()
                                    cs[activeClip] = currentClip.copy(endMs = cut)
                                    cs.add(activeClip + 1, currentClip.copy(startMs = cut,
                                        positionMs = if (currentClip.track == 0) 0L else playheadMs))
                                    val updated = projects.toMutableList()
                                    updated[selected] = project.copy(clips = cs)
                                    projects = updated
                                    saveProjects(updated)
                                    activeClip += 1
                                }) { Text("Split at playhead") }
                                if (currentClip.track == 0) {
                                    val mainIndices = project.clips.indices.filter { project.clips[it].track == 0 }
                                    val place = mainIndices.indexOf(activeClip)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(enabled = place > 0, onClick = {
                                            val cs = project.clips.toMutableList()
                                            val previous = mainIndices[place - 1]
                                            val item = cs.removeAt(activeClip)
                                            cs.add(previous, item)
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                            saveProjects(updated)
                                            activeClip = previous
                                        }) { Text("Move left") }
                                        OutlinedButton(enabled = place >= 0 && place < mainIndices.lastIndex, onClick = {
                                            val cs = project.clips.toMutableList()
                                            val next = mainIndices[place + 1]
                                            val item = cs.removeAt(activeClip)
                                            cs.add(next, item)
                                            val updated = projects.toMutableList()
                                            updated[selected] = project.copy(clips = cs)
                                            projects = updated
                                            saveProjects(updated)
                                            activeClip = next
                                        }) { Text("Move right") }
                                    }
                                }
                            }
                            Spacer(Modifier.height(48.dp))
                            Text("V0.5: scrollable timeline, splitting and clip rearrangement. Full multitrack export remains a later milestone.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    pendingImport?.let { pending ->
                        ImportReviewDialog(pending,
                            onCancel = { pendingImport = null },
                            onConfirm = { start, end ->
                                if (selected in projects.indices) {
                                    val updated = projects.toMutableList()
                                    val project = updated[selected]
                                    updated[selected] = project.copy(clips = project.clips +
                                        Clip(pending.uri, pending.kind, pending.track, start, end))
                                    projects = updated
                                    saveProjects(updated)
                                }
                                pendingImport = null
                            })
                    }
                    if (newDialog) {
                        var projectName by remember { mutableStateOf("") }
                        var ratio by remember { mutableStateOf("9:16") }
                        AlertDialog(
                            onDismissRequest = { newDialog = false },
                            title = { Text("Create project") },
                            text = {
                                Column {
                                    OutlinedTextField(projectName, { projectName = it }, label = { Text("Project name") }, singleLine = true)
                                    Spacer(Modifier.height(8.dp))
                                    listOf("9:16", "16:9", "1:1", "4:5").forEach { option ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(ratio == option, onClick = { ratio = option })
                                            Text(option)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    projects = projects + Project(projectName.ifBlank { "Untitled project" }, ratio)
                                    saveProjects(projects)
                                    selected = projects.lastIndex
                                    newDialog = false
                                }) { Text("Create") }
                            },
                            dismissButton = { TextButton(onClick = { newDialog = false }) { Text("Cancel") } }
                        )
                    }
                }
            }
        }
    }
    private fun getDuration(uri: Uri): Long = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { retriever.release() }
    } catch (_: Exception) { 0L }

    private fun loadProjects(): List<Project> {
        val primary = prefs.getString("data", "[]") ?: "[]"
        val backup = prefs.getString("data_backup", null)
        return try {
            parseProjects(primary)
        } catch (_: Exception) {
            if (backup == null) emptyList()
            else try { parseProjects(backup) } catch (_: Exception) { emptyList() }
        }
    }

    private fun parseProjects(json: String): List<Project> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val clips = obj.optJSONArray("clips") ?: JSONArray()
            Project(obj.getString("name"), obj.getString("ratio"), (0 until clips.length()).map { j ->
                val c = clips.getJSONObject(j)
                Clip(c.getString("uri"), c.getString("kind"), c.getInt("track"), c.optLong("startMs", 0L), c.optLong("endMs", 0L), c.optLong("positionMs", 0L), c.optDouble("pipScale", 0.35).toFloat(), c.optDouble("pipX", 0.55).toFloat(), c.optDouble("pipY", 0.55).toFloat(), c.optString("caption", ""), c.optDouble("textSize", 62.0).toFloat(), c.optInt("textColor", -1), c.optDouble("textX", 0.0).toFloat(), c.optDouble("textY", -0.65).toFloat(), c.optDouble("imageX", 0.0).toFloat(), c.optDouble("imageY", 0.0).toFloat(), c.optDouble("imageScale", 1.0).toFloat())
            })
        }
    }

    private fun saveProjects(projects: List<Project>) {
        val snapshot = projects.toList()
        latestSnapshot = snapshot
        queuedWrite?.let(writeHandler::removeCallbacks)
        queuedWrite = Runnable { writeProjects(snapshot) }
        writeHandler.postDelayed(queuedWrite!!, 300L)
    }

    override fun onStop() {
        queuedWrite?.let(writeHandler::removeCallbacks)
        latestSnapshot?.let(::writeProjects)
        super.onStop()
    }

    private fun writeProjects(projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            val clips = JSONArray()
            p.clips.forEach { c -> clips.put(JSONObject().put("uri", c.uri).put("kind", c.kind).put("track", c.track).put("startMs", c.startMs).put("endMs", c.endMs).put("positionMs", c.positionMs).put("pipScale", c.pipScale.toDouble()).put("pipX", c.pipX.toDouble()).put("pipY", c.pipY.toDouble()).put("caption", c.caption).put("textSize", c.textSize.toDouble()).put("textColor", c.textColor).put("textX", c.textX.toDouble()).put("textY", c.textY.toDouble()).put("imageX", c.imageX.toDouble()).put("imageY", c.imageY.toDouble()).put("imageScale", c.imageScale.toDouble())) }
            arr.put(JSONObject().put("name", p.name).put("ratio", p.ratio).put("clips", clips))
        }
        val serialized = arr.toString()
        val previous = prefs.getString("data", null)
        if (previous == serialized) return
        val editor = prefs.edit()
        if (previous != null) {
            try {
                JSONArray(previous)
                editor.putString("data_backup", previous)
            } catch (_: Exception) { }
        }
        editor.putString("data", serialized).apply()
    }


}

@Composable
private fun VideoPreview(source: String, startMs: Long, endMs: Long, playheadMs: Long, modifier: Modifier = Modifier.fillMaxWidth().height(240.dp)) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(source, startMs, endMs) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.Builder().setUri(Uri.parse(source))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build())
            playWhenReady = false
            prepare()
        }
    }
    LaunchedEffect(player, playheadMs) {
        val target = playheadMs.coerceIn(0L, (endMs - startMs).coerceAtLeast(0L))
        // Avoid expensive decoder seeks for minor timeline state changes.
        if (kotlin.math.abs(player.currentPosition - target) > 80L) player.seekTo(target)
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply {
            this.player = player
            useController = false
            setKeepContentOnPlayerReset(true)
        } },
        update = { view -> if (view.player !== player) view.player = player },
        modifier = modifier.background(dark)
    )
}

@Composable
private fun ImportReviewDialog(item: PendingImport, onCancel: () -> Unit, onConfirm: (Long, Long) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val duration = item.durationMs
    var startMs by remember(item.uri) { mutableLongStateOf(0L) }
    var endMs by remember(item.uri) { mutableLongStateOf(duration) }
    val player = remember(item.uri) {
        if (item.kind == "image") null else ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(item.uri)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    var thumbnails by remember(item.uri) { mutableStateOf<List<androidx.compose.ui.graphics.ImageBitmap>>(emptyList()) }
    LaunchedEffect(item.uri) {
        thumbnails = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (item.kind != "video" || duration <= 0) emptyList() else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(item.uri))
                (0 until 7).mapNotNull { index ->
                    retriever.getFrameAtTime(duration * index * 1000L / 6L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { raw ->
                        val scaled = Bitmap.createScaledBitmap(raw, 100, 64, false)
                        if (scaled !== raw) raw.recycle()
                        scaled.asImageBitmap()
                    }
                }
            } catch (_: Exception) { emptyList() } finally { retriever.release() }
        }
        }
    }
    var waveform by remember(item.uri) { mutableStateOf<List<Float>>(emptyList()) }
    LaunchedEffect(item.uri) {
        if (item.kind == "audio") {
            waveform = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cachedAudioPeaks(context, Uri.parse(item.uri))
            }
        }
    }
    var waveformLoaded by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(waveform) { if (waveform.isNotEmpty()) waveformLoaded = true }
    var rangePlaying by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(player, rangePlaying, endMs) {
        while (rangePlaying && player != null) {
            if (player.currentPosition >= endMs || !player.isPlaying) {
                player.pause()
                rangePlaying = false
            }
            kotlinx.coroutines.delay(50L)
        }
    }
    var speechQuery by remember(item.uri) { mutableStateOf("") }
    var transcriptWords by remember(item.uri) { mutableStateOf<List<SpokenWord>?>(null) }
    var speechStatus by remember(item.uri) { mutableStateOf("Not scanned") }
    var speechError by remember(item.uri) { mutableStateOf(false) }
    var showTranscript by remember(item.uri) { mutableStateOf(false) }
    var beforeSeconds by remember(item.uri) { mutableFloatStateOf(2f) }
    var afterSeconds by remember(item.uri) { mutableFloatStateOf(2f) }
    var selectedMatch by remember(item.uri) { mutableIntStateOf(-1) }
    var scanningSpeech by remember(item.uri) { mutableStateOf(false) }
    val speechScope = rememberCoroutineScope()
    val recognizer = remember(context) { NativeWhisper(context) }
    LaunchedEffect(item.uri) {
        transcriptWords = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            TranscriptCache(context).load(TranscriptCache(context).sourceKey(Uri.parse(item.uri), recognizer.modelId))
        }
        if (transcriptWords != null) speechStatus = "Cached transcript loaded: ${transcriptWords!!.size} timed words"
    }
    val speechMatches = remember(transcriptWords, speechQuery) {
        SpeechSearch.find(transcriptWords.orEmpty(), speechQuery)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Preview and trim " + item.kind) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.kind == "video" && player != null) {
                    AndroidView(factory = { ctx -> PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    } }, modifier = Modifier.fillMaxWidth().height(210.dp).background(dark))
                } else if (item.kind == "audio" && player != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Audio preview", modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                            Text(if (player.isPlaying) "Pause" else "Play")
                        }
                    }
                    Text("Use the preview controls to listen, then choose the section to import.")
                } else {
                    Text("Image selected (images use a default 5-second duration).")
                }
                if (item.kind != "image" && duration > 0L) {
                    Text(if (item.kind == "video") "Video trim range" else "Audio trim range",
                        style = MaterialTheme.typography.labelMedium)
                    var stripWidth by remember { mutableIntStateOf(0) }
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val handleWidth = with(density) { 14.dp.toPx() }
                    Box(Modifier.fillMaxWidth().height(66.dp)
                        .background(lavender, RoundedCornerShape(6.dp))
                        .onSizeChanged { stripWidth = it.width }) {
                        if (item.kind == "video" && thumbnails.isNotEmpty()) {
                            Row(Modifier.fillMaxSize()) {
                                thumbnails.forEach { bitmap ->
                                    Image(bitmap, "Video frame", Modifier.weight(1f).fillMaxHeight(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                }
                            }
                        } else if (item.kind == "audio" && waveform.isNotEmpty()) {
                            Canvas(Modifier.fillMaxSize()) {
                                val step = size.width / waveform.size
                                waveform.forEachIndexed { i, peak ->
                                    val half = (peak.coerceIn(0f, 1f) * size.height * 0.46f).coerceAtLeast(1f)
                                    val x = (i + 0.5f) * step
                                    drawLine(purple, Offset(x, size.height / 2f - half),
                                        Offset(x, size.height / 2f + half),
                                        strokeWidth = step.coerceAtLeast(1f))
                                }
                            }
                        } else {
                            Text(if (item.kind == "audio") "Waveform unavailable or loading…" else "Frames unavailable",
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (stripWidth > 0) {
                            val left = (stripWidth * startMs.toFloat() / duration).toInt()
                            val right = (stripWidth * endMs.toFloat() / duration).toInt()
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(Color.Black.copy(alpha = 0.5f), size = androidx.compose.ui.geometry.Size(left.toFloat(), size.height))
                                drawRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(right.toFloat(), 0f),
                                    size = androidx.compose.ui.geometry.Size((size.width - right).coerceAtLeast(0f), size.height))
                                drawRect(purple.copy(alpha = 0.12f), topLeft = Offset(left.toFloat(), 0f),
                                    size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(0).toFloat(), size.height))
                            }
                            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(left - (handleWidth / 2f).toInt(), 0) }
                                .width(14.dp).fillMaxHeight().background(purple, RoundedCornerShape(3.dp))
                                .pointerInput(stripWidth, duration) {
                                    detectDragGestures { change, delta ->
                                        change.consume()
                                        startMs = (startMs + (delta.x / stripWidth * duration).toLong())
                                            .coerceIn(0L, (endMs - 100L).coerceAtLeast(0L))
                                        player?.seekTo(startMs)
                                    }
                                })
                            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(right - (handleWidth / 2f).toInt(), 0) }
                                .width(14.dp).fillMaxHeight().background(purple, RoundedCornerShape(3.dp))
                                .pointerInput(stripWidth, duration) {
                                    detectDragGestures { change, delta ->
                                        change.consume()
                                        endMs = (endMs + (delta.x / stripWidth * duration).toLong())
                                            .coerceIn((startMs + 100L).coerceAtMost(duration), duration)
                                        player?.seekTo((endMs - 1L).coerceAtLeast(startMs))
                                    }
                                })
                        }
                    }
                    Text("Drag the purple edges; the undimmed area is the section to import.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (item.kind != "image" && duration > 0L && player != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            if (rangePlaying) {
                                player.pause()
                                rangePlaying = false
                            } else {
                                player.seekTo(startMs)
                                player.play()
                                rangePlaying = true
                            }
                        }) { Text(if (rangePlaying) "Stop selection" else "Play selection") }
                        TextButton(onClick = { player.pause(); rangePlaying = false; player.seekTo(startMs) }) {
                            Text("Jump to in")
                        }
                    }
                }
                if (item.kind != "image" && duration > 0L) {
                    HorizontalDivider()
                    Text("Search & Trim", fontWeight = FontWeight.SemiBold)
                    Text(speechStatus, style = MaterialTheme.typography.bodySmall,
                        color = if (speechError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (scanningSpeech) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (transcriptWords != null && transcriptWords!!.isEmpty()) {
                        Text("No timestamped words recognized. Try a clearer spoken clip or scan again.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(enabled = !scanningSpeech, onClick = {
                        scanningSpeech = true
                        speechError = false
                        speechScope.launch {
                            try {
                                if (!recognizer.hasModel()) {
                                    speechStatus = "Downloading offline English model…"
                                    recognizer.installModel { progress ->
                                        speechStatus = "Downloading model: ${(progress * 100).toInt()}%"
                                    }
                                }
                                speechStatus = "Preparing audio for speech recognition…"
                                val words = recognizer.transcribe(Uri.parse(item.uri)) { progress ->
                                    speechStatus = when {
                                        progress < 0.1f -> "Step 1/3: Decoding audio…"
                                        progress < 1f -> "Step 2/3: Recognizing spoken words (this can take several minutes)…"
                                        else -> "Step 3/3: Preparing searchable transcript…"
                                    }
                                }
                                transcriptWords = words
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val cache = TranscriptCache(context)
                                    cache.save(cache.sourceKey(Uri.parse(item.uri), recognizer.modelId), words)
                                }
                                speechStatus = if (words.isEmpty())
                                    "Scan finished but no timestamped speech was recognized."
                                else "Scan complete: ${words.size} words. Search below or view transcript."
                            } catch (error: Exception) {
                                speechError = true
                                speechStatus = "Speech scan failed: ${error.message ?: "Unknown error"}"
                            } finally { scanningSpeech = false }
                        }
                    }) { Text(if (scanningSpeech) "Scanning…" else if (recognizer.hasModel()) "Scan speech" else "Download model & scan") }
                    if (transcriptWords != null) {
                        OutlinedTextField(
                            value = speechQuery,
                            onValueChange = { speechQuery = it; selectedMatch = -1 },
                            label = { Text("Find spoken word or phrase") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(when {
                            speechQuery.isBlank() -> "Enter a word or phrase to search."
                            transcriptWords!!.isEmpty() -> "No speech was recognized to search."
                            speechMatches.isEmpty() -> "No matches for your phrase among ${transcriptWords!!.size} recognized words."
                            else -> "${speechMatches.size} matching occurrence(s) found."
                        }, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showTranscript = !showTranscript }) {
                            Text(if (showTranscript) "Hide recognized transcript" else "View recognized transcript")
                        }
                        if (showTranscript) {
                            val previewText = transcriptWords!!.take(300).joinToString(" ") { it.text }
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(if (previewText.isBlank()) "(No recognized speech)" else previewText,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (transcriptWords!!.size > 300)
                                Text("Showing the first 300 words.", style = MaterialTheme.typography.bodySmall)
                        }
                        speechMatches.take(25).forEachIndexed { index, match ->
                            TextButton(onClick = {
                                selectedMatch = index
                                val selection = SpeechSearch.select(
                                    match, (beforeSeconds * 1000).toLong(),
                                    (afterSeconds * 1000).toLong(), duration
                                )
                                startMs = selection.startMs
                                endMs = selection.endMs
                                player?.seekTo(startMs)
                            }) {
                                Text("Match ${index + 1}: ${"%.2f".format(java.util.Locale.US, match.startMs / 1000.0)}s – ${"%.2f".format(java.util.Locale.US, match.endMs / 1000.0)}s")
                            }
                        }
                        if (speechMatches.isNotEmpty()) {
                            Text("Before phrase: ${beforeSeconds}s")
                            Slider(value = beforeSeconds, onValueChange = {
                                beforeSeconds = it
                                speechMatches.getOrNull(selectedMatch)?.let { match ->
                                    val range = SpeechSearch.select(match, (beforeSeconds * 1000).toLong(), (afterSeconds * 1000).toLong(), duration)
                                    startMs = range.startMs; endMs = range.endMs
                                }
                            }, valueRange = 0f..15f, steps = 29)
                            Text("After phrase: ${afterSeconds}s")
                            Slider(value = afterSeconds, onValueChange = {
                                afterSeconds = it
                                speechMatches.getOrNull(selectedMatch)?.let { match ->
                                    val range = SpeechSearch.select(match, (beforeSeconds * 1000).toLong(), (afterSeconds * 1000).toLong(), duration)
                                    startMs = range.startMs; endMs = range.endMs
                                }
                            }, valueRange = 0f..15f, steps = 29)
                        }
                    }
                    HorizontalDivider()
                    Text("In point: ${"%.2f".format(java.util.Locale.US, startMs / 1000.0)}s")
                    Slider(value = startMs.toFloat(), valueRange = 0f..duration.toFloat(),
                        onValueChange = {
                            startMs = it.toLong().coerceAtMost((endMs - 100L).coerceAtLeast(0L))
                            player?.seekTo(startMs)
                        })
                    Text("Out point: ${"%.2f".format(java.util.Locale.US, endMs / 1000.0)}s")
                    Slider(value = endMs.toFloat(), valueRange = 0f..duration.toFloat(),
                        onValueChange = {
                            endMs = it.toLong().coerceAtLeast((startMs + 100L).coerceAtMost(duration))
                            player?.seekTo((endMs - 500L).coerceAtLeast(startMs))
                        })
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { startMs = (startMs - 33L).coerceAtLeast(0L); player?.seekTo(startMs) }) { Text("In −") }
                        OutlinedButton(onClick = { startMs = (startMs + 33L).coerceAtMost(endMs - 100L); player?.seekTo(startMs) }) { Text("In +") }
                        OutlinedButton(onClick = { endMs = (endMs - 33L).coerceAtLeast(startMs + 100L); player?.seekTo(endMs - 33L) }) { Text("Out −") }
                        OutlinedButton(onClick = { endMs = (endMs + 33L).coerceAtMost(duration); player?.seekTo(endMs - 33L) }) { Text("Out +") }
                    }
                    Text("Importing ${"%.2f".format(java.util.Locale.US, (endMs - startMs) / 1000.0)} seconds")
                } else if (duration <= 0L && item.kind != "image") {
                    Text("Duration unavailable. This file cannot be trimmed yet.")
                }
            }
        },
        confirmButton = {
            Button(onClick = { player?.pause(); onConfirm(startMs, endMs) },
                enabled = item.kind == "image" || (duration > 0L && endMs > startMs)) {
                Text("Import trimmed clip")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private val waveformCache = object : android.util.LruCache<String, List<Float>>(32) {}
private val waveformLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

private fun cachedAudioPeaks(context: android.content.Context, uri: Uri): List<Float> {
    val key = uri.toString()
    synchronized(waveformCache) { waveformCache.get(key)?.let { return it } }
    val lock = waveformLocks.getOrPut(key) { Any() }
    synchronized(lock) {
        synchronized(waveformCache) { waveformCache.get(key)?.let { return it } }
        val filename = "wave_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".bin"
        val file = java.io.File(context.cacheDir, filename)
        val disk = try {
            if (file.length() in 8..4096) java.io.DataInputStream(file.inputStream()).use { stream ->
                val count = stream.readInt()
                if (count in 1..512) List(count) { stream.readFloat() } else emptyList()
            } else emptyList()
        } catch (_: Exception) { emptyList<Float>() }
        if (disk.isNotEmpty()) {
            synchronized(waveformCache) { waveformCache.put(key, disk) }
            return disk
        }
        val result = extractAudioPeaks(context, uri)
        if (result.isNotEmpty()) {
            synchronized(waveformCache) { waveformCache.put(key, result) }
            try {
                java.io.DataOutputStream(file.outputStream()).use { stream ->
                    stream.writeInt(result.size)
                    result.forEach { stream.writeFloat(it) }
                }
            } catch (_: Exception) { }
        }
        return result
    }
}

private fun extractAudioPeaks(context: android.content.Context, uri: Uri): List<Float> {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val index = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return emptyList()
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val peaks = FloatArray(96)
            var samplesSeen = 0L
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var inputDone = false
            var outputDone = false
            var iterations = 0
            while (!outputDone && iterations++ < 25000) {
                if (Thread.currentThread().isInterrupted) break
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10000)
                if (outputIndex >= 0) {
                    if (info.size > 0 && durationUs > 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val slice = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                        val bin = ((info.presentationTimeUs.toDouble() / durationUs) * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
                        var maximum = peaks[bin]
                        while (slice.remaining() >= 2) {
                            val amplitude = kotlin.math.abs(slice.short.toInt()) / 32768f
                            if (amplitude > maximum) maximum = amplitude
                            samplesSeen++
                        }
                        peaks[bin] = maximum
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            return if (samplesSeen > 0) peaks.toList() else emptyList()
        } finally {
            codec.stop()
            codec.release()
        }
    } catch (_: Exception) { return emptyList() }
    finally { extractor.release() }
}

@Composable
private fun TimelineMediaStrip(
    uri: String, kind: String, duration: Long, startMs: Long, endMs: Long,
    onTrim: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    var frames by remember(uri) { mutableStateOf<List<androidx.compose.ui.graphics.ImageBitmap>>(emptyList()) }
    var peaks by remember(uri) { mutableStateOf<List<Float>>(emptyList()) }
    LaunchedEffect(uri, kind, duration) {
        if (kind == "video") {
            frames = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(uri))
                    (0 until 8).mapNotNull { i ->
                        retriever.getFrameAtTime(duration * i * 1000L / 7L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { raw ->
                            val scaled = Bitmap.createScaledBitmap(raw, 100, 60, false)
                            if (scaled !== raw) raw.recycle()
                            scaled.asImageBitmap()
                        }
                    }
                } catch (_: Exception) { emptyList() } finally { retriever.release() }
            }
        } else if (kind == "audio") {
            peaks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cachedAudioPeaks(context, Uri.parse(uri))
            }
        }
    }
    var stripWidth by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val handlePx = with(density) { 14.dp.roundToPx() }
    Box(Modifier.fillMaxWidth().height(62.dp).background(lavender, RoundedCornerShape(6.dp))
        .onSizeChanged { stripWidth = it.width }) {
        if (kind == "video" && frames.isNotEmpty()) {
            Row(Modifier.fillMaxSize()) {
                frames.forEach { frame ->
                    Image(frame, "Timeline frame", Modifier.weight(1f).fillMaxHeight(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
            }
        } else if (kind == "audio" && peaks.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val step = size.width / peaks.size
                peaks.forEachIndexed { i, peak ->
                    val half = (peak.coerceIn(0f, 1f) * size.height * 0.46f).coerceAtLeast(1f)
                    val x = (i + 0.5f) * step
                    drawLine(purple, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half),
                        strokeWidth = step.coerceAtLeast(1f))
                }
            }
        } else Text("Loading media preview…", Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.bodySmall)
        if (stripWidth > 0 && duration > 0) {
            val left = (stripWidth * startMs.toFloat() / duration).toInt()
            val right = (stripWidth * endMs.toFloat() / duration).toInt()
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black.copy(alpha = 0.46f),
                    size = androidx.compose.ui.geometry.Size(left.toFloat(), size.height))
                drawRect(Color.Black.copy(alpha = 0.46f), topLeft = Offset(right.toFloat(), 0f),
                    size = androidx.compose.ui.geometry.Size((size.width - right).coerceAtLeast(0f), size.height))
            }
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(left - handlePx / 2, 0) }
                .width(14.dp).fillMaxHeight().background(purple, RoundedCornerShape(3.dp))
                .pointerInput(uri, stripWidth, duration) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        val next = (startMs + (delta.x / stripWidth * duration).toLong())
                            .coerceIn(0L, (endMs - 100L).coerceAtLeast(0L))
                        onTrim(next, endMs)
                    }
                })
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(right - handlePx / 2, 0) }
                .width(14.dp).fillMaxHeight().background(purple, RoundedCornerShape(3.dp))
                .pointerInput(uri, stripWidth, duration) {
                    detectDragGestures { change, delta ->
                        change.consume()
                        val next = (endMs + (delta.x / stripWidth * duration).toLong())
                            .coerceIn((startMs + 100L).coerceAtMost(duration), duration)
                        onTrim(startMs, next)
                    }
                })
        }
    }
    Text("Drag purple edges to trim this timeline clip.", style = MaterialTheme.typography.bodySmall)
}

private val previewImageCache = PreviewImageCache()

private class PreviewImageCache {
    private val cache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap): Int = value.width * value.height * 4
    }
    @Synchronized
    fun get(context: android.content.Context, uri: Uri): androidx.compose.ui.graphics.ImageBitmap? {
        cache.get(uri.toString())?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 1080 || bounds.outHeight / sample > 1080) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            bitmap.asImageBitmap().also { cache.put(uri.toString(), it) }
        } catch (_: Exception) { null }
    }
}
