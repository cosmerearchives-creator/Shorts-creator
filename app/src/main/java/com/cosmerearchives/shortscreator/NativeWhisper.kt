package com.cosmerearchives.shortscreator

import android.content.Context
import android.net.Uri
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
        onProgress(0f)
        val samples = MediaSpeechDecoder.decode(context, uri)
        onProgress(0.2f)
        val transcript = recognize(modelFile.absolutePath, samples).mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 3) null else {
                val start = fields[1].toLongOrNull()
                val end = fields[2].toLongOrNull()
                if (start != null && end != null && end > start && start >= 0)
                    SpokenWord(fields[0].trim(), start, end) else null
            }
        }
        onProgress(1f)
        transcript
    }
}
