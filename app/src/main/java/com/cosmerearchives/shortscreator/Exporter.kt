package com.cosmerearchives.shortscreator

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.common.OverlaySettings
import android.graphics.Bitmap
import androidx.media3.transformer.Effects
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/** v0.2: a single trimmed source video; not yet a multitrack composition. */
object Exporter {
    fun exportTimeline(context: Context, clips: List<Clip>, callback: (String) -> Unit) {
        val main = clips.filter { it.track == 0 }
        require(main.isNotEmpty()) { "Add main-track videos before exporting" }
        require(main.all { it.kind == "video" && it.endMs > it.startMs }) {
            "Timeline export currently supports trimmed video clips on the main track only"
        }
        val output = File(context.cacheDir, "timeline-${System.currentTimeMillis()}.mp4")
        val visualOverlays = clips.filter { it.track == 1 }
        require(visualOverlays.all { it.kind == "image" || it.kind == "video" || it.kind == "text" }) { "Unsupported visual overlay" }
        val images = visualOverlays.filter { it.kind == "image" }.map { item ->
            val bitmap = context.contentResolver.openInputStream(Uri.parse(item.uri))?.use {
                BitmapFactory.decodeStream(it)
            } ?: error("Unable to open overlay image")
            item to bitmap
        }
        val captions = visualOverlays.filter { it.kind == "text" && it.caption.isNotBlank() }.map { item ->
            val bitmap = Bitmap.createBitmap(1024, 192, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.WHITE
                textSize = 62f
                textAlign = Paint.Align.CENTER
                setShadowLayer(5f, 2f, 3f, AndroidColor.BLACK)
            }
            val words = item.caption.replace("\\n", " ").split(" ")
            val lines = mutableListOf<String>()
            var line = ""
            for (word in words) {
                val next = if (line.isEmpty()) word else "$"+"line $"+ "word"
                if (paint.measureText(next) > 940f && line.isNotEmpty()) {
                    lines.add(line)
                    line = word
                } else line = next
            }
            if (line.isNotEmpty()) lines.add(line)
            lines.take(2).forEachIndexed { i, text -> canvas.drawText(text, 512f, 75f + i * 75f, paint) }
            item to bitmap
        }
        var timelineStartMs = 0L
        val edits = main.map { clip ->
            val segmentStart = timelineStartMs
            timelineStartMs += clip.endMs - clip.startMs
            val media = MediaItem.Builder().setUri(Uri.parse(clip.uri))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()).build()
            val overlays = (images + captions).map { (image, bitmap) ->
                object : BitmapOverlay() {
                    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap
                    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                        val timeMs = segmentStart + presentationTimeUs / 1000L
                        val visible = timeMs >= image.positionMs && timeMs < image.positionMs + image.endMs.coerceAtLeast(5000L)
                        return StaticOverlaySettings.Builder()
                            .setAlphaScale(if (visible) 1f else 0f)
                            .build()
                    }
                }
            }
            val effects = if (overlays.isNotEmpty()) listOf(OverlayEffect(overlays)) else emptyList()
            EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), effects)).build()
        }
        val mainSequence = EditedMediaItemSequence.withAudioAndVideoFrom(edits)
        val audio = clips.filter { it.track == 2 && it.kind == "audio" }
        val sequences = mutableListOf(mainSequence)
        audio.forEach { clip ->
            require(clip.endMs > clip.startMs) { "Audio needs a known duration before timeline export" }
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            if (clip.positionMs > 0) builder.addGap(clip.positionMs * 1000L)
            val item = MediaItem.Builder().setUri(Uri.parse(clip.uri))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()).build()
            builder.addItem(EditedMediaItem.Builder(item).build())
            sequences.add(builder.build())
        }
        val pipVideos = visualOverlays.filter { it.kind == "video" }
        pipVideos.forEach { clip ->
            require(clip.endMs > clip.startMs) { "Video overlay has no valid duration" }
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            if (clip.positionMs > 0L) builder.addGap(clip.positionMs * 1000L)
            val media = MediaItem.Builder().setUri(Uri.parse(clip.uri))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()).build()
            builder.addItem(EditedMediaItem.Builder(media).setRemoveAudio(true).build())
            sequences.add(builder.build())
        }
        val pipStart = sequences.size - pipVideos.size
        val compositor = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes.first()
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): androidx.media3.common.OverlaySettings =
                if (inputId >= pipStart && pipVideos.isNotEmpty()) {
                    StaticOverlaySettings.Builder()
                        .setScale(pipVideos[inputId - pipStart].pipScale, pipVideos[inputId - pipStart].pipScale)
                        .setOverlayFrameAnchor(0f, 0f)
                        .setBackgroundFrameAnchor(pipVideos[inputId - pipStart].pipX, pipVideos[inputId - pipStart].pipY)
                        .build()
                } else StaticOverlaySettings.Builder().build()
        }
        val composition = Composition.Builder(sequences).setVideoCompositorSettings(compositor).build()
        startExport(context, output, callback) { transformer -> transformer.start(composition, output.absolutePath) }
    }

    fun export(context: Context, source: String, startMs: Long, endMs: Long, callback: (String) -> Unit) {
        val output = File(context.cacheDir, "shorts-${System.currentTimeMillis()}.mp4")
        val media = MediaItem.Builder()
            .setUri(Uri.parse(source))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            ).build()
        val edited = EditedMediaItem.Builder(media).build()
        startExport(context, output, callback) { transformer -> transformer.start(edited, output.absolutePath) }
    }

    private fun startExport(context: Context, output: File, callback: (String) -> Unit, start: (Transformer) -> Unit) {
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    try {
                        val resolver = context.contentResolver
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, output.name)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShortsCreator")
                            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            ?: error("Unable to create video destination")
                        resolver.openOutputStream(uri)?.use { sink ->
                            output.inputStream().use { it.copyTo(sink) }
                        } ?: error("Unable to write output video")
                        if (Build.VERSION.SDK_INT >= 29) {
                            values.clear()
                            values.put(MediaStore.Video.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                        }
                        callback("Export saved to Movies/ShortsCreator")
                    } catch (e: Exception) {
                        callback("Export completed, but saving failed: ${e.message}")
                    } finally { output.delete() }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    output.delete()
                    callback("Export failed: ${exportException.message}")
                }
            }).build()
        start(transformer)
    }
}
