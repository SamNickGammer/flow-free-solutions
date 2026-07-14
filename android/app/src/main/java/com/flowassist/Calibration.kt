package com.flowassist

import android.content.Context
import flow.detect.Bounds

/**
 * Per-device calibration: if auto-detection misreads the board, the user pins it down once and we
 * never guess again on this phone.
 *
 * Detection is the least-proven part of this app — themes, DPI, notches, ad banners. This is the
 * escape hatch that keeps the product usable when it is wrong.
 */
data class Calib(val bounds: Bounds?, val size: Pair<Int, Int>?)

object Calibration {
    private const val PREFS = "flowassist.calib"

    fun load(ctx: Context): Calib? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("set", false)) return null
        val b = if (p.contains("x0")) Bounds(
            p.getInt("x0", 0), p.getInt("y0", 0), p.getInt("x1", 0), p.getInt("y1", 0)) else null
        val r = p.getInt("rows", 0); val c = p.getInt("cols", 0)
        return Calib(b, if (r > 0 && c > 0) r to c else null)
    }

    fun save(ctx: Context, b: Bounds?, size: Pair<Int, Int>?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            clear()
            putBoolean("set", true)
            b?.let { putInt("x0", it.x0); putInt("y0", it.y0); putInt("x1", it.x1); putInt("y1", it.y1) }
            size?.let { putInt("rows", it.first); putInt("cols", it.second) }
            apply()
        }
    }

    fun clear(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}
