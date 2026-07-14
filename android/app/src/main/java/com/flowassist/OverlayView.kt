package com.flowassist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View
import flow.Cell
import flow.detect.Detector

/**
 * The hint layer. Deliberately MILD.
 *
 * This is not the answer painted over the game — it is something you can see THROUGH, that tells
 * you which way to move:
 *   - faint paths (~35% alpha), thinner than the game's own pipe
 *   - direction arrows, because a line alone does not tell you which end to start from
 *   - a pulsing ring on the start dot, so your finger knows where to land first
 *
 * The window carries FLAG_NOT_TOUCHABLE (see AssistService), so your swipes land on the real game
 * underneath this.
 */
@SuppressLint("ViewConstructor")
class OverlayView(
    ctx: Context,
    private val grid: Detector.Grid,
    private val paths: Map<Char, List<Cell>>,
    private val colors: Map<Char, Int>,
) : View(ctx) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val fallback = intArrayOf(
        0xFFFF5A52.toInt(), 0xFF3D8BFD.toInt(), 0xFF2FC76A.toInt(), 0xFFF5C542.toInt(),
        0xFFFB8B3C.toInt(), 0xFFA98BD8.toInt(), 0xFF4FC3D9.toInt(), 0xFFE87DA8.toInt(),
        0xFF8B5A2B.toInt(), 0xFFF0F0F0.toInt(), 0xFF76B900.toInt(), 0xFFB00038.toInt(),
        0xFF00938A.toInt(), 0xFF6464E8.toInt(), 0xFFC0C040.toInt(), 0xFFE05080.toInt(),
    )

    private var phase = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // gentle pulse on the start rings
        post(object : Runnable {
            override fun run() {
                phase = (phase + 0.045f) % 1f
                invalidate()
                postDelayed(this, 33)
            }
        })
    }

    private fun colorOf(ch: Char, i: Int): Int = colors[ch] ?: fallback[i % fallback.size]

    private fun px(c: Cell): PointF {
        val (x, y) = grid.centerOf(c.row, c.col)
        return PointF(x.toFloat(), y.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val cell = minOf(grid.cellW, grid.cellH).toFloat()
        line.strokeWidth = cell * 0.22f          // thinner than the game's own pipe
        arrow.strokeWidth = cell * 0.075f
        ring.strokeWidth = cell * 0.06f

        paths.keys.sorted().forEachIndexed { i, ch ->
            val p = paths[ch] ?: return@forEachIndexed
            if (p.size < 2) return@forEachIndexed
            val col = colorOf(ch, i)

            // ---- faint path
            line.color = col
            line.alpha = 90                       // ~35% — you must still see the board underneath
            val path = Path()
            val first = px(p[0])
            path.moveTo(first.x, first.y)
            for (k in 1 until p.size) {
                val q = px(p[k])
                path.lineTo(q.x, q.y)
            }
            canvas.drawPath(path, line)

            // ---- direction arrows: which way to swipe
            arrow.color = col
            arrow.alpha = 235
            for (k in 1 until p.size) {
                val a = px(p[k - 1]); val b = px(p[k])
                drawChevron(canvas, a, b, cell * 0.16f)
            }

            // ---- pulsing ring on the START dot: land your finger here
            ring.color = col
            val grow = cell * (0.30f + 0.10f * phase)
            ring.alpha = (150 * (1f - phase)).toInt().coerceIn(0, 255)
            canvas.drawCircle(first.x, first.y, grow, ring)
        }
    }

    /** A chevron at the midpoint of a step, pointing the way the flow travels. */
    private fun drawChevron(canvas: Canvas, a: PointF, b: PointF, size: Float) {
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1f) return
        val ux = dx / len; val uy = dy / len
        val px_ = -uy; val py = ux                 // perpendicular

        val tipX = mx + ux * size * 0.55f
        val tipY = my + uy * size * 0.55f
        val backX = mx - ux * size * 0.45f
        val backY = my - uy * size * 0.45f

        val p = Path()
        p.moveTo(backX + px_ * size * 0.55f, backY + py * size * 0.55f)
        p.lineTo(tipX, tipY)
        p.lineTo(backX - px_ * size * 0.55f, backY - py * size * 0.55f)
        canvas.drawPath(p, arrow)
    }
}
