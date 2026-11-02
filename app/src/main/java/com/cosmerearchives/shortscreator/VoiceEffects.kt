package com.cosmerearchives.shortscreator

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/** Offline effects on generated PCM16 WAV. Never mutates the original speech file. */
object VoiceEffects {
    val names = listOf("Normal", "Robot", "Echo", "Reverb", "Deep", "Chipmunk", "Radio", "Demon", "Smooth Wobble", "Glitchy Wobble")
    suspend fun render(source: String, effect: String, intensity: Float, rate: Float = 4f, grit: Float = 0.25f): String =
        withContext(Dispatchers.IO) {
            if (effect == "Normal") return@withContext source
            require(effect in names) { "Unsupported voice effect" }
            val sourceFile = File(requireNotNull(Uri.parse(source).path))
            val bytes = sourceFile.readBytes()
            require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Expected WAV speech" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.getShort(20).toInt() == 1 && buffer.getShort(34).toInt() == 16) { "Expected PCM16" }
            val channels = buffer.getShort(22).toInt()
            val sampleRate = buffer.getInt(24)
            require(channels in 1..2 && sampleRate > 0) { "Unsupported voice format" }
            var offset = 12
            var length = 0
            while (offset + 8 <= bytes.size) {
                val chunkLength = buffer.getInt(offset + 4)
                require(chunkLength >= 0 && offset + 8L + chunkLength <= bytes.size) { "Invalid WAV chunk" }
                if (String(bytes, offset, 4) == "data") { offset += 8; length = chunkLength; break }
                offset += 8 + chunkLength + (chunkLength and 1)
            }
            require(length >= 2) { "Speech WAV has no samples" }
            val count = length / 2
            val samples = FloatArray(count) { i -> buffer.getShort(offset + 2 * i) / 32768f }
            val output = FloatArray(count)
            val mix = intensity.coerceIn(0f, 1f)
            val speed = rate.coerceIn(0.5f, 14f)
            val rough = grit.coerceIn(0f, 1f)
            fun delayed(i: Int, millis: Int): Float {
                val pos = i - (sampleRate * millis / 1000) * channels
                return if (pos in samples.indices) samples[pos] else 0f
            }
            fun resample(i: Int, factor: Float): Float {
                val frame = i / channels
                val ch = i % channels
                val position = frame * factor
                val first = position.toInt()
                val fraction = position - first
                val countFrames = samples.size / channels
                if (first !in 0 until countFrames) return 0f
                val a = samples[first * channels + ch]
                val b = samples[minOf(first + 1, countFrames - 1) * channels + ch]
                return a + (b - a) * fraction
            }
            for (i in samples.indices) {
                val dry = samples[i]
                val t = (i / channels).toDouble() / sampleRate
                val wet = when (effect) {
                    "Robot" -> dry * (if (sin(2.0 * PI * 45.0 * t) >= 0) 1f else -0.7f)
                    "Echo" -> dry * 0.6f + delayed(i, 210) * 0.55f + delayed(i, 420) * 0.32f
                    "Reverb" -> dry * 0.65f + delayed(i, 43) * 0.23f + delayed(i, 71) * 0.19f + delayed(i, 113) * 0.16f
                    "Deep" -> resample(i, 0.78f)
                    "Chipmunk" -> resample(i, 1.27f)
                    "Radio" -> (tanh((dry - delayed(i, 3) * 0.8f) * 3.0) * 0.7).toFloat()
                    "Demon" -> (tanh((dry + resample(i, 0.78f) * 0.7f) * 2.6) * 0.7).toFloat()
                    "Smooth Wobble", "Glitchy Wobble" -> {
                        val mod = sin(2.0 * PI * speed * t)
                        val shift = (mod * (4.0 + 18.0 * mix) * sampleRate / 1000.0).toInt() * channels
                        val warbled = samples[(i + shift).coerceIn(0, samples.lastIndex)]
                        if (effect == "Glitchy Wobble") {
                            (tanh(warbled * (1.0 + rough * 7.0)) * (if (sin(2.0 * PI * speed * 1.7 * t) > 0.1) 1.0 else 0.45)).toFloat()
                        } else warbled
                    }
                    else -> dry
                }
                output[i] = (dry * (1f - mix) + wet * mix).coerceIn(-1f, 1f)
            }
            val file = File(sourceFile.parentFile, "effect-" + UUID.randomUUID() + ".wav")
            val result = bytes.copyOf()
            val outBuffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
            output.forEachIndexed { i, value -> outBuffer.putShort(offset + 2 * i, (value * 32767).toInt().coerceIn(-32768, 32767).toShort()) }
            file.writeBytes(result)
            Uri.fromFile(file).toString()
        }
}
