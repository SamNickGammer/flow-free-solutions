package flow.detect

/**
 * A screenshot, as plain pixels. NO Android types, NO java.awt.
 *
 * Android hands us `Bitmap.getPixels(...)` -> IntArray of ARGB.
 * The desktop CLI hands us ImageIO -> IntArray of ARGB.
 * Detection code below never learns which.
 *
 * (This is why ImageIO must never leak into this package: it does not exist on Android.)
 */
class Image(val w: Int, val h: Int, val px: IntArray) {
    init { require(px.size == w * h) { "pixel buffer is ${px.size}, expected ${w * h}" } }

    operator fun get(x: Int, y: Int): Int = px[y * w + x]
    fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h
}

/** Bounding box of the board within the screenshot, in pixels. */
data class Bounds(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0
}

// ---------------------------------------------------------------- colour helpers

inline fun red(c: Int) = (c shr 16) and 0xFF
inline fun green(c: Int) = (c shr 8) and 0xFF
inline fun blue(c: Int) = c and 0xFF
fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/** Squared Euclidean distance in RGB. Cheap and good enough to tell Flow Free's dots apart. */
fun dist2(a: Int, b: Int): Int {
    val dr = red(a) - red(b)
    val dg = green(a) - green(b)
    val db = blue(a) - blue(b)
    return dr * dr + dg * dg + db * db
}

fun luma(c: Int): Int = (red(c) * 299 + green(c) * 587 + blue(c) * 114) / 1000

/**
 * How saturated a colour is, 0..255. Flow Free's endpoint dots are vividly saturated; the board
 * background, the grid lines and the empty cells are all grey. That single fact does most of the
 * work of telling a dot from a cell.
 */
fun saturation(c: Int): Int {
    val r = red(c); val g = green(c); val b = blue(c)
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
    return mx - mn
}

/** Median colour of a square patch — median, not mean, so one stray antialiased pixel can't drag it. */
fun Image.patchMedian(cx: Int, cy: Int, radius: Int): Int {
    val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
    for (y in cy - radius..cy + radius) {
        for (x in cx - radius..cx + radius) {
            if (!inside(x, y)) continue
            val c = this[x, y]
            rs.add(red(c)); gs.add(green(c)); bs.add(blue(c))
        }
    }
    if (rs.isEmpty()) return 0
    rs.sort(); gs.sort(); bs.sort()
    val m = rs.size / 2
    return rgb(rs[m], gs[m], bs[m])
}

/** The single most common colour in a region — Flow Free's board background is a large flat field. */
fun Image.dominantColor(b: Bounds, step: Int = 2): Int {
    val counts = HashMap<Int, Int>()
    var y = b.y0
    while (y < b.y1) {
        var x = b.x0
        while (x < b.x1) {
            // quantise to 5 bits/channel so antialiasing doesn't shatter the histogram
            val c = this[x, y]
            val q = rgb(red(c) and 0xF8, green(c) and 0xF8, blue(c) and 0xF8)
            counts[q] = (counts[q] ?: 0) + 1
            x += step
        }
        y += step
    }
    return counts.maxByOrNull { it.value }?.key ?: 0
}
