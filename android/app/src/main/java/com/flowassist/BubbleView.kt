package com.flowassist

import android.content.Context
import android.graphics.*
import android.view.View

/** The floating button. Idle: half-faded at the edge. Busy: a spinner. */
class BubbleView(ctx: Context) : View(ctx) {

    private val size = (52 * resources.displayMetrics.density).toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF04222A.toInt()
    }
    private var busy = false
    private var spin = 0f

    init { alpha = 0.85f }

    fun setBusy(b: Boolean) {
        busy = b
        if (b) {
            alpha = 1f
            post(object : Runnable {
                override fun run() {
                    if (!busy) return
                    spin += 12f
                    invalidate()
                    postDelayed(this, 24)
                }
            })
        } else alpha = 0.85f
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, size)

    override fun onDraw(c: Canvas) {
        val r = size / 2f
        val sw = size * 0.06f
        glyph.strokeWidth = sw

        fill.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            if (busy) 0xFF4B5262.toInt() else 0xFF2DD4E8.toInt(),
            if (busy) 0xFF2B3040.toInt() else 0xFF0E7C94.toInt(),
            Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r - sw / 2, fill)

        if (busy) {
            glyph.color = 0xFFC8CEDE.toInt()
            val inset = size * 0.28f
            c.drawArc(RectF(inset, inset, size - inset, size - inset),
                spin, 270f, false, glyph)
        } else {
            // the flow glyph: a pipe from one dot to another
            glyph.color = 0xFF04222A.toInt()
            val p = Path()
            val a = size * 0.26f; val b = size * 0.74f; val m = size * 0.5f
            p.moveTo(a, b); p.lineTo(m - size * 0.05f, b)
            p.cubicTo(m + size * 0.10f, b, m - size * 0.10f, a, m + size * 0.05f, a)
            p.lineTo(b, a)
            c.drawPath(p, glyph)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF04222A.toInt() }
            c.drawCircle(a, b, size * 0.075f, dot)
            c.drawCircle(b, a, size * 0.075f, dot)
        }
    }
}
