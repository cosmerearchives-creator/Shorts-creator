package com.cosmerearchives.shortscreator

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min

/** Shared deterministic audio-driven artwork for preview and rendered video frames. */
object OrbitalScribble {
    fun draw(canvas: Canvas, width: Float, height: Float, timeMs: Long, amplitude: Float, sensitivity: Float = 1f) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.28f
        val energy = (amplitude * sensitivity.coerceIn(0.25f, 3f)).coerceIn(0f, 1f)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 207, 250)
            style = Paint.Style.STROKE
            strokeWidth = min(width, height) * 0.004f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val ring = Paint(ink).apply {
            alpha = 160
            strokeWidth = min(width, height) * 0.0026f
        }
        canvas.drawCircle(cx, cy, radius * 1.35f, ring)
        val t = timeMs / 1000.0
        for (strand in 0 until 19) {
            val path = Path()
            for (point in 0..110) {
                val a = point * 2.0 * PI / 110.0
                val drift = sin(a * (3 + strand % 6) + t * (0.8 + strand % 5 * 0.13) + strand * 1.6)
                val spikes = cos(a * (11 + strand % 9) - t * 2.2 + strand * 0.9)
                val r = radius * (0.2 + 0.34 * sin(strand * 1.7 + a * 2.0) +
                    (0.16 + energy * 0.62) * drift + (0.04 + energy * 0.21) * spikes)
                val x = cx + (r * cos(a + strand * 0.22)).toFloat()
                val y = cy + (r * sin(a + strand * 0.22)).toFloat()
                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            ink.alpha = 100 + strand % 5 * 27
            canvas.drawPath(path, ink)
        }
    }
}
