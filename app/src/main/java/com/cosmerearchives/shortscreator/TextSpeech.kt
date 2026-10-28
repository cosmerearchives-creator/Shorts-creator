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
    suspend fun generate(context: Context, projectId: String, spokenText: String, speed: Float = 1f, pitch: Float = 1f, volume: Float = 1f): Pair<String, Long> {
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
                        tts.setSpeechRate(speed.coerceIn(0.5f, 2f))
                        tts.setPitch(pitch.coerceIn(0.5f, 2f))
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
                                if (duration > 0L) {
                                    try { applyVolume(file, volume.coerceIn(0f, 1f)) }
                                    catch (e: Exception) {
                                        file.delete()
                                        if (continuation.isActive) continuation.resumeWithException(e)
                                        return
                                    }
                                }
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
    private fun applyVolume(file: File, volume: Float) {
        if (volume == 1f) return
        java.io.RandomAccessFile(file, "rw").use { wav ->
            require(wav.length() >= 44L) { "Invalid speech WAV" }
            val bytes = ByteArray(44)
            wav.readFully(bytes)
            require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Expected WAV speech" }
            val format = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            require(format.getShort(20).toInt() == 1 && format.getShort(34).toInt() == 16) { "Expected PCM16 speech" }
            val data = java.nio.ByteBuffer.wrap("data".toByteArray())
            var offset = 12L
            while (offset + 8L <= wav.length()) {
                wav.seek(offset)
                val id = ByteArray(4)
                wav.readFully(id)
                val size = java.lang.Integer.reverseBytes(wav.readInt()).toLong() and 0xffffffffL
                if (id.contentEquals("data".toByteArray())) {
                    val end = minOf(wav.length(), offset + 8 + size)
                    val chunk = ByteArray(65536)
                    var at = offset + 8
                    while (at + 1 < end) {
                        val count = minOf(chunk.size.toLong(), end - at).toInt() and -2
                        wav.seek(at)
                        wav.readFully(chunk, 0, count)
                        for (i in 0 until count step 2) {
                            val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 255)).toShort().toInt()
                            val v = (sample * volume).toInt().coerceIn(-32768, 32767)
                            chunk[i] = v.toByte()
                            chunk[i + 1] = (v shr 8).toByte()
                        }
                        wav.seek(at)
                        wav.write(chunk, 0, count)
                        at += count
                    }
                    return
                }
                offset += 8 + size + (size and 1L)
            }
            error("Speech WAV has no audio data")
        }
    }

}
