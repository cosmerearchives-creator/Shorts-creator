package com.cosmerearchives.shortscreator

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import androidx.media3.effect.Presentation
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
    fun exportTimeline(context: Context, clips: List<Clip>, ratio: String, callback: (String) -> Unit) {
        val (outputWidth, outputHeight) = exportDimensions(ratio)
        val presentation = Presentation.createForWidthAndHeight(outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val main = clips.filter { it.track == 0 }
        require(main.isNotEmpty()) { "Add main media before exporting" }
        if (main.all { it.kind == "audio" }) {
            exportAudioEqualizer(context, clips, ratio, callback)
            return
        }
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
                color = item.textColor
                textSize = item.textSize
                textAlign = Paint.Align.CENTER
                setShadowLayer(5f, 2f, 3f, AndroidColor.BLACK)
            }
            val words = item.caption.replace("\\n", " ").split(" ")
            val lines = mutableListOf<String>()
            var line = ""
            for (word in words) {
                val next = if (line.isEmpty()) word else line + " " + word
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
                            .setScale(if (image.kind == "image") image.imageScale else 1f, if (image.kind == "image") image.imageScale else 1f)
                            .setBackgroundFrameAnchor(when (image.kind) { "text" -> image.textX; "image" -> image.imageX; else -> 0f }, when (image.kind) { "text" -> image.textY; "image" -> image.imageY; else -> 0f })
                            .build()
                    }
                }
            }
            val effects = if (overlays.isNotEmpty()) listOf(OverlayEffect(overlays)) else emptyList()
            EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), listOf(presentation) + effects)).build()
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
        clips.filter { it.track == 1 && it.kind == "text" && it.ttsUri.isNotBlank() }.forEach { caption ->
            val speech = android.net.Uri.parse(caption.ttsUri)
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            if (caption.positionMs > 0L) builder.addGap(caption.positionMs * 1000L)
            val item = MediaItem.Builder().setUri(speech).build()
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
            override fun getOutputSize(inputSizes: List<Size>): Size = Size(outputWidth, outputHeight)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): androidx.media3.common.OverlaySettings =
                if (inputId >= pipStart && pipVideos.isNotEmpty()) {
                    StaticOverlaySettings.Builder()
                        .setScale(pipVideos[inputId - pipStart].pipScale, pipVideos[inputId - pipStart].pipScale)
                        .setOverlayFrameAnchor(0f, 0f)
                        .setBackgroundFrameAnchor(pipVideos[inputId - pipStart].pipX, pipVideos[inputId - pipStart].pipY)
                        .build()
                } else StaticOverlaySettings.Builder().build()
        }
        val composition = Composition.Builder(sequences)
            .setVideoCompositorSettings(compositor)
            // Apply the project dimensions to the composed output, not just input clips.
            .setEffects(Effects(emptyList(), listOf(
                Presentation.createForWidthAndHeight(outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            )))
            .build()
        startExport(context, output, callback, outputWidth to outputHeight) { transformer -> transformer.start(composition, output.absolutePath) }
    }

    private fun exportAudioEqualizer(context: Context, clips: List<Clip>, ratio: String, callback: (String) -> Unit) {
        val main = clips.filter { it.track == 0 }
        require(main.all { it.endMs > it.startMs }) { "Invalid audio duration" }
        require(clips.none { it.track == 1 }) { "Visual overlays on audio-only projects are not yet supported" }
        callback("Preparing equalizer video…")
        kotlin.concurrent.thread {
            try {
                val peaks = main.map { extractAudioPeaks(context, Uri.parse(it.uri)) }
                val (width, height) = exportDimensions(ratio)
                val background = File(context.cacheDir, "equalizer-background.png")
                if (!background.exists()) {
                    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.rgb(32, 27, 47))
                    background.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val visualItems = main.mapIndexed { index, clip ->
                            val levels = peaks[index]
                            val overlay = object : BitmapOverlay() {
                                override fun getBitmap(presentationTimeUs: Long): Bitmap {
                                    val w = 480
                                    val h = (480f * height / width).toInt()
                                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    val canvas = Canvas(bitmap)
                                    val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(163, 129, 225) }
                                    val time = clip.startMs + presentationTimeUs / 1000L
                                    val bin = ((time.toDouble() / clip.endMs.coerceAtLeast(1L)) * levels.size).toInt().coerceIn(0, (levels.size - 1).coerceAtLeast(0))
                                    val level = levels.getOrNull(bin)?.coerceIn(0.08f, 1f) ?: 0.2f
                                    for (i in 0 until 32) {
                                        val phase = presentationTimeUs / 170000.0 + i * 0.7
                                        val motion = kotlin.math.abs(kotlin.math.sin(phase) * kotlin.math.cos(phase * 0.45)).toFloat()
                                        val barH = h * 0.65f * level * (0.2f + 0.8f * motion)
                                        val x = (i + 0.5f) * w / 32
                                        canvas.drawRoundRect(x - 5f, (h - barH) / 2, x + 5f,
                                            (h + barH) / 2, 4f, 4f, brush)
                                    }
                                    return bitmap
                                }
                            }
                            val image = MediaItem.Builder().setUri(Uri.fromFile(background))
                                .setImageDurationMs(clip.endMs - clip.startMs).build()
                            EditedMediaItem.Builder(image).setFrameRate(15)
                                .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)),
                                    Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
                                .build()
                        }
                        val audioItems = main.map { clip ->
                            val media = MediaItem.Builder().setUri(Uri.parse(clip.uri))
                                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()).build()
                            EditedMediaItem.Builder(media).build()
                        }
                        val sequences = listOf(EditedMediaItemSequence.withVideoFrom(visualItems),
                            EditedMediaItemSequence.withAudioFrom(audioItems))
                        val output = File(context.cacheDir, "equalizer-" + System.currentTimeMillis() + ".mp4")
                        callback("Encoding equalizer video…")
                        startExport(context, output, callback, width to height) { transformer ->
                            transformer.start(Composition.Builder(sequences).build(), output.absolutePath)
                        }
                    } catch (e: Exception) { callback("Equalizer export failed: " + e.message) }
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback("Equalizer preparation failed: " + e.message)
                }
            }
        }
    }

    fun export(context: Context, source: String, startMs: Long, endMs: Long, ratio: String, callback: (String) -> Unit) {
        val output = File(context.cacheDir, "shorts-${System.currentTimeMillis()}.mp4")
        val media = MediaItem.Builder()
            .setUri(Uri.parse(source))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            ).build()
        val (width, height) = exportDimensions(ratio)
        val presentation = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val edited = EditedMediaItem.Builder(media).setEffects(Effects(emptyList(), listOf(presentation))).build()
        startExport(context, output, callback, width to height) { transformer -> transformer.start(edited, output.absolutePath) }
    }

    private fun exportDimensions(ratio: String): Pair<Int, Int> = when (ratio) {
        "9:16" -> 1080 to 1920
        "1:1" -> 1080 to 1080
        "4:5" -> 1080 to 1350
        else -> 1920 to 1080
    }

    private fun startExport(context: Context, output: File, callback: (String) -> Unit, expectedSize: Pair<Int, Int>, start: (Transformer) -> Unit) {
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    var destination: Uri? = null
                    try {
                        val retriever = MediaMetadataRetriever()
                        val actualSize = try {
                            retriever.setDataSource(output.absolutePath)
                            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                            if (width == null || height == null) null else if (rotation % 180 != 0) height to width else width to height
                        } finally { retriever.release() }
                        check(actualSize == expectedSize) {
                            "Export resolution mismatch: expected ${expectedSize.first}x${expectedSize.second}, got ${actualSize?.first}x${actualSize?.second}. File was not saved."
                        }
                        val resolver = context.contentResolver
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, output.name)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShortsCreator")
                            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            ?: error("Unable to create video destination")
                        destination = uri
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
                        destination?.let { failedUri ->
                            try { context.contentResolver.delete(failedUri, null, null) } catch (_: Exception) { }
                        }
                        callback("Export completed, but saving failed: ${e.message}")
                    } finally { output.delete() }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    output.delete()
                    callback("Export failed: ${exportException.message}")
                }
            }).build()
        try {
            start(transformer)
        } catch (e: Exception) {
            output.delete()
            callback("Export could not start: ${e.message}")
        }
    }
}
