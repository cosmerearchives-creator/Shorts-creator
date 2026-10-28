package com.cosmerearchives.shortscreator

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Generate a persistent audio file for a text overlay and return its measured duration. */
object TextSpeech {
    suspend fun generate(context: Context, projectId: String, spokenText: String): Pair<String, Long> {
        require(spokenText.isNotBlank()) { "Enter text to speak" }
        val dir = File(context.filesDir, "tts/$projectId").apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString() + ".wav")
        return suspendCancellableCoroutine { continuation ->
            var engine: TextToSpeech? = null
            var finished = false
            fun fail(message: String) {
                if (finished) return
                finished = true
                engine?.shutdown()
                file.delete()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
            }
            engine = TextToSpeech(context.applicationContext) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    fail("The Android speech engine could not initialize")
                } else {
                    val tts = engine
                    if (tts == null) {
                        fail("Speech engine unavailable")
                    } else {
                        tts.language = Locale.getDefault()
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) {
                                if (finished) return
                                finished = true
                                val duration = try {
                                    val retriever = MediaMetadataRetriever()
                                    try {
                                        retriever.setDataSource(file.absolutePath)
                                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    } finally { retriever.release() }
                                } catch (_: Exception) { 0L }
                                tts.shutdown()
                                if (duration <= 0L) {
                                    file.delete()
                                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Could not measure generated speech"))
                                } else if (continuation.isActive) continuation.resume(Uri.fromFile(file).toString() to duration)
                            }
                            override fun onError(utteranceId: String?) { fail("Speech generation failed") }
                        })
                        val result = tts.synthesizeToFile(spokenText, android.os.Bundle(), file, "shorts-" + UUID.randomUUID().toString())
                        if (result != TextToSpeech.SUCCESS) fail("The speech engine rejected the text")
                    }
                }
            }
            continuation.invokeOnCancellation {
                engine?.stop()
                engine?.shutdown()
                file.delete()
            }
        }
    }
}
