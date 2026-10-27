package com.cosmerearchives.shortscreator

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class NativeWhisper(private val context: Context) : SpeechTranscriber {
    override val modelId = "ggml-tiny.en-v1"
    private val modelFile: File get() = File(context.filesDir, "ggml-tiny.en.bin")

    companion object {
        init { System.loadLibrary("shorts_speech") }
    }

    private external fun recognize(modelPath: String, audio: FloatArray): Array<String>

    fun hasModel(): Boolean = modelFile.exists() && modelFile.length() > 60_000_000L

    suspend fun installModel(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (hasModel()) return@withContext
        val destination = modelFile
        val partial = File(destination.absolutePath + ".partial")
        try {
            val connection = (URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin").openConnection() as HttpURLConnection)
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode !in 200..299) error("Model download HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong
                var readBytes = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            readBytes += count
                            if (total > 0) onProgress(readBytes.toFloat() / total)
                        }
                    }
                }
            } finally { connection.disconnect() }
            check(partial.length() > 60_000_000L) { "Downloaded model is incomplete" }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
        } finally { partial.delete() }
    }

    override suspend fun transcribe(uri: Uri, onProgress: (Float) -> Unit): List<SpokenWord> = withContext(Dispatchers.Default) {
        check(hasModel()) { "Download the offline English model first" }
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("Media duration is unavailable")
        } finally { retriever.release() }
        require(durationMs in 1..(2L * 60L * 60L * 1000L)) { "Speech scanning supports recordings up to two hours" }

        val windowMs = 25_000L
        val overlapMs = 1_000L
        val advanceMs = windowMs - overlapMs
        val totalChunks = ((durationMs - 1) / advanceMs + 1).toInt()
        val cache = TranscriptCache(context)
        val baseKey = cache.sourceKey(uri, modelId)
        val allWords = ArrayList<SpokenWord>()
        for (chunk in 0 until totalChunks) {
            currentCoroutineContext().ensureActive()
            val startMs = chunk * advanceMs
            val endMs = minOf(startMs + windowMs, durationMs)
            if (startMs >= endMs) break
            val chunkKey = "$baseKey-chunk-$chunk"
            val cached = cache.load(chunkKey)
            val words = if (cached != null) cached else {
                val audio = MediaSpeechDecoder.decodeWindow(context, uri, startMs, endMs)
                currentCoroutineContext().ensureActive()
                val decoded = if (audio.isEmpty()) emptyList() else
                    recognize(modelFile.absolutePath, audio).mapNotNull { line ->
                        val fields = line.split('\\t')
                        if (fields.size != 3) null else {
                            val from = fields[1].toLongOrNull()
                            val to = fields[2].toLongOrNull()
                            if (from != null && to != null && to > from && from >= 0)
                                SpokenWord(fields[0].trim(), startMs + from, startMs + to) else null
                        }
                    }
                currentCoroutineContext().ensureActive()
                cache.save(chunkKey, decoded)
                decoded
            }
            // Keep the first chunk's overlapping words; skip duplicates from the next chunk.
            val cutoffMs = if (chunk == 0) 0L else startMs + overlapMs
            allWords.addAll(words.filter { it.startMs >= cutoffMs && it.startMs < durationMs })
            onProgress((chunk + 1).toFloat() / totalChunks)
        }
        allWords.sortedBy { it.startMs }
    }

}
