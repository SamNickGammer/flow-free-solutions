package flow.detect

import flow.Board
import flow.Cell
import kotlin.math.roundToInt

/**
 * Draws a Flow-Free-looking board to an [Image].
 *
 * This is TEST SCAFFOLDING, and it is important to be honest about what it proves and what it
 * does not.
 *
 *   IT PROVES: the detection pipeline is logically sound — bounds, independent row/col sizing,
 *              three-way cell classification, colour pairing, wall-vs-gridline discrimination.
 *              A render -> detect -> compare round-trip against known ground truth is a real test.
 *
 *   IT DOES NOT PROVE: that detection survives a REAL screenshot. Real ones bring themes,
 *              gradients, drop shadows, antialiasing, DPI scaling, notches and ad banners. Passing
 *              here is necessary, not sufficient.
 *
 * We add jitter and antialiasing below precisely so this is not a trivial self-fulfilling test.
 * The real validation is a folder of actual screenshots — see solver/README.
 */
object BoardRender {

    val PALETTE = intArrayOf(
        rgb(0xE8, 0x3B, 0x35), rgb(0x35, 0x7E, 0xE8), rgb(0x33, 0xB1, 0x5E),
        rgb(0xE8, 0xC5, 0x36), rgb(0xE8, 0x7A, 0x2E), rgb(0x9B, 0x59, 0xD6),
        rgb(0x33, 0xC4, 0xD9), rgb(0xE8, 0x62, 0xA5), rgb(0x8B, 0x5A, 0x2B),
        rgb(0xF0, 0xF0, 0xF0), rgb(0x76, 0xB9, 0x00), rgb(0xB0, 0x00, 0x38),
        rgb(0x00, 0x93, 0x8A), rgb(0x64, 0x64, 0xE8), rgb(0xC0, 0xC0, 0x40),
        rgb(0xE0, 0x50, 0x80),
    )

    const val BG = 0xFF2B2B2B.toInt()          // board background (grey, unsaturated)
    const val LINE = 0xFF3A3A3A.toInt()        // ordinary grid line — only slightly off the bg
    const val WALL = 0xFFD8D8D8.toInt()        // wall — markedly BRIGHTER, and thicker
    const val HOLE = 0xFF141414.toInt()        // hole — darker than the board, still grey
    const val PAGE = 0xFF101014.toInt()        // page behind the board

    /**
     * @param cell pixels per cell
     * @param pad  page margin around the board
     * @param jitter shift the board by a pixel or two — a detector that only works when the
     *               board is perfectly placed is not a detector
     */
    fun render(
        b: Board,
        colors: Map<Char, Int> = defaultColors(b),
        cell: Int = 48,
        pad: Int = 30,
        jitter: Int = 3,
    ): Pair<Image, Detector.Grid> {
        val bw = b.cols * cell
        val bh = b.rows * cell
        val w = bw + pad * 2
        val h = bh + pad * 2 + 40                    // extra strip: pretend there's a UI bar
        val px = IntArray(w * h) { PAGE }
        val img = Image(w, h, px)

        val ox = pad + jitter
        val oy = pad + jitter

        fun set(x: Int, y: Int, c: Int) { if (img.inside(x, y)) px[y * w + x] = c }

        // board plate
        for (y in oy until oy + bh) for (x in ox until ox + bw) set(x, y, BG)

        // grid lines (thin, faint)
        for (i in 0..b.cols) for (y in oy until oy + bh) set(ox + i * cell, y, LINE)
        for (j in 0..b.rows) for (x in ox until ox + bw) set(x, oy + j * cell, LINE)

        // holes
        for (hcell in b.holes) {
            for (y in 0 until cell) for (x in 0 until cell) {
                set(ox + hcell.col * cell + x, oy + hcell.row * cell + y, HOLE)
            }
        }

        // endpoint dots, with a soft antialiased edge
        for ((ch, pair) in b.endpoints) {
            val c = colors[ch] ?: PALETTE[0]
            for (e in listOf(pair.first, pair.second)) {
                val cx = ox + e.col * cell + cell / 2
                val cy = oy + e.row * cell + cell / 2
                val r = cell * 0.34
                for (y in (cy - r).toInt() - 2..(cy + r).toInt() + 2) {
                    for (x in (cx - r).toInt() - 2..(cx + r).toInt() + 2) {
                        val d = Math.hypot((x - cx).toDouble(), (y - cy).toDouble())
                        if (d <= r - 1) set(x, y, c)
                        else if (d <= r + 1) {                     // antialias against the board
                            val t = ((r + 1 - d) / 2.0).coerceIn(0.0, 1.0)
                            val bgc = if (img.inside(x, y)) img[x, y] else BG
                            set(x, y, blend(bgc, c, t))
                        }
                    }
                }
            }
        }

        // walls — thick and bright, ON the shared border
        val thick = maxOf(3, cell / 12)
        for ((a, z) in b.walls) {
            val (p, q) = if (a.row < z.row || (a.row == z.row && a.col < z.col)) a to z else z to a
            if (p.row == q.row) {                       // vertical bar between (r,c) and (r,c+1)
                val x = ox + q.col * cell
                for (y in oy + p.row * cell + 3 until oy + (p.row + 1) * cell - 3) {
                    for (t in -thick / 2..thick / 2) set(x + t, y, WALL)
                }
            } else {                                    // horizontal bar between (r,c) and (r+1,c)
                val y = oy + q.row * cell
                for (x in ox + p.col * cell + 3 until ox + (p.col + 1) * cell - 3) {
                    for (t in -thick / 2..thick / 2) set(x, y + t, WALL)
                }
            }
        }

        val grid = Detector.Grid(
            Bounds(ox, oy, ox + bw, oy + bh), b.rows, b.cols, cell.toDouble(), cell.toDouble())
        return img to grid
    }

    fun defaultColors(b: Board): Map<Char, Int> =
        b.colors.mapIndexed { i, ch -> ch to PALETTE[i % PALETTE.size] }.toMap()

    private fun blend(a: Int, b: Int, t: Double): Int = rgb(
        (red(a) + (red(b) - red(a)) * t).roundToInt(),
        (green(a) + (green(b) - green(a)) * t).roundToInt(),
        (blue(a) + (blue(b) - blue(a)) * t).roundToInt(),
    )
}
