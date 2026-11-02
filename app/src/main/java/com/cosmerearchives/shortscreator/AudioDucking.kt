package com.cosmerearchives.shortscreator

/** Smooth shared volume envelope for preview and offline timeline export. */
object AudioDucking {
    private const val FADE_MS = 250L
    fun gain(timeMs: Long, intervals: List<Pair<Long, Long>>): Float {
        var reduction = 0f
        for ((start, end) in intervals) {
            if (end <= start) continue
            val envelope = when {
                timeMs < start - FADE_MS || timeMs >= end + FADE_MS -> 0f
                timeMs < start -> (timeMs - (start - FADE_MS)).toFloat() / FADE_MS
                timeMs < end -> 1f
                else -> (end + FADE_MS - timeMs).toFloat() / FADE_MS
            }.coerceIn(0f, 1f)
            reduction = maxOf(reduction, envelope)
        }
        return 1f - 0.75f * reduction
    }
}
