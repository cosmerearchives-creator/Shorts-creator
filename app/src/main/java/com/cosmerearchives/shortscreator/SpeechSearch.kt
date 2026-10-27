package com.cosmerearchives.shortscreator

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** A word with timing relative to the original source (not a trimmed clip). */
data class SpokenWord(val text: String, val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs >= startMs) }
}
data class SpeechMatch(val phrase: String, val startMs: Long, val endMs: Long)
data class TrimSelection(val startMs: Long, val endMs: Long)

/** The recognition engine supplies timed words; matching and trimming never depend on its implementation. */
object SpeechSearch {
    private fun words(text: String): List<String> {
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
        return Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)?")
            .findAll(normalized).map { it.value.replace('’', '\'') }.toList()
    }

    fun find(transcript: List<SpokenWord>, query: String): List<SpeechMatch> {
        val target = words(query)
        if (target.isEmpty()) return emptyList()
        val tokens = transcript.flatMap { word ->
            words(word.text).map { token -> token to word }
        }
        if (tokens.size < target.size) return emptyList()
        return (0..tokens.size - target.size).mapNotNull { index ->
            if (target.indices.all { tokens[index + it].first == target[it] }) {
                val first = tokens[index].second
                val last = tokens[index + target.size - 1].second
                SpeechMatch(query, first.startMs, last.endMs)
            } else null
        }.distinctBy { it.startMs to it.endMs }
    }

    fun select(match: SpeechMatch, beforeMs: Long, afterMs: Long, mediaDurationMs: Long): TrimSelection {
        require(beforeMs >= 0 && afterMs >= 0 && mediaDurationMs >= 0)
        val start = (match.startMs - beforeMs).coerceAtLeast(0)
        val end = (match.endMs + afterMs).coerceAtMost(mediaDurationMs)
        return TrimSelection(start.coerceAtMost(end), end)
    }
}

/**
 * Stores only timestamped words, never raw audio. The source fingerprint includes URI,
 * size, modification date and recognition model identifier to prevent accidental cache reuse.
 */
class TranscriptCache(private val context: Context) {
    private val directory: File
        get() = File(context.filesDir, "speech-transcripts").also { it.mkdirs() }

    fun sourceKey(uri: Uri, modelId: String): String {
        val meta = try { context.contentResolver.query(uri, arrayOf(
            android.provider.OpenableColumns.SIZE,
            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ), null, null, null)?.use { cursor ->
            val sizeAt = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            val modifiedAt = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val size = if (sizeAt >= 0 && cursor.moveToFirst()) cursor.getLong(sizeAt) else -1
            val modified = if (modifiedAt >= 0 && cursor.position >= 0 && !cursor.isNull(modifiedAt)) cursor.getLong(modifiedAt) else -1
            "$size:$modified"
        } ?: "unknown" } catch (_: Exception) { "unknown" }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$uri|$meta|$modelId|v1".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun load(key: String): List<SpokenWord>? = try {
        val stable = File(directory, "$key.json")
        if (!stable.exists()) {
            val legacy = File(context.cacheDir, "speech-transcripts/$key.json")
            if (legacy.isFile) {
                legacy.copyTo(stable, overwrite = false)
                legacy.delete()
            }
        }
        val file = File(directory, "$key.json")
        if (!file.isFile) null else {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SpokenWord(item.getString("text"), item.getLong("start"), item.getLong("end"))
            }
        }
    } catch (_: Exception) { null }

    fun copyEntries(sourceKey: String, destinationKey: String) {
        val origin = File(directory, "$sourceKey.json")
        if (!origin.exists()) load(sourceKey)
        File(directory, "$sourceKey.json").takeIf { it.exists() }?.copyTo(
            File(directory, "$destinationKey.json"), overwrite = true
        )
        directory.listFiles()?.filter { it.name.startsWith("$sourceKey-chunk-") && it.extension == "json" }
            ?.forEach { file -> file.copyTo(File(directory, destinationKey + file.name.removePrefix(sourceKey)), overwrite = true) }
    }

    fun deleteEntries(key: String): Int {
        val files = directory.listFiles()?.filter {
            it.name == "$key.json" || (it.name.startsWith("$key-chunk-") && it.extension == "json")
        }.orEmpty()
        return files.count { it.delete() }
    }

    fun save(key: String, words: List<SpokenWord>) {
        val data = JSONArray()
        words.forEach { data.put(JSONObject().put("text", it.text).put("start", it.startMs).put("end", it.endMs)) }
        val target = File(directory, "$key.json")
        val temp = File(directory, "$key.tmp")
        temp.writeText(data.toString())
        if (!temp.renameTo(target)) {
            target.writeText(data.toString())
            temp.delete()
        }
    }
}

/** Adapter boundary for the planned NDK whisper.cpp recognizer. */
interface SpeechTranscriber {
    val modelId: String
    suspend fun transcribe(uri: Uri, onProgress: (Float) -> Unit): List<SpokenWord>
}
