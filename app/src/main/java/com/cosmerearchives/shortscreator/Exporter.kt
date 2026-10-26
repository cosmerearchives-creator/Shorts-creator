package com.cosmerearchives.shortscreator

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/** v0.2: a single trimmed source video; not yet a multitrack composition. */
object Exporter {
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
        transformer.start(edited, output.absolutePath)
    }
}
