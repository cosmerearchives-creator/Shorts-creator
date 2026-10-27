package com.cosmerearchives.shortscreator

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.floor

/** Audio decoding boundary for the offline speech recognizer.
 * Runs on a worker thread. Initial implementation limits materialized audio to five minutes.
 */
object MediaSpeechDecoder {
    const val SAMPLE_RATE = 16000
    private const val MAX_SECONDS = 300

    fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("This media file has no audio track")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val decoded = ArrayList<Float>()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            while (!outputEnded) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Audio decode cancelled")
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, read, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val current = codec.outputFormat
                        sampleRate = current.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = current.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputEncoding = if (current.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            current.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else android.media.AudioFormat.ENCODING_PCM_16BIT
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytesPerSample = when (outputEncoding) {
                            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                            android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                            else -> error("Unsupported decoded PCM encoding: $outputEncoding")
                        }
                        val frames = info.size / (bytesPerSample * channelCount)
                        val source = buffer.slice().order(ByteOrder.nativeOrder())
                        repeat(frames) {
                            var sample = 0f
                            repeat(channelCount) {
                                sample += if (bytesPerSample == 4) source.float else source.short / 32768f
                            }
                            decoded.add(sample / channelCount)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (decoded.size > sampleRate * MAX_SECONDS) error("Audio exceeds five-minute scan limit")
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    }
                }
            }
            if (decoded.isEmpty()) error("No audio samples decoded")
            val input = decoded.toFloatArray()
            if (sampleRate == SAMPLE_RATE) return input
            val outputSize = ((input.size.toLong() * SAMPLE_RATE) / sampleRate).toInt()
            return FloatArray(outputSize) { i ->
                val position = i.toDouble() * sampleRate / SAMPLE_RATE
                val lower = floor(position).toInt().coerceIn(0, input.lastIndex)
                val upper = (lower + 1).coerceAtMost(input.lastIndex)
                val fraction = (position - lower).toFloat()
                input[lower] * (1f - fraction) + input[upper] * fraction
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }
}
