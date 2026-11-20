package com.roboduck.image

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10
import kotlin.math.max

/** A row of thin bars showing recent sound level. */
class LevelView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val count = 32
    private val levels = FloatArray(count)
    private var head = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.line) }

    var active = false
        set(value) {
            field = value
            paint.color = context.getColor(if (value) R.color.fg else R.color.line)
            if (!value) levels.fill(0f)
            invalidate()
        }

    fun push(rms: Float) {
        val db = 20f * log10(max(rms, 1e-5f))
        levels[head] = ((db + 60f) / 55f).coerceIn(0f, 1f)
        head = (head + 1) % count
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val gap = width / (count * 2f - 1f)
        val barW = gap
        val radius = barW / 2f
        val midY = height / 2f
        for (i in 0 until count) {
            val v = levels[(head + i) % count]
            val h = max(barW, v * height)
            val x = i * 2f * gap
            canvas.drawRoundRect(x, midY - h / 2f, x + barW, midY + h / 2f, radius, radius, paint)
        }
    }
}
