package com.cosmerearchives.shortscreator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
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

private val ivory = Color(0xFFF5F4EF)
private val lavender = Color(0xFFEFE9F5)
private val purple = Color(0xFF685B86)
private val ink = Color(0xFF353238)
private val dark = Color(0xFF292630)

data class Clip(val uri: String, val kind: String, val track: Int, val startMs: Long = 0L, val endMs: Long = 0L)
data class Project(val name: String, val ratio: String, val clips: List<Clip> = emptyList())

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("projects", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var projects by remember { mutableStateOf(loadProjects()) }
            var selected by remember { mutableIntStateOf(-1) }
            var newDialog by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var selectedTrack by remember { mutableIntStateOf(0) }
            var updateStatus by remember { mutableStateOf("") }
            var exportStatus by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                thread { val result = checkForUpdate(); runOnUiThread {
                    if (result.startsWith("Latest release:")) updateStatus = result
                } }
            }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null && selected in projects.indices) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) { }
                    val updated = projects.toMutableList()
                    val current = updated[selected]
                    val mime = contentResolver.getType(uri).orEmpty()
                    updated[selected] = current.copy(clips = current.clips + Clip(uri.toString(), if (mime.startsWith("audio/")) "audio" else if (mime.startsWith("image/")) "image" else "video", selectedTrack, 0L, if (mime.startsWith("video/")) getDuration(uri) else 0L))
                    projects = updated
                    saveProjects(projects)
                }
            }
            MaterialTheme(colorScheme = lightColorScheme(primary = purple, background = ivory, surface = Color.White, onSurface = ink)) {
                Surface(color = ivory, modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
                                    val result = checkForUpdate()
                                    runOnUiThread { updateStatus = result }
                                }
                            }) { Text("Check for updates") }
                            Text(updateStatus)
                            OutlinedButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cosmerearchives-creator/Shorts-creator/releases/latest"))) }) { Text("Open latest release") }
                            Text("Updates require Android installation approval. Install only official signed releases.", color = ink)
                        } else if (selected !in projects.indices) {
                            if (updateStatus.startsWith("Latest release:")) {
                                Card(colors = CardDefaults.cardColors(containerColor = lavender)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text(updateStatus)
                                        TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cosmerearchives-creator/Shorts-creator/releases"))) }) { Text("View update") }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                            Text("Your projects", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { newDialog = true }) { Icon(Icons.Default.Add, null); Text(" New project") }
                            Spacer(Modifier.height(16.dp))
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(projects.indices.toList()) { index ->
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
                            var activeClip by remember(selected, projects) { mutableIntStateOf(0) }
                            val currentClip = project.clips.getOrNull(activeClip)
                            if (currentClip != null && currentClip.kind == "video") {
                                key(currentClip.uri, currentClip.startMs, currentClip.endMs) { VideoPreview(currentClip.uri, currentClip.startMs, currentClip.endMs) }
                            } else {
                                Box(Modifier.fillMaxWidth().height(230.dp).background(dark, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                    Text(if (currentClip?.kind == "audio") "Audio track selected" else "Import a video to preview", color = Color.White)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { selectedTrack = 0; launcher.launch(arrayOf("video/*", "audio/*")) }) { Text("Import") }
                                OutlinedButton(onClick = { selectedTrack = 1; launcher.launch(arrayOf("video/*", "image/*", "audio/*")) }) { Text("Add overlay") }
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
                                            Exporter.export(this@MainActivity, currentClip.uri, currentClip.startMs, currentClip.endMs) {
                                                exportStatus = it
                                            }
                                        } catch (e: Exception) { exportStatus = "Export failed: ${e.message}" }
                                    }) { Text("Export selected clip") }
                                    if (exportStatus.isNotBlank()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Timeline (starter)", fontWeight = FontWeight.Bold)
                            val lanes = listOf("V2 Overlay" to 1, "V1 Magnetic" to 0)
                            lanes.forEach { (label, track) ->
                                Card(colors = CardDefaults.cardColors(containerColor = lavender), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(label, color = purple, fontWeight = FontWeight.SemiBold)
                                        project.clips.forEachIndexed { i, clip ->
                                            if (clip.track == track) {
                                                TextButton(onClick = { activeClip = i }) {
                                                    Text((if (clip.kind == "audio") "♪ " else "▣ ") + "Clip ${i+1}" + if (activeClip == i) " • selected" else "")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Text("V0.2: trim and export one selected video. Multitrack compositing, ripple edits, and splitting are not ready yet.", style = MaterialTheme.typography.bodySmall)
                        }
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

    private fun loadProjects(): List<Project> = try {
        val arr = JSONArray(prefs.getString("data", "[]"))
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val clips = obj.optJSONArray("clips") ?: JSONArray()
            Project(obj.getString("name"), obj.getString("ratio"), (0 until clips.length()).map { j ->
                val c = clips.getJSONObject(j)
                Clip(c.getString("uri"), c.getString("kind"), c.getInt("track"), c.optLong("startMs", 0L), c.optLong("endMs", 0L))
            })
        }
    } catch (_: Exception) { emptyList() }

    private fun saveProjects(projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            val clips = JSONArray()
            p.clips.forEach { c -> clips.put(JSONObject().put("uri", c.uri).put("kind", c.kind).put("track", c.track).put("startMs", c.startMs).put("endMs", c.endMs)) }
            arr.put(JSONObject().put("name", p.name).put("ratio", p.ratio).put("clips", clips))
        }
        prefs.edit().putString("data", arr.toString()).apply()
    }

    private fun checkForUpdate(): String = try {
        val conn = URL("https://api.github.com/repos/cosmerearchives-creator/Shorts-creator/releases?per_page=20").openConnection() as HttpURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val code = conn.responseCode
        if (code == 404) "No published release yet." else if (code !in 200..299) "GitHub check failed (HTTP $code)." else {
            val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val data = (0 until releases.length()).map { releases.getJSONObject(it) }.firstOrNull { !it.optBoolean("draft", false) }
            val remote = data?.optString("tag_name").orEmpty()
            if (remote.isBlank()) "No version information available."
            else if (remote == "v0.2.0-alpha") "You're on the latest release."
            else "Latest release: $remote. Tap Open latest release to review and download."
        }
    } catch (e: Exception) { "Update check unavailable: ${e.localizedMessage ?: "network error"}" }
}

@Composable
private fun VideoPreview(source: String, startMs: Long, endMs: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(source) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.Builder().setUri(Uri.parse(source)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build())
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player } }, modifier = Modifier.fillMaxWidth().height(240.dp).background(dark))
}
